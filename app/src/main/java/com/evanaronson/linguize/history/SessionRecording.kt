package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import java.security.MessageDigest

/** Facts about a session known when it starts. */
data class SessionContext(
    val origin: Origin,
    val hostApp: String?,
    val requestedLanguage: String?,
    val punctuation: String,
    val judgments: String,
    val provider: String,
    val model: String,
    val promptHash: String,
    val appVersion: String,
    val deviceId: String,
)

/**
 * Turns what happens on one card into history records. Pure Kotlin: it never touches
 * storage, the caller saves what it returns. One instance per session (per check()).
 *
 * The caller reports, in order: the start (constructor), each attempt as it finishes,
 * every change to the revision the writer makes (accept, accept all, undo), copies,
 * and finally the close. See docs/history-spec.md, "Decisions, defined".
 *
 * Only the current attempt's suggestions are decided at the close; an earlier attempt's
 * are [Decision.Superseded] as soon as the next attempt replaces them. What was accepted
 * and what was copied is tracked per attempt, since a re-check carries identical accepted
 * edits over as new edits ([Revision.acceptMatching]). Once closed, further reports are
 * ignored.
 */
class SessionRecording(
    text: String,
    context: SessionContext,
    now: Long,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** The session as it stands; save it after the constructor and after each [attempt]. */
    var session: SessionRecord
        private set

    /** Rows for earlier attempts' suggestions, already decided. */
    private val superseded = mutableListOf<Pending>()

    /** What the current attempt offers; null before the first attempt or after a failed one. */
    private var revision: Revision? = null

    /** Edits of the current attempt accepted at some point, including ones carried over. */
    private val everAccepted = mutableSetOf<Int>()

    /** Edits of the current attempt that were on offer when their section was copied. */
    private val copiedEdits = mutableSetOf<Int>()

    /** Kinds copied at any point in the session, for the outcome. */
    private val copiedKinds = mutableSetOf<EditKind>()

    private var closed: SessionDetail? = null

    init {
        session = SessionRecord(
            id = newId(),
            deviceId = context.deviceId,
            createdAt = now,
            updatedAt = now,
            startedAt = now,
            origin = context.origin,
            hostApp = context.hostApp,
            requestedLanguage = context.requestedLanguage,
            text = text,
            textHash = hash(text),
            punctuation = context.punctuation,
            judgments = context.judgments,
            provider = context.provider,
            model = context.model,
            promptHash = context.promptHash,
            appVersion = context.appVersion,
        )
    }

    /**
     * An attempt finished. [revision] is what the card now offers (null when the attempt
     * failed or the result has nothing to review); the previous attempt's suggestions,
     * if any were shown, become [Decision.Superseded]. Returns the session to save.
     */
    fun attempt(
        at: Long,
        settled: List<Settled>,
        verdict: String?,
        failure: String?,
        failureDetail: String?,
        revision: Revision?,
        meaning: String?,
    ): SessionRecord {
        if (closed != null) return session
        this.revision?.let { previous ->
            val index = session.attempts.lastIndex
            previous.edits.forEach { superseded += Pending(index, it, Decision.Superseded, at) }
        }
        this.revision = revision
        everAccepted.clear()
        copiedEdits.clear()
        revision?.let { remember(it) }
        val attempt = Attempt(at, settled.map { SettledAnswer(it.about, it.answer) }, verdict, failure, failureDetail)
        session = session.copy(
            updatedAt = maxOf(session.updatedAt, at),
            attempts = session.attempts + attempt,
            meaning = if (failure == null) meaning else session.meaning,
        )
        return session
    }

    /** The writer accepted or undid something; [revision] is the new state. */
    fun changed(revision: Revision) {
        if (closed != null) return
        this.revision = revision
        remember(revision)
    }

    /** The writer copied the version shown for [kind]. */
    fun copied(kind: EditKind) {
        if (closed != null) return
        copiedKinds += kind
        revision?.let { copiedEdits += it.remaining(kind).map { edit -> edit.id } }
    }

    /**
     * The card closed. [finalText] is what went back to the app (null if nothing did).
     * Returns the closed session and one suggestion row per suggestion shown in any
     * attempt, each with its decision. Calling it twice returns the same result.
     */
    fun close(at: Long, finalText: String?): SessionDetail {
        closed?.let { return it }
        val index = session.attempts.lastIndex
        val current = revision?.let { revision -> revision.edits.map { Pending(index, it, decide(revision, it), at) } }.orEmpty()
        session = session.copy(
            updatedAt = maxOf(session.updatedAt, at),
            closedAt = at,
            finalText = finalText,
            outcome = when {
                finalText != null -> Outcome.Applied
                copiedKinds.isNotEmpty() -> Outcome.Copied
                session.attempts.lastOrNull()?.failure != null -> Outcome.Failed
                else -> Outcome.None
            },
        )
        val rows = (superseded + current).map { it.record(session, at) }
        return SessionDetail(session, rows).also { closed = it }
    }

    private fun remember(revision: Revision) {
        everAccepted += revision.acceptedEdits.map { it.id }
    }

    /** The decision for one of the current attempt's edits, as things stand at the close. */
    private fun decide(revision: Revision, edit: Edit) = when {
        revision.isApplied(edit.id) -> Decision.Accepted
        revision.isRetired(edit.id) -> Decision.Retired
        edit.id in copiedEdits -> Decision.Copied
        edit.id in everAccepted -> Decision.Undone
        else -> Decision.Ignored
    }

    /** A suggestion whose decision is known, waiting for the close to become a row. */
    private inner class Pending(val attempt: Int, val edit: Edit, val decision: Decision, val decidedAt: Long) {
        fun record(session: SessionRecord, at: Long) = SuggestionRecord(
            id = newId(),
            sessionId = session.id,
            deviceId = session.deviceId,
            createdAt = at,
            updatedAt = at,
            attempt = attempt,
            kind = edit.kind.name.lowercase(),
            start = edit.start,
            end = edit.end,
            fromText = edit.from,
            toText = edit.replacement,
            why = edit.why,
            decision = decision,
            decidedAt = decidedAt,
        )
    }

    companion object {
        /** Short, stable hash of a text, for finding the same text checked again. */
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}

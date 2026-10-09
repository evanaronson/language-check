package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled

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

    init {
        TODO()
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
    ): SessionRecord = TODO()

    /** The writer accepted or undid something; [revision] is the new state. */
    fun changed(revision: Revision): Unit = TODO()

    /** The writer copied the version shown for [kind]. */
    fun copied(kind: EditKind): Unit = TODO()

    /**
     * The card closed. [finalText] is what went back to the app (null if nothing did).
     * Returns the closed session and one suggestion row per suggestion shown in any
     * attempt, each with its decision. Calling it twice returns the same result.
     */
    fun close(at: Long, finalText: String?): SessionDetail = TODO()

    companion object {
        /** Short, stable hash of a text, for finding the same text checked again. */
        fun hash(text: String): String = TODO()
    }
}

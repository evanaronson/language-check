package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** What history needs from the app besides its store. Every value is read on the caller's thread, so must be cheap. */
interface HistoryEnvironment {
    /** Whether checks are recorded. Read before every write. */
    val enabled: Boolean

    /** A random id made once per install. */
    val deviceId: String

    /** This build's version name. */
    val appVersion: String
}

/** What a session records about how its check runs, known when it starts. */
data class Opening(
    val origin: Origin,
    /** The app the text came from, when known. */
    val hostApp: String?,
    /** The language asked for, by name; null for auto-detect. */
    val requestedLanguage: String?,
    val punctuation: Punctuation,
    val judgments: Judgments,
    val provider: Provider,
    val model: String,
    val promptHash: String,
)

/** A verdict kept from an earlier session on the same text and settings, to show again. */
data class Kept(
    /** The earlier session's id. */
    val sessionId: String,
    /** The model's answer, as stored ([Attempt.verdict]). */
    val verdict: String,
    /** The writer's answers to assumptions it was made with. */
    val settled: List<Settled>,
)

/** The answer a first attempt shows: the model's, or a [reused] kept one. */
data class Answer<T>(val value: T, val reused: Kept?)

/**
 * The history of the card's checks: opens a session when a check starts, records each
 * attempt, the writer's changes and copies, and closes it with what went back to the app.
 * Everything that decides what's recorded lives here or in [SessionRecording]; the view
 * model only forwards to it.
 *
 * - Writes never hold up the card: they're queued on [scope] (which outlives the screen)
 *   in the order they were made, and their failures are only logged.
 * - [HistoryEnvironment.enabled] is read before every write. Turned off while a card is
 *   open, the open session stops being recorded: nothing more is written for it.
 * - A text over [maxChars] isn't recorded: the check refuses it before any request.
 * - The first attempt of a session races the model against a kept verdict for the same
 *   text and settings (see [firstAttempt]); the request leaves before any history work.
 *
 * Not thread-safe: call it from one thread (the main thread, in the app).
 */
class CheckHistory(
    private val store: HistoryStore,
    private val environment: HistoryEnvironment,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val maxChars: Int = Int.MAX_VALUE,
    private val reuseWithin: Long = REUSE_WITHIN_MS,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** The open session's record; null when history is off or no card is open. */
    private var recording: SessionRecording? = null

    /** The last write queued, so writes land in the order they were made. */
    private var lastWrite: Job? = null

    /** The open session as it stands, or null. */
    val session: SessionRecord? get() = recording?.session

    /**
     * Starts a fresh check of [text]: starts [ask] (the model request) at once, then opens
     * a session for it and, while the request runs, looks for a kept verdict. A kept
     * verdict that arrives before the model's answer is turned into a result by [reuse]
     * and shown instead, and the request is cancelled; otherwise the model's answer is
     * returned (or its failure thrown) as soon as it comes, whatever the lookup is doing,
     * so the lookup can never add latency. Closes nothing: [close] the previous session first.
     */
    suspend fun <T> firstAttempt(
        text: String,
        opening: Opening,
        ask: suspend () -> T,
        reuse: suspend (Kept) -> T?,
    ): Answer<T> = coroutineScope {
        // Undispatched: the request is on its way before anything below runs.
        val answer = async(start = CoroutineStart.UNDISPATCHED) { capture { ask() } }
        val opened = open(text, opening)
        // Not a child: a blocking query that's slow must never be waited for.
        val lookup: Deferred<Kept?>? = opened?.let { session -> scope.async { find(session) } }
        try {
            val kept = lookup?.let {
                select<Kept?> {
                    answer.onAwait { null }
                    lookup.onAwait { it }
                }
            }
            if (kept != null && answer.isActive) {
                val reused = quietly("show a kept answer") { reuse(kept) }
                if (reused != null && answer.isActive) {
                    answer.cancel()
                    return@coroutineScope Answer(reused, kept)
                }
            }
            Answer(answer.await().getOrThrow(), null)
        } finally {
            lookup?.cancel()
        }
    }

    /**
     * Opens a session for [text], saved right away; returns it, or null when it isn't
     * recorded (history is off, or the text is over [maxChars]).
     */
    fun open(text: String, opening: Opening): SessionRecord? {
        recording = null
        if (text.length > maxChars) return null
        if (quietly("read whether history is on") { environment.enabled } != true) return null
        val opened = quietly("open a session") {
            val context = SessionContext(
                origin = opening.origin,
                hostApp = opening.hostApp,
                requestedLanguage = opening.requestedLanguage,
                punctuation = Stored.punctuation.encode(opening.punctuation),
                judgments = Stored.judgments.encode(opening.judgments),
                provider = Stored.provider.encode(opening.provider),
                model = opening.model,
                promptHash = opening.promptHash,
                appVersion = environment.appVersion,
                deviceId = environment.deviceId,
            )
            SessionRecording(text, context, clock(), newId)
        } ?: return null
        recording = opened
        val session = opened.session
        write("save a session") { store.save(session) }
        return session.takeIf { recording != null }
    }

    /** An attempt came back with [result], from the model's answer [verdict] or a kept one ([reusedFrom]). */
    fun succeeded(settled: List<Settled>, verdict: String, result: CheckResult, reusedFrom: String? = null) {
        val recording = recording ?: return
        val reviewed = result as? CheckResult.Reviewed
        val session = quietly("record an attempt") {
            recording.attempt(
                at = clock(),
                settled = settled,
                verdict = verdict,
                failure = null,
                failureDetail = null,
                revision = reviewed?.revision,
                meaning = reviewed?.meaning?.takeIf { it.isNotBlank() },
                status = statusOf(result),
                reusedFrom = reusedFrom,
            )
        } ?: return
        write("save an attempt") { store.save(session) }
    }

    /** An attempt failed for [reason] (a `CheckFailure.Reason` name). */
    fun failed(settled: List<Settled>, reason: String, detail: String?) {
        val recording = recording ?: return
        val session = quietly("record a failed attempt") {
            recording.attempt(clock(), settled, null, reason, detail, null, null)
        } ?: return
        write("save an attempt") { store.save(session) }
    }

    /** The writer accepted or undid something; [revision] is the new state. */
    fun changed(revision: Revision) {
        recording?.let { quietly("record a change") { it.changed(revision) } }
    }

    /** The writer copied the version shown for [kind]. */
    fun copied(kind: EditKind) {
        recording?.let { quietly("record a copy") { it.copied(kind) } }
    }

    /** Closes the open session, if any, with what went back to the app ([finalText], null for nothing). */
    fun close(finalText: String?) {
        val recording = recording ?: return
        this.recording = null
        val closed = quietly("close a session") { recording.close(clock(), finalText) } ?: return
        write("write a closed session") { store.close(closed.session, closed.suggestions) }
    }

    /** Waits for the writes queued so far. */
    suspend fun flush() {
        lastWrite?.join()
    }

    /** The newest kept verdict for [session]'s text and settings, or null. Never throws. */
    private suspend fun find(session: SessionRecord): Kept? = quietly("look for a kept answer") {
        val kept = store.reusable(session.reuseKey, since = session.startedAt - reuseWithin) ?: return@quietly null
        val attempt = kept.attempts.lastOrNull() ?: return@quietly null
        val verdict = attempt.verdict ?: return@quietly null
        Kept(kept.id, verdict, attempt.settled.map { Settled(it.about, it.answer) })
    }

    /**
     * Queues a write on [scope] after the ones queued before it. When history has been
     * turned off, the open session stops being recorded and nothing is written.
     */
    private fun write(what: String, block: suspend () -> Unit) {
        if (quietly("read whether history is on") { environment.enabled } != true) {
            recording = null
            return
        }
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            quietly(what) { block() }
        }
    }

    /** History must never break a check: a failure is logged and gives null. */
    private inline fun <R> quietly(what: String, block: () -> R): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("History: couldn't $what", e)
        null
    }

    companion object {
        /** How recent a kept answer must be to be shown again instead of asking the model. */
        const val REUSE_WITHIN_MS = 10 * 60 * 1000L

        /** What a result says the model found. */
        fun statusOf(result: CheckResult): Verdict.Status = when (result) {
            is CheckResult.Reviewed -> Verdict.Status.Ok
            CheckResult.Unclear -> Verdict.Status.Unclear
            is CheckResult.WrongLanguage -> Verdict.Status.WrongLanguage
        }
    }
}

/** [block]'s value or what it threw, but never swallowing cancellation. */
private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

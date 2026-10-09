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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What history needs from the app besides its store. Read off the main thread when a
 * session opens (the first read can mean loading preferences, or making the device id);
 * [enabled] is read again before every write, by then cheaply.
 */
interface HistoryEnvironment {
    /** Whether checks are recorded. Read before every write. */
    val enabled: Boolean

    /** A random id made once per install. */
    val deviceId: String

    /** This build's version name. */
    val appVersion: String
}

/**
 * What a session records about how its check runs, known when it starts. Every attempt of
 * a session runs with the same: a re-check with a different one starts a new session
 * ([recheck][CheckHistory.recheck]).
 */
data class Opening(
    val origin: Origin,
    /** The app the text came from, when known. */
    val hostApp: String?,
    /** The language asked for, by name; null for auto-detect. */
    val requestedLanguage: String?,
    /** The writer's own language, which the meaning and reasons are asked in. */
    val nativeLanguage: String,
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
    /** The model's answer, as stored ([Attempt.raw]). */
    val raw: String,
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
 * - A session holds the attempts made with one [Opening]: a re-check with another
 *   (settings changed while the card was up) closes it and opens a new one ([recheck]).
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
    /** Where [environment] is first read when a session opens. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The open session's record; null when history is off or no card is open. */
    private var recording: SessionRecording? = null

    /** What [recording] was opened with. */
    private var opening: Opening? = null

    /** The last write queued, so writes land in the order they were made. */
    private var lastWrite: Job? = null

    /** The open session as it stands, or null. */
    internal val session: SessionRecord? get() = recording?.session

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
     * Before a re-check of the open session's text with [opening]: when it isn't what the
     * session was opened with (the provider, model, options, prompt or native language
     * changed while the card was up), closes the session as nothing applied and opens a new
     * one, so that every attempt of a session, and every answer reused from it, was made
     * with the settings it records. Does nothing when no session is open.
     */
    suspend fun recheck(text: String, opening: Opening) {
        if (recording == null || opening == this.opening) return
        close(finalText = null)
        open(text, opening)
    }

    /**
     * Opens a session for [text], saved right away; returns it, or null when it isn't
     * recorded (history is off, or the text is over [maxChars]). The environment is read
     * on [io].
     */
    internal suspend fun open(text: String, opening: Opening): SessionRecord? {
        recording = null
        this.opening = null
        if (text.length > maxChars) return null
        val context = withContext(io) {
            quietly("open a session") {
                if (!environment.enabled) return@quietly null
                SessionContext(
                    origin = opening.origin,
                    hostApp = opening.hostApp,
                    requestedLanguage = opening.requestedLanguage,
                    nativeLanguage = opening.nativeLanguage,
                    punctuation = Stored.punctuation.encode(opening.punctuation),
                    judgments = Stored.judgments.encode(opening.judgments),
                    provider = Stored.provider.encode(opening.provider),
                    model = opening.model,
                    promptHash = opening.promptHash,
                    appVersion = environment.appVersion,
                    deviceId = environment.deviceId,
                )
            }
        } ?: return null
        val opened = quietly("open a session") { SessionRecording(text, context, clock(), newId) } ?: return null
        recording = opened
        this.opening = opening
        val session = opened.session
        write("save a session") { store.save(session) }
        return session.takeIf { recording != null }
    }

    /** An attempt came back with [result], from the model's answer [raw] or a kept one ([reusedFrom]). */
    fun succeeded(settled: List<Settled>, raw: String, result: CheckResult, reusedFrom: String? = null) {
        val recording = recording ?: return
        val reviewed = result as? CheckResult.Reviewed
        val session = quietly("record an attempt") {
            recording.attempt(
                at = clock(),
                settled = settled,
                raw = raw,
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

    /**
     * An attempt failed for [reason] (a `CheckFailure.Reason` name). [raw] is the model's
     * answer when one came but couldn't be read.
     */
    fun failed(settled: List<Settled>, reason: String, detail: String?, raw: String? = null) {
        val recording = recording ?: return
        val session = quietly("record a failed attempt") {
            recording.attempt(clock(), settled, raw, reason, detail, null, null)
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
        opening = null
        val closed = quietly("close a session") { recording.close(clock(), finalText) } ?: return
        write("write a closed session") { store.close(closed.session, closed.suggestions) }
    }

    /**
     * Blocks the calling thread until the writes queued so far are done, for at most
     * [timeoutMs]: for a host whose process may be killed as soon as it finishes, so the
     * close it just queued isn't lost. Gives up quietly at the limit; the writes go on.
     */
    fun awaitWrites(timeoutMs: Long) {
        val pending = lastWrite?.takeIf { it.isActive } ?: return
        quietly("wait for history writes") { runBlocking { withTimeoutOrNull(timeoutMs) { pending.join() } } }
    }

    /** Waits for the writes queued so far. */
    internal suspend fun flush() {
        lastWrite?.join()
    }

    /** The newest kept answer for [session]'s text and settings, or null. Never throws. */
    private suspend fun find(session: SessionRecord): Kept? = quietly("look for a kept answer") {
        val kept = store.reusable(session.reuseKey, since = session.startedAt - reuseWithin) ?: return@quietly null
        val attempt = kept.decidedAttempt()?.let(kept.session.attempts::get) ?: return@quietly null
        Kept(kept.session.id, attempt.raw ?: return@quietly null, attempt.settled)
    }

    /**
     * Queues a write on [scope] after the ones queued before it. When history has been
     * turned off, the open session stops being recorded and nothing is written.
     */
    private fun write(what: String, block: suspend () -> Unit) {
        if (quietly("read whether history is on") { environment.enabled } != true) {
            recording = null
            opening = null
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

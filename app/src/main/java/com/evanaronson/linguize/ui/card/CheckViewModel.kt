package com.evanaronson.linguize.ui.card

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.CheckContext
import com.evanaronson.linguize.Checked
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CardState {
    data class Loading(val text: String) : CardState
    data class Failed(val reason: CheckFailure.Reason, val detail: String?) : CardState
    data class Done(
        val result: CheckResult,
        /** The writer's answers to assumptions, kept until the card closes. */
        val settled: List<Settled> = emptyList(),
    ) : CardState
}

/**
 * One check and the changes the writer has accepted from it. Accepted changes
 * survive checking again (after an answer to an assumption, a retry, or new
 * settings) wherever the new suggestions are the same.
 *
 * When history is on, each check is a session in [App.history]: opened with the first
 * request, an attempt per answer, closed by [dismiss], the next [check] or the view model
 * going away. History never holds up or changes the card: its writes run on [App.appScope]
 * after the request has left, and its failures are only logged. A check of the same text
 * with the same settings within [REUSE_WITHIN_MS] shows the kept answer instead of asking
 * the model again.
 */
class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var text = ""
    private var language: Language? = null
    private var origin = Origin.Tester
    private var hostApp: String? = null
    private var settled: List<Settled> = emptyList()

    /** Changes accepted before the current check started, kept while it runs. */
    private var carried: List<Edit> = emptyList()
    private var job: Job? = null

    /** The open session's record; null when history is off or no card is open. */
    private var recording: SessionRecording? = null

    /** The last history write queued, so writes land in the order they were made. */
    private var lastWrite: Job? = null

    /** This build's version name, kept with each session. Read off the main thread, once. */
    @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33.
    private val appVersion: String by lazy { app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty() }

    /** Null before the first check and after [dismiss]. */
    var state by mutableStateOf<CardState?>(null)
        private set

    /** The text with accepted changes, or null when nothing has been accepted. */
    val workingText: String?
        get() {
            val revision = reviewed?.revision ?: Revision(text, carried, listOf(carried.map { it.id }))
            return revision.takeIf { it.acceptedCount > 0 }?.workingText
        }

    /**
     * Checks [text] as [language], or detects the language when it's null, starting afresh.
     * [origin] and [hostApp] (the app the text came from, when known) go into history.
     */
    fun check(text: String, language: Language?, origin: Origin, hostApp: String? = null) {
        // A new check replaces the card; whatever it had accepted was never applied.
        closeSession(applied = false)
        this.text = text
        this.language = language
        this.origin = origin
        this.hostApp = hostApp
        settled = emptyList()
        run(fresh = true)
    }

    /** Checks the same text again, e.g. after settings changed, keeping answers and accepted changes. */
    fun recheck() = run(fresh = false)

    fun retry() = run(fresh = false)

    /** Overrides an assumption with the writer's [answer] and checks again. */
    fun settle(about: String, answer: String) {
        settled = settled.filterNot { it.about == about } + Settled(about, answer)
        run(fresh = false)
    }

    private fun run(fresh: Boolean) {
        if (fresh) {
            carried = emptyList()
        } else {
            reviewed?.let { carried = it.revision.acceptedEdits }
        }
        val text = text
        val language = language
        val keep = carried
        job?.cancel()
        state = CardState.Loading(text)
        job = viewModelScope.launch {
            var answers = settled
            var recording = recording
            var verdict: String? = null
            val shown = try {
                // Creating the checker reads its prompt from assets, so not on the main thread.
                val checker = withContext(Dispatchers.Default) { app.checker }
                val context = checker.context()
                val opened = if (fresh) openSession(checker, context, text, language) else null
                if (opened != null) {
                    recording = opened.recording
                    this@CheckViewModel.recording = recording
                    save(opened.recording.session)
                    opened.earlier?.let { answers = it.settled }
                    settled = answers
                }
                val checked = opened?.earlier?.checked ?: checker.check(text, language, answers, context)
                verdict = checked.verdict
                val result = checked.result
                val kept = if (result is CheckResult.Reviewed) {
                    result.copy(revision = result.revision.acceptMatching(keep))
                } else {
                    result
                }
                CardState.Done(kept, answers)
            } catch (failure: CheckFailure) {
                CardState.Failed(failure.reason, failure.detail)
            }
            state = shown
            recording?.let { recordAttempt(it, answers, verdict, shown) }
        }
    }

    fun accept(id: Int) = update { it.accept(id) }

    /** Accepts every remaining change of [kind]; returns true when nothing is left to decide. */
    fun acceptAll(kind: EditKind): Boolean {
        update { it.acceptAll(kind) }
        return reviewed?.isResolved == true
    }

    fun undo() = update { it.undo() }

    /** The writer copied the version shown for [kind]; only history needs to know. */
    fun copied(kind: EditKind) {
        recording?.let { quietly("record a copy") { it.copied(kind) } }
    }

    /** Closes the card. [applied] says whether its accepted changes went back to the text. */
    fun dismiss(applied: Boolean = true) {
        closeSession(applied)
        job?.cancel()
        carried = emptyList()
        state = null
    }

    override fun onCleared() = closeSession()

    private val reviewed get() = (state as? CardState.Done)?.result as? CheckResult.Reviewed

    private fun update(change: (Revision) -> Revision) {
        val done = state as? CardState.Done ?: return
        val current = done.result as? CheckResult.Reviewed ?: return
        val revision = change(current.revision)
        state = done.copy(result = current.copy(revision = revision))
        recording?.let { quietly("record a change") { it.changed(revision) } }
    }

    /** A new session's record, and an earlier answer to the same text to show instead of asking the model. */
    private class Opened(val recording: SessionRecording, val earlier: Earlier?)

    /** A kept answer shown again, with the writer's answers to assumptions it was made with. */
    private class Earlier(val checked: Checked, val settled: List<Settled>)

    /**
     * Starts the history session for a fresh check, off the main thread, and looks for the
     * same text checked with the same settings in the last few minutes. Null when history is
     * off or can't be written: the check then goes ahead unrecorded. The lookup is one indexed
     * query; if it's slow or fails, the model is asked as usual.
     */
    private suspend fun openSession(checker: Checker, context: CheckContext, text: String, language: Language?): Opened? {
        val origin = origin
        val hostApp = hostApp
        return withContext(Dispatchers.IO) {
            quietly("open a session") {
                if (!app.settings.historyEnabled) return@quietly null
                val now = System.currentTimeMillis()
                val session = SessionContext(
                    origin = origin,
                    hostApp = hostApp,
                    requestedLanguage = language?.name,
                    punctuation = context.punctuation.name,
                    judgments = context.judgments.name,
                    provider = context.provider.name,
                    model = context.model,
                    promptHash = context.promptHash,
                    appVersion = appVersion,
                    deviceId = app.settings.deviceId,
                )
                val recording = SessionRecording(text, session, now)
                val kept = quietly("look for a kept answer") {
                    withTimeoutOrNull(REUSE_LOOKUP_MS) {
                        app.history.reusable(
                            textHash = recording.session.textHash,
                            requestedLanguage = session.requestedLanguage,
                            punctuation = session.punctuation,
                            judgments = session.judgments,
                            provider = session.provider,
                            model = session.model,
                            promptHash = session.promptHash,
                            since = now - REUSE_WITHIN_MS,
                        )
                    }
                }
                val attempt = kept?.attempts?.lastOrNull()
                val earlier = attempt?.verdict?.let { verdict ->
                    try {
                        Earlier(checker.reuse(text, language, verdict, context), attempt.settled.map { Settled(it.about, it.answer) })
                    } catch (e: CheckFailure) {
                        Log.w(TAG, "History: couldn't read a kept answer", e)
                        null
                    }
                }
                Opened(recording, earlier)
            }
        }
    }

    private fun recordAttempt(recording: SessionRecording, settled: List<Settled>, verdict: String?, shown: CardState) {
        val failed = shown as? CardState.Failed
        val result = (shown as? CardState.Done)?.result as? CheckResult.Reviewed
        val session = quietly("record an attempt") {
            recording.attempt(
                at = System.currentTimeMillis(),
                settled = settled,
                verdict = verdict,
                failure = failed?.reason?.name,
                failureDetail = failed?.detail,
                revision = result?.revision,
                meaning = result?.meaning?.takeIf { it.isNotBlank() },
            )
        } ?: return
        save(session)
    }

    /** Closes the open session, if any, with what goes back to the app: the text with accepted changes. */
    private fun closeSession(applied: Boolean = true) {
        val recording = recording ?: return
        this.recording = null
        val finalText = if (applied) workingText else null
        val closed = quietly("close a session") { recording.close(System.currentTimeMillis(), finalText) } ?: return
        write("write a closed session") { app.history.close(closed.session, closed.suggestions) }
    }

    private fun save(session: SessionRecord) = write("save a session") { app.history.save(session) }

    /**
     * Queues a history write on the app's scope, which outlives this view model, after the
     * writes queued before it.
     */
    private fun write(what: String, block: suspend () -> Unit) {
        val previous = lastWrite
        lastWrite = app.appScope.launch {
            previous?.join()
            quietly(what) { block() }
        }
    }

    private companion object {
        /** How recent a kept answer must be to be shown again instead of asking the model. */
        const val REUSE_WITHIN_MS = 10 * 60 * 1000L

        /** The longest the lookup for a kept answer may hold up a check. */
        const val REUSE_LOOKUP_MS = 300L
    }
}

private const val TAG = "CheckViewModel"

/** Runs a history step. History must never break a check, so a failure is logged and gives null. */
private inline fun <T> quietly(what: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "History: couldn't $what", e)
    null
}

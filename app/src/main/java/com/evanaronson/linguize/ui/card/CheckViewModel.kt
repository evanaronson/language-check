package com.evanaronson.linguize.ui.card

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.Checked
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.Answer
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * History is [CheckHistory]'s job; this only tells it what happens. A session is opened
 * with each fresh [check], gets an attempt per answer, and is closed by [dismiss], the
 * next [check] or the view model going away. [onCleared] closes it as not applied: a
 * host that hands the text back calls `dismiss(applied = true)` itself before finishing.
 */
class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val history = CheckHistory(
        store = app.history,
        environment = app.historyEnvironment,
        scope = app.appScope,
        log = { message, e -> Log.w(TAG, message, e) },
        maxChars = Checker.MAX_CHARS,
    )
    private var text = ""
    private var language: Language? = null
    private var origin = Origin.Tester
    private var hostApp: String? = null
    private var settled: List<Settled> = emptyList()

    /** Changes accepted before the current check started, kept while it runs. */
    private var carried: List<Edit> = emptyList()
    private var job: Job? = null

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
        history.close(finalText = null)
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
        val origin = origin
        val hostApp = hostApp
        val keep = carried
        job?.cancel()
        state = CardState.Loading(text)
        job = viewModelScope.launch {
            var answers = settled
            val shown = try {
                // Creating the checker reads its prompt from assets, so not on the main thread.
                val checker = withContext(Dispatchers.Default) { app.checker }
                val context = checker.context()
                val ask: suspend () -> Checked = { checker.check(text, language, answers, context) }
                val answer = if (fresh) {
                    val opening = Opening(
                        origin = origin,
                        hostApp = hostApp,
                        requestedLanguage = language?.name,
                        punctuation = context.punctuation,
                        judgments = context.judgments,
                        provider = context.provider,
                        model = context.model,
                        promptHash = context.promptHash,
                    )
                    history.firstAttempt(text, opening, ask) { kept -> checker.reuse(text, language, kept.verdict, context) }
                } else {
                    Answer(ask(), null)
                }
                answer.reused?.let {
                    answers = it.settled
                    settled = it.settled
                }
                val result = answer.value.result
                val kept = if (result is CheckResult.Reviewed) {
                    result.copy(revision = result.revision.acceptMatching(keep))
                } else {
                    result
                }
                history.succeeded(answers, answer.value.verdict, kept, reusedFrom = answer.reused?.sessionId)
                CardState.Done(kept, answers)
            } catch (failure: CheckFailure) {
                history.failed(answers, failure.reason.name, failure.detail)
                CardState.Failed(failure.reason, failure.detail)
            }
            state = shown
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
    fun copied(kind: EditKind) = history.copied(kind)

    /**
     * Closes the card. [applied] says whether its accepted changes went back to the text:
     * pass true only when the host really hands [workingText] back.
     */
    fun dismiss(applied: Boolean = true) {
        history.close(finalText = if (applied) workingText else null)
        job?.cancel()
        carried = emptyList()
        state = null
    }

    /** Gone without [dismiss]: nothing was handed back. */
    override fun onCleared() = history.close(finalText = null)

    private val reviewed get() = (state as? CardState.Done)?.result as? CheckResult.Reviewed

    private fun update(change: (Revision) -> Revision) {
        val done = state as? CardState.Done ?: return
        val current = done.result as? CheckResult.Reviewed ?: return
        val revision = change(current.revision)
        state = done.copy(result = current.copy(revision = revision))
        history.changed(revision)
    }
}

private const val TAG = "CheckViewModel"

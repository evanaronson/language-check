package com.evanaronson.linguize.ui.card

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
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
 */
class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private var text = ""
    private var language: Language? = null
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
        this.text = text
        this.language = language
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
        val settled = settled
        val keep = carried
        job?.cancel()
        state = CardState.Loading(text)
        job = viewModelScope.launch {
            state = try {
                // Creating the checker reads its prompt from assets, so not on the main thread.
                val checker = withContext(Dispatchers.Default) { app.checker }
                val result = checker.check(text, language, settled)
                val kept = if (result is CheckResult.Reviewed) {
                    result.copy(revision = result.revision.acceptMatching(keep))
                } else {
                    result
                }
                CardState.Done(kept, settled)
            } catch (failure: CheckFailure) {
                CardState.Failed(failure.reason, failure.detail)
            }
        }
    }

    fun accept(id: Int) = update { it.accept(id) }

    /** Accepts every remaining change of [kind]; returns true when nothing is left to decide. */
    fun acceptAll(kind: EditKind): Boolean {
        update { it.acceptAll(kind) }
        return reviewed?.isResolved == true
    }

    fun undo() = update { it.undo() }

    fun dismiss() {
        job?.cancel()
        carried = emptyList()
        state = null
    }

    private val reviewed get() = (state as? CardState.Done)?.result as? CheckResult.Reviewed

    private fun update(change: (Revision) -> Revision) {
        val done = state as? CardState.Done ?: return
        val current = done.result as? CheckResult.Reviewed ?: return
        state = done.copy(result = current.copy(revision = change(current.revision)))
    }
}

package com.evanaronson.languagecheck.ui.card

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.languagecheck.App
import com.evanaronson.languagecheck.llm.CheckFailure
import com.evanaronson.languagecheck.review.CheckResult
import com.evanaronson.languagecheck.review.Edit
import com.evanaronson.languagecheck.review.EditKind
import com.evanaronson.languagecheck.review.Language
import com.evanaronson.languagecheck.review.Revision
import com.evanaronson.languagecheck.review.Settled
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface CardState {
    data class Loading(val text: String) : CardState
    data class Failed(val reason: CheckFailure.Reason, val detail: String?) : CardState
    data class Done(
        val result: CheckResult,
        /** The writer's answers to assumptions, kept until the card closes. */
        val settled: List<Settled> = emptyList(),
    ) : CardState
}

/** One check and the changes the writer has accepted from it. */
class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val checks = (application as App).checks
    private var text = ""
    private var language: Language? = null
    private var settled: List<Settled> = emptyList()
    private var job: Job? = null

    /** Null before the first check and after [dismiss]. */
    var state by mutableStateOf<CardState?>(null)
        private set

    /** The text with accepted changes, or null when nothing has been accepted. */
    val workingText: String?
        get() = reviewed?.revision?.takeIf { it.acceptedCount > 0 }?.workingText

    /** Checks [text] as [language], or detects the language when it's null. */
    fun check(text: String, language: Language?) {
        this.text = text
        this.language = language
        settled = emptyList()
        run(keepAccepted = emptyList())
    }

    fun retry() = run(keepAccepted = emptyList())

    /**
     * Overrides an assumption with the writer's [answer] and checks again. Changes
     * already accepted stay accepted wherever the new suggestions are the same.
     */
    fun settle(about: String, answer: String) {
        val accepted = reviewed?.revision?.acceptedEdits.orEmpty()
        settled = settled.filterNot { it.about == about } + Settled(about, answer)
        run(keepAccepted = accepted)
    }

    private fun run(keepAccepted: List<Edit>) {
        val settled = settled
        job?.cancel()
        state = CardState.Loading(text)
        job = viewModelScope.launch {
            state = try {
                val result = checks.check(text, language, settled)
                val kept = if (result is CheckResult.Reviewed) {
                    result.copy(revision = result.revision.acceptMatching(keepAccepted))
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
        state = null
    }

    private val reviewed get() = (state as? CardState.Done)?.result as? CheckResult.Reviewed

    private fun update(change: (Revision) -> Revision) {
        val current = reviewed ?: return
        val settled = (state as? CardState.Done)?.settled.orEmpty()
        state = CardState.Done(current.copy(revision = change(current.revision)), settled)
    }
}

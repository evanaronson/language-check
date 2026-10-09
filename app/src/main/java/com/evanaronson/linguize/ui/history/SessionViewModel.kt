package com.evanaronson.linguize.ui.history

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.Replayed
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One past check, as its page shows it. */
sealed interface SessionPage {
    data object Loading : SessionPage

    /** Deleted, or never kept. */
    data object Missing : SessionPage

    data class Shown(
        val session: SessionRecord,
        val appLabel: String?,
        /** The last successful answer, rebuilt as the card showed it; null when none can be shown. */
        val card: CardState.Done?,
        /** Why the last attempt failed, if it did. */
        val failure: CheckFailure.Reason?,
    ) : SessionPage
}

/** Loads the past check the detail page shows, and deletes it. */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val labels = AppLabels(application.packageManager)
    private var id: String? = null
    private var job: Job? = null

    var page by mutableStateOf<SessionPage>(SessionPage.Loading)
        private set

    /**
     * Shows session [id], read again each time: since it was last shown, its card may have
     * closed or its first save landed. The same session stays on screen while it's reread.
     */
    fun load(id: String) {
        if (id != this.id) page = SessionPage.Loading
        this.id = id
        job?.cancel()
        job = viewModelScope.launch {
            val detail = app.history.detail(id)
            page = if (detail == null) {
                SessionPage.Missing
            } else {
                // Creating the checker reads its prompt from assets, so not on the main thread.
                withContext(Dispatchers.IO) { shown(detail, app.checker.replay(detail)) }
            }
        }
    }

    /** Deletes the session shown. The page closes straight away, so on the app's scope. */
    fun delete() {
        val id = id ?: return
        this.id = null
        app.appScope.launch { app.history.delete(id) }
    }

    /** [replayed] is the card the session ended with ([com.evanaronson.linguize.history.replay]); null when there's none to show. */
    private fun shown(detail: SessionDetail, replayed: Replayed?): SessionPage.Shown {
        val session = detail.session
        val failure = session.attempts.lastOrNull()?.failure
            ?.let { name -> CheckFailure.Reason.entries.firstOrNull { it.name == name } }
        return SessionPage.Shown(session, labels.of(session.hostApp), replayed?.let(::readOnlyCard), failure)
    }
}

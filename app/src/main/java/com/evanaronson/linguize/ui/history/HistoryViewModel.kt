package com.evanaronson.linguize.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row in Recent: the session, and the name of the app it came from when that's known. */
data class RecentRow(val summary: SessionSummary, val appLabel: String?)

/**
 * The Recent list on Home. Deleting from the list hides the row at once; the row is
 * deleted for real only once the writer has had the moment to undo.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val labels = AppLabels(application.packageManager)
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Newest first; null until the first read. */
    val recent: StateFlow<List<RecentRow>?> = combine(
        app.history.recent(LIMIT)
            .map { rows -> rows.map { RecentRow(it, labels.of(it.hostApp)) } }
            .flowOn(Dispatchers.IO),
        hidden,
    ) { rows, hidden -> rows.filterNot { it.summary.id in hidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Takes a row off the list while its deletion can still be undone. */
    fun hide(id: String) = hidden.update { it + id }

    fun restore(id: String) = hidden.update { it - id }

    /** Deletes for good. On the app's scope, so it happens even as the screen closes. */
    fun delete(id: String) {
        app.appScope.launch { app.history.delete(id) }
    }

    private companion object {
        const val LIMIT = 50
    }
}

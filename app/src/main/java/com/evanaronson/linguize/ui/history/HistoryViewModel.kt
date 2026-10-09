package com.evanaronson.linguize.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * deleted for real once the writer has had the moment to undo. That moment belongs to
 * this view model, not to the screen, so rotating or switching to dark mode while
 * "Check deleted · Undo" shows neither deletes the row early nor loses the Undo.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val labels = AppLabels(application.packageManager)

    /** Rows off the list: the one whose deletion can still be undone, and those deleted since. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val pending = MutableStateFlow<String?>(null)

    /** Commits [pending] when no Undo is on screen to answer for it. */
    private var timer: Job? = null

    /** Newest first; null until the first read. */
    val recent: StateFlow<List<RecentRow>?> = combine(
        app.history.recent(LIMIT)
            .map { rows -> rows.map { RecentRow(it, labels.of(it.hostApp)) } }
            .flowOn(Dispatchers.IO),
        hidden,
    ) { rows, hidden -> rows.filterNot { it.summary.id in hidden } }
        // No replay of a stale list once Home comes back (after Clear, say): null until read again.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    /** The row deleted from the list that can still be undone; null when there's none. */
    val pendingDelete: StateFlow<String?> = pending.asStateFlow()

    /**
     * Takes [id] off the list at once and deletes it for good unless [undoDelete] comes
     * first. A deletion still pending is committed now: there's one Undo at a time.
     */
    fun delete(id: String) {
        commitDelete()
        hidden.update { it + id }
        pending.value = id
        startTimer()
    }

    /** Puts the pending row back. Does nothing once [id] is no longer the pending one. */
    fun undoDelete(id: String) {
        if (pending.value != id) return
        timer?.cancel()
        pending.value = null
        hidden.update { it - id }
    }

    /**
     * Deletes the pending row for good: Undo went away unanswered, or the screen closed.
     * With [id], only if that row is still the pending one.
     */
    fun commitDelete(id: String? = null) {
        val pendingId = pending.value ?: return
        if (id != null && id != pendingId) return
        timer?.cancel()
        pending.value = null
        // On the app's scope, so it happens even as the screen closes.
        app.appScope.launch { app.history.delete(pendingId) }
    }

    /** Undo for [id] is on screen: the deletion waits for the writer's answer. */
    fun undoShown(id: String) {
        if (pending.value == id) timer?.cancel()
    }

    /**
     * Undo for [id] left the screen unanswered, because the screen changed or is being
     * recreated. The deletion goes ahead after a moment unless Undo is shown again.
     */
    fun undoHidden(id: String) {
        if (pending.value == id) startTimer()
    }

    /** The screen is gone for good (the activity finished): nothing is left to undo with. */
    override fun onCleared() = commitDelete()

    private fun startTimer() {
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(UNDO_MS)
            commitDelete()
        }
    }

    private companion object {
        const val LIMIT = 50

        /** How long a deletion waits for Undo while no Undo is on screen; about a short snackbar's time. */
        const val UNDO_MS = 4_000L
    }
}

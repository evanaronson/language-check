package com.evanaronson.linguize.history

import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Where history is kept. One implementation today (SQLite, on the phone); the rest of
 * the app only sees this interface. All functions are safe to call from the main
 * thread: implementations move their own work off it.
 *
 * Nothing here throws but cancellation: a store that can't be read or written logs it
 * and answers with nothing (an empty list, null, zero, false), because history must
 * never take the app down with it.
 *
 * [clear] and [delete] are final for the sessions they remove, including one still open
 * on a card: a later [save] or [close] of a session started before the last [clear], or
 * of one [delete]d, is dropped rather than bringing it back.
 */
interface HistoryStore {
    /** Adds a session as it opens, or updates it while it's open. Never changes a closed session. */
    suspend fun save(session: SessionRecord)

    /** Closes a session: writes its final row and its suggestions, in one transaction. */
    suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>)

    /** Marks sessions that never closed (the process ended) as [Outcome.Abandoned]. */
    suspend fun markAbandoned(now: Long)

    /** The number of sessions kept, not counting deleted ones. */
    fun count(): Flow<Int>

    /** The date of the oldest session kept, or null when there are none. */
    fun since(): Flow<Long?>

    /** Newest first, closed sessions only, not counting deleted ones. */
    fun recent(limit: Int): Flow<List<SessionSummary>>

    suspend fun detail(id: String): SessionDetail?

    /**
     * The most recent closed session matching [key], started at or after [since], whose
     * last attempt succeeded: its verdict can be shown again without asking the model.
     */
    suspend fun reusable(key: ReuseKey, since: Long): SessionRecord?

    suspend fun delete(id: String)

    /** Deletes everything. */
    suspend fun clear()

    /**
     * Writes every kept session with its suggestions as JSON Lines, one [SessionDetail] per
     * line. Returns false when it couldn't (the database or [out] failed); what was written
     * to [out] by then is incomplete and shouldn't be shared.
     */
    suspend fun export(out: OutputStream): Boolean
}

/**
 * What [HistoryStore.clear] and [HistoryStore.delete] removed in this process, so a
 * session still open on a card when it was removed isn't written back by its next save
 * or its close. A store consults it inside the same transaction as the write, so a clear
 * and a save can't interleave. Sessions open in an earlier process are closed as
 * abandoned at start, so nothing older needs remembering.
 */
internal class Forgotten(private val clock: () -> Long) {
    /** When everything was last cleared; sessions started then or before are gone. */
    @Volatile
    private var clearedAt = Long.MIN_VALUE

    private val deleted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun cleared() {
        clearedAt = clock()
        deleted.clear()
    }

    fun deleted(id: String) {
        deleted += id
    }

    /** Whether [session] may still be written. */
    fun allows(session: SessionRecord): Boolean = session.startedAt > clearedAt && session.id !in deleted
}

package com.evanaronson.linguize.history

import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * Where history is kept. One implementation today (Room, on the phone); the rest of
 * the app only sees this interface. All functions are safe to call from the main
 * thread: implementations move their own work off it.
 */
interface HistoryStore {
    /** Adds a session as it opens, or replaces it with a newer version of itself. */
    suspend fun save(session: SessionRecord)

    /** Closes a session: replaces its row and writes its suggestions, in one transaction. */
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
     * The most recent closed session on exactly this text and these settings, started at
     * or after [since], whose last attempt succeeded: its verdict can be shown again
     * without asking the model.
     */
    suspend fun reusable(
        textHash: String,
        requestedLanguage: String?,
        punctuation: String,
        judgments: String,
        provider: String,
        model: String,
        promptHash: String,
        since: Long,
    ): SessionRecord?

    suspend fun delete(id: String)

    /** Deletes everything. */
    suspend fun clear()

    /** Writes every kept session with its suggestions as JSON Lines, one [SessionDetail] per line. */
    suspend fun export(out: OutputStream)
}

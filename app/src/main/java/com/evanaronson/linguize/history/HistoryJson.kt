package com.evanaronson.linguize.history

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * The JSON side of history: the `attempts` column and the export. Kept apart from
 * HistorySchema so that the row mapping can be read without the serialization plugin.
 */

/** Every field written, unknown ones skipped on reading. */
internal val historyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val attemptList = ListSerializer(Attempt.serializer())

internal fun encodeAttempts(attempts: List<Attempt>): String = historyJson.encodeToString(attemptList, attempts)

/** The `attempts` column read back; empty when it can't be read, which only loses the audit trail. */
internal fun decodeAttempts(stored: String): List<Attempt> = try {
    historyJson.decodeFromString(attemptList, stored)
} catch (_: SerializationException) {
    emptyList()
} catch (_: IllegalArgumentException) {
    emptyList()
}

/** One line of the export: a session with its suggestions, as JSON with no line breaks. */
internal fun exportLine(detail: SessionDetail): String = historyJson.encodeToString(SessionDetail.serializer(), detail)

package com.evanaronson.linguize.history

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * The `attempts` column of a version-1 row in this version's shape: an attempt's answer
 * moves from `verdict` to `raw`. Anything else, and a column that can't be read, is left
 * as it is.
 */
internal fun upgradeAttempts(stored: String): String {
    val attempts = try {
        historyJson.parseToJsonElement(stored) as? JsonArray ?: return stored
    } catch (_: SerializationException) {
        return stored
    }
    val upgraded = attempts.map { attempt ->
        if (attempt !is JsonObject || "verdict" !in attempt || "raw" in attempt) return@map attempt
        JsonObject(attempt.entries.associate { (key, value) -> (if (key == "verdict") "raw" else key) to value })
    }
    return if (upgraded == attempts) stored else JsonArray(upgraded).toString()
}

/**
 * A row as JSON, column by column, exactly as it's stored: numbers as numbers, text as
 * text, and `attempts` as the JSON it holds. Nothing is decoded, so a token this build
 * doesn't know, or a field of an attempt it doesn't know, is written out unchanged.
 */
internal fun rowJson(row: Row, columns: List<String>): JsonObject = JsonObject(
    columns.associateWith { column ->
        when (column) {
            in INTEGER_COLUMNS -> row.longOrNull(column)?.let(::JsonPrimitive) ?: JsonNull
            "attempts" -> row.stringOrNull(column)?.let(::storedJson) ?: JsonNull
            else -> row.stringOrNull(column)?.let(::JsonPrimitive) ?: JsonNull
        }
    },
)

/** [stored] as the JSON it is, or as a string when it isn't JSON. */
private fun storedJson(stored: String): JsonElement = try {
    historyJson.parseToJsonElement(stored)
} catch (_: SerializationException) {
    JsonPrimitive(stored)
}

/**
 * One line of the export: a session row with its suggestion rows, in [SessionDetail]'s
 * shape, as JSON with no line breaks.
 */
internal fun exportLine(session: JsonObject, suggestions: List<JsonObject>): String =
    JsonObject(mapOf("session" to session, "suggestions" to JsonArray(suggestions))).toString()

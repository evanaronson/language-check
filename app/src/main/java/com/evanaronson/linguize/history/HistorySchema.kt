package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind

/*
 * The SQLite shape of history, and the mapping between rows and records. Pure Kotlin
 * with no Android in it, so the mapping is unit-tested; SqliteHistoryStore runs it.
 *
 * Columns are named exactly like the fields of the records, so a row in a SQLite browser
 * reads like the JSON it exports as. Enums are stored as their [Stored] tokens and read
 * leniently. `attempts` is JSON (it's
 * kept for re-deriving things later, never queried); everything else is a real column.
 * Identifiers are always double-quoted because `end` is an SQL keyword.
 */
internal object HistorySchema {
    const val NAME = "history.db"

    /**
     * The database's version, for SQLiteOpenHelper. Separate from [SessionRecord.SCHEMA],
     * which is the shape of a row as it would travel to a server.
     */
    const val VERSION = 1

    const val SESSIONS = "sessions"
    const val SUGGESTIONS = "suggestions"

    /** In [SessionRecord]'s field order; a test holds the two together. */
    val SESSION_COLUMNS = listOf(
        "id", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "startedAt", "closedAt",
        "origin", "hostApp", "requestedLanguage", "text", "textHash", "finalText", "outcome",
        "punctuation", "judgments", "provider", "model", "promptHash", "appVersion", "attempts", "meaning", "status",
    )

    /** In [SuggestionRecord]'s field order; a test holds the two together. */
    val SUGGESTION_COLUMNS = listOf(
        "id", "sessionId", "deviceId", "schema", "createdAt", "updatedAt", "deletedAt", "attempt",
        "kind", "start", "end", "fromText", "toText", "why", "decision", "decidedAt",
    )

    val CREATE = listOf(
        """
        CREATE TABLE $SESSIONS (
            "id" TEXT PRIMARY KEY NOT NULL,
            "deviceId" TEXT NOT NULL,
            "schema" INTEGER NOT NULL,
            "createdAt" INTEGER NOT NULL,
            "updatedAt" INTEGER NOT NULL,
            "deletedAt" INTEGER,
            "startedAt" INTEGER NOT NULL,
            "closedAt" INTEGER,
            "origin" TEXT NOT NULL,
            "hostApp" TEXT,
            "requestedLanguage" TEXT,
            "text" TEXT NOT NULL,
            "textHash" TEXT NOT NULL,
            "finalText" TEXT,
            "outcome" TEXT,
            "punctuation" TEXT NOT NULL,
            "judgments" TEXT NOT NULL,
            "provider" TEXT NOT NULL,
            "model" TEXT NOT NULL,
            "promptHash" TEXT NOT NULL,
            "appVersion" TEXT NOT NULL,
            "attempts" TEXT NOT NULL,
            "meaning" TEXT,
            "status" TEXT
        )
        """,
        """
        CREATE TABLE $SUGGESTIONS (
            "id" TEXT PRIMARY KEY NOT NULL,
            "sessionId" TEXT NOT NULL REFERENCES $SESSIONS("id") ON DELETE CASCADE,
            "deviceId" TEXT NOT NULL,
            "schema" INTEGER NOT NULL,
            "createdAt" INTEGER NOT NULL,
            "updatedAt" INTEGER NOT NULL,
            "deletedAt" INTEGER,
            "attempt" INTEGER NOT NULL,
            "kind" TEXT NOT NULL,
            "start" INTEGER NOT NULL,
            "end" INTEGER NOT NULL,
            "fromText" TEXT NOT NULL,
            "toText" TEXT NOT NULL,
            "why" TEXT,
            "decision" TEXT NOT NULL,
            "decidedAt" INTEGER NOT NULL
        )
        """,
        // Recent, the count and the oldest date: kept rows, by start.
        """CREATE INDEX sessions_kept ON $SESSIONS("deletedAt", "startedAt")""",
        // reusable(): the same text, recently.
        """CREATE INDEX sessions_text ON $SESSIONS("textHash", "startedAt")""",
        // A session's suggestions, for Recent's counts, the detail view and the cascade.
        """CREATE INDEX suggestions_session ON $SUGGESTIONS("sessionId")""",
    ).map { it.trimIndent() }

    /**
     * Adds a session or updates it in place, but never changes one that's closed: a closed
     * session is final, so a save that arrives after close() is dropped. (An update, not a
     * REPLACE, which would delete the row and cascade to its suggestions.)
     */
    val SAVE_SESSION = upsert(SESSIONS, SESSION_COLUMNS) + """ WHERE $SESSIONS."closedAt" IS NULL"""

    /** Adds or updates a session whatever its state: closing writes the final version. */
    val CLOSE_SESSION = upsert(SESSIONS, SESSION_COLUMNS)

    val INSERT_SUGGESTION = "INSERT OR REPLACE INTO $SUGGESTIONS (${quoted(SUGGESTION_COLUMNS)}) " +
        "VALUES (${SUGGESTION_COLUMNS.joinToString { "?" }})"

    /** Binds now, now, and the start of this process: sessions opened since are left alone. */
    val MARK_ABANDONED = """
        UPDATE $SESSIONS SET "closedAt" = ?, "updatedAt" = ?, "outcome" = '${Stored.outcome.encode(Outcome.Abandoned)}'
        WHERE "closedAt" IS NULL AND "startedAt" < ?
    """.trimIndent()

    const val COUNT = """SELECT COUNT(*) AS "n" FROM $SESSIONS WHERE "deletedAt" IS NULL"""

    const val SINCE = """SELECT MIN("startedAt") AS "since" FROM $SESSIONS WHERE "deletedAt" IS NULL"""

    /**
     * Closed, kept sessions, newest first, with counts from the suggestions that weren't
     * superseded: those are the ones decided at the close. Only the start of each text is
     * read: a row shows one line of it.
     */
    fun recent(limit: Int) = """
        SELECT s."id", s."startedAt", s."origin", s."hostApp", s."text", s."outcome", s."status",
            COALESCE(SUM(g."kind" = '$FIX'), 0) AS "fixes",
            COALESCE(SUM(g."kind" = '$NATURAL'), 0) AS "rewordings",
            COALESCE(SUM(g."decision" = '$ACCEPTED'), 0) AS "taken"
        FROM (
            SELECT "id", "startedAt", "origin", "hostApp", substr("text", 1, ${SessionSummary.TEXT_PREFIX}) AS "text", "outcome", "status"
            FROM $SESSIONS
            WHERE "deletedAt" IS NULL AND "closedAt" IS NOT NULL
            ORDER BY "startedAt" DESC
            LIMIT $limit
        ) AS s
        LEFT JOIN $SUGGESTIONS AS g
            ON g."sessionId" = s."id" AND g."deletedAt" IS NULL AND g."decision" != '$SUPERSEDED'
        GROUP BY s."id"
        ORDER BY s."startedAt" DESC
    """.trimIndent()

    private val FIX = Stored.editKind.encode(EditKind.Fix)
    private val NATURAL = Stored.editKind.encode(EditKind.Natural)
    private val ACCEPTED = Stored.decision.encode(Decision.Accepted)
    private val SUPERSEDED = Stored.decision.encode(Decision.Superseded)

    const val SESSION = """SELECT * FROM $SESSIONS WHERE "id" = ? AND "deletedAt" IS NULL"""

    const val SESSION_SUGGESTIONS =
        """SELECT * FROM $SUGGESTIONS WHERE "sessionId" = ? AND "deletedAt" IS NULL ORDER BY "attempt", "start", "end" """

    const val ALL_SESSIONS = """SELECT * FROM $SESSIONS WHERE "deletedAt" IS NULL ORDER BY "startedAt" """

    /**
     * Candidates for reuse, newest first; binds [reusableArgs]. Whether the last attempt
     * succeeded is read from `attempts` by the caller.
     */
    fun reusable(requestedLanguage: String?): String {
        val language = if (requestedLanguage == null) "\"requestedLanguage\" IS NULL" else "\"requestedLanguage\" = ?"
        return """
        SELECT * FROM $SESSIONS
        WHERE "textHash" = ?
            AND $language
            AND "punctuation" = ? AND "judgments" = ? AND "provider" = ? AND "model" = ? AND "promptHash" = ?
            AND "startedAt" >= ?
            AND "closedAt" IS NOT NULL AND "deletedAt" IS NULL AND "outcome" != '${Stored.outcome.encode(Outcome.Failed)}'
        ORDER BY "startedAt" DESC
        LIMIT 20
        """.trimIndent()
    }

    /** The values [reusable] binds, in order. */
    fun reusableArgs(key: ReuseKey, since: Long): List<String> =
        listOfNotNull(key.textHash, key.requestedLanguage, key.punctuation, key.judgments, key.provider, key.model, key.promptHash) +
            since.toString()

    const val DELETE_SESSION = """DELETE FROM $SESSIONS WHERE "id" = ?"""

    /** Clearing everything; the suggestions go by cascade too, this is just quicker. */
    val CLEAR = listOf("DELETE FROM $SUGGESTIONS", "DELETE FROM $SESSIONS")

    const val DELETE_SUGGESTIONS_OF = """DELETE FROM $SUGGESTIONS WHERE "sessionId" = ?"""

    private fun quoted(columns: List<String>) = columns.joinToString { "\"$it\"" }

    private fun upsert(table: String, columns: List<String>) =
        "INSERT INTO $table (${quoted(columns)}) VALUES (${columns.joinToString { "?" }}) " +
            "ON CONFLICT(\"id\") DO UPDATE SET " +
            columns.filter { it != "id" }.joinToString { "\"$it\" = excluded.\"$it\"" }
}

/** One column of the current row, by name. SQLite's cursor in the app; a map in tests. */
internal interface Row {
    fun stringOrNull(column: String): String?

    fun longOrNull(column: String): Long?

    fun string(column: String): String = checkNotNull(stringOrNull(column)) { "$column is null" }

    fun long(column: String): Long = checkNotNull(longOrNull(column)) { "$column is null" }
}

/** The values to bind for [HistorySchema.SESSION_COLUMNS], by column. */
internal fun SessionRecord.values(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "deviceId" to deviceId,
    "schema" to schema,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "deletedAt" to deletedAt,
    "startedAt" to startedAt,
    "closedAt" to closedAt,
    "origin" to Stored.origin.encode(origin),
    "hostApp" to hostApp,
    "requestedLanguage" to requestedLanguage,
    "text" to text,
    "textHash" to textHash,
    "finalText" to finalText,
    "outcome" to outcome?.let(Stored.outcome::encode),
    "punctuation" to punctuation,
    "judgments" to judgments,
    "provider" to provider,
    "model" to model,
    "promptHash" to promptHash,
    "appVersion" to appVersion,
    "attempts" to encodeAttempts(attempts),
    "meaning" to meaning,
    "status" to status?.let(Stored.status::encode),
)

/** The values to bind for [HistorySchema.SUGGESTION_COLUMNS], by column. */
internal fun SuggestionRecord.values(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "sessionId" to sessionId,
    "deviceId" to deviceId,
    "schema" to schema,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "deletedAt" to deletedAt,
    "attempt" to attempt,
    "kind" to Stored.editKind.encode(kind),
    "start" to start,
    "end" to end,
    "fromText" to fromText,
    "toText" to toText,
    "why" to why,
    "decision" to Stored.decision.encode(decision),
    "decidedAt" to decidedAt,
)

/**
 * A session row as a record. Lenient where a newer build could have written something
 * this one doesn't know: an unknown origin reads as [UNKNOWN_ORIGIN], an unknown outcome
 * as null, unreadable attempts as none.
 */
internal fun sessionRecord(row: Row) = SessionRecord(
    id = row.string("id"),
    deviceId = row.string("deviceId"),
    schema = row.long("schema").toInt(),
    createdAt = row.long("createdAt"),
    updatedAt = row.long("updatedAt"),
    deletedAt = row.longOrNull("deletedAt"),
    startedAt = row.long("startedAt"),
    closedAt = row.longOrNull("closedAt"),
    origin = Stored.origin.decodeOrName(row.string("origin")) ?: UNKNOWN_ORIGIN,
    hostApp = row.stringOrNull("hostApp"),
    requestedLanguage = row.stringOrNull("requestedLanguage"),
    text = row.string("text"),
    textHash = row.string("textHash"),
    finalText = row.stringOrNull("finalText"),
    outcome = Stored.outcome.decodeOrName(row.stringOrNull("outcome")),
    punctuation = row.string("punctuation"),
    judgments = row.string("judgments"),
    provider = row.string("provider"),
    model = row.string("model"),
    promptHash = row.string("promptHash"),
    appVersion = row.string("appVersion"),
    attempts = decodeAttempts(row.string("attempts")),
    meaning = row.stringOrNull("meaning"),
    status = Stored.status.decode(row.stringOrNull("status")),
)

/**
 * A suggestion row as a record, or null when its kind or decision is one this build
 * doesn't know: a suggestion can't be shown or counted without them.
 */
internal fun suggestionRecord(row: Row): SuggestionRecord? {
    val kind = Stored.editKind.decodeOrName(row.string("kind")) ?: return null
    val decision = Stored.decision.decodeOrName(row.string("decision")) ?: return null
    return SuggestionRecord(
        id = row.string("id"),
        sessionId = row.string("sessionId"),
        deviceId = row.string("deviceId"),
        schema = row.long("schema").toInt(),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        deletedAt = row.longOrNull("deletedAt"),
        attempt = row.long("attempt").toInt(),
        kind = kind,
        start = row.long("start").toInt(),
        end = row.long("end").toInt(),
        fromText = row.string("fromText"),
        toText = row.string("toText"),
        why = row.stringOrNull("why"),
        decision = decision,
        decidedAt = row.long("decidedAt"),
    )
}

/** A row of [HistorySchema.recent]. */
internal fun sessionSummary(row: Row) = SessionSummary(
    id = row.string("id"),
    startedAt = row.long("startedAt"),
    origin = Stored.origin.decodeOrName(row.string("origin")) ?: UNKNOWN_ORIGIN,
    hostApp = row.stringOrNull("hostApp"),
    text = row.string("text"),
    outcome = Stored.outcome.decodeOrName(row.stringOrNull("outcome")),
    fixes = row.long("fixes").toInt(),
    rewordings = row.long("rewordings").toInt(),
    taken = row.long("taken").toInt(),
    status = Stored.status.decode(row.stringOrNull("status")),
)

/**
 * What an origin this build doesn't know reads as: the selection menu, where most checks
 * start. Recent shows it like any other check rather than hiding it as the tester.
 */
internal val UNKNOWN_ORIGIN = Origin.Menu

/** Whether a session's verdict can be shown again: its last attempt came back with one. */
internal fun SessionRecord.lastAttemptSucceeded(): Boolean =
    attempts.lastOrNull()?.let { it.verdict != null && it.failure == null } == true

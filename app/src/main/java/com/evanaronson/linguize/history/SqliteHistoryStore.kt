package com.evanaronson.linguize.history

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * [HistoryStore] on the phone's SQLite, with the SQL written out in [HistorySchema].
 * The framework's SQLiteOpenHelper rather than Room: two tables don't need code generation.
 *
 * Creating it touches nothing; the database is opened (and made, the first time) by the
 * first call, on [Dispatchers.IO] like every other. The observed queries re-run after any
 * write made through this store.
 *
 * Deleting is a hard delete for now. The rows have `deletedAt` so a deletion can travel
 * once there's a sync; until then there's nothing to carry it, and the writer asked for
 * the text to be gone.
 */
class SqliteHistoryStore(context: Context) : HistoryStore {
    private val context = context.applicationContext
    private val helper = Helper(this.context)

    /** Bumped after every write, so the observed queries run again. */
    private val changes = MutableStateFlow(0L)

    override suspend fun save(session: SessionRecord) = write { db ->
        db.execute(HistorySchema.SAVE_SESSION, session.values().values)
    }

    override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = write { db ->
        db.execute(HistorySchema.CLOSE_SESSION, session.values().values)
        // Closing twice writes the same rows again rather than adding to them.
        db.execute(HistorySchema.DELETE_SUGGESTIONS_OF, listOf(session.id))
        val insert = db.compileStatement(HistorySchema.INSERT_SUGGESTION)
        try {
            for (suggestion in suggestions) {
                insert.clearBindings()
                insert.bindAll(suggestion.values().values)
                insert.executeInsert()
            }
        } finally {
            insert.close()
        }
    }

    /**
     * Closes sessions left open, started before [now]: call it with the time the process
     * started, so a check that opens while this runs isn't caught. Doesn't make the
     * database when there isn't one yet: then there's nothing to mark.
     */
    override suspend fun markAbandoned(now: Long) {
        val exists = withContext(Dispatchers.IO) { context.getDatabasePath(HistorySchema.NAME).exists() }
        if (!exists) return
        write { db -> db.execute(HistorySchema.MARK_ABANDONED, listOf(now, now, now)) }
    }

    override fun count(): Flow<Int> = observe { db ->
        db.select(HistorySchema.COUNT) { it.long("n").toInt() }.single()
    }

    override fun since(): Flow<Long?> = observe { db ->
        db.select(HistorySchema.SINCE) { it.longOrNull("since") }.single()
    }

    override fun recent(limit: Int): Flow<List<SessionSummary>> = observe { db ->
        db.select(HistorySchema.recent(limit), read = ::sessionSummary)
    }

    override suspend fun detail(id: String): SessionDetail? = withContext(Dispatchers.IO) {
        helper.writableDatabase.detail(id)
    }

    override suspend fun reusable(
        textHash: String,
        requestedLanguage: String?,
        punctuation: String,
        judgments: String,
        provider: String,
        model: String,
        promptHash: String,
        since: Long,
    ): SessionRecord? = withContext(Dispatchers.IO) {
        val args = listOfNotNull(textHash, requestedLanguage, punctuation, judgments, provider, model, promptHash) +
            since.toString()
        helper.writableDatabase
            .select(HistorySchema.reusable(requestedLanguage), args, ::sessionRecord)
            .firstOrNull { it.lastAttemptSucceeded() }
    }

    /** Removes the session and, by cascade, its suggestions. */
    override suspend fun delete(id: String) = write { db ->
        db.execute(HistorySchema.DELETE_SESSION, listOf(id))
    }

    override suspend fun clear() = write { db ->
        HistorySchema.CLEAR.forEach(db::execSQL)
    }

    /** Oldest first. Leaves [out] open; the caller closes it. */
    override suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val writer = out.bufferedWriter()
        // One transaction, so a delete during the export can't split a session from its suggestions.
        db.beginTransaction()
        try {
            db.eachRow(HistorySchema.ALL_SESSIONS) { row ->
                val session = sessionRecord(row)
                writer.write(exportLine(SessionDetail(session, db.suggestions(session.id))))
                writer.newLine()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        writer.flush()
    }

    private fun SQLiteDatabase.detail(id: String): SessionDetail? {
        val session = select(HistorySchema.SESSION, listOf(id), ::sessionRecord).firstOrNull() ?: return null
        return SessionDetail(session, suggestions(id))
    }

    private fun SQLiteDatabase.suggestions(sessionId: String) =
        select(HistorySchema.SESSION_SUGGESTIONS, listOf(sessionId), ::suggestionRecord)

    /** Runs [block] in a transaction on [Dispatchers.IO], then tells the observed queries. */
    private suspend fun write(block: (SQLiteDatabase) -> Unit) {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        changes.update { it + 1 }
    }

    /** [read] now and again after every write, on [Dispatchers.IO]. */
    private fun <T> observe(read: (SQLiteDatabase) -> T): Flow<T> = changes
        .map { read(helper.writableDatabase) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, HistorySchema.NAME, null, HistorySchema.VERSION) {
        init {
            // Lets the observed queries read while a close is being written.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            HistorySchema.CREATE.forEach(db::execSQL)
        }

        /**
         * There's only version 1. Whoever makes version 2 writes the migration here; until
         * then, failing loudly beats opening a database whose shape the queries don't know.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No migration for history from version $oldVersion to $newVersion")
        }
    }
}

private class CursorRow(private val cursor: Cursor) : Row {
    override fun stringOrNull(column: String): String? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getString(index)
    }

    override fun longOrNull(column: String): Long? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getLong(index)
    }
}

/** Calls [each] for every row of [sql]. Numbers in [args] compare as numbers against INTEGER columns. */
private fun SQLiteDatabase.eachRow(sql: String, args: List<String> = emptyList(), each: (Row) -> Unit) {
    rawQuery(sql, args.toTypedArray()).use { cursor ->
        val row = CursorRow(cursor)
        while (cursor.moveToNext()) each(row)
    }
}

private fun <T> SQLiteDatabase.select(sql: String, args: List<String> = emptyList(), read: (Row) -> T): List<T> =
    buildList { eachRow(sql, args) { add(read(it)) } }

/** Runs one statement with [values] bound in order; returns the rows changed. */
private fun SQLiteDatabase.execute(sql: String, values: Collection<Any?>): Int {
    val statement = compileStatement(sql)
    try {
        statement.bindAll(values)
        return statement.executeUpdateDelete()
    } finally {
        statement.close()
    }
}

private fun SQLiteStatement.bindAll(values: Collection<Any?>) {
    values.forEachIndexed { i, value ->
        when (value) {
            null -> bindNull(i + 1)
            is String -> bindString(i + 1, value)
            is Int -> bindLong(i + 1, value.toLong())
            is Long -> bindLong(i + 1, value)
            else -> error("History can't store a ${value::class.simpleName}")
        }
    }
}

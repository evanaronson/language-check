package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.llm.Provider
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySchemaTest {
    internal val session = SessionRecord(
        id = "s1",
        deviceId = "device",
        createdAt = 1_000,
        updatedAt = 2_000,
        startedAt = 1_000,
        closedAt = 2_000,
        origin = Origin.Button,
        hostApp = "org.telegram.messenger",
        requestedLanguage = "Catalan",
        nativeLanguage = "English",
        text = "Com estas?\nBé.",
        textHash = "abc123",
        finalText = "Com estàs?\nBé.",
        outcome = Outcome.Applied,
        punctuation = "moderate",
        judgments = "both",
        provider = "gemini",
        model = "gemini-x",
        promptHash = "p1",
        appVersion = "0.1.7",
        attempts = listOf(
            Attempt(at = 1_100, failure = "Timeout"),
            Attempt(at = 1_500, settled = listOf(Settled("tu", "informal")), raw = """{"status":"ok"}""", reusedFrom = "s0"),
        ),
        meaning = "How are you?",
        status = Verdict.Status.Ok,
    )

    internal val suggestion = SuggestionRecord(
        id = "g1",
        sessionId = "s1",
        deviceId = "device",
        createdAt = 2_000,
        updatedAt = 2_000,
        attempt = 1,
        kind = EditKind.Fix,
        start = 4,
        end = 9,
        fromText = "estas",
        toText = "estàs",
        why = "Accent",
        decision = Decision.Accepted,
        decidedAt = 2_000,
    )

    @Test
    fun aSessionReadsBackAsItWasWritten() {
        assertEquals(session, sessionRecord(MapRow(session.values())))
        val open = session.copy(
            closedAt = null, outcome = null, finalText = null, hostApp = null, nativeLanguage = null, attempts = emptyList(), status = null,
        )
        assertEquals(open, sessionRecord(MapRow(open.values())))
    }

    @Test
    fun aSuggestionReadsBackAsItWasWritten() {
        assertEquals(suggestion, suggestionRecord(MapRow(suggestion.values())))
        val bare = suggestion.copy(why = null, decision = Decision.Superseded)
        assertEquals(bare, suggestionRecord(MapRow(bare.values())))
    }

    @Test
    fun enumsAreStoredAsLowercaseTokens() {
        assertEquals("button", session.values()["origin"])
        assertEquals("applied", session.values()["outcome"])
        assertEquals("ok", session.values()["status"])
        assertEquals("accepted", suggestion.values()["decision"])
        assertEquals("fix", suggestion.values()["kind"])
        assertEquals("wrong_language", Stored.status.encode(Verdict.Status.WrongLanguage))
        assertEquals("naturalize", Stored.judgments.encode(Judgments.NaturalizeOnly))
        assertEquals("casual", Stored.punctuation.encode(Punctuation.Casual))
        assertEquals("openai", Stored.provider.encode(Provider.OpenAI))
    }

    @Test
    fun everyTokenReadsBack() {
        fun <E : Enum<E>> roundTrips(tokens: Tokens<E>, entries: List<E>) = entries.forEach { assertEquals(it, tokens.decode(tokens.encode(it))) }
        roundTrips(Stored.origin, Origin.entries)
        roundTrips(Stored.decision, Decision.entries)
        roundTrips(Stored.outcome, Outcome.entries)
        roundTrips(Stored.editKind, EditKind.entries)
        roundTrips(Stored.status, Verdict.Status.entries)
        roundTrips(Stored.punctuation, Punctuation.entries)
        roundTrips(Stored.judgments, Judgments.entries)
        roundTrips(Stored.provider, Provider.entries)
    }

    @Test
    fun unknownTokensReadLeniently() {
        assertNull(Stored.decision.decode("postponed"))
        assertNull(Stored.decision.decode(null))
        // Settings written by earlier builds hold constant names.
        assertEquals(Judgments.FixOnly, Stored.judgments.decodeOrName("FixOnly"))
        assertNull(Stored.judgments.decodeOrName("Everything"))

        val newer = session.values() + mapOf("origin" to "widget", "outcome" to "snoozed", "status" to "maybe", "attempts" to "not json")
        val read = sessionRecord(MapRow(newer))
        // Names are read as tokens only: the upgrade rewrote them.
        assertNull(sessionRecord(MapRow(session.values() + ("origin" to "Button"))).origin)
        assertNull(read.origin)
        assertNull(read.outcome)
        assertNull(read.status)
        assertTrue(read.attempts.isEmpty())

        assertNull(suggestionRecord(MapRow(suggestion.values() + ("kind" to "style"))))
        assertNull(suggestionRecord(MapRow(suggestion.values() + ("decision" to "postponed"))))

        val summary = sessionSummary(MapRow(mapOf<String, Any?>(
            "id" to "s1", "startedAt" to 1L, "origin" to "widget", "hostApp" to null, "text" to "t",
            "outcome" to "snoozed", "status" to "unclear", "fixes" to 1L, "rewordings" to 0L, "taken" to 0L,
        )))
        assertNull(summary.origin)
        assertNull(summary.outcome)
        assertEquals(Verdict.Status.Unclear, summary.status)
    }

    @Test
    fun recentReadsOnlyThePrefixOfEachText() {
        val sql = HistorySchema.recent(50)
        assertTrue(sql.contains("substr(\"text\", 1, ${SessionSummary.TEXT_PREFIX})"))
        assertFalse(sql.contains("SELECT * FROM"))
        assertTrue(sql.contains("'superseded'") && sql.contains("'accepted'") && sql.contains("'fix'"))
    }

    @Test
    fun theReuseKeyBindsInOrder() {
        val key = session.reuseKey
        assertEquals(
            listOf("abc123", "Catalan", "English", "moderate", "both", "gemini", "gemini-x", "p1", "5"),
            HistorySchema.reusableArgs(key, 5),
        )
        assertEquals(8, HistorySchema.reusableArgs(key.copy(requestedLanguage = null), 5).size)
        assertEquals(7, HistorySchema.reusableArgs(key.copy(requestedLanguage = null, nativeLanguage = null), 5).size)
    }

    @Test
    fun aClearOrDeleteForgetsSessionsStillOpen() {
        val forgotten = Forgotten()
        assertTrue(forgotten.saving("s1"))
        assertTrue(forgotten.saving("s2"))
        forgotten.deleted("s1")
        assertFalse(forgotten.saving("s1"))
        assertFalse(forgotten.closing("s1"))
        assertTrue(forgotten.saving("s2"))

        // Whatever the clock says: what was open at the clear is gone, what opens after isn't.
        forgotten.cleared()
        assertFalse(forgotten.saving("s2"))
        assertFalse(forgotten.closing("s2"))
        assertTrue(forgotten.saving("s3"))
        assertTrue(forgotten.closing("s3"))
    }

    @Test
    fun theSessionsOpenedHereAreTheOnesStillOpen() {
        val forgotten = Forgotten()
        forgotten.saving("a")
        forgotten.saving("b")
        forgotten.saving("c")
        forgotten.closing("b")
        forgotten.deleted("c")
        assertEquals(listOf("a"), forgotten.openHere())
    }

    @Test
    fun abandonedMeansOpenAndNotOpenedHere() {
        val none = HistorySchema.markAbandoned(0)
        assertTrue(none.contains("\"closedAt\" IS NULL"))
        assertFalse(none.contains("NOT IN"))
        assertFalse(none.contains("startedAt"))
        assertTrue(HistorySchema.markAbandoned(2).contains("\"id\" NOT IN (?, ?)"))
    }

    @Test
    fun theCountIsWhatRecentShows() {
        assertTrue(HistorySchema.COUNT.contains("\"closedAt\" IS NOT NULL"))
        assertTrue(HistorySchema.SINCE.contains("\"closedAt\" IS NOT NULL"))
    }

    @Test
    fun onlyASessionWhoseLastAttemptSucceededCanBeReused() {
        assertTrue(session.lastAttemptSucceeded())
        assertFalse(session.copy(attempts = session.attempts.reversed()).lastAttemptSucceeded())
        assertFalse(session.copy(attempts = emptyList()).lastAttemptSucceeded())
        // An answer that came but couldn't be read isn't one to show again.
        val unreadable = Attempt(at = 1_600, raw = "not json", failure = "BadResponse")
        assertFalse(session.copy(attempts = session.attempts + unreadable).lastAttemptSucceeded())
    }

    @Test
    fun theDecidedAttemptIsTheOneTheDecidedSuggestionsCameFrom() {
        val ok = Attempt(at = 1, raw = "{}")
        val failed = Attempt(at = 2, failure = "Offline")
        fun decided(attempts: List<Attempt>, vararg rows: Pair<Int, Decision>) = SessionDetail(
            session.copy(attempts = attempts),
            rows.map { (attempt, decision) -> suggestion.copy(attempt = attempt, decision = decision) },
        ).decidedAttempt()
        // The suggestions decided at the close came from attempt 0; attempt 1 was unclear.
        assertEquals(0, decided(listOf(ok, ok), 0 to Decision.Accepted))
        assertEquals(1, decided(listOf(ok, ok), 0 to Decision.Superseded, 1 to Decision.Ignored))
        // No suggestions: the last that succeeded.
        assertEquals(1, decided(listOf(ok, ok, failed)))
        assertNull(decided(listOf(failed)))
        assertNull(decided(emptyList()))
    }

    @Test
    fun theLanguagesAreMatchedEvenWhenAuto() {
        val key = session.reuseKey
        assertTrue(HistorySchema.reusable(key.copy(requestedLanguage = null)).contains("\"requestedLanguage\" IS NULL"))
        assertTrue(HistorySchema.reusable(key).contains("\"requestedLanguage\" = ?"))
        assertTrue(HistorySchema.reusable(key).contains("\"nativeLanguage\" = ?"))
        assertTrue(HistorySchema.reusable(key.copy(nativeLanguage = null)).contains("\"nativeLanguage\" IS NULL"))
    }

    @Test
    fun version1UpgradesAddingWhatsMissingAndRenamingEnums() {
        val withoutStatus = HistorySchema.SESSION_COLUMNS.toSet() - "status" - "nativeLanguage"
        val first = HistorySchema.upgrade(1, withoutStatus)!!
        assertTrue(first.any { it.contains("ADD COLUMN \"status\"") })
        assertTrue(first.any { it.contains("ADD COLUMN \"nativeLanguage\"") })
        val later = HistorySchema.upgrade(1, HistorySchema.SESSION_COLUMNS.toSet() - "nativeLanguage")!!
        assertFalse(later.any { it.contains("ADD COLUMN \"status\"") })
        for (column in listOf("origin", "outcome", "punctuation", "judgments", "provider", "kind", "decision")) {
            assertTrue(column, later.any { it.contains("SET \"$column\" = CASE") })
        }
        assertTrue(later.any { it.contains("WHEN 'Accepted' THEN 'accepted'") })
        assertTrue(later.any { it.contains("WHEN 'FixOnly' THEN 'fix'") })
        assertTrue(later.any { it.contains("WHEN 'Gemini' THEN 'gemini'") })
        assertTrue(later.any { it.contains("WHEN 'Menu' THEN 'menu'") })
    }

    @Test
    fun aVersionWithNoWayUpHasNone() {
        assertNull(HistorySchema.upgrade(0, emptySet()))
        assertNull(HistorySchema.upgrade(HistorySchema.VERSION, emptySet()))
    }

    @Test
    fun anUpgradedAttemptKeepsItsAnswerAsRaw() {
        val v1 = """[{"at":1,"settled":[{"about":"tu","answer":"informal"}],"verdict":"{\"status\":\"ok\"}","failure":null}]"""
        val v2 = upgradeAttempts(v1)
        val attempt = historyJson.parseToJsonElement(v2).jsonArray.single().jsonObject
        assertEquals(listOf("at", "settled", "raw", "failure"), attempt.keys.toList())
        assertEquals("""{"status":"ok"}""", attempt["raw"]!!.jsonPrimitive.content)
        assertEquals("informal", attempt["settled"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        // Already upgraded, or not readable: left as it is.
        assertEquals(v2, upgradeAttempts(v2))
        assertEquals("{broken", upgradeAttempts("{broken"))
        assertEquals("[]", upgradeAttempts("[]"))
    }

    @Test
    fun anExportedRowIsTheRowAsStored() {
        val row = MapRow(session.values() + mapOf("origin" to "widget", "status" to "maybe"))
        val json = rowJson(row, HistorySchema.SESSION_COLUMNS)
        assertEquals(HistorySchema.SESSION_COLUMNS, json.keys.toList())
        assertEquals("\"widget\"", json["origin"].toString())
        assertEquals("\"maybe\"", json["status"].toString())
        assertEquals("1000", json["startedAt"].toString())
        assertEquals("null", json["deletedAt"].toString())
        // Attempts are their JSON, not a string of it.
        assertTrue(json["attempts"].toString().startsWith("[{"))
        val unreadable = rowJson(MapRow(session.values() + ("attempts" to "{broken")), HistorySchema.SESSION_COLUMNS)
        assertEquals("\"{broken\"", unreadable["attempts"].toString())

        val line = exportLine(json, listOf(rowJson(MapRow(suggestion.values() + ("kind" to "style")), HistorySchema.SUGGESTION_COLUMNS)))
        assertFalse(line.contains('\n'))
        assertTrue(line, line.startsWith("{\"session\":{\"id\":\"s1\""))
        assertTrue(line, line.contains("\"suggestions\":[{\"id\":\"g1\""))
        assertTrue(line, line.contains("\"kind\":\"style\""))
    }
}

/** A row of a table as a map, standing in for SQLite's cursor. */
internal class MapRow(private val values: Map<String, Any?>) : Row {
    override fun stringOrNull(column: String) = value(column) as String?

    override fun longOrNull(column: String) = (value(column) as Number?)?.toLong()

    private fun value(name: String): Any? {
        check(name in values) { "No column $name" }
        return values[name]
    }
}

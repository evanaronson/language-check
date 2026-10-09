package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.llm.Provider
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
            Attempt(at = 1_500, settled = listOf(SettledAnswer("tu", "informal")), verdict = """{"status":"ok"}""", reusedFrom = "s0"),
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
        val open = session.copy(closedAt = null, outcome = null, finalText = null, hostApp = null, attempts = emptyList(), status = null)
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
        assertEquals(UNKNOWN_ORIGIN, read.origin)
        assertNull(read.outcome)
        assertNull(read.status)
        assertTrue(read.attempts.isEmpty())

        assertNull(suggestionRecord(MapRow(suggestion.values() + ("kind" to "style"))))
        assertNull(suggestionRecord(MapRow(suggestion.values() + ("decision" to "postponed"))))

        val summary = sessionSummary(MapRow(mapOf<String, Any?>(
            "id" to "s1", "startedAt" to 1L, "origin" to "widget", "hostApp" to null, "text" to "t",
            "outcome" to "snoozed", "status" to "unclear", "fixes" to 1L, "rewordings" to 0L, "taken" to 0L,
        )))
        assertEquals(UNKNOWN_ORIGIN, summary.origin)
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
        assertEquals(listOf("abc123", "Catalan", "moderate", "both", "gemini", "gemini-x", "p1", "5"), HistorySchema.reusableArgs(key, 5))
        assertEquals(7, HistorySchema.reusableArgs(key.copy(requestedLanguage = null), 5).size)
    }

    @Test
    fun aClearOrDeleteForgetsSessionsStillOpen() {
        var now = 1_000L
        val forgotten = Forgotten { now }
        assertTrue(forgotten.allows(session))
        forgotten.deleted("s1")
        assertFalse(forgotten.allows(session))
        assertTrue(forgotten.allows(session.copy(id = "s2")))
        now = 1_000
        forgotten.cleared()
        assertFalse(forgotten.allows(session.copy(id = "s2", startedAt = 999)))
        assertFalse(forgotten.allows(session.copy(id = "s2", startedAt = 1_000)))
        assertTrue(forgotten.allows(session.copy(id = "s3", startedAt = 1_001)))
    }

    @Test
    fun onlyASessionWhoseLastAttemptSucceededCanBeReused() {
        assertTrue(session.lastAttemptSucceeded())
        assertFalse(session.copy(attempts = session.attempts.reversed()).lastAttemptSucceeded())
        assertFalse(session.copy(attempts = emptyList()).lastAttemptSucceeded())
    }

    @Test
    fun theLanguageIsMatchedEvenWhenItIsAuto() {
        assertTrue(HistorySchema.reusable(null).contains("\"requestedLanguage\" IS NULL"))
        assertTrue(HistorySchema.reusable("Catalan").contains("\"requestedLanguage\" = ?"))
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

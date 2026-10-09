package com.evanaronson.linguize.history

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySchemaTest {
    private val session = SessionRecord(
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
        punctuation = "Moderate",
        judgments = "Both",
        provider = "Gemini",
        model = "gemini-x",
        promptHash = "p1",
        appVersion = "0.1.7",
        attempts = listOf(
            Attempt(at = 1_100, failure = "Timeout"),
            Attempt(at = 1_500, settled = listOf(SettledAnswer("tu", "informal")), verdict = """{"status":"fixes"}"""),
        ),
        meaning = "How are you?",
    )

    private val suggestion = SuggestionRecord(
        id = "g1",
        sessionId = "s1",
        deviceId = "device",
        createdAt = 2_000,
        updatedAt = 2_000,
        attempt = 1,
        kind = "fix",
        start = 4,
        end = 9,
        fromText = "estas",
        toText = "estàs",
        why = "Accent",
        decision = Decision.Accepted,
        decidedAt = 2_000,
    )

    @Test
    fun everyFieldOfASessionHasAColumn() {
        val fields = names(SessionRecord.serializer().descriptor)
        assertEquals(fields, HistorySchema.SESSION_COLUMNS)
        assertEquals(fields, session.values().keys.toList())
        fields.forEach { assertTrue(it, HistorySchema.CREATE[0].contains("\"$it\" ")) }
    }

    @Test
    fun everyFieldOfASuggestionHasAColumn() {
        val fields = names(SuggestionRecord.serializer().descriptor)
        assertEquals(fields, HistorySchema.SUGGESTION_COLUMNS)
        assertEquals(fields, suggestion.values().keys.toList())
        fields.forEach { assertTrue(it, HistorySchema.CREATE[1].contains("\"$it\" ")) }
    }

    @Test
    fun aSessionReadsBackAsItWasWritten() {
        assertEquals(session, sessionRecord(MapRow(session.values())))
        val open = session.copy(closedAt = null, outcome = null, finalText = null, hostApp = null, attempts = emptyList())
        assertEquals(open, sessionRecord(MapRow(open.values())))
    }

    @Test
    fun aSuggestionReadsBackAsItWasWritten() {
        assertEquals(suggestion, suggestionRecord(MapRow(suggestion.values())))
        val bare = suggestion.copy(why = null, decision = Decision.Superseded)
        assertEquals(bare, suggestionRecord(MapRow(bare.values())))
    }

    @Test
    fun enumsAreStoredByName() {
        assertEquals("Button", session.values()["origin"])
        assertEquals("Applied", session.values()["outcome"])
        assertEquals("Accepted", suggestion.values()["decision"])
    }

    @Test
    fun anExportLineIsOneLineThatReadsBack() {
        val detail = SessionDetail(session, listOf(suggestion))
        val line = exportLine(detail)
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"schema\":1"))
        assertEquals(detail, historyJson.decodeFromString(SessionDetail.serializer(), line))
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun names(descriptor: SerialDescriptor) = (0 until descriptor.elementsCount).map(descriptor::getElementName)

    private class MapRow(private val values: Map<String, Any?>) : Row {
        override fun stringOrNull(column: String) = value(column) as String?

        override fun longOrNull(column: String) = (value(column) as Number?)?.toLong()

        private fun value(name: String): Any? {
            check(name in values) { "No column $name" }
            return values[name]
        }
    }
}

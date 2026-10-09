package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** The store on a real SQLite, as Robolectric runs it. A plain Application, so the app's own start-up doesn't touch the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SqliteHistoryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 10_000L
    private val store = SqliteHistoryStore(context, NAME) { now }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    private fun open(id: String, startedAt: Long, text: String = "Com estas?", model: String = "m") = SessionRecord(
        id = id,
        deviceId = "device",
        createdAt = startedAt,
        updatedAt = startedAt,
        startedAt = startedAt,
        origin = Origin.Menu,
        requestedLanguage = "Catalan",
        text = text,
        textHash = SessionRecording.hash(text),
        punctuation = "moderate",
        judgments = "both",
        provider = "gemini",
        model = model,
        promptHash = "p1",
        appVersion = "1.0",
    )

    private fun SessionRecord.answered(at: Long, verdict: String? = """{"status":"ok"}""", failure: String? = null) =
        copy(updatedAt = at, attempts = attempts + Attempt(at, verdict = verdict, failure = failure), status = Verdict.Status.Ok.takeIf { failure == null })

    private fun SessionRecord.closed(at: Long, outcome: Outcome = Outcome.None) = copy(updatedAt = at, closedAt = at, outcome = outcome)

    private fun suggestion(session: SessionRecord, id: String, kind: EditKind, decision: Decision, attempt: Int = 0) = SuggestionRecord(
        id = id,
        sessionId = session.id,
        deviceId = "device",
        createdAt = session.updatedAt,
        updatedAt = session.updatedAt,
        attempt = attempt,
        kind = kind,
        start = 4,
        end = 9,
        fromText = "estas",
        toText = "estàs",
        why = "Accent",
        decision = decision,
        decidedAt = session.updatedAt,
    )

    @Test
    fun aSessionIsSavedOpenAndThenClosedWithItsSuggestions() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        assertEquals(opened, store.detail("s1")?.session)
        // Open sessions aren't in Recent but are counted.
        assertTrue(store.recent(10).first().isEmpty())
        assertEquals(1, store.count().first())
        assertEquals(5_000L, store.since().first())

        val closed = opened.answered(6_000).closed(7_000, Outcome.Applied).copy(finalText = "Com estàs?")
        val suggestions = listOf(
            suggestion(closed, "g1", EditKind.Fix, Decision.Accepted),
            suggestion(closed, "g2", EditKind.Natural, Decision.Ignored),
            suggestion(closed, "g0", EditKind.Fix, Decision.Superseded),
        )
        store.close(closed, suggestions)
        val detail = store.detail("s1")!!
        assertEquals(closed, detail.session)
        assertEquals(suggestions.toSet(), detail.suggestions.toSet())

        val summary = store.recent(10).first().single()
        assertEquals("s1", summary.id)
        assertEquals(Outcome.Applied, summary.outcome)
        assertEquals(Verdict.Status.Ok, summary.status)
        assertEquals(1, summary.fixes)
        assertEquals(1, summary.rewordings)
        assertEquals(1, summary.taken)

        // Closing again replaces the suggestions rather than adding to them.
        store.close(closed, suggestions.take(1))
        assertEquals(1, store.detail("s1")!!.suggestions.size)
    }

    @Test
    fun aClosedSessionIsntChangedByALateSave() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        store.close(opened.closed(6_000), emptyList())
        store.save(opened.answered(6_500))
        assertEquals(6_000L, store.detail("s1")!!.session.closedAt)
        assertTrue(store.detail("s1")!!.session.attempts.isEmpty())
    }

    @Test
    fun clearingWhileACardIsOpenKeepsItsSessionGone() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        now = 8_000
        store.clear()
        assertEquals(0, store.count().first())

        store.save(opened.answered(9_000))
        store.close(opened.answered(9_000).closed(9_500), listOf(suggestion(opened, "g1", EditKind.Fix, Decision.Accepted)))
        assertNull(store.detail("s1"))
        assertEquals(0, store.count().first())

        // A check started after the clear is recorded as usual.
        val later = open("s2", 8_001)
        store.save(later)
        assertNotNull(store.detail("s2"))
    }

    @Test
    fun deletingAnOpenSessionKeepsItGone() = runBlocking<Unit> {
        val opened = open("s1", 5_000)
        store.save(opened)
        store.save(open("s2", 5_100))
        store.delete("s1")
        store.close(opened.answered(6_000).closed(7_000), emptyList())
        assertNull(store.detail("s1"))
        assertNotNull(store.detail("s2"))
        assertEquals(1, store.count().first())
    }

    @Test
    fun sessionsLeftOpenByAnEarlierProcessAreAbandoned() = runBlocking<Unit> {
        store.save(open("old", 5_000))
        store.save(open("new", 20_000))
        store.markAbandoned(10_000)
        val old = store.detail("old")!!.session
        assertEquals(Outcome.Abandoned, old.outcome)
        assertEquals(10_000L, old.closedAt)
        assertNull(store.detail("new")!!.session.closedAt)
        assertEquals(listOf("old"), store.recent(10).first().map { it.id })
    }

    @Test
    fun recentIsNewestFirstWithTheStartOfEachText() = runBlocking<Unit> {
        val long = "a".repeat(1_000)
        store.close(open("s1", 1_000, text = long).closed(1_500), emptyList())
        store.close(open("s2", 2_000).closed(2_500), emptyList())
        val recent = store.recent(10).first()
        assertEquals(listOf("s2", "s1"), recent.map { it.id })
        assertEquals(SessionSummary.TEXT_PREFIX, recent[1].text.length)
        assertEquals(listOf("s2"), store.recent(1).first().map { it.id })
        assertEquals(long, store.detail("s1")!!.session.text)
    }

    @Test
    fun aRecentSuccessfulCheckOfTheSameTextCanBeReused() = runBlocking<Unit> {
        val done = open("s1", 5_000).answered(5_500).closed(6_000)
        store.close(done, emptyList())
        assertEquals("s1", store.reusable(done.reuseKey, since = 4_000)?.id)
        // Too old, other settings, other text, other language.
        assertNull(store.reusable(done.reuseKey, since = 5_001))
        assertNull(store.reusable(done.reuseKey.copy(model = "other"), since = 4_000))
        assertNull(store.reusable(done.reuseKey.copy(textHash = "other"), since = 4_000))
        assertNull(store.reusable(done.reuseKey.copy(requestedLanguage = null), since = 4_000))

        // Auto-detect matches auto-detect.
        val auto = open("s2", 5_000, model = "auto").copy(requestedLanguage = null).answered(5_500).closed(6_000)
        store.close(auto, emptyList())
        assertEquals("s2", store.reusable(auto.reuseKey, since = 4_000)?.id)

        // Not one whose last attempt failed, nor one still open.
        val failed = open("s3", 5_000, model = "failing").answered(5_200).answered(5_500, verdict = null, failure = "Offline")
            .closed(6_000, Outcome.Failed)
        store.close(failed, emptyList())
        assertNull(store.reusable(failed.reuseKey, since = 4_000))
        val stillOpen = open("s4", 5_000, model = "open").answered(5_500)
        store.save(stillOpen)
        assertNull(store.reusable(stillOpen.reuseKey, since = 4_000))
    }

    @Test
    fun exportWritesOneLinePerSession() = runBlocking<Unit> {
        val closed = open("s1", 5_000).answered(5_500).closed(6_000)
        store.close(closed, listOf(suggestion(closed, "g1", EditKind.Fix, Decision.Ignored)))
        store.save(open("s2", 7_000))
        val out = ByteArrayOutputStream()
        assertTrue(store.export(out))
        val lines = out.toString(Charsets.UTF_8.name()).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("\"kind\":\"fix\"") && lines[0].contains("\"decision\":\"ignored\""))
    }

    private companion object {
        const val NAME = "history-test.db"
    }
}

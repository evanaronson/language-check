package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.OutputStream

/** The history lifecycle of a card, against a store that only remembers what it was asked. */
class CheckHistoryTest {
    private val rain = "Com estas amb la pluja?"
    private val verdict = Verdict(
        status = Verdict.Status.Ok,
        hasErrors = true,
        corrected = "Com estàs amb la pluja?",
        moreNatural = true,
        natural = "Com portes la pluja?",
    )
    private val result = interpret(rain, verdict) as CheckResult.Reviewed

    private val settings = SessionSettings(
        nativeLanguage = "English",
        punctuation = "moderate",
        judgments = "fix",
        provider = "openai",
        model = "a-model",
        promptHash = "p1",
    )

    private val opening = Opening(origin = Origin.Menu, hostApp = "org.telegram.messenger", requestedLanguage = "ca", settings = settings)

    private val store = FakeStore()
    private val environment = object : HistoryEnvironment {
        @Volatile override var enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000L
    private val logged: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private var ids = 0
    private val history = CheckHistory(
        store = store,
        environment = environment,
        scope = scope,
        clock = { now },
        log = { message, _ -> logged += message },
        maxChars = 100,
        newId = { "id${ids++}" },
    )

    @After
    fun stop() = scope.cancel()

    @Test
    fun aCardIsOneSessionSavedAtOpenAfterEachAttemptAndClosed() = runBlocking<Unit> {
        val opened = history.open(rain, opening)!!
        assertEquals("moderate", opened.punctuation)
        assertEquals("fix", opened.judgments)
        assertEquals("openai", opened.provider)
        assertEquals("device", opened.deviceId)
        assertEquals("1.0", opened.appVersion)
        assertEquals(1_000, opened.startedAt)

        now = 1_100
        history.succeeded(emptyList(), "{}", result)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        history.changed(fixed)
        now = 1_200
        history.close(fixed.workingText)
        history.flush()

        assertEquals(listOf("save", "save", "close"), store.calls.map { it.first })
        val (_, closed, suggestions) = store.calls.last()
        assertEquals(Outcome.Applied, closed.outcome)
        assertEquals(Verdict.Status.Ok, closed.status)
        assertEquals(1, closed.attempts.size)
        assertEquals(Decision.Accepted, suggestions.single { it.kind == EditKind.Fix }.decision)
        assertNull(history.session)
    }

    @Test
    fun closingTwiceOrWithNothingOpenWritesNothingMore() = runBlocking<Unit> {
        history.close(null)
        history.open(rain, opening)
        history.close(null)
        history.close(null)
        history.flush()
        assertEquals(listOf("save", "close"), store.calls.map { it.first })
    }

    @Test
    fun withHistoryOffNothingIsWritten() = runBlocking<Unit> {
        environment.enabled = false
        assertNull(history.open(rain, opening))
        history.succeeded(emptyList(), "{}", result)
        history.close(rain)
        history.flush()
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun turningHistoryOffStopsRecordingTheOpenSession() = runBlocking<Unit> {
        history.open(rain, opening)
        history.flush()
        environment.enabled = false
        history.succeeded(emptyList(), "{}", result)
        environment.enabled = true
        history.changed(result.revision.acceptAll(EditKind.Fix))
        history.close(rain)
        history.flush()
        assertEquals(listOf("save"), store.calls.map { it.first })
        assertNull(history.session)
    }

    @Test
    fun aTextTooLongToCheckIsntRecorded() = runBlocking<Unit> {
        assertNull(history.open("x".repeat(101), opening))
        history.failed(emptyList(), "TooLong", null)
        history.close(null)
        history.flush()
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aFailedAttemptIsRecorded() = runBlocking<Unit> {
        history.open(rain, opening)
        history.failed(listOf(Settled("tu", "informal")), "Offline", "no network")
        history.close(null)
        history.flush()
        val closed = store.calls.last().second
        assertEquals(Outcome.Failed, closed.outcome)
        assertEquals("Offline", closed.attempts.single().failure)
        assertEquals(listOf(Settled("tu", "informal")), closed.attempts.single().settled)
    }

    @Test
    fun aStoreThatFailsDoesntBreakTheCard() = runBlocking<Unit> {
        store.failing = true
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        history.close(null)
        history.flush()
        assertEquals(3, logged.size)
    }

    @Test
    fun theModelsAnswerIsUsedWhenThereIsNothingKept() = runBlocking<Unit> {
        val answer = history.firstAttempt(rain, opening, ask = { "model" }, reuse = { "kept" })
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun aKeptVerdictThatArrivesFirstIsShownAndTheRequestCancelled() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{\"status\":\"ok\"}")
        var cancelled = false
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                try {
                    delay(10_000)
                    "model"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            reuse = { kept -> "kept ${kept.raw}" },
        )
        assertEquals("kept {\"status\":\"ok\"}", answer.value)
        assertEquals("earlier", answer.reused?.sessionId)
        assertEquals(listOf(Settled("tu", "informal")), answer.reused?.settled)
        assertTrue(cancelled)

        // The lookup asked for the same text and settings, from the last ten minutes.
        val (key, since) = store.lookedUp.single()
        assertEquals(SessionRecording.hash(rain), key.textHash)
        assertEquals("ca", key.requestedLanguage)
        assertEquals("English", key.settings.nativeLanguage)
        assertEquals("fix", key.settings.judgments)
        assertEquals(1_000 - CheckHistory.REUSE_WITHIN_MS, since)

        history.succeeded(answer.reused!!.settled, answer.reused!!.raw, result, reusedFrom = answer.reused!!.sessionId)
        history.close(null)
        history.flush()
        assertEquals("earlier", store.calls.last().second.attempts.single().reusedFrom)
    }

    @Test
    fun aSlowLookupNeverHoldsUpTheModelsAnswer() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        store.gate = CompletableDeferred()
        val answer = withTimeout(5_000) {
            history.firstAttempt(rain, opening, ask = { "model" }, reuse = { "kept" })
        }
        assertEquals(Answer("model", null), answer)
        store.gate!!.complete(Unit)
    }

    @Test
    fun aKeptVerdictThatCantBeReadFallsBackToTheModel() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                delay(200)
                "model"
            },
            reuse = { null },
        )
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun theModelsFailureIsThrownAsIs() = runBlocking<Unit> {
        try {
            history.firstAttempt<String>(rain, opening, ask = { throw IllegalStateException("offline") }, reuse = { "kept" })
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("offline", e.message)
        }
        assertTrue(history.session != null)
    }

    @Test
    fun withHistoryOffThereIsNoLookup() = runBlocking<Unit> {
        environment.enabled = false
        store.kept = keptSession("earlier", "{}")
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                delay(100)
                "model"
            },
            reuse = { "kept" },
        )
        assertEquals("model", answer.value)
        assertTrue(store.lookedUp.isEmpty())
        assertFalse(store.calls.isNotEmpty())
    }

    @Test
    fun aKeptSessionShowsItsDecidedAttemptsAnswer() = runBlocking<Unit> {
        // Answered, then a re-check came back unclear: the card's suggestions are the first answer's.
        val first = SessionRecording(rain, keptContext, 500, newId = { "earlier" })
        first.attempt(600, listOf(Settled("tu", "informal")), "{\"first\":1}", null, null, result.revision, null)
        first.attempt(650, emptyList(), "{\"second\":1}", null, null, null, null, Verdict.Status.Unclear)
        store.kept = first.close(700, null)
        val answer = history.firstAttempt(rain, opening, ask = { delay(10_000); "model" }, reuse = { it.raw })
        assertEquals("{\"first\":1}", answer.value)
        assertEquals(listOf(Settled("tu", "informal")), answer.reused?.settled)
    }

    @Test
    fun aRecheckWithOtherSettingsClosesTheSessionAndOpensAnother() = runBlocking<Unit> {
        val first = history.open(rain, opening)!!
        history.succeeded(emptyList(), "{}", result)
        // Same settings: the same session.
        history.recheck(rain, opening)
        assertEquals(first.id, history.session?.id)

        now = 1_500
        history.recheck(rain, opening.copy(settings = settings.copy(model = "another-model")))
        val second = history.session!!
        assertTrue(second.id != first.id)
        assertEquals("another-model", second.model)
        assertEquals(1_500, second.startedAt)
        history.succeeded(emptyList(), "{}", result)
        history.close(null)
        history.flush()

        assertEquals(listOf("save", "save", "close", "save", "save", "close"), store.calls.map { it.first })
        val closedFirst = store.calls[2].second
        assertEquals(first.id, closedFirst.id)
        assertEquals("a-model", closedFirst.model)
        assertEquals(1, closedFirst.attempts.size)
        assertEquals("another-model", store.calls.last().second.model)
        assertEquals(1, store.calls.last().second.attempts.size)
    }

    @Test
    fun aRecheckWithNothingOpenOpensNothing() = runBlocking<Unit> {
        environment.enabled = false
        history.open(rain, opening)
        environment.enabled = true
        history.recheck(rain, opening.copy(settings = settings.copy(model = "another-model")))
        history.flush()
        assertNull(history.session)
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aFailedAttemptKeepsTheAnswerThatCouldntBeRead() = runBlocking<Unit> {
        history.open(rain, opening)
        history.failed(emptyList(), "BadResponse", "not in the expected format", raw = "{\"status\":")
        history.close(null)
        history.flush()
        val attempt = store.calls.last().second.attempts.single()
        assertEquals("{\"status\":", attempt.raw)
        assertEquals("BadResponse", attempt.failure)
    }

    @Test
    fun waitingForWritesBlocksUntilTheCloseIsWrittenButNotLonger() {
        runBlocking { history.open(rain, opening) }
        history.close(null)
        history.awaitWrites(5_000)
        assertEquals(listOf("save", "close"), store.calls.map { it.first })

        store.gate = CompletableDeferred()
        store.slowClose = true
        runBlocking { history.open(rain, opening) }
        history.close(null)
        val started = System.nanoTime()
        history.awaitWrites(100)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        store.gate!!.complete(Unit)
    }

    @Test
    fun theStatusFollowsTheResult() {
        assertEquals(Verdict.Status.Ok, CheckHistory.statusOf(result))
        assertEquals(Verdict.Status.Unclear, CheckHistory.statusOf(CheckResult.Unclear))
        assertEquals(Verdict.Status.WrongLanguage, CheckHistory.statusOf(CheckResult.WrongLanguage("Catalan", "Spanish")))
    }

    private val keptContext = SessionContext(opening.copy(hostApp = null), "1.0", "device")

    private fun keptSession(id: String, raw: String) = SessionRecording(rain, keptContext, 500, newId = { id }).also {
        it.attempt(600, listOf(Settled("tu", "informal")), raw, null, null, null, null)
    }.close(700, null)

    private class FakeStore : HistoryStore {
        val calls: MutableList<Triple<String, SessionRecord, List<SuggestionRecord>>> = java.util.Collections.synchronizedList(mutableListOf())
        val lookedUp: MutableList<Pair<ReuseKey, Long>> = java.util.Collections.synchronizedList(mutableListOf())

        @Volatile var failing = false

        @Volatile var kept: SessionDetail? = null

        @Volatile var gate: CompletableDeferred<Unit>? = null

        /** Whether [close] waits for [gate] too. */
        @Volatile var slowClose = false

        override suspend fun save(session: SessionRecord) {
            if (failing) error("disk full")
            calls += Triple("save", session, emptyList())
        }

        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) {
            if (failing) error("disk full")
            if (slowClose) gate?.await()
            calls += Triple("close", session, suggestions)
        }

        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? {
            lookedUp += key to since
            gate?.await()
            return kept
        }

        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun detail(id: String): SessionDetail? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}

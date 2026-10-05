package com.evanaronson.linguize.llm

import com.evanaronson.linguize.llm.CheckFailure.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTest {
    @Test
    fun errorsMapToWhatTheCardSays() {
        val cases = listOf(
            400 to """{"error":{"message":"API key not valid","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""" to Reason.BadKey,
            401 to """{"error":{"message":"Incorrect API key provided"}}""" to Reason.BadKey,
            403 to """{"error":{"message":"Permission denied"}}""" to Reason.BadKey,
            403 to """{"error":{"message":"This model is not available to you"}}""" to Reason.BadModel,
            404 to """{"error":{"message":"models/x is not found"}}""" to Reason.BadModel,
            429 to """{"error":{"message":"Slow down"}}""" to Reason.RateLimited,
            503 to "" to Reason.Server,
            418 to "teapot" to Reason.BadResponse,
        )
        for ((call, expected) in cases) {
            val (code, payload) = call
            assertEquals("$code $payload", expected, failureFor(code, payload).reason)
        }
    }

    @Test
    fun theProvidersOwnMessageIsKept() {
        assertEquals("Slow down", failureFor(429, """{"error":{"message":" Slow down "}}""").detail)
        assertEquals(null, failureFor(500, "<html>").detail)
    }

    @Test
    fun modelsForOtherJobsAreLeftOutAndTheNewestComeFirst() {
        val ids = listOf("gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-embedding-001", "gemini-3.5-flash-lite", "gemini-2.5-flash-tts")
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-2.5-flash"), textModels(ids))
    }
}

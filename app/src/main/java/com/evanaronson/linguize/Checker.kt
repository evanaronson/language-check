package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs checks with the provider, model and options chosen in settings.
 * Every failure is a [CheckFailure]. Storage, key decryption and the network run off the main thread.
 */
class Checker(
    private val settings: Settings,
    private val keys: ApiKeys,
    private val clients: Map<Provider, ProviderClient>,
) {
    /**
     * Checks [text] as [language], or lets the model detect the language when it's null.
     * [settled] are the writer's answers to assumptions from an earlier check of the same text.
     */
    suspend fun check(text: String, language: Language?, settled: List<Settled> = emptyList()): CheckResult {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong)
        return failingAsCheckFailure {
            val provider = settings.provider
            val judgments = settings.judgments
            val request = CheckRequest(text, language, settings.punctuation, judgments, settled, settings.nativeLanguage)
            val model = settings.model(provider) ?: provider.recommendedModel
            val verdict = clients.getValue(provider).check(key(provider), model, request)
            interpret(text, verdict, judgments, expectedLanguage = language?.name)
        }
    }

    /** Runs a short check with [model]; returns how long it took in milliseconds. */
    suspend fun testModel(provider: Provider, model: String): Long = failingAsCheckFailure {
        val started = System.nanoTime()
        clients.getValue(provider).check(key(provider), model, TEST_REQUEST)
        (System.nanoTime() - started) / 1_000_000
    }

    /** The models [provider]'s key can use, newest first. */
    suspend fun models(provider: Provider): List<String> = failingAsCheckFailure {
        clients.getValue(provider).models(key(provider))
    }

    private fun key(provider: Provider): String {
        val key = keys.get(provider) ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        // HTTP headers only carry printable ASCII; anything else can't be a real key.
        if (key.any { it !in ' '..'~' }) throw CheckFailure(CheckFailure.Reason.BadKey, "The saved key contains characters a key can't have")
        return key
    }

    /** Runs [block] off the main thread; anything unexpected becomes a [CheckFailure] rather than a crash. */
    private suspend fun <T> failingAsCheckFailure(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, e.message, e)
        }
    }

    private companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000

        val TEST_REQUEST = CheckRequest("Bon dia, com estas?", null, Punctuation.Moderate, Judgments.Both)
    }
}

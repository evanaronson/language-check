package com.evanaronson.languagecheck

import com.evanaronson.languagecheck.llm.CheckFailure
import com.evanaronson.languagecheck.llm.CheckRequest
import com.evanaronson.languagecheck.llm.Provider
import com.evanaronson.languagecheck.llm.ProviderClient
import com.evanaronson.languagecheck.review.CheckResult
import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Language
import com.evanaronson.languagecheck.review.Punctuation
import com.evanaronson.languagecheck.review.Settled
import com.evanaronson.languagecheck.review.interpret
import com.evanaronson.languagecheck.settings.ApiKeys
import com.evanaronson.languagecheck.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs checks with the provider, model and options chosen in settings.
 * Every failure is a [CheckFailure]. Storage and key decryption run off the main thread.
 */
class CheckService(
    private val settings: Settings,
    private val keys: ApiKeys,
    private val clients: Map<Provider, ProviderClient>,
) {
    /** The chosen model for [provider], or its recommended one. */
    fun modelFor(provider: Provider) = settings.model(provider) ?: provider.recommendedModel

    /**
     * Checks [text] as [language], or lets the model detect the language when it's null.
     * [settled] are the writer's answers to assumptions from an earlier check of the same text.
     */
    suspend fun check(text: String, language: Language?, settled: List<Settled> = emptyList()): CheckResult {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong)
        return withContext(Dispatchers.IO) {
            val provider = settings.provider
            val judgments = settings.judgments
            val request = CheckRequest(text, language, settings.punctuation, judgments, settled, settings.nativeLanguage)
            val verdict = clients.getValue(provider).check(key(provider), modelFor(provider), request)
            interpret(text, verdict, judgments, expectedLanguage = language?.name)
        }
    }

    /** Runs a short check with [model]; returns how long it took in milliseconds. */
    suspend fun testModel(provider: Provider, model: String): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        clients.getValue(provider).check(key(provider), model, TEST_REQUEST)
        (System.nanoTime() - started) / 1_000_000
    }

    /** The models [provider]'s key can use, newest first. */
    suspend fun models(provider: Provider): List<String> = withContext(Dispatchers.IO) {
        clients.getValue(provider).models(key(provider))
    }

    private fun key(provider: Provider) = keys.get(provider) ?: throw CheckFailure(CheckFailure.Reason.NoKey)

    private companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000

        val TEST_REQUEST = CheckRequest("Bon dia, com estas?", null, Punctuation.Moderate, Judgments.Both)
    }
}

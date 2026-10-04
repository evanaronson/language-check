package com.evanaronson.languagecheck

import android.app.Application
import com.evanaronson.languagecheck.check.CheckFailure
import com.evanaronson.languagecheck.check.CheckRequest
import com.evanaronson.languagecheck.check.CheckResult
import com.evanaronson.languagecheck.check.Checker
import com.evanaronson.languagecheck.check.GeminiChecker
import com.evanaronson.languagecheck.check.ModelCatalog
import com.evanaronson.languagecheck.check.OpenAIChecker
import com.evanaronson.languagecheck.check.Prompt
import com.evanaronson.languagecheck.check.interpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    val keys by lazy { KeyStorage(this) }
    val settings by lazy { AppSettings(this) }

    // One client for the life of the process so repeat checks reuse the open connection.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val catalog by lazy { ModelCatalog(http) }

    private val prompt by lazy { Prompt.load(this) }

    private val gemini by lazy {
        GeminiChecker(http, prompt, apiKey = { keys.key(Provider.Gemini) }, model = { modelFor(Provider.Gemini) })
    }
    private val openAI by lazy {
        OpenAIChecker(http, prompt, apiKey = { keys.key(Provider.OpenAI) }, model = { modelFor(Provider.OpenAI) })
    }

    fun modelFor(provider: Provider) = settings.model(provider) ?: provider.recommendedModel

    /** The checker for the provider chosen in settings. */
    private fun checker(): Checker = when (settings.provider) {
        Provider.Gemini -> gemini
        Provider.OpenAI -> openAI
    }

    /** Checks [text] with the provider, model and options chosen in settings. Throws CheckFailure. */
    suspend fun check(text: String): CheckResult {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong)
        // Key decryption and prompt loading happen on first use; keep them off the main thread.
        return withContext(Dispatchers.IO) {
            val language = settings.language
            val checks = settings.checks
            val verdict = checker().check(request(text, language, checks))
            interpret(text, verdict, language?.name, checks.fixes, checks.naturalness)
        }
    }

    /**
     * Runs a short check with [model] to see whether it works with this app.
     * Returns the time taken, or throws CheckFailure with the provider's reason.
     */
    suspend fun testModel(provider: Provider, model: String): Long = withContext(Dispatchers.IO) {
        val checker = when (provider) {
            Provider.Gemini -> gemini
            Provider.OpenAI -> openAI
        }
        val started = System.nanoTime()
        checker.check(request("Bon dia, com estas?", null, Checks.Both).copy(model = model))
        (System.nanoTime() - started) / 1_000_000
    }

    private fun request(text: String, language: Language?, checks: Checks) = CheckRequest(
        text = text,
        language = language?.promptName,
        punctuation = settings.punctuation.promptName,
        checks = checks.promptName,
    )

    private companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000
    }
}

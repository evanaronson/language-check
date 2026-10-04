package com.evanaronson.languagecheck

import android.app.Application
import com.evanaronson.languagecheck.check.CheckFailure
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

    /** Checks [text] with the provider, model and language chosen in settings. Throws CheckFailure. */
    suspend fun check(text: String): CheckResult {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong)
        // Key decryption and prompt loading happen on first use; keep them off the main thread.
        val (verdict, language) = withContext(Dispatchers.IO) {
            val language = settings.language
            checker().check(text, language?.promptName, settings.punctuation.promptName) to language
        }
        return interpret(text, verdict, language?.name)
    }

    private companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000
    }
}

package com.evanaronson.languagecheck

import android.app.Application
import com.evanaronson.languagecheck.check.Checker
import com.evanaronson.languagecheck.check.GeminiChecker
import com.evanaronson.languagecheck.check.OpenAIChecker
import com.evanaronson.languagecheck.check.Prompt
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    val keys by lazy { KeyStorage(this) }

    // One client for the life of the process so repeat checks reuse the open connection.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val prompt by lazy { Prompt.load(this) }

    private val gemini by lazy { GeminiChecker(http, prompt, apiKey = { keys.key(Provider.Gemini) }) }
    private val openAI by lazy { OpenAIChecker(http, prompt, apiKey = { keys.key(Provider.OpenAI) }) }

    /** The checker for the provider chosen in settings. */
    fun checker(): Checker = when (keys.provider) {
        Provider.Gemini -> gemini
        Provider.OpenAI -> openAI
    }
}

package com.evanaronson.languagecheck

import android.app.Application
import com.evanaronson.languagecheck.llm.GeminiClient
import com.evanaronson.languagecheck.llm.OpenAIClient
import com.evanaronson.languagecheck.llm.Prompt
import com.evanaronson.languagecheck.llm.Provider
import com.evanaronson.languagecheck.settings.ApiKeys
import com.evanaronson.languagecheck.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Creates the app's long-lived objects once, on first use. */
class App : Application() {
    val settings by lazy { Settings(this) }
    val keys by lazy { ApiKeys(this) }
    val menu by lazy { SelectionMenu(this) }

    /** Counts how often the settings screen has left the foreground, so the overlay can come back after it. */
    val settingsClosed = MutableStateFlow(0)

    val checks by lazy {
        // One HTTP client for the life of the process, so repeat checks reuse the open connection.
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val prompt = Prompt.load(this)
        CheckService(
            settings,
            keys,
            mapOf(
                Provider.Gemini to GeminiClient(http, prompt),
                Provider.OpenAI to OpenAIClient(http, prompt),
            ),
        )
    }
}

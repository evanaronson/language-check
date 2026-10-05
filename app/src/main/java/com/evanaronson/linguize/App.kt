package com.evanaronson.linguize

import android.app.Application
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.SelectionMenu
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Creates the app's long-lived objects once, on first use. */
class App : Application() {
    val settings by lazy { Settings(this) }
    val keys by lazy { ApiKeys(this) }
    val menu by lazy { SelectionMenu(this) }

    val checker by lazy {
        // One HTTP client for the life of the process, so repeat checks reuse the open connection.
        // The answer arrives all at once, so reading may take as long as the whole call.
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val prompt = Prompt.load(this)
        Checker(
            settings,
            keys,
            mapOf(
                Provider.Gemini to GeminiClient(http, prompt),
                Provider.OpenAI to OpenAIClient(http, prompt),
            ),
        )
    }

    /**
     * Counts the times the settings screen was left (closed, or the user went home or to
     * recents), so a card hidden while it was open knows to come back.
     */
    val settingsLeft = MutableStateFlow(0)
}

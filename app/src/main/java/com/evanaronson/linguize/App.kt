package com.evanaronson.linguize

import android.app.Application
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.SelectionMenu
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Creates the app's long-lived objects once, on first use. */
class App : Application() {
    val settings by lazy { Settings(this) }
    val keys by lazy { ApiKeys(this) }
    val menu by lazy { SelectionMenu(this) }

    /** Every check and what came of it. Opens its database on first use, off the main thread. */
    val history: HistoryStore by lazy { SqliteHistoryStore(this) }

    /**
     * Lives as long as the process. For writes that must outlive a screen, like recording a
     * closing card after its view model's scope is cancelled; nothing else belongs here.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            prompt,
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

    override fun onCreate() {
        super.onCreate()
        // onCreate runs once per process, so this does too. A session still open now was
        // open when the last process ended; ones started from now on belong to this one.
        val started = System.currentTimeMillis()
        // Housekeeping: if the database can't be opened, the next write will say so; don't crash at start.
        appScope.launch { runCatching { history.markAbandoned(started) } }
    }
}

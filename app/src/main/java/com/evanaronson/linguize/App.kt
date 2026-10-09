package com.evanaronson.linguize

import android.app.Application
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.SelectionMenu
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.history.HistoryEnvironment
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

    /** This build's version name. */
    @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33.
    val appVersion: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

    /**
     * What history reads from the app. A check reads it off the main thread when its session
     * opens (the first read loads preferences and may make the device id); [onCreate] also
     * warms it up.
     */
    val historyEnvironment = object : HistoryEnvironment {
        override val enabled get() = settings.historyEnabled
        override val deviceId get() = settings.deviceId
        override val appVersion get() = this@App.appVersion
    }

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
        // onCreate runs once per process, so this does too. A session open in the database
        // that this process didn't open was open when an earlier process ended.
        appScope.launch {
            // Made (and committed) once, here, rather than racing on the first checks.
            runCatching { settings.deviceId }
            runCatching { settings.historyEnabled }
            runCatching { appVersion }
            // Housekeeping; the store logs its own failures and never throws.
            history.markAbandoned(System.currentTimeMillis())
        }
    }
}

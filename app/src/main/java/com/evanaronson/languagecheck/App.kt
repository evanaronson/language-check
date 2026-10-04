package com.evanaronson.languagecheck

import android.app.Application
import com.evanaronson.languagecheck.check.Checker
import com.evanaronson.languagecheck.check.GeminiChecker
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

    private val checker by lazy {
        GeminiChecker(http, Prompt.load(this), apiKey = keys::geminiKey)
    }

    fun checker(): Checker = checker
}

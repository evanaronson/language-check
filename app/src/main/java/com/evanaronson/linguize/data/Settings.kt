package com.evanaronson.linguize.data

import android.content.Context
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.Provider

/**
 * The choices made on the settings screen. API keys live in [ApiKeys]; which
 * languages appear in the selection menu lives with the menu entries themselves.
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: Provider
        get() = read(PROVIDER, Provider.Gemini)
        set(value) = write(PROVIDER, value.name)

    var punctuation: Punctuation
        get() = read(PUNCTUATION, Punctuation.Moderate)
        set(value) = write(PUNCTUATION, value.name)

    var judgments: Judgments
        get() = read(JUDGMENTS, Judgments.Both)
        set(value) = write(JUDGMENTS, value.name)

    /** Whether checks are kept in history. Turning it off keeps what's already there. */
    var historyEnabled: Boolean
        get() = prefs.getBoolean(HISTORY, true)
        set(value) = prefs.edit().putBoolean(HISTORY, value).apply()

    /** A random id made once per install, so history rows from several phones can merge later. */
    val deviceId: String
        get() = prefs.getString(DEVICE_ID, null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).apply() }

    /** The writer's own language: the app's UI language, which the meaning and reasons are written in. */
    val nativeLanguage: String
        get() = context.getString(R.string.native_language)

    /** Every choice that affects a check, to tell whether settings changed in the meantime. */
    val snapshot: String
        get() = listOf(provider, punctuation, judgments, model(provider), nativeLanguage).joinToString()

    /** The chosen model, or null for the provider's recommended one. */
    fun model(provider: Provider): String? = prefs.getString(modelKey(provider), null)

    fun setModel(provider: Provider, model: String?) = write(modelKey(provider), model)

    private inline fun <reified E : Enum<E>> read(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun write(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    private fun modelKey(provider: Provider) = "model.${provider.name}"

    private companion object {
        const val PROVIDER = "provider"
        const val PUNCTUATION = "punctuation"
        const val JUDGMENTS = "judgments"
        const val HISTORY = "history"
        const val DEVICE_ID = "deviceId"
    }
}

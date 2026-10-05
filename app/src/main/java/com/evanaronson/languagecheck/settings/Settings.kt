package com.evanaronson.languagecheck.settings

import android.content.Context
import com.evanaronson.languagecheck.R
import com.evanaronson.languagecheck.llm.Provider
import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Punctuation

/**
 * The choices made on the settings screen. API keys live in [ApiKeys]; which
 * languages appear in the selection menu lives with the menu entries themselves.
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Builds before 4 kept the provider choice alongside the API keys.
        val legacy = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
        legacy.getString(PROVIDER, null)?.let { old ->
            if (!prefs.contains(PROVIDER)) write(PROVIDER, old)
            legacy.edit().remove(PROVIDER).apply()
        }
    }

    var provider: Provider
        get() = read(PROVIDER, Provider.Gemini)
        set(value) = write(PROVIDER, value.name)

    var punctuation: Punctuation
        get() = read(PUNCTUATION, Punctuation.Moderate)
        set(value) = write(PUNCTUATION, value.name)

    var judgments: Judgments
        get() = read(JUDGMENTS, Judgments.Both)
        set(value) = write(JUDGMENTS, value.name)

    /** The writer's own language: the app's UI language, which the meaning and reasons are written in. */
    val nativeLanguage: String
        get() = context.getString(R.string.native_language)

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

        /** Named "checks" in earlier builds; kept so the saved choice carries over. */
        const val JUDGMENTS = "checks"
    }
}

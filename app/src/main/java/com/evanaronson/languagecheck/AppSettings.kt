package com.evanaronson.languagecheck

import android.content.Context

/** Provider, model and language choices from the settings screen. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: Provider
        get() = prefs.getString(PROVIDER, null)
            ?.let { name -> Provider.entries.firstOrNull { it.name == name } }
            ?: Provider.Gemini
        set(value) = prefs.edit().putString(PROVIDER, value.name).apply()

    /** Null means automatic detection. */
    var language: Language?
        get() = Language.byCode(prefs.getString(LANGUAGE, null))
        set(value) = prefs.edit().putString(LANGUAGE, value?.code).apply()

    /** The chosen model, or null for the provider's recommended one. */
    fun model(provider: Provider): String? = prefs.getString("model.${provider.name}", null)

    fun setModel(provider: Provider, model: String?) =
        prefs.edit().putString("model.${provider.name}", model).apply()

    private companion object {
        const val PROVIDER = "provider"
        const val LANGUAGE = "language"
    }
}

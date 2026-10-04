package com.evanaronson.languagecheck.check

/** One model provider. Each implementation sends the same prompt and schema. */
interface Checker {
    /**
     * Returns the model's judgment of [text], or throws [CheckFailure].
     * [language] names the language to judge it as, or is null to detect it;
     * [punctuation] is the strictness level named in the prompt.
     */
    suspend fun check(text: String, language: String?, punctuation: String): ModelVerdict
}

class CheckFailure(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NoKey, BadKey, BadModel, Offline, Timeout, RateLimited, Server, BadResponse }
}

/** The user message: the setting lines the prompt expects, then the text. */
internal fun userMessage(text: String, language: String?, punctuation: String) =
    "Language: ${language ?: "auto"}\nPunctuation: $punctuation\nText: $text"

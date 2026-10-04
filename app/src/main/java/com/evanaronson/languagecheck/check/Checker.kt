package com.evanaronson.languagecheck.check

/** One model provider. Each implementation sends the same prompt and schema. */
interface Checker {
    /**
     * Returns the model's judgment of [text], or throws [CheckFailure].
     * [language] names the language to judge it as, or is null to detect it.
     */
    suspend fun check(text: String, language: String?): ModelVerdict
}

class CheckFailure(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NoKey, BadKey, BadModel, Offline, Timeout, RateLimited, Server, BadResponse }
}

/** The user message: the language line the prompt expects, then the text. */
internal fun userMessage(text: String, language: String?) =
    "Language: ${language ?: "auto"}\nText: $text"

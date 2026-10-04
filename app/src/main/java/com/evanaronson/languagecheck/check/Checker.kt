package com.evanaronson.languagecheck.check

/** One model provider. Each implementation sends the same prompt and schema. */
interface Checker {
    /** Returns the model's judgment, or throws [CheckFailure]. */
    suspend fun check(request: CheckRequest): ModelVerdict
}

data class CheckRequest(
    val text: String,
    /** The language to judge the text as, or null to detect it. */
    val language: String?,
    /** Strictness level named in the prompt: strict, moderate or casual. */
    val punctuation: String,
    /** Which judgments to make, named in the prompt: both, fix or naturalize. */
    val checks: String,
    /** Overrides the model chosen in settings, e.g. to test another one. */
    val model: String? = null,
) {
    /** The user message: the setting lines the prompt expects, then the text. */
    fun userMessage() = "Language: ${language ?: "auto"}\nPunctuation: $punctuation\nChecks: $checks\nText: $text"
}

class CheckFailure(
    val reason: Reason,
    /** The provider's own error message, when there is one. */
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: reason.name, cause) {
    enum class Reason { NoKey, BadKey, BadModel, Offline, Timeout, RateLimited, Server, BadResponse, TooLong }
}

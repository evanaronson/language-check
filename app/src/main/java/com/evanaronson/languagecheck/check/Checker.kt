package com.evanaronson.languagecheck.check

/** One model provider. Gemini today; OpenAI can be added as a second implementation. */
interface Checker {
    /** Returns the model's judgment of [text], or throws [CheckFailure]. */
    suspend fun check(text: String): ModelVerdict
}

class CheckFailure(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NoKey, BadKey, Offline, Timeout, RateLimited, Server, BadResponse }
}

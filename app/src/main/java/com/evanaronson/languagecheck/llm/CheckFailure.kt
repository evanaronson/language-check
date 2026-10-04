package com.evanaronson.languagecheck.llm

/** Why a check couldn't produce a result; the card shows a message per [Reason]. */
class CheckFailure(
    val reason: Reason,
    /** The provider's own explanation, when there is one. */
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: reason.name, cause) {
    enum class Reason { NoKey, BadKey, BadModel, Offline, Timeout, RateLimited, Server, BadResponse, TooLong }
}

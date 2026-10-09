package com.evanaronson.linguize.llm

/** Why a check couldn't produce a result; the card shows a message per [Reason]. */
class CheckFailure(
    val reason: Reason,
    /** The provider's own explanation, when there is one. */
    val detail: String? = null,
    cause: Throwable? = null,
    /** The model's answer, when one came but couldn't be used; history keeps it. */
    val raw: String? = null,
) : Exception(detail ?: reason.name, cause) {
    enum class Reason {
        NoKey, BadKey, BadModel, Offline, Timeout, RateLimited, Server, BadResponse, TooLong,

        /** The selection menu was asked for more checks than it allows in a minute; nothing was sent. */
        TooMany,
    }
}

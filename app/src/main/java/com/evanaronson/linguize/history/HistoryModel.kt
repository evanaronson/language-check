package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.Serializable

/*
 * The history data model: what is stored on the phone and, later, what a sync would
 * upload as-is. Rows use client-made UUIDs, epoch-millisecond UTC timestamps, soft
 * deletion and a schema version, so rows from several devices can merge on a server
 * without translation. See docs/history-spec.md.
 *
 * Enums are kept as the lowercase tokens in [Stored], the same in the database and the
 * export, and read leniently: see [Tokens].
 */

/** Where a check was started. */
@Serializable(with = OriginSerializer::class)
enum class Origin { Menu, Button, Tester }

/** What became of one suggestion by the time its card closed. */
@Serializable(with = DecisionSerializer::class)
enum class Decision {
    /** Applied in the final text. */
    Accepted,

    /** Accepted at some point, not applied at the end. */
    Undone,

    /** A fix overtaken by an accepted rewording that includes it. */
    Retired,

    /** Still on offer when the writer copied its section's version. */
    Copied,

    /** Still on offer when the card closed. */
    Ignored,

    /** From an earlier attempt that a re-check replaced. */
    Superseded,
}

/** How a session ended. */
@Serializable(with = OutcomeSerializer::class)
enum class Outcome {
    /** Accepted changes went back to the app. */
    Applied,

    /** Nothing applied; a version was copied. */
    Copied,

    /** Closed with nothing applied or copied. */
    None,

    /** The last attempt failed. */
    Failed,

    /** Never closed: the process ended with the card open. Set on the next start. */
    Abandoned,
}

/** One time the card opened on a text, and everything that happened until it closed. */
@Serializable
data class SessionRecord(
    val id: String,
    val deviceId: String,
    val schema: Int = SCHEMA,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val startedAt: Long,
    /** Null while the card is open. */
    val closedAt: Long? = null,
    val origin: Origin,
    /** Package name of the app the text came from; null when unknown or for the tester. */
    val hostApp: String? = null,
    /** The language the writer asked for, by name; null for auto-detect. */
    val requestedLanguage: String? = null,
    /** The text that was checked. */
    val text: String,
    /** Short hash of [text], to find the same text checked again. */
    val textHash: String,
    /** What went back to the app; null when nothing was applied. */
    val finalText: String? = null,
    /** Null while open. */
    val outcome: Outcome? = null,
    /** The settings in force, as [Stored] tokens: they change what gets suggested. */
    val punctuation: String,
    val judgments: String,
    val provider: String,
    val model: String,
    /** Hash of the system prompt and schema, so verdicts from different prompts aren't compared as equal. */
    val promptHash: String,
    val appVersion: String,
    /** Every request made for this text, oldest first. */
    val attempts: List<Attempt> = emptyList(),
    /** What the text means, from the last successful attempt. */
    val meaning: String? = null,
    /**
     * What the last successful attempt found: ok (there's a review, maybe with nothing to
     * suggest), unclear, or wrong_language. Null when no attempt succeeded.
     */
    val status: Verdict.Status? = null,
) {
    /** What another check must match to show this one's verdict again. */
    val reuseKey: ReuseKey get() = ReuseKey(textHash, requestedLanguage, punctuation, judgments, provider, model, promptHash)

    companion object {
        const val SCHEMA = 1
    }
}

/** One request to the model within a session: the first check, or a re-check. */
@Serializable
data class Attempt(
    val at: Long,
    /** The writer's answers to assumptions sent with this request. */
    val settled: List<SettledAnswer> = emptyList(),
    /** The model's answer exactly as it came back (JSON); null when the attempt failed. */
    val verdict: String? = null,
    /** `CheckFailure.Reason` name, when the attempt failed. */
    val failure: String? = null,
    val failureDetail: String? = null,
    /**
     * The session whose kept verdict was shown again instead of asking the model; null
     * when the model was asked.
     */
    val reusedFrom: String? = null,
)

@Serializable
data class SettledAnswer(val about: String, val answer: String)

/** One suggestion the writer saw, and what they did with it. */
@Serializable
data class SuggestionRecord(
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val schema: Int = SessionRecord.SCHEMA,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** Index into the session's attempts. */
    val attempt: Int,
    @Serializable(with = EditKindSerializer::class)
    val kind: EditKind,
    /** Position in the session's text. */
    val start: Int,
    val end: Int,
    val fromText: String,
    val toText: String,
    val why: String? = null,
    val decision: Decision,
    val decidedAt: Long,
)

/** Everything a check must share with an earlier one for the earlier verdict to be shown again. */
data class ReuseKey(
    val textHash: String,
    val requestedLanguage: String?,
    val punctuation: String,
    val judgments: String,
    val provider: String,
    val model: String,
    val promptHash: String,
)

/** A row in the Recent list. */
data class SessionSummary(
    val id: String,
    val startedAt: Long,
    val origin: Origin,
    val hostApp: String?,
    /** The start of the text checked, at most [TEXT_PREFIX] characters. */
    val text: String,
    val outcome: Outcome?,
    val fixes: Int,
    val rewordings: Int,
    /** Suggestions that ended Accepted. */
    val taken: Int,
    /** [SessionRecord.status]: what the last successful attempt found; null when none succeeded. */
    val status: Verdict.Status? = null,
) {
    companion object {
        /** How much of a session's text a Recent row carries. */
        const val TEXT_PREFIX = 300
    }
}

/** A session with its suggestions, for the detail view and export. */
@Serializable
data class SessionDetail(val session: SessionRecord, val suggestions: List<SuggestionRecord>)

package com.evanaronson.languagecheck.check

import kotlinx.serialization.Serializable

/** The raw judgment returned by a model, matching assets/check_schema.json. */
@Serializable
data class ModelVerdict(
    val status: String,
    val language: String = "",
    val has_errors: Boolean = false,
    val corrected: String = "",
    val fixes: List<ModelChange> = emptyList(),
    val more_natural: Boolean = false,
    val natural: String = "",
    val natural_changes: List<ModelChange> = emptyList(),
)

@Serializable
data class ModelChange(
    val from: String = "",
    val to: String = "",
    /** A few words of the new text around [to], so a lone comma can be placed exactly. */
    val context: String = "",
    val why: String = "",
)

/** What the result card shows. */
sealed interface CheckResult {
    /** Nothing to say about the judgments that were made. */
    data class AllGood(val checkedFixes: Boolean, val checkedNaturalness: Boolean) : CheckResult

    /** The model could not tell what the text means. */
    data object Unclear : CheckResult

    /** The text isn't in the language chosen in settings. */
    data class WrongLanguage(val expected: String) : CheckResult

    data class Feedback(
        /** The text with every suggested fix and rewording, and which are accepted. */
        val revision: Revision,
        val checkedFixes: Boolean = true,
        val checkedNaturalness: Boolean = true,
    ) : CheckResult
}

/**
 * Turns a model verdict into what the card shows. Fixes and rewordings are
 * both worked out against the writer's original text, so either kind can be
 * accepted on its own; a "change" that changes nothing produces no edit.
 */
fun interpret(
    original: String,
    verdict: ModelVerdict,
    expectedLanguage: String? = null,
    checkFixes: Boolean = true,
    checkNaturalness: Boolean = true,
): CheckResult {
    when (verdict.status) {
        "unclear" -> return CheckResult.Unclear
        "wrong_language" -> return expectedLanguage?.let { CheckResult.WrongLanguage(it) } ?: CheckResult.Unclear
    }

    val fixes = verdict.corrected.trim()
        .takeIf { checkFixes && verdict.has_errors && it.isNotEmpty() }
        ?.let { Edits.fixes(original, it, verdict.fixes) }
        .orEmpty()
    val naturals = verdict.natural.trim()
        .takeIf { checkNaturalness && verdict.more_natural && it.isNotEmpty() }
        ?.let { Edits.naturals(original, it, verdict.natural_changes, firstId = fixes.size) }
        .orEmpty()

    return if (fixes.isEmpty() && naturals.isEmpty()) {
        CheckResult.AllGood(checkFixes, checkNaturalness)
    } else {
        CheckResult.Feedback(Revision(original, fixes + naturals), checkFixes, checkNaturalness)
    }
}

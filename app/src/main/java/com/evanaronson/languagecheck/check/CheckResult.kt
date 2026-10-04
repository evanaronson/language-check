package com.evanaronson.languagecheck.check

import kotlinx.serialization.Serializable

/** The raw judgment returned by a model, matching assets/check_schema.json. */
@Serializable
data class ModelVerdict(
    val status: String,
    val language: String = "other",
    val has_errors: Boolean = false,
    val corrected: String = "",
    val more_natural: Boolean = false,
    val natural: String = "",
)

/** What the result card shows. */
sealed interface CheckResult {
    /** Correct and natural: nothing to say. */
    data object AllGood : CheckResult

    /** The model could not tell what the text means. */
    data object Unclear : CheckResult

    /** Not Catalan or Spanish. */
    data object NotSupported : CheckResult

    data class Feedback(
        /** Null when no correction is needed. */
        val correction: Suggestion?,
        /** Null when the text already sounds natural. */
        val natural: Suggestion?,
    ) : CheckResult
}

data class Suggestion(
    val text: String,
    /** Character ranges in [text] that differ from what it was compared against. */
    val changed: List<IntRange>,
    /** Number of separate edits; shown as "1 fix", "2 fixes". */
    val edits: Int,
)

/**
 * Turns a model verdict into what the card shows, discarding "changes" that
 * don't actually change anything so a no-op never shows up as a fix.
 */
fun interpret(original: String, verdict: ModelVerdict): CheckResult {
    when (verdict.status) {
        "unclear" -> return CheckResult.Unclear
        "not_supported" -> return CheckResult.NotSupported
    }

    val correction = verdict.corrected
        .takeIf { verdict.has_errors }
        ?.let { suggestion(original, it) }
    val base = correction?.text ?: original
    val natural = verdict.natural
        .takeIf { verdict.more_natural }
        ?.let { suggestion(base, it) }

    return if (correction == null && natural == null) {
        CheckResult.AllGood
    } else {
        CheckResult.Feedback(correction, natural)
    }
}

private fun suggestion(from: String, to: String): Suggestion? {
    val text = to.trim()
    if (text.isEmpty()) return null
    val diff = WordDiff.compare(from.trim(), text)
    if (diff.edits == 0) return null
    return Suggestion(text, diff.changed, diff.edits)
}

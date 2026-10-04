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
        /** Null when no correction is needed, or corrections weren't checked. */
        val correction: Suggestion?,
        /** Null when the text already sounds natural, or naturalness wasn't checked. */
        val natural: Suggestion?,
        val checkedFixes: Boolean = true,
        val checkedNaturalness: Boolean = true,
    ) : CheckResult
}

data class Suggestion(
    val text: String,
    /** Each distinct change, located in [text]. */
    val changes: List<Change>,
    /** Number of separate edits; shown as "1 fix", "2 fixes". */
    val edits: Int,
)

data class Change(
    /** Characters in the suggestion's text covered by this change. */
    val range: IntRange,
    /** What the writer had, when known; empty for something added, like a comma. */
    val from: String?,
    /** Short reason, when known. */
    val why: String?,
)

/**
 * Turns a model verdict into what the card shows, discarding "changes" that
 * don't actually change anything so a no-op never shows up as a fix.
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

    val correction = verdict.corrected
        .takeIf { checkFixes && verdict.has_errors }
        ?.let { suggestion(original, it, verdict.fixes) }
    val base = correction?.text ?: original
    val natural = verdict.natural
        .takeIf { checkNaturalness && verdict.more_natural }
        ?.let { suggestion(base, it, verdict.natural_changes) }

    return if (correction == null && natural == null) {
        CheckResult.AllGood(checkFixes, checkNaturalness)
    } else {
        CheckResult.Feedback(correction, natural, checkFixes, checkNaturalness)
    }
}

private fun suggestion(from: String, to: String, reported: List<ModelChange>): Suggestion? {
    val text = to.trim()
    if (text.isEmpty()) return null
    val diff = WordDiff.compare(from.trim(), text)
    if (diff.edits == 0) return null

    // Prefer the model's own list: it keeps adjacent mistakes separate and says why.
    val located = locate(text, reported)
    if (located.isNotEmpty()) return Suggestion(text, located, reported.size)

    val changes = diff.changed.map { Change(it, from = null, why = null) }
    return Suggestion(text, changes, diff.edits)
}

/**
 * Places each reported change in [text], in order: finds its context, then
 * its replacement inside that context, so a single added comma gets exactly
 * its own highlight. Changes may touch but never overlap.
 */
internal fun locate(text: String, reported: List<ModelChange>): List<Change> {
    val changes = mutableListOf<Change>()
    var searchFrom = 0
    for (change in reported) {
        val target = change.to.trim()
        if (target.isEmpty()) continue
        val range = placeInContext(text, change.context.trim(), target, searchFrom)
            ?: (find(text, target, searchFrom) ?: find(text, target, 0))?.let { it until it + target.length }
            ?: continue
        if (changes.any { it.range.first <= range.last && range.first <= it.range.last }) continue
        changes += Change(range, change.from.trim(), change.why.trim().ifEmpty { null })
        // The next change may sit in the same few words (an accent, then a comma after it).
        searchFrom = maxOf(0, minOf(range.first, text.lastIndexOf(' ', range.first).coerceAtLeast(0)))
    }
    return changes.sortedBy { it.range.first }
}

private fun placeInContext(text: String, context: String, target: String, searchFrom: Int): IntRange? {
    if (context.isEmpty() || target !in context) return null
    val at = find(text, context, searchFrom) ?: find(text, context, 0) ?: return null
    val offset = context.indexOf(target)
    return (at + offset) until (at + offset + target.length)
}

/**
 * Index of [target] in [text] at or after [from], preferring a match that isn't
 * inside a longer word, so "bien" doesn't land in "también".
 */
private fun find(text: String, target: String, from: Int): Int? {
    var fallback: Int? = null
    var index = text.indexOf(target, from)
    while (index >= 0) {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + target.length)
        val wholeWord = (before == null || !before.isLetter() || !target.first().isLetter()) &&
            (after == null || !after.isLetter() || !target.last().isLetter())
        if (wholeWord) return index
        if (fallback == null) fallback = index
        index = text.indexOf(target, index + 1)
    }
    return fallback
}

package com.evanaronson.languagecheck.review

import com.evanaronson.languagecheck.review.Alignment.Op
import com.evanaronson.languagecheck.review.Alignment.Type

/**
 * Turns the model's full corrected or natural text into edits of the original,
 * so positions never depend on the model reporting them; its change list only
 * supplies reasons.
 */
internal object Edits {
    /** One edit per changed word and one per punctuation mark. */
    fun fixes(original: String, target: String, reported: List<VerdictChange>, firstId: Int = 0): List<Edit> =
        withReasons(spans(original, target, atomic = true), reported, EditKind.Fix, firstId)

    /**
     * Phrase-level rewordings. Uses the model's own list when it accounts for
     * the whole natural text, otherwise falls back to aligning the two texts.
     */
    fun naturals(original: String, target: String, reported: List<VerdictChange>, firstId: Int = 0): List<Edit> =
        located(original, target, reported, firstId)
            ?: withReasons(spans(original, target, atomic = false), reported, EditKind.Natural, firstId)

    private data class Span(val start: Int, val end: Int, val from: String, val replacement: String)

    /** The model's rewordings found in the original, or null if they don't produce [target]. */
    private fun located(original: String, target: String, reported: List<VerdictChange>, firstId: Int): List<Edit>? {
        if (reported.isEmpty()) return null
        val edits = mutableListOf<Edit>()
        var searchFrom = 0
        for (change in reported) {
            val from = change.from.trim()
            if (from.isEmpty()) return null
            val at = find(original, from, searchFrom) ?: find(original, from, 0) ?: return null
            val edit = Edit(firstId + edits.size, EditKind.Natural, at, at + from.length, from, change.to.trim(), reason(change))
            if (edits.any { it.overlaps(edit) }) return null
            edits += edit
            searchFrom = edit.end
        }
        return edits.takeIf { render(original, it).text.trim() == target.trim() }
    }

    private fun withReasons(spans: List<Span>, reported: List<VerdictChange>, kind: EditKind, firstId: Int): List<Edit> {
        val unused = reported.toMutableList()
        return spans.mapIndexed { index, span ->
            val match = bestMatch(span, unused)?.also { unused.remove(it) }
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, match?.let(::reason))
        }
    }

    private fun bestMatch(span: Span, candidates: List<VerdictChange>): VerdictChange? {
        val to = span.replacement.trim()
        val from = span.from.trim()
        return candidates.firstOrNull { it.to.trim() == to && it.from.trim() == from }
            ?: candidates.firstOrNull { it.to.trim() == to }
            ?: candidates.firstOrNull { from.isNotEmpty() && it.from.trim() == from }
            ?: candidates.firstOrNull {
                val other = it.to.trim()
                other.isNotEmpty() && to.isNotEmpty() && (other in to || to in other)
            }
    }

    private fun reason(change: VerdictChange) = change.why.trim().ifEmpty { null }

    /** Groups the alignment's changes into spans: atomic for fixes, whole phrases for rewordings. */
    private fun spans(original: String, target: String, atomic: Boolean): List<Span> {
        val ops = Alignment.align(original, target)
        if (ops.isEmpty()) return emptyList()

        // Position in the original before each op, for placing insertions.
        val before = IntArray(ops.size)
        var pos = 0
        ops.forEachIndexed { k, op ->
            before[k] = pos
            op.a?.let { pos = it.end }
        }

        val groups = group(ops, atomic)
        return groups.map { group ->
            val removed = (group[0]..group[1]).mapNotNull { ops[it].a }
            val start = removed.firstOrNull()?.start ?: before[group[0]]
            val end = removed.lastOrNull()?.end ?: start
            tidy(original, Span(start, end, original.substring(start, end), replacement(ops, group)))
        }
    }

    /** Op-index ranges, each one edit. */
    private fun group(ops: List<Op>, atomic: Boolean): List<IntArray> {
        // Consecutive changes form a group; for fixes, every punctuation mark stands alone.
        val groups = mutableListOf<IntArray>()
        var current: IntArray? = null
        for ((k, op) in ops.withIndex()) {
            val open = current
            if (op.isMatch) {
                // In a rewording, a single kept space between two changes doesn't end the phrase.
                val bridges = !atomic && open != null && op.token.type == Type.Space &&
                    k + 1 < ops.size && !ops[k + 1].isMatch
                if (bridges) open!![1] = k else current = null
            } else if (atomic && op.isPunct) {
                groups += intArrayOf(k, k)
                current = null
            } else if (open == null) {
                current = intArrayOf(k, k).also { groups += it }
            } else {
                open[1] = k
            }
        }

        // A group that only changes spacing joins the group before it.
        val joined = mutableListOf<IntArray>()
        for (group in groups) {
            val onlySpace = (group[0]..group[1]).all { ops[it].token.type == Type.Space }
            val previous = joined.lastOrNull()
            if (onlySpace && previous != null && previous[1] == group[0] - 1) previous[1] = group[1] else joined += group
        }

        // A pure deletion has nothing to show, so it grows to include the next unchanged word.
        fun isKeptWord(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Word
        for (group in joined) {
            if (replacement(ops, group).isNotEmpty()) continue
            val next = (group[1] + 1 until ops.size).firstOrNull(::isKeptWord)
            if (next != null) {
                group[1] = next
            } else {
                (group[0] - 1 downTo 0).firstOrNull(::isKeptWord)?.let { group[0] = it }
            }
        }

        val merged = mutableListOf<IntArray>()
        for (group in joined.sortedBy { it[0] }) {
            val previous = merged.lastOrNull()
            if (previous != null && group[0] <= previous[1]) previous[1] = maxOf(previous[1], group[1]) else merged += group
        }
        return merged
    }

    private fun replacement(ops: List<Op>, group: IntArray) =
        (group[0]..group[1]).mapNotNull { ops[it].b?.text }.joinToString("")

    /** Keeps spaces out of highlights: "␣estas amb" → "␣portes" becomes "estas amb" → "portes". */
    private fun tidy(original: String, span: Span): Span {
        var (start, end, from, replacement) = span
        while (from.isNotEmpty() && replacement.isNotEmpty() && from[0] == replacement[0] && from[0].isWhitespace()) {
            from = from.drop(1)
            replacement = replacement.drop(1)
            start++
        }
        while (from.isNotEmpty() && replacement.isNotEmpty() && from.last() == replacement.last() && from.last().isWhitespace()) {
            from = from.dropLast(1)
            replacement = replacement.dropLast(1)
            end--
        }
        // An inserted "␣al" before a space is the same as "al␣" after it.
        if (start == end && replacement.length > 1 && replacement[0].isWhitespace() && original.getOrNull(start) == replacement[0]) {
            replacement = replacement.drop(1) + replacement[0]
            start++
            end++
        }
        return Span(start, end, from, replacement)
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
}

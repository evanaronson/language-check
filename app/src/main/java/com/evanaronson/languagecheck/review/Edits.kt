package com.evanaronson.languagecheck.review

import com.evanaronson.languagecheck.review.Alignment.Op
import com.evanaronson.languagecheck.review.Alignment.Type

/**
 * Turns the model's full corrected and natural texts into edits of the
 * original, so positions never depend on the model reporting them; its change
 * lists only supply reasons.
 */
internal object Edits {
    /** One edit per changed word and one per punctuation mark. */
    fun fixes(original: String, corrected: String, reported: List<VerdictChange>, firstId: Int = 0): List<Edit> {
        val spans = spans(original, corrected, atomic = true).map { tidy(original, it) }
        return toEdits(spans, reported, EditKind.Fix, firstId)
    }

    /**
     * Phrase-level rewordings. The natural text is written on top of the
     * corrected one, so rewordings are found against the corrected text (where
     * the fixes cancel out) and then mapped back onto the original. A rewording
     * that covers a fixed word takes in that word's original form.
     */
    fun naturals(
        original: String,
        fixes: List<Edit>,
        natural: String,
        reported: List<VerdictChange>,
        firstId: Int = 0,
    ): List<Edit> {
        val base = render(original, fixes).text
        val placed = place(fixes)
        val spans = (located(base, natural, reported) ?: spans(base, natural, atomic = false))
            .map { tidy(original, toOriginal(it, base, original, placed)) }
            // Changes that only restate a fix, or undo one, don't change the original.
            .filter { it.from != it.replacement }
        return toEdits(spans, reported, EditKind.Natural, firstId)
    }

    private data class Span(
        val start: Int,
        val end: Int,
        val from: String,
        val replacement: String,
        val why: String? = null,
        /** Ids of the fixes this span's replacement includes. */
        val includes: Set<Int> = emptySet(),
    )

    private fun toEdits(spans: List<Span>, reported: List<VerdictChange>, kind: EditKind, firstId: Int): List<Edit> {
        val unused = reported.toMutableList()
        return spans.mapIndexed { index, span ->
            val why = span.why ?: bestMatch(span, unused)?.also { unused.remove(it) }?.let(::reason)
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, why, span.includes)
        }
    }

    /** The model's rewordings found in [base], or null unless applying them produces [target]. */
    private fun located(base: String, target: String, reported: List<VerdictChange>): List<Span>? {
        if (reported.isEmpty()) return null
        val spans = mutableListOf<Span>()
        var searchFrom = 0
        for (change in reported) {
            val from = change.from.trim()
            if (from.isEmpty()) return null
            val at = find(base, from, searchFrom) ?: find(base, from, 0) ?: return null
            if (spans.any { at < it.end && it.start < at + from.length }) return null
            spans += Span(at, at + from.length, from, change.to.trim(), reason(change))
            searchFrom = at + from.length
        }
        spans.sortBy { it.start }
        val applied = StringBuilder()
        var pos = 0
        for (span in spans) {
            applied.append(base, pos, span.start).append(span.replacement)
            pos = span.end
        }
        applied.append(base, pos, base.length)
        return spans.takeIf { applied.toString().trim() == target.trim() }
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

    // --- Mapping corrected-text positions back to the original -------------

    /** A fix and where its replacement sits in the corrected text. */
    private data class Placed(val fix: Edit, val baseStart: Int, val baseEnd: Int)

    private fun place(fixes: List<Edit>): List<Placed> {
        var delta = 0
        return fixes.sortedWith(editOrder).map { fix ->
            val start = fix.start + delta
            delta += fix.replacement.length - (fix.end - fix.start)
            Placed(fix, start, start + fix.replacement.length)
        }
    }

    /** [span] is in corrected-text positions; the result is in original positions. */
    private fun toOriginal(span: Span, base: String, original: String, placed: List<Placed>): Span {
        // A span edge inside a fix's replacement widens to take in the whole fix.
        var start = span.start
        var end = span.end
        var prefix = ""
        var suffix = ""
        for (p in placed) {
            if (p.baseStart < start && start < p.baseEnd) {
                prefix = base.substring(p.baseStart, start)
                start = p.baseStart
            }
            if (p.baseStart < end && end < p.baseEnd) {
                suffix = base.substring(end, p.baseEnd)
                end = p.baseEnd
            }
        }
        val from = originalPosition(start, placed)
        val to = originalPosition(end, placed)
        // Fixes whose corrected text lies inside the span are part of the rewording,
        // including insertions at its edges, which share no original characters with it.
        val includes = placed
            .filter { it.baseStart < it.baseEnd && start <= it.baseStart && it.baseEnd <= end }
            .map { it.fix.id }
            .toSet()
        return Span(from, to, original.substring(from, to), prefix + span.replacement + suffix, span.why, includes)
    }

    /** Maps a corrected-text position that isn't inside any fix to the original. */
    private fun originalPosition(position: Int, placed: List<Placed>): Int {
        var delta = 0
        for (p in placed) {
            if (position <= p.baseStart) return position - (p.baseStart - p.fix.start)
            delta = p.baseEnd - p.fix.end
        }
        return position - delta
    }

    // --- Grouping the alignment into spans ---------------------------------

    /** Spans of [base] that change to reach [target]: atomic for fixes, whole phrases for rewordings. */
    private fun spans(base: String, target: String, atomic: Boolean): List<Span> {
        val ops = Alignment.align(base, target)
        if (ops.isEmpty()) return emptyList()

        // Position in the base text before each op, for placing insertions.
        val before = IntArray(ops.size)
        var pos = 0
        ops.forEachIndexed { k, op ->
            before[k] = pos
            op.a?.let { pos = it.end }
        }

        return group(ops, atomic).map { group ->
            val removed = (group[0]..group[1]).mapNotNull { ops[it].a }
            val start = removed.firstOrNull()?.start ?: before[group[0]]
            val end = removed.lastOrNull()?.end ?: start
            Span(start, end, base.substring(start, end), replacement(ops, group))
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

        // A pure deletion has nothing to show, so it joins the word change next to it
        // ("y neo" → "Neo"), or else grows to include the nearest unchanged word.
        fun isKeptSpace(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Space
        fun isKeptWord(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Word
        fun wordGroupAt(k: Int) = joined.firstOrNull { k in it[0]..it[1] }
            ?.takeIf { group -> (group[0]..group[1]).none { ops[it].isPunct } }
        for (group in joined) {
            if (replacement(ops, group).isNotEmpty()) continue
            var after = group[1] + 1
            while (after < ops.size && isKeptSpace(after)) after++
            var before = group[0] - 1
            while (before >= 0 && isKeptSpace(before)) before--
            val next = if (after < ops.size && !ops[after].isMatch) wordGroupAt(after) else null
            val previous = if (before >= 0 && !ops[before].isMatch) wordGroupAt(before) else null
            when {
                next != null -> group[1] = next[1]
                previous != null -> group[0] = previous[0]
                else -> {
                    val kept = (group[1] + 1 until ops.size).firstOrNull(::isKeptWord)
                    if (kept != null) {
                        group[1] = kept
                    } else {
                        (group[0] - 1 downTo 0).firstOrNull(::isKeptWord)?.let { group[0] = it }
                    }
                }
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
    private fun tidy(text: String, span: Span): Span {
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
        if (start == end && replacement.length > 1 && replacement[0].isWhitespace() && text.getOrNull(start) == replacement[0]) {
            replacement = replacement.drop(1) + replacement[0]
            start++
            end++
        }
        return span.copy(start = start, end = end, from = from, replacement = replacement)
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

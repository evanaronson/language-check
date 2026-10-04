package com.evanaronson.languagecheck.check

import java.text.Normalizer

/** Which section of the card an edit belongs to. */
enum class EditKind { Fix, Natural }

/**
 * One suggested change, anchored to a span of the writer's original text, so
 * edits can be accepted in any order without disturbing each other.
 */
data class Edit(
    val id: Int,
    val kind: EditKind,
    /** Start of the replaced span in the original text. */
    val start: Int,
    /** End of the span (exclusive); equal to [start] when something is only inserted. */
    val end: Int,
    /** The original text being replaced; empty for an insertion. */
    val from: String,
    val replacement: String,
    val why: String?,
) {
    val isInsertion get() = start == end

    fun overlaps(other: Edit): Boolean = when {
        !isInsertion && !other.isInsertion -> start < other.end && other.start < end
        isInsertion && !other.isInsertion -> other.start < start && start < other.end
        !isInsertion && other.isInsertion -> start < other.start && other.start < end
        else -> start == other.start && kind != other.kind
    }
}

/**
 * Turns the model's full corrected or natural text into edits by aligning it
 * with the original, word by word and mark by mark. Positions therefore never
 * depend on the model reporting them; its change list only supplies reasons.
 */
object Edits {
    /** One edit per changed word and one per punctuation mark. */
    fun fixes(original: String, target: String, reported: List<ModelChange>, firstId: Int = 0): List<Edit> {
        val spans = spans(original, target, atomic = true)
        return withReasons(spans, reported, EditKind.Fix, firstId)
    }

    /**
     * Phrase-level rewordings. Uses the model's own list when it accounts for
     * the whole natural text, otherwise falls back to aligning the two texts.
     */
    fun naturals(original: String, target: String, reported: List<ModelChange>, firstId: Int = 0): List<Edit> {
        located(original, target, reported, firstId)?.let { return it }
        return withReasons(spans(original, target, atomic = false), reported, EditKind.Natural, firstId)
    }

    /** Applies [edits] to [original]. */
    fun apply(original: String, edits: List<Edit>): String {
        val out = StringBuilder()
        var pos = 0
        for (edit in edits.sortedWith(order)) {
            if (edit.start < pos) continue
            out.append(original, pos, edit.start).append(edit.replacement)
            pos = edit.end
        }
        return out.append(original, pos, original.length).toString()
    }

    /** Insertions come before a replacement starting at the same place. */
    internal val order = compareBy<Edit>({ it.start }, { if (it.isInsertion) 0 else 1 })

    private data class Span(val start: Int, val end: Int, val from: String, val replacement: String)

    private fun located(original: String, target: String, reported: List<ModelChange>, firstId: Int): List<Edit>? {
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
        return edits.sortedWith(order).takeIf { apply(original, it).trim() == target.trim() }
    }

    private fun withReasons(spans: List<Span>, reported: List<ModelChange>, kind: EditKind, firstId: Int): List<Edit> {
        val unused = reported.toMutableList()
        return spans.mapIndexed { index, span ->
            val match = bestMatch(span, unused)?.also { unused.remove(it) }
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, match?.let(::reason))
        }
    }

    private fun bestMatch(span: Span, candidates: List<ModelChange>): ModelChange? {
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

    private fun reason(change: ModelChange) = change.why.trim().ifEmpty { null }

    // --- Alignment ---------------------------------------------------------

    private enum class Type { Word, Space, Punct }

    private data class Token(val text: String, val start: Int, val type: Type) {
        val end get() = start + text.length
    }

    /** One step of the alignment: a token kept, replaced, removed (b null) or added (a null). */
    private data class Op(val a: Token?, val b: Token?) {
        val isMatch get() = a != null && b != null && a.text == b.text
        val token get() = a ?: b!!
        val isPunct get() = a?.type == Type.Punct || b?.type == Type.Punct
    }

    private fun spans(original: String, target: String, atomic: Boolean): List<Span> {
        val ops = align(tokenize(original), tokenize(target))
        if (ops.isEmpty()) return emptyList()

        // Position in the original before each op, for placing insertions.
        val before = IntArray(ops.size)
        var pos = 0
        ops.forEachIndexed { k, op ->
            before[k] = pos
            op.a?.let { pos = it.end }
        }

        // Group consecutive changes; for fixes, every punctuation mark stands alone.
        val groups = mutableListOf<IntArray>()
        var current: IntArray? = null
        for ((k, op) in ops.withIndex()) {
            val open = current
            if (op.isMatch) {
                // For rewordings, a single kept space between two changes doesn't end the phrase.
                val bridgesChanges = !atomic && open != null && op.token.type == Type.Space &&
                    k + 1 < ops.size && !ops[k + 1].isMatch
                if (bridgesChanges) open!![1] = k else current = null
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
        fun replacement(group: IntArray) = (group[0]..group[1]).mapNotNull { ops[it].b?.text }.joinToString("")
        fun isKeptWord(k: Int) = ops[k].isMatch && ops[k].token.type == Type.Word
        for (group in joined) {
            if (replacement(group).isNotEmpty()) continue
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

        return merged.map { group ->
            val removed = (group[0]..group[1]).mapNotNull { ops[it].a }
            val start = removed.firstOrNull()?.start ?: before[group[0]]
            val end = removed.lastOrNull()?.end ?: start
            tidy(original, Span(start, end, original.substring(start, end), replacement(group)))
        }
    }

    /** Keeps spaces out of highlights: "␣estas amb" → "␣portes" becomes "estas amb" → "portes". */
    private fun tidy(original: String, span: Span): Span {
        var (start, end, from, replacement) = span
        while (from.isNotEmpty() && replacement.isNotEmpty() && from[0] == replacement[0] && from[0].isWhitespace()) {
            from = from.drop(1); replacement = replacement.drop(1); start++
        }
        while (from.isNotEmpty() && replacement.isNotEmpty() && from.last() == replacement.last() && from.last().isWhitespace()) {
            from = from.dropLast(1); replacement = replacement.dropLast(1); end--
        }
        // An inserted "␣al" before a space is the same as "al␣" after it.
        if (start == end && replacement.length > 1 && replacement[0].isWhitespace() && original.getOrNull(start) == replacement[0]) {
            replacement = replacement.drop(1) + replacement[0]
            start++; end++
        }
        return Span(start, end, from, replacement)
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val type = typeOf(text[i])
            var j = i + 1
            if (type != Type.Punct) while (j < text.length && typeOf(text[j]) == type) j++
            tokens += Token(text.substring(i, j), i, type)
            i = j
        }
        return tokens
    }

    private fun typeOf(c: Char) = when {
        c.isWhitespace() -> Type.Space
        c.isLetterOrDigit() || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Type.Word
        else -> Type.Punct
    }

    private fun gap(t: Token) = if (t.type == Type.Space) 1 else 2

    /** Cost of aligning [x] with [y], or null when they can't be aligned (a word with a comma). */
    private fun substitution(x: Token, y: Token): Int? = when {
        x.text == y.text -> 0
        x.type != y.type -> null
        x.type == Type.Space -> 1
        x.type == Type.Word && fold(x.text) == fold(y.text) -> 1
        else -> 2
    }

    /** Lowercase without accents, so "estas" pairs with "Estás". */
    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun align(a: List<Token>, b: List<Token>): List<Op> {
        val n = a.size
        val m = b.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) cost[i][0] = cost[i - 1][0] + gap(a[i - 1])
        for (j in 1..m) cost[0][j] = cost[0][j - 1] + gap(b[j - 1])
        for (i in 1..n) {
            for (j in 1..m) {
                var best = minOf(cost[i - 1][j] + gap(a[i - 1]), cost[i][j - 1] + gap(b[j - 1]))
                substitution(a[i - 1], b[j - 1])?.let { best = minOf(best, cost[i - 1][j - 1] + it) }
                cost[i][j] = best
            }
        }
        val ops = ArrayDeque<Op>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val sub = if (i > 0 && j > 0) substitution(a[i - 1], b[j - 1]) else null
            when {
                sub != null && cost[i][j] == cost[i - 1][j - 1] + sub -> ops.addFirst(Op(a[--i], b[--j]))
                i > 0 && cost[i][j] == cost[i - 1][j] + gap(a[i - 1]) -> ops.addFirst(Op(a[--i], null))
                else -> ops.addFirst(Op(null, b[--j]))
            }
        }
        return ops
    }
}

/**
 * Index of [target] in [text] at or after [from], preferring a match that isn't
 * inside a longer word, so "bien" doesn't land in "también".
 */
internal fun find(text: String, target: String, from: Int): Int? {
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

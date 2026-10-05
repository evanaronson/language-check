package com.evanaronson.linguize.core

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Aligns two versions of a text token by token (words, single punctuation
 * marks, runs of spaces) with the fewest changes, preferring to pair a word
 * with its own corrected spelling ("estas" with "Estás").
 */
internal object Alignment {
    enum class Type { Word, Space, Punct }

    data class Token(val text: String, val start: Int, val type: Type) {
        val end get() = start + text.length

        /** Lowercase without accents, for pairing a word with its corrected spelling. */
        val folded: String = if (type == Type.Word) fold(text) else text
    }

    /** One step of the alignment: a token kept, replaced, removed (b null) or added (a null). */
    data class Op(val a: Token?, val b: Token?) {
        val isMatch get() = a != null && b != null && a.text == b.text
        val token get() = a ?: b!!
        val isPunct get() = a?.type == Type.Punct || b?.type == Type.Punct
    }

    fun align(original: String, target: String): List<Op> {
        val a = tokenize(original)
        val b = tokenize(target)
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

    /** Words, runs of spaces and single punctuation marks, never splitting a character such as an emoji. */
    private fun tokenize(text: String): List<Token> {
        val clusters = characters(text)
        val types = clusters.map { typeOf(text.codePointAt(it.first)) }.toMutableList()
        // A hyphen or middle dot between letters belongs to the word: "dir-ho", "col·legi".
        for (k in 1 until clusters.lastIndex) {
            val cluster = clusters[k]
            if (cluster.last == cluster.first && text[cluster.first] in JOINERS &&
                types[k - 1] == Type.Word && types[k + 1] == Type.Word
            ) {
                types[k] = Type.Word
            }
        }
        val tokens = mutableListOf<Token>()
        var k = 0
        while (k < clusters.size) {
            val type = types[k]
            var next = k + 1
            if (type != Type.Punct) while (next < clusters.size && types[next] == type) next++
            tokens += Token(text.substring(clusters[k].first, clusters[next - 1].last + 1), clusters[k].first, type)
            k = next
        }
        return tokens
    }

    /** The user-perceived characters of [text], as index ranges. */
    private fun characters(text: String): List<IntRange> {
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        val clusters = mutableListOf<IntRange>()
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            clusters += start until end
            start = end
            end = boundaries.next()
        }
        return clusters
    }

    private fun typeOf(codePoint: Int) = when {
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> Type.Space
        Character.isLetterOrDigit(codePoint) || codePoint == '\''.code || codePoint == '’'.code ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() -> Type.Word
        else -> Type.Punct
    }

    private fun gap(t: Token) = if (t.type == Type.Space) 1 else 2

    /** Cost of pairing [x] with [y], or null when they can't pair (a word with a comma). */
    private fun substitution(x: Token, y: Token): Int? = when {
        x.text == y.text -> 0
        x.type != y.type -> null
        x.type == Type.Space -> 1
        x.type == Type.Word && x.folded == y.folded -> 1
        else -> 2
    }

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private val JOINERS = setOf('-', '·', '‐')
}

package com.evanaronson.languagecheck.review

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
        c.isLetterOrDigit() || c == '\'' || c == '’' || isCombiningMark(c) -> Type.Word
        else -> Type.Punct
    }

    private fun gap(t: Token) = if (t.type == Type.Space) 1 else 2

    /** Cost of pairing [x] with [y], or null when they can't pair (a word with a comma). */
    private fun substitution(x: Token, y: Token): Int? = when {
        x.text == y.text -> 0
        x.type != y.type -> null
        x.type == Type.Space -> 1
        x.type == Type.Word && fold(x.text) == fold(y.text) -> 1
        else -> 2
    }

    /** Lowercase without accents. */
    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot(::isCombiningMark)

    private fun isCombiningMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()
}

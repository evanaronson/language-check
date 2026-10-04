package com.evanaronson.languagecheck.check

/**
 * Word-level diff between two short texts. Punctuation attached to a word
 * counts as part of it, so "estas?" -> "estàs?" is one changed word.
 */
object WordDiff {
    data class Result(
        /** Character ranges in the new text covering inserted or changed words. */
        val changed: List<IntRange>,
        /** Number of contiguous runs of changes (insertions, deletions or replacements). */
        val edits: Int,
    )

    private data class Token(val text: String, val range: IntRange)

    private val wordPattern = Regex("""\S+""")

    fun compare(old: String, new: String): Result {
        val a = tokens(old)
        val b = tokens(new)

        // Longest common subsequence over words; texts are a sentence or two.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i].text == b[j].text) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val changed = mutableListOf<IntRange>()
        var edits = 0
        var inEdit = false
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i].text == b[j].text -> {
                    inEdit = false
                    i++
                    j++
                }
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    if (!inEdit) edits++
                    inEdit = true
                    addRange(changed, b[j].range)
                    j++
                }
                else -> {
                    if (!inEdit) edits++
                    inEdit = true
                    i++
                }
            }
        }
        return Result(changed, edits)
    }

    private fun tokens(text: String) =
        wordPattern.findAll(text).map { Token(it.value, it.range) }.toList()

    /** Adds a range, merging it with the previous one when only whitespace separates them. */
    private fun addRange(ranges: MutableList<IntRange>, range: IntRange) {
        val last = ranges.lastOrNull()
        if (last != null && range.first - last.last <= 2) {
            ranges[ranges.lastIndex] = last.first..range.last
        } else {
            ranges += range
        }
    }
}

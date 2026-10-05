package com.evanaronson.linguize.core

/**
 * Selected text split from what surrounds it: the part to check (trimmed of
 * surrounding whitespace) and the text before and after it, so a corrected
 * version can be put back in its place without touching anything else.
 */
data class Selection(val before: String, val text: String, val after: String) {
    /** The full text the selection was taken from. */
    val full get() = before + text + after

    /** The full text with [replacement] in place of [text]. */
    fun with(replacement: String) = before + replacement + after

    /** Where the cursor goes after [replacement] is put back: just after it. */
    fun cursorAfter(replacement: String) = before.length + replacement.length

    companion object {
        /** All of [text], minus any whitespace around it. */
        fun of(text: String) = of(text, 0, text.length)

        /**
         * Splits [full] around the selection between [start] and [end] (in either order),
         * or around all of it when nothing is selected.
         */
        fun of(full: String, start: Int, end: Int): Selection {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            val selected = from >= 0 && from < to && to <= full.length
            val part = if (selected) full.substring(from, to) else full
            val offset = if (selected) from else 0
            val leading = part.length - part.trimStart().length
            if (leading == part.length) return Selection(full, "", "")
            val trailing = part.length - part.trimEnd().length
            return Selection(
                before = full.substring(0, offset + leading),
                text = part.trim(),
                after = full.substring(offset + part.length - trailing),
            )
        }
    }
}

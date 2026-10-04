package com.evanaronson.languagecheck.accessibility

/**
 * The text read from a field: the part to check (the selection, or everything)
 * and what surrounds it, so a corrected version can be put back in its place.
 */
data class FieldText(val before: String, val text: String, val after: String) {
    /** The field's full text with [replacement] in place of [text]. */
    fun with(replacement: String) = before + replacement + after

    /** Where the cursor goes after [replacement] is put back: just after it. */
    fun cursorAfter(replacement: String) = before.length + replacement.length

    companion object {
        /**
         * Splits [full] around the selection [start]..[end], or around all of it when
         * nothing is selected. Surrounding spaces stay outside the checked text.
         */
        fun of(full: String, start: Int, end: Int): FieldText {
            val selected = start in 0..<end && end <= full.length
            val from = if (selected) start else 0
            val to = if (selected) end else full.length
            val part = full.substring(from, to)
            val leading = part.length - part.trimStart().length
            val trailing = part.length - part.trimEnd().length
            if (leading == part.length) return FieldText(full, "", "")
            return FieldText(
                before = full.substring(0, from + leading),
                text = part.trim(),
                after = full.substring(to - trailing),
            )
        }
    }
}

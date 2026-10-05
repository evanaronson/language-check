package com.evanaronson.linguize.core

/** A language a check can be pinned to; none means the model detects it. */
data class Language(
    val name: String,
    /** The variety treated as standard. */
    val variety: String,
) {
    companion object {
        val Catalan = Language("Catalan", "standard Central Catalan")
        val Spanish = Language("Spanish", "Peninsular")
    }
}

/** How strictly fixes cover punctuation and capitalisation; check_prompt.md defines each level. */
enum class Punctuation { Strict, Moderate, Casual }

/** Which judgments a check makes; the model skips the others entirely. */
enum class Judgments(val kinds: Set<EditKind>) {
    Both(setOf(EditKind.Fix, EditKind.Natural)),
    FixOnly(setOf(EditKind.Fix)),
    NaturalizeOnly(setOf(EditKind.Natural)),
}

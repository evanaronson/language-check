package com.evanaronson.languagecheck.review

/** A language checks can be pinned to; none chosen means the model detects it. */
data class Language(
    val code: String,
    val name: String,
    /** The variety treated as standard. */
    val variety: String,
) {
    companion object {
        val all = listOf(
            Language("ca", "Catalan", "standard Central Catalan"),
            Language("es", "Spanish", "Peninsular"),
        )

        fun byCode(code: String?) = all.firstOrNull { it.code == code }
    }
}

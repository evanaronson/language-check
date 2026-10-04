package com.evanaronson.languagecheck.review

/** A language a check can be pinned to; none means the model detects it. */
data class Language(
    val code: String,
    val name: String,
    /** The variety treated as standard. */
    val variety: String,
) {
    companion object {
        val Catalan = Language("ca", "Catalan", "standard Central Catalan")
        val Spanish = Language("es", "Spanish", "Peninsular")
    }
}

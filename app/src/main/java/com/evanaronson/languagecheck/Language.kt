package com.evanaronson.languagecheck

/** A language checks can be pinned to; null in settings means detect automatically. */
data class Language(
    val code: String,
    val name: String,
    /** How the language and variety are named to the model. */
    val promptName: String,
) {
    companion object {
        val all = listOf(
            Language("ca", "Catalan", "Catalan (standard Central Catalan)"),
            Language("es", "Spanish", "Spanish (Peninsular)"),
        )

        fun byCode(code: String?) = all.firstOrNull { it.code == code }
    }
}

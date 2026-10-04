package com.evanaronson.languagecheck

/** How strictly checks judge punctuation and capitalisation; defined in check_prompt.md. */
enum class Punctuation(val label: String, val description: String) {
    Strict("Strict", "Full standard punctuation, including ¿ ¡ and final full stops"),
    Moderate("Moderate", "Separate sentences and mark questions; no final full stop or ¿ needed"),
    Casual("Casual", "Only when missing punctuation would confuse a reader"),
    ;

    /** The value sent on the prompt's `Punctuation:` line. */
    val promptName get() = name.lowercase()
}

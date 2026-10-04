package com.evanaronson.languagecheck

/** Which judgments a check makes; the prompt skips the other one entirely. */
enum class Checks(val label: String, val fixes: Boolean, val naturalness: Boolean) {
    Both("Fix and naturalize", fixes = true, naturalness = true),
    FixOnly("Fix only", fixes = true, naturalness = false),
    NaturalizeOnly("Naturalize only", fixes = false, naturalness = true),
    ;

    /** The value sent on the prompt's `Checks:` line. */
    val promptName
        get() = when (this) {
            Both -> "both"
            FixOnly -> "fix"
            NaturalizeOnly -> "naturalize"
        }
}

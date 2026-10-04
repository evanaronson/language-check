package com.evanaronson.languagecheck.review

/** Which judgments a check makes; the model skips the others entirely. */
enum class Judgments(val label: String, val kinds: Set<EditKind>) {
    Both("Fix and naturalize", setOf(EditKind.Fix, EditKind.Natural)),
    FixOnly("Fix only", setOf(EditKind.Fix)),
    NaturalizeOnly("Naturalize only", setOf(EditKind.Natural)),
}

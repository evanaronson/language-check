package com.evanaronson.linguize.ui.settings

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation

val Punctuation.label: String
    get() = when (this) {
        Punctuation.Strict -> "Strict"
        Punctuation.Moderate -> "Moderate"
        Punctuation.Casual -> "Casual"
    }

val Punctuation.description: String
    get() = when (this) {
        Punctuation.Strict -> "Full standard punctuation, including ¿ ¡ and final full stops"
        Punctuation.Moderate -> "Separate sentences and mark questions; no final full stop or ¿ needed"
        Punctuation.Casual -> "Only when missing punctuation would confuse a reader"
    }

val Judgments.label: String
    get() = when (this) {
        Judgments.Both -> "Fix and naturalize"
        Judgments.FixOnly -> "Fix only"
        Judgments.NaturalizeOnly -> "Naturalize only"
    }

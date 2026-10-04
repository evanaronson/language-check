package com.evanaronson.languagecheck.check

import android.content.Context

/**
 * The instructions and output schema, kept in assets so the eval script in
 * eval/ tests exactly what the app sends.
 */
class Prompt(val system: String, val schemaJson: String) {
    companion object {
        fun load(context: Context) = Prompt(
            system = context.assets.open("check_prompt.md").bufferedReader().use { it.readText() },
            schemaJson = context.assets.open("check_schema.json").bufferedReader().use { it.readText() },
        )
    }
}

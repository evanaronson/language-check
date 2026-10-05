package com.evanaronson.languagecheck.llm

import android.content.Context
import com.evanaronson.languagecheck.review.Judgments
import com.evanaronson.languagecheck.review.Punctuation
import com.evanaronson.languagecheck.review.Verdict
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The contract with the model: instructions, answer schema, the user message
 * format and how answers are read. The instructions and schema live in assets
 * so the eval script in eval/ tests exactly what the app sends.
 */
class Prompt(val system: String, schemaJson: String) {
    /** The answer format, as JSON Schema. */
    val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    /** The setting lines check_prompt.md describes, then the text. */
    fun userMessage(request: CheckRequest) = buildString {
        val language = request.language?.let { "${it.name} (${it.variety})" } ?: "auto"
        appendLine("Language: $language")
        appendLine("Punctuation: ${token(request.punctuation)}")
        appendLine("Checks: ${token(request.judgments)}")
        appendLine("Native: ${request.native} (write meaning, assumptions and reasons in ${request.native})")
        request.settled.forEach { appendLine("Settled: ${it.about} → ${it.answer}") }
        append("Text: ${request.text}")
    }

    fun parseVerdict(answer: String): Verdict {
        if (answer.isBlank()) throw CheckFailure(CheckFailure.Reason.BadResponse, "The model returned no answer")
        return try {
            json.decodeFromString<Verdict>(answer)
        } catch (e: SerializationException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, "The model's answer wasn't in the expected format", e)
        } catch (e: IllegalArgumentException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, "The model's answer wasn't in the expected format", e)
        }
    }

    private fun token(punctuation: Punctuation) = when (punctuation) {
        Punctuation.Strict -> "strict"
        Punctuation.Moderate -> "moderate"
        Punctuation.Casual -> "casual"
    }

    private fun token(judgments: Judgments) = when (judgments) {
        Judgments.Both -> "both"
        Judgments.FixOnly -> "fix"
        Judgments.NaturalizeOnly -> "naturalize"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context) = Prompt(
            system = context.assets.open("check_prompt.md").bufferedReader().use { it.readText() },
            schemaJson = context.assets.open("check_schema.json").bufferedReader().use { it.readText() },
        )
    }
}

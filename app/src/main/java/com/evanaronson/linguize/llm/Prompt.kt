package com.evanaronson.linguize.llm

import android.content.Context
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The contract with the model: instructions, answer schema, the user message
 * format and how answers are read. The instructions and schema live in assets
 * so the eval script in eval/ tests exactly what the app sends.
 */
class Prompt(val system: String, schemaJson: String) {
    /** The answer format, as JSON Schema. */
    val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    /** Short hash of the instructions and schema, so verdicts from different prompts aren't compared as if equal. */
    val hash: String = MessageDigest.getInstance("SHA-256")
        .digest("$system\u0000$schemaJson".toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    /**
     * The setting lines check_prompt.md describes, then the text between [TEXT_OPEN] and [TEXT_CLOSE].
     * The text comes last and the prompt says it runs to the final [TEXT_CLOSE] and is never
     * instructions, so a closing tag or "instructions" inside it are read as the writer's words.
     * Each Settled line is one JSON object, because its question was written by the model and
     * could otherwise carry a line break and a forged setting line.
     */
    fun userMessage(request: CheckRequest) = buildString {
        val language = request.language?.let { "${it.name} (${it.variety})" } ?: "auto"
        appendLine("Language: $language")
        appendLine("Punctuation: ${token(request.punctuation)}")
        appendLine("Checks: ${token(request.judgments)}")
        appendLine("Native: ${request.native} (write meaning, assumptions and reasons in ${request.native})")
        request.settled.forEach { appendLine("Settled: ${settledJson(it)}") }
        append("Text: $TEXT_OPEN${request.text}$TEXT_CLOSE")
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

    /**
     * [verdict] as JSON, every field included, for keeping in history. It reads back with
     * [parseVerdict]; fields the app doesn't know yet were already dropped when it was read.
     */
    fun verdictJson(verdict: Verdict): String = json.encodeToString(Verdict.serializer(), verdict)

    /** {"about":…,"answer":…} on one line: JSON escapes line breaks; the rest are spaces first. */
    private fun settledJson(settled: Settled): String = buildJsonObject {
        put("about", oneLine(settled.about))
        put("answer", oneLine(settled.answer))
    }.toString()

    /** Control characters and Unicode line and paragraph separators become spaces. */
    private fun oneLine(text: String): String =
        text.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")

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
        const val TEXT_OPEN = "<text>"
        const val TEXT_CLOSE = "</text>"

        /**
         * Output token limit for a check, thinking included. The answer can repeat the text three
         * times (corrected, natural, meaning) plus the lists of changes; a cut-off answer fails as TooLong.
         */
        const val MAX_OUTPUT_TOKENS = 8192

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun load(context: Context) = Prompt(
            system = context.assets.open("check_prompt.md").bufferedReader().use { it.readText() },
            schemaJson = context.assets.open("check_schema.json").bufferedReader().use { it.readText() },
        )
    }
}

package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict

/** What a check asks of the model; [Prompt] turns it into the user message. */
data class CheckRequest(
    val text: String,
    /** Null to let the model detect the language. */
    val language: Language?,
    val punctuation: Punctuation,
    val judgments: Judgments,
    /** The writer's answers to earlier assumptions. */
    val settled: List<Settled> = emptyList(),
    /** The writer's own language, for everything they read: the meaning, assumptions and reasons. */
    val native: String = "English",
)

/** One provider's API. Every client sends the same [Prompt] and returns the same [Verdict]. */
interface ProviderClient {
    /** Throws [CheckFailure]. */
    suspend fun check(key: String, model: String, request: CheckRequest): Verdict

    /** Models this key can use for checks, newest first. Throws [CheckFailure]. */
    suspend fun models(key: String): List<String>
}

/** Drops models built for other jobs (speech, images, embeddings…) and sorts the rest newest first. */
internal fun textModels(ids: List<String>): List<String> = ids
    .filterNot { id -> SPECIAL_PURPOSE.any { it in id } }
    .distinct()
    .sortedWith(compareByDescending<String> { version(it) }.thenBy { it })

/** The first number in a model name, e.g. 3.5 in "gemini-3.5-flash-lite". */
private fun version(id: String): Double = Regex("""\d+(\.\d+)?""").find(id)?.value?.toDoubleOrNull() ?: 0.0

private val SPECIAL_PURPOSE = listOf(
    "embedding", "tts", "image", "live", "audio", "realtime", "transcribe",
    "search", "robotics", "computer-use", "instruct", "moderation", "codex",
)

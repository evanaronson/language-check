package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.data.ApiKeys
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The settings a check runs with, read once so the request and the record of it agree. */
data class CheckContext(
    val provider: Provider,
    val model: String,
    val punctuation: Punctuation,
    val judgments: Judgments,
    /** The writer's own language, which the meaning and reasons are written in. */
    val native: String,
    /** [Prompt.hash] of the instructions and schema sent. */
    val promptHash: String,
)

/** A finished check: what the card shows, the model's answer as JSON ([Prompt.verdictJson]), and the settings it ran with. */
data class Checked(val result: CheckResult, val verdict: String, val context: CheckContext)

/**
 * Runs checks with the provider, model and options chosen in settings.
 * Every failure is a [CheckFailure]. Storage, key decryption and the network run off the main thread.
 */
class Checker(
    private val settings: Settings,
    private val keys: ApiKeys,
    private val prompt: Prompt,
    private val clients: Map<Provider, ProviderClient>,
) {
    /** The settings a check started now would run with. */
    suspend fun context(): CheckContext = failingAsCheckFailure { readContext() }

    /**
     * Checks [text] as [language], or lets the model detect the language when it's null.
     * [settled] are the writer's answers to assumptions from an earlier check of the same text.
     * Runs with [context] when given, else with the settings as they are now.
     */
    suspend fun check(
        text: String,
        language: Language?,
        settled: List<Settled> = emptyList(),
        context: CheckContext? = null,
    ): Checked {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong)
        return failingAsCheckFailure {
            val used = context ?: readContext()
            val request = CheckRequest(text, language, used.punctuation, used.judgments, settled, used.native)
            val verdict = clients.getValue(used.provider).check(key(used.provider), used.model, request)
            Checked(interpret(text, verdict, used.judgments, expectedLanguage = language?.name), prompt.verdictJson(verdict), used)
        }
    }

    /**
     * Shows a [verdict] kept from an earlier check of the same [text] (JSON, as [Checked.verdict])
     * again, without asking the model. Throws [CheckFailure] when it can't be read.
     */
    suspend fun reuse(text: String, language: Language?, verdict: String, context: CheckContext): Checked = failingAsCheckFailure {
        val result = interpret(text, prompt.parseVerdict(verdict), context.judgments, expectedLanguage = language?.name)
        Checked(result, verdict, context)
    }

    /** Runs a short check with [model]; returns how long it took in milliseconds. */
    suspend fun testModel(provider: Provider, model: String): Long = failingAsCheckFailure {
        val started = System.nanoTime()
        clients.getValue(provider).check(key(provider), model, TEST_REQUEST)
        (System.nanoTime() - started) / 1_000_000
    }

    /** The models [provider]'s key can use, newest first. */
    suspend fun models(provider: Provider): List<String> = failingAsCheckFailure {
        clients.getValue(provider).models(key(provider))
    }

    private fun readContext(): CheckContext {
        val provider = settings.provider
        return CheckContext(
            provider = provider,
            model = settings.model(provider) ?: provider.recommendedModel,
            punctuation = settings.punctuation,
            judgments = settings.judgments,
            native = settings.nativeLanguage,
            promptHash = prompt.hash,
        )
    }

    private fun key(provider: Provider): String {
        val key = keys.get(provider) ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        // HTTP headers only carry printable ASCII; anything else can't be a real key.
        if (key.any { it !in ' '..'~' }) throw CheckFailure(CheckFailure.Reason.BadKey, "The saved key contains characters a key can't have")
        return key
    }

    /** Runs [block] off the main thread; anything unexpected becomes a [CheckFailure] rather than a crash. */
    private suspend fun <T> failingAsCheckFailure(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, e.message, e)
        }
    }

    private companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000

        val TEST_REQUEST = CheckRequest("Bon dia, com estas?", null, Punctuation.Moderate, Judgments.Both)
    }
}

package com.evanaronson.languagecheck.llm

import com.evanaronson.languagecheck.review.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/** OpenAI's Responses API over plain REST. */
class OpenAIClient(private val http: OkHttpClient, private val prompt: Prompt) : ProviderClient {
    /** Strict structured output rejects Gemini's non-standard propertyOrdering. */
    private val schema = JsonObject(prompt.schema - "propertyOrdering")

    /** Models that rejected reasoning effort "none"; they get their default instead. */
    private val noReasoningControl = ConcurrentHashMap.newKeySet<String>()

    override suspend fun check(key: String, model: String, request: CheckRequest): Verdict {
        val noReasoning = model !in noReasoningControl
        val call = Request.Builder()
            .url("$BASE/responses")
            .header("Authorization", "Bearer $key")
            .post(body(model, request, noReasoning).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, payload) = http.newCall(call).await()
        if (code == 400 && noReasoning && "reasoning" in payload.lowercase()) {
            noReasoningControl += model
            return check(key, model, request)
        }
        if (code != 200) throw failureFor(code, payload)
        return prompt.parseVerdict(answer(payload))
    }

    override suspend fun models(key: String): List<String> {
        val call = Request.Builder().url("$BASE/models").header("Authorization", "Bearer $key").build()
        val (code, payload) = http.newCall(call).await()
        if (code != 200) throw failureFor(code, payload)
        val ids = readResponse(payload) { page ->
            page["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        }
        return textModels(ids.filter { it.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(it) })
    }

    private fun body(model: String, request: CheckRequest, noReasoning: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", prompt.system)
        put("input", prompt.userMessage(request))
        put("store", false)
        // Room for a long paragraph twice over plus the list of changes.
        put("max_output_tokens", 4096)
        // No reasoning keeps a check inside the ~2 s budget.
        if (noReasoning) putJsonObject("reasoning") { put("effort", "none") }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "check")
                put("strict", true)
                put("schema", schema)
            }
        }
    }

    /** The text of the output message. */
    private fun answer(payload: String): String = readResponse(payload) { root ->
        root["output"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "message" }
            .flatMap { it["content"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "output_text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
    }

    private companion object {
        const val BASE = "https://api.openai.com/v1"
    }
}

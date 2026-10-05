package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * The Gemini Developer API over plain REST. The official SDKs pull in Firebase
 * or Ktor plus Google auth; two endpoints don't need them.
 */
class GeminiClient(private val http: OkHttpClient, private val prompt: Prompt) : ProviderClient {
    /** Models that rejected the minimal thinking level; they get their default instead. */
    private val noMinimalThinking = ConcurrentHashMap.newKeySet<String>()

    override suspend fun check(key: String, model: String, request: CheckRequest): Verdict {
        val minimal = model !in noMinimalThinking
        val call = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body(request, minimal).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, payload) = http.newCall(call).await()
        if (code == 400 && minimal && "thinking" in payload.lowercase()) {
            noMinimalThinking += model
            return check(key, model, request)
        }
        if (code != 200) throw failureFor(code, payload)
        return prompt.parseVerdict(answer(payload))
    }

    override suspend fun models(key: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "$BASE/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val (code, payload) = http.newCall(Request.Builder().url(url).header("x-goog-api-key", key).build()).await()
            if (code != 200) throw failureFor(code, payload)
            pageToken = readResponse(payload) { page ->
                for (model in page["models"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                    val id = model["name"]?.jsonPrimitive?.content?.removePrefix("models/") ?: continue
                    val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                    if (id.startsWith("gemini") && "generateContent" in methods) ids += id
                }
                page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            }
        } while (pageToken != null)
        return textModels(ids)
    }

    private fun body(request: CheckRequest, minimalThinking: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt.userMessage(request)) }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", prompt.schema)
            // Room for a long paragraph twice over plus the list of changes.
            put("maxOutputTokens", 4096)
            // Minimal thinking keeps a check inside the ~2 s budget.
            if (minimalThinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "MINIMAL") }
        }
    }

    /** The answer text, without any thought parts. */
    private fun answer(payload: String): String = readResponse(payload) { root ->
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw CheckFailure(CheckFailure.Reason.BadResponse, "The model returned no answer")
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        if (text.isBlank()) {
            val finish = candidate["finishReason"]?.jsonPrimitive?.content ?: "unknown"
            throw CheckFailure(CheckFailure.Reason.BadResponse, "The model returned no answer ($finish)")
        }
        text
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

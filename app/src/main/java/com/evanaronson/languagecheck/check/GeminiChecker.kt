package com.evanaronson.languagecheck.check

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Calls the Gemini Developer API's generateContent over plain REST.
 * The official SDKs pull in Firebase or Ktor plus Google auth; one POST doesn't need them.
 */
class GeminiChecker(
    private val http: OkHttpClient,
    private val prompt: Prompt,
    private val apiKey: () -> String?,
    private val model: () -> String,
) : Checker {
    private val json = Json { ignoreUnknownKeys = true }
    private val schema = json.parseToJsonElement(prompt.schemaJson)

    /** Models that rejected the minimal thinking level; they get the default instead. */
    private val noMinimalThinking = ConcurrentHashMap.newKeySet<String>()

    override suspend fun check(request: CheckRequest): ModelVerdict {
        val key = apiKey() ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        val model = request.model ?: model()
        val minimal = model !in noMinimalThinking
        val (code, payload) = send(key, model, body(request, minimal))
        if (code == 400 && minimal && "thinking" in payload.lowercase()) {
            noMinimalThinking += model
            return check(request)
        }
        if (code != 200) throw failureFor(code, payload)
        return parse(payload)
    }

    private suspend fun send(key: String, model: String, body: JsonObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).await()
    }

    private fun body(request: CheckRequest, minimalThinking: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", request.userMessage()) }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", schema)
            // Room for a long paragraph twice over plus the list of changes.
            put("maxOutputTokens", 4096)
            // Minimal thinking keeps a check inside the ~2 s budget.
            if (minimalThinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "MINIMAL") }
        }
    }

    private fun parse(payload: String): ModelVerdict = try {
        val candidate = json.parseToJsonElement(payload).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw CheckFailure(CheckFailure.Reason.BadResponse)
        val answer = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filterNot { it["thought"]?.jsonPrimitive?.boolean == true }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        if (answer.isBlank()) {
            val finish = candidate["finishReason"]?.jsonPrimitive?.content ?: "unknown"
            throw CheckFailure(CheckFailure.Reason.BadResponse, "The model returned no answer ($finish)")
        }
        decode(answer)
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
    }

    private fun decode(answer: String): ModelVerdict = try {
        json.decodeFromString<ModelVerdict>(answer)
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, "The model's answer wasn't in the expected format", e)
    }
}

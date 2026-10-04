package com.evanaronson.languagecheck.check

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Calls OpenAI's Responses API over plain REST, with the same prompt and schema as Gemini. */
class OpenAIChecker(
    private val http: OkHttpClient,
    private val prompt: Prompt,
    private val apiKey: () -> String?,
    private val model: String = MODEL,
) : Checker {
    private val json = Json { ignoreUnknownKeys = true }

    // Strict structured output rejects Gemini's propertyOrdering and requires additionalProperties.
    private val schema = JsonObject(
        json.parseToJsonElement(prompt.schemaJson).jsonObject - "propertyOrdering" +
            ("additionalProperties" to JsonPrimitive(false)),
    )

    override suspend fun check(text: String): ModelVerdict {
        val key = apiKey() ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(body(text).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, payload) = http.newCall(request).await()
        if (code != 200) throw CheckFailure(failureFor(code, payload))
        return parse(payload)
    }

    private fun body(text: String): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", prompt.system)
        put("input", "Text: $text")
        put("store", false)
        put("max_output_tokens", 512)
        // No reasoning keeps a check inside the ~2 s budget.
        putJsonObject("reasoning") { put("effort", "none") }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "check")
                put("strict", true)
                put("schema", schema)
            }
        }
    }

    private fun parse(payload: String): ModelVerdict = try {
        val answer = json.parseToJsonElement(payload).jsonObject["output"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "message" }
            .flatMap { it["content"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "output_text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        json.decodeFromString<ModelVerdict>(answer)
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, e)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, e)
    }

    companion object {
        const val MODEL = "gpt-6-sol"
    }
}

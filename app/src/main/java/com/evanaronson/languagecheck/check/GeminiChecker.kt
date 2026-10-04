package com.evanaronson.languagecheck.check

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Calls the Gemini Developer API's generateContent over plain REST.
 * The official SDKs pull in Firebase or Ktor plus Google auth; one POST doesn't need them.
 */
class GeminiChecker(
    private val http: OkHttpClient,
    private val prompt: Prompt,
    private val apiKey: () -> String?,
    private val model: String = MODEL,
) : Checker {
    private val json = Json { ignoreUnknownKeys = true }
    private val schema = json.parseToJsonElement(prompt.schemaJson)

    override suspend fun check(text: String): ModelVerdict {
        val key = apiKey() ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body(text).toString().toRequestBody(JSON))
            .build()

        val (code, payload) = http.newCall(request).await()
        if (code != 200) throw CheckFailure(failureFor(code, payload))
        return parse(payload)
    }

    private fun body(text: String): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", "Text: $text") }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", schema)
            put("maxOutputTokens", 512)
            // Minimal thinking keeps a check inside the ~2 s budget.
            putJsonObject("thinkingConfig") { put("thinkingLevel", "MINIMAL") }
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
        json.decodeFromString<ModelVerdict>(answer)
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, e)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, e)
    }

    private fun failureFor(code: Int, payload: String) = when {
        code == 400 && "API_KEY_INVALID" in payload -> CheckFailure.Reason.BadKey
        code == 401 || code == 403 -> CheckFailure.Reason.BadKey
        code == 429 -> CheckFailure.Reason.RateLimited
        code >= 500 -> CheckFailure.Reason.Server
        else -> CheckFailure.Reason.BadResponse
    }

    companion object {
        const val MODEL = "gemini-3.5-flash-lite"
        private val JSON = "application/json".toMediaType()
    }
}

/** Runs the call on OkHttp's dispatcher and cancels it if the caller goes away. */
private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body.string() }
                } catch (e: IOException) {
                    onFailure(call, e)
                    return
                }
                cont.resume(result)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                val reason = when (e) {
                    is UnknownHostException -> CheckFailure.Reason.Offline
                    is InterruptedIOException -> CheckFailure.Reason.Timeout
                    else -> CheckFailure.Reason.Offline
                }
                cont.resumeWithException(CheckFailure(reason, e))
            }
        },
    )
}

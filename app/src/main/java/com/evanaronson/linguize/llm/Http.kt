package com.evanaronson.linguize.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** Status code and body. Runs on OkHttp's dispatcher and cancels the call if the caller goes away. */
internal suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
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
                // Timeouts are InterruptedIOExceptions; anything else means no usable connection.
                val reason = if (e is InterruptedIOException) CheckFailure.Reason.Timeout else CheckFailure.Reason.Offline
                cont.resumeWithException(CheckFailure(reason, cause = e))
            }
        },
    )
}

/** Reads a provider's JSON response; anything malformed becomes a BadResponse rather than a crash. */
internal inline fun <T> readResponse(payload: String, read: (JsonObject) -> T): T = try {
    read(Json.parseToJsonElement(payload).jsonObject)
} catch (e: SerializationException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
} catch (e: IllegalArgumentException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
} catch (e: IllegalStateException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
}

/** Turns an HTTP error into what the card tells the user, keeping the provider's message. */
internal fun failureFor(code: Int, payload: String): CheckFailure {
    val lower = payload.lowercase()
    val reason = when {
        code == 400 && "api_key_invalid" in lower -> CheckFailure.Reason.BadKey
        code == 401 -> CheckFailure.Reason.BadKey
        code == 404 -> CheckFailure.Reason.BadModel
        // Gemini answers 403 for models a key isn't allowed to use, and 400 for options a model doesn't support.
        (code == 403 || code == 400) && "model" in lower -> CheckFailure.Reason.BadModel
        code == 403 -> CheckFailure.Reason.BadKey
        code == 429 -> CheckFailure.Reason.RateLimited
        code >= 500 -> CheckFailure.Reason.Server
        else -> CheckFailure.Reason.BadResponse
    }
    return CheckFailure(reason, errorMessage(payload))
}

/** The `error.message` field both Gemini and OpenAI use, or null. */
private fun errorMessage(payload: String): String? = runCatching {
    Json.parseToJsonElement(payload).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

package com.evanaronson.languagecheck.check

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** Runs the call on OkHttp's dispatcher and cancels it if the caller goes away. */
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

/** Maps an HTTP error status to what the card tells the user. */
internal fun failureFor(code: Int, payload: String) = when {
    code == 400 && "API_KEY_INVALID" in payload -> CheckFailure.Reason.BadKey
    code == 401 || code == 403 -> CheckFailure.Reason.BadKey
    code == 429 -> CheckFailure.Reason.RateLimited
    code >= 500 -> CheckFailure.Reason.Server
    else -> CheckFailure.Reason.BadResponse
}

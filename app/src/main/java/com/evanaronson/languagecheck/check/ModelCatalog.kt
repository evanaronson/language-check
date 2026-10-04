package com.evanaronson.languagecheck.check

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Asks each provider which models the key can use, so the list never goes stale. */
class ModelCatalog(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Text models usable with generateContent, newest-looking first. */
    suspend fun gemini(key: String): List<String> {
        val models = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val page = get(Request.Builder().url(url).header("x-goog-api-key", key).build())
            page["models"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { model ->
                val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                val id = model["name"]?.jsonPrimitive?.content?.removePrefix("models/") ?: return@forEach
                if ("generateContent" in methods && id.startsWith("gemini") && !isSpecialPurpose(id)) {
                    models += id
                }
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return sorted(models)
    }

    /** Chat-capable GPT and o-series models. */
    suspend fun openAI(key: String): List<String> {
        val page = get(Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").build())
        val models = page["data"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            .filter { (it.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(it)) && !isSpecialPurpose(it) }
        return sorted(models)
    }

    private suspend fun get(request: Request): JsonObject {
        val (code, payload) = http.newCall(request).await()
        if (code != 200) throw failureFor(code, payload)
        return json.parseToJsonElement(payload).jsonObject
    }

    private fun isSpecialPurpose(id: String) = SPECIAL_PURPOSE.any { it in id }

    /** Newest versions first: compares the numbers in each name, then the name. */
    private fun sorted(models: List<String>) = models.distinct().sortedWith(
        compareByDescending<String> { version(it) }.thenBy { it },
    )

    private fun version(id: String): Double =
        Regex("""\d+(\.\d+)?""").find(id)?.value?.toDoubleOrNull() ?: 0.0

    private companion object {
        val SPECIAL_PURPOSE = listOf(
            "embedding", "tts", "image", "live", "audio", "realtime", "transcribe",
            "search", "robotics", "computer-use", "instruct", "moderation", "codex",
        )
    }
}

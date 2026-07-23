package com.rusyn.wristhook.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Redeems a short pairing code (from the andriy.lol/pair page) into a webhook
 * URL + optional auth header, so the full URL never has to be typed on the
 * watch. Codes are one-time use and expire server-side after ~10 minutes.
 */
object Pairing {

    // Relay that mints/redeems codes. Kept here so a fork can point it at its
    // own self-hosted endpoint.
    private const val REDEEM_ENDPOINT = "https://andriy.lol/api/pair/redeem"

    data class Config(val url: String, val authHeader: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun redeem(code: String): Result<Config> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = code.trim().uppercase().replace("\\s".toRegex(), "")
            val payload = JSONObject().put("code", normalized).toString()
            val request = Request.Builder()
                .url(REDEEM_ENDPOINT)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${response.code}"
                    throw IOException(msg)
                }
                val json = JSONObject(text)
                val url = json.optString("url")
                if (url.isBlank()) throw IOException("No URL returned")
                Config(url = url, authHeader = json.optString("auth"))
            }
        }
    }
}

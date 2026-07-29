package com.rusyn.wristhook.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a recorded note as multipart/form-data: a `file` part with the audio.
 * Discord webhooks additionally require a `payload_json` part, which is added
 * automatically when the URL points at Discord — other endpoints get a clean
 * single-part upload.
 */
object Uploader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Builds the client (thread pools, TLS setup) ahead of the first send. */
    suspend fun prewarm() = withContext(Dispatchers.IO) { client }

    private fun isDiscord(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host == "discord.com" || host == "discordapp.com" ||
            host.endsWith(".discord.com") || host.endsWith(".discordapp.com")
    }

    suspend fun upload(url: String, authHeader: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = url.toHttpUrlOrNull() ?: throw IOException("Invalid webhook URL")
                // Belt and braces with the manifest's cleartext ban: never send
                // audio (or the Authorization header) over plain HTTP.
                if (!parsed.isHttps) throw IOException("Webhook URL must be https")

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .apply {
                        // Discord rejects file-only webhooks without payload_json.
                        if (isDiscord(url)) addFormDataPart("payload_json", """{"content":""}""")
                    }
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("audio/mp4".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(parsed)
                    .post(body)
                    .apply { if (authHeader.isNotBlank()) header("Authorization", authHeader) }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val snippet = response.body?.string().orEmpty().take(120)
                        throw IOException("HTTP ${response.code} $snippet".trim())
                    }
                }
            }
        }
}

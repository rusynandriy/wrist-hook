package com.rusyn.quicknote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a recorded note as multipart/form-data in the exact shape a Discord
 * webhook expects: a `file` part with the audio, plus a `payload_json` part.
 */
object Uploader {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun upload(url: String, authHeader: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", """{"content":""}""")
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("audio/mp4".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(url) // throws IllegalArgumentException on a malformed URL
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

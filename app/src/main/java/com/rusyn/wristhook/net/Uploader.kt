package com.rusyn.wristhook.net

import android.content.Context
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Uploads a recorded note as multipart/form-data: a `file` part with the audio.
 * Discord webhooks additionally require a `payload_json` part, which is added
 * automatically when the URL points at Discord — other endpoints get a clean
 * single-part upload.
 *
 * Routing: the default network first (the phone's Bluetooth proxy when it's
 * nearby). If there is no usable default, or the send dies on a connection-level
 * error, it asks Wear OS for the watch's own Wi-Fi/LTE and sends over that.
 */
object Uploader {

    /** The server answered with a non-2xx status: a different network won't help. */
    class HttpStatusException(message: String) : IOException(message)

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

    suspend fun upload(context: Context, url: String, authHeader: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = url.toHttpUrlOrNull() ?: throw IOException("Invalid webhook URL")
                // Belt and braces with the manifest's cleartext ban: never send
                // audio (or the Authorization header) over plain HTTP.
                if (!parsed.isHttps) throw IOException("Webhook URL must be https")
                val request = buildRequest(parsed, url, authHeader, file)

                if (StandaloneNetwork.defaultIsUsable(context)) {
                    try {
                        return@runCatching execute(client, request)
                    } catch (e: HttpStatusException) {
                        throw e
                    } catch (e: IOException) {
                        // Default network vanished mid-send (phone walked out of
                        // range): fall through and try the watch's own radios.
                    }
                }
                StandaloneNetwork.withWifiOrCellular(context) { network ->
                    execute(clientOn(network), request)
                } ?: throw IOException(
                    "No connection: phone away and Wi-Fi/LTE didn't come up"
                )
            }
        }

    private fun buildRequest(
        parsed: okhttp3.HttpUrl,
        url: String,
        authHeader: String,
        file: File,
    ): Request {
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

        return Request.Builder()
            .url(parsed)
            .post(body)
            .apply { if (authHeader.isNotBlank()) header("Authorization", authHeader) }
            .build()
    }

    /** A client whose sockets and DNS go over [network] rather than the default. */
    private fun clientOn(network: Network): OkHttpClient =
        client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .build()

    private fun execute(http: OkHttpClient, request: Request) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val snippet = response.body?.string().orEmpty().take(120)
                throw HttpStatusException("HTTP ${response.code} $snippet".trim())
            }
        }
    }
}

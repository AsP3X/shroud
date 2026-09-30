package de.corespace.shroud.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * JSON over HTTP to `/api/v1` (`APIClient.swift`). The base URL is read from the server settings
 * on every call, so a change in the Server sheet applies to the next request. Sessions are
 * `Authorization: Bearer <token>`; nothing identifies the device besides the token.
 */
class ApiClient(
    private val baseUrl: () -> String,
    val json: Json,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun <T> get(path: String, token: String?, response: KSerializer<T>): T =
        decode(execute("GET", path, token, null), response)

    suspend fun <B, T> post(path: String, token: String?, body: B, bodySerializer: KSerializer<B>, response: KSerializer<T>): T =
        decode(execute("POST", path, token, encode(body, bodySerializer)), response)

    suspend fun <B> put(path: String, token: String?, body: B, bodySerializer: KSerializer<B>) {
        execute("PUT", path, token, encode(body, bodySerializer))
    }

    /** POST without a body whose answer carries nothing (logout → 204). */
    suspend fun postEmpty(path: String, token: String?) {
        execute("POST", path, token, ByteArray(0).toRequestBody(null))
    }

    private fun <B> encode(body: B, serializer: KSerializer<B>): RequestBody =
        json.encodeToString(serializer, body).toRequestBody(JSON)

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw ApiError.Decoding(e.message ?: "decode")
    } catch (e: IllegalArgumentException) {
        throw ApiError.Decoding(e.message ?: "decode")
    }

    private fun url(path: String): HttpUrl {
        val root = baseUrl().trimEnd('/')
        val url = "$root/${path.trimStart('/')}".toHttpUrlOrNull()
            ?: throw ApiError.Transport("That server address is not a valid URL.")
        // The manifest allows cleartext for self-hosted servers; only local ones may use it.
        if (!url.isHttps && !ServerConfiguration.isLocalNetworkHost(url.host)) {
            throw ApiError.Transport(ServerConfiguration.PLAIN_HTTP_REFUSED)
        }
        return url
    }

    /**
     * Runs the request and returns the body of a 2xx, or throws [ApiError]. The URL is resolved
     * before the first suspension, so a call started before a server change still goes to the old
     * server. Network and body I/O run on [Dispatchers.IO]; every I/O failure is a Transport error.
     */
    private suspend fun execute(method: String, path: String, token: String?, body: RequestBody?): String {
        val request = Request.Builder()
            .url(url(path))
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).await().use {
                    val text = it.body.string()
                    if (!it.isSuccessful) throw errorFor(it.code, text)
                    text
                }
            } catch (e: IOException) {
                throw ApiError.Transport(transportMessage(e))
            }
        }
    }

    private fun errorFor(status: Int, body: String): ApiError {
        val envelope = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()
        return when {
            envelope != null -> ApiError.Server(envelope.error.code, envelope.error.message, status)
            status == 401 -> ApiError.Server(ErrorCodes.UNAUTHORIZED, "Unauthorized", 401)
            else -> ApiError.Transport("Request failed with status $status")
        }
    }

    private fun transportMessage(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "Can’t find the server. Check the address in Server settings."
        is java.net.ConnectException -> "Can’t reach the server. Check that it is running and the address is right."
        is java.net.SocketTimeoutException -> "The server took too long to answer. Try again."
        is javax.net.ssl.SSLException -> "A secure connection to the server failed."
        else -> "The connection to the server failed. Try again."
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .cache(null)
            // A redirect would skip the local-only cleartext check and could replay a password
            // to another host. The API never redirects.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}

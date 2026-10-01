package de.corespace.shroud.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
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
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * JSON over HTTP to `/api/v1` — the port of `APIClient.swift` (api-realtime §2).
 *
 * - **URL** (`APIClient.swift:383-396`): the base URL is read from the server settings on every
 *   call (iOS builds a fresh client per service call, `:15-22`), so a change in the Server sheet
 *   applies to the next request; it is resolved before the first suspension, so a call already
 *   started still goes to the server it was meant for. The path is trimmed of `/` and appended;
 *   dynamic text segments go through [pathSegment]; query items are sorted by name (`:388-390`).
 *   `http://` is refused before any request unless the host is local
 *   ([ServerConfiguration.isLocalNetworkHost], the iOS `NSAllowsLocalNetworking`).
 * - **Headers** (`:318-326, 352-363`): `Accept` = JSON, octet-stream, then any type (as iOS);
 *   `Authorization: Bearer <token>` only for a non-empty token; `Content-Type: application/json`
 *   exactly when a JSON body is sent. No `x-request-id`, no cookies, no HTTP cache.
 * - **Auth outcomes** (`:328-342`): every answer to a request that carried a token reports to
 *   [authOutcomes] before its status is interpreted — 2xx → success, `401 DEVICE_REMOVED` →
 *   removal with that token, other 401 → failure. Token-less calls (register, log in) and transport
 *   errors never report, so a wrong password or being offline never ends a session.
 * - **Errors** (`:398-410`): no answer → [ApiError.Transport]; non-2xx → [errorFor] (with
 *   `Retry-After`); a 2xx that does not decode → [ApiError.Decoding]; a `204` or empty body is
 *   success for the `Unit` verbs.
 * - **Timeouts** (`:24-45`): connect/read/write 20 s idle, no call timeout for JSON (fail fast on
 *   a dead server, `waitsForConnectivity = false`); media transfers use [mediaHttp], derived from
 *   [http] with a one-hour call timeout (the iOS resource timeout) and sharing its pool.
 *
 * Network and body I/O run on [Dispatchers.IO]. Cancelling the calling coroutine cancels the
 * request. Nothing here logs: no tokens, bodies or ids ever reach a log.
 */
class ApiClient(
    private val baseUrl: () -> String,
    val json: Json,
    val http: OkHttpClient = defaultHttpClient(),
) {
    /**
     * Where authenticated answers are reported (the iOS `SessionAuthBridge`). Set once by the
     * container when the session exists (it breaks the `SessionController` ↔ `ShroudApi` cycle);
     * null reports nothing. Called on IO threads.
     */
    @Volatile var authOutcomes: AuthOutcomeListener? = null

    /**
     * The client for `PUT/GET media/{id}/content`: a call may take up to an hour (the iOS
     * `timeoutIntervalForResource = 3600`, `APIClient.swift:24-37`), while the 20 s idle timeouts
     * still end a stalled transfer. Shares [http]'s connection pool and dispatcher.
     */
    val mediaHttp: OkHttpClient by lazy { http.newBuilder().callTimeout(MEDIA_CALL_TIMEOUT_HOURS, TimeUnit.HOURS).build() }

    /** GET and decode (`APIClient.swift:52-68`). */
    suspend fun <T> get(path: String, token: String?, response: KSerializer<T>, query: Map<String, String> = emptyMap()): T =
        decode(send("GET", path, token, null, query), response)

    /** POST a JSON body and decode the answer (`APIClient.swift:70-86`). */
    suspend fun <B, T> post(path: String, token: String?, body: B, bodySerializer: KSerializer<B>, response: KSerializer<T>): T =
        decode(send("POST", path, token, encode(body, bodySerializer)), response)

    /** POST a JSON body; the answer carries nothing (204) (`postNoContent(path:body:)`, `APIClient.swift:94-108`). */
    suspend fun <B> postUnit(path: String, token: String?, body: B, bodySerializer: KSerializer<B>) {
        send("POST", path, token, encode(body, bodySerializer))
    }

    /** POST without a body; the answer carries nothing (logout, delivered) (`postNoContent(path:)`, `APIClient.swift:88-92`). */
    suspend fun postEmpty(path: String, token: String?) {
        send("POST", path, token, EMPTY_BODY)
    }

    /** POST without a body and decode the answer (hangup, reject, heartbeat, share code, chat read, push test) (`APIClient.swift:110-124`). */
    suspend fun <T> postEmpty(path: String, token: String?, response: KSerializer<T>): T =
        decode(send("POST", path, token, EMPTY_BODY), response)

    /** PUT a JSON body and decode the answer (`APIClient.swift:126-142`). */
    suspend fun <B, T> put(path: String, token: String?, body: B, bodySerializer: KSerializer<B>, response: KSerializer<T>): T =
        decode(send("PUT", path, token, encode(body, bodySerializer)), response)

    /** PUT a JSON body; the answer carries nothing (204) (`putNoContent`, `APIClient.swift:144-158`). */
    suspend fun <B> putUnit(path: String, token: String?, body: B, bodySerializer: KSerializer<B>) {
        send("PUT", path, token, encode(body, bodySerializer))
    }

    /** The W0 name of [putUnit]; kept until W2-INT moves the last caller (00-plan §2.2 W1-NET). */
    @Deprecated("Renamed: the answer-less PUT is putUnit.", ReplaceWith("putUnit(path, token, body, bodySerializer)"))
    suspend fun <B> put(path: String, token: String?, body: B, bodySerializer: KSerializer<B>) =
        putUnit(path, token, body, bodySerializer)

    /** DELETE; the answer carries nothing (204) (`deleteNoContent`, `APIClient.swift:160-174`). */
    suspend fun deleteUnit(path: String, token: String?, query: Map<String, String> = emptyMap()) {
        send("DELETE", path, token, null, query)
    }

    /** DELETE and decode the answer (a chat delete's outcome) (`APIClient.swift:209-225`). */
    suspend fun <T> delete(path: String, token: String?, response: KSerializer<T>, query: Map<String, String> = emptyMap()): T =
        decode(send("DELETE", path, token, null, query), response)

    /**
     * The status and body as they came, for routes whose error answers carry data (a reaction's
     * `409` holds the current record) (`response(_:path:…)`, `APIClient.swift:189-207`). Throws only
     * when no answer came; the auth outcome is still reported. A POST or PUT without [jsonBody]
     * sends an empty body.
     */
    suspend fun raw(
        method: String,
        path: String,
        token: String?,
        jsonBody: String? = null,
        query: Map<String, String> = emptyMap(),
    ): RawResponse {
        val verb = method.uppercase()
        val body = jsonBody?.encodeToByteArray()?.toRequestBody(JSON_MEDIA_TYPE)
            ?: if (verb in BODY_METHODS) EMPTY_BODY else null
        val request = request(verb, path, token, body, query)
        return exchange(http, request) { response ->
            val text = response.body.string()
            noteAuthOutcome(response.code, text, token)
            RawResponse(response.code, text)
        }
    }

    /**
     * PUT raw bytes (sealed media) from [body], whose length must be known; [onProgress] gets
     * 0…1 as the body leaves the device (`putRaw(…onProgress:)`, `APIClient.swift:262-288`). The
     * content type is [body]'s own.
     */
    suspend fun putBytes(path: String, token: String, body: RequestBody, onProgress: ((Double) -> Unit)? = null) {
        val counted = if (onProgress != null) ProgressRequestBody(body, onProgress) else body
        val request = request("PUT", path, token, counted, emptyMap())
        exchange(mediaHttp, request) { response -> successText(response, token) }
    }

    /**
     * GET raw bytes into memory (photos, voice); [onProgress] gets 0…1 against `Content-Length`
     * (`getRaw(…onProgress:)`, `APIClient.swift:290-314`). Large videos use [getToFile].
     */
    suspend fun getBytes(path: String, token: String, onProgress: ((Double) -> Unit)? = null): ByteArray {
        val request = request("GET", path, token, null, emptyMap())
        return exchange(mediaHttp, request) { response ->
            requireSuccess(response, token)
            val out = Buffer()
            pump(progressSource(response, onProgress)) { source -> source.read(out, SEGMENT_BYTES) }
            out.readByteArray()
        }
    }

    /**
     * GET raw bytes into [target] (sealed videos up to 2 GiB, which must not pass through memory);
     * returns the bytes written. [target] is created or truncated; a transfer that fails or is
     * cancelled deletes it, so no partial file is left behind.
     */
    suspend fun getToFile(path: String, token: String, target: File, onProgress: ((Double) -> Unit)? = null): Long {
        val request = request("GET", path, token, null, emptyMap())
        return exchange(mediaHttp, request) { response ->
            requireSuccess(response, token)
            var complete = false
            try {
                target.sink().buffer().use { sink ->
                    val written = pump(progressSource(response, onProgress)) { source ->
                        source.read(sink.buffer, SEGMENT_BYTES).also { sink.emitCompleteSegments() }
                    }
                    sink.flush()
                    complete = true
                    written
                }
            } finally {
                if (!complete) target.delete()
            }
        }
    }

    /**
     * The error for a non-2xx answer (`APIError.from`, `APIError.swift:41-59`) — see
     * [ApiError.Companion.from]. [retryAfter] is the raw `Retry-After` header.
     */
    fun errorFor(status: Int, body: String, retryAfter: String? = null): ApiError = ApiError.from(status, body, retryAfter)

    // ---- Internals ----

    /** Sends one JSON-API request and returns the 2xx body as text. */
    private suspend fun send(
        method: String,
        path: String,
        token: String?,
        body: RequestBody?,
        query: Map<String, String> = emptyMap(),
    ): String {
        val request = request(method, path, token, body, query)
        return exchange(http, request) { response -> successText(response, token) }
    }

    /** Built before any suspension: the URL is the server's at the time of the call. */
    private fun request(method: String, path: String, token: String?, body: RequestBody?, query: Map<String, String>): Request =
        Request.Builder()
            .url(url(path, query))
            .header("Accept", ACCEPT)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .method(method, body)
            .build()

    /** `APIClient.resolveURL` (`APIClient.swift:383-396`) plus the local-only cleartext rule. */
    internal fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val root = baseUrl().trimEnd('/')
        val relative = path.trim('/')
        val url = (if (relative.isEmpty()) root else "$root/$relative").toHttpUrlOrNull()
            ?: throw ApiError.Transport("That server address is not a valid URL.")
        // The manifest allows cleartext for self-hosted servers; only local ones may use it.
        if (!url.isHttps && !ServerConfiguration.isLocalNetworkHost(url.host)) {
            throw ApiError.Transport(ServerConfiguration.PLAIN_HTTP_REFUSED)
        }
        if (query.isEmpty()) return url
        return url.newBuilder().apply { query.toSortedMap().forEach { (name, value) -> addQueryParameter(name, value) } }.build()
    }

    /**
     * Runs [request] on [client] and hands the open response to [handle] on [Dispatchers.IO]; the
     * response is closed afterwards. Every I/O failure is a [ApiError.Transport] — unless the
     * caller was cancelled, which stays a cancellation.
     */
    private suspend fun <R> exchange(client: OkHttpClient, request: Request, handle: CoroutineScope.(Response) -> R): R =
        withContext(Dispatchers.IO) {
            val scope = this
            try {
                client.newCall(request).await().use { scope.handle(it) }
            } catch (e: IOException) {
                ensureActive()
                throw ApiError.Transport(transportMessage(e))
            }
        }

    /** The whole body of a 2xx, after reporting the auth outcome; otherwise the error. */
    private fun successText(response: Response, token: String?): String {
        val text = response.body.string()
        noteAuthOutcome(response.code, text, token)
        if (!response.isSuccessful) throw errorFor(response.code, text, response.header("Retry-After"))
        return text
    }

    /** For streamed bodies: reports the outcome from the status line and throws on a non-2xx. */
    private fun requireSuccess(response: Response, token: String?) {
        if (response.isSuccessful) {
            noteAuthOutcome(response.code, "", token)
            return
        }
        val text = response.body.string()
        noteAuthOutcome(response.code, text, token)
        throw errorFor(response.code, text, response.header("Retry-After"))
    }

    private fun progressSource(response: Response, onProgress: ((Double) -> Unit)?): BufferedSource {
        val body = response.body
        return if (onProgress == null) body.source() else ProgressSource(body.source(), body.contentLength(), onProgress).buffer()
    }

    /** Reads [source] to its end with [step], checking for cancellation between chunks; returns the byte count. */
    private inline fun CoroutineScope.pump(source: BufferedSource, step: (BufferedSource) -> Long): Long {
        var total = 0L
        while (true) {
            ensureActive()
            val count = step(source)
            if (count == -1L) return total
            total += count
        }
    }

    /**
     * Session policy (`APIClient.noteAuthOutcome`, `APIClient.swift:328-342`): only requests that
     * carried a token count, `DEVICE_REMOVED` is final on the first answer, and nothing but 2xx and
     * 401 is reported.
     */
    private fun noteAuthOutcome(status: Int, body: String, token: String?) {
        if (token.isNullOrEmpty()) return
        val listener = authOutcomes ?: return
        when {
            status in 200..299 -> listener.onAuthenticatedSuccess()
            status == 401 ->
                if (errorFor(status, body).isDeviceRemoved) listener.onDeviceRemoved(token) else listener.onAuthenticationFailure()
        }
    }

    private fun <B> encode(body: B, serializer: KSerializer<B>): RequestBody =
        json.encodeToString(serializer, body).encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE)

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException; so are the DTOs' own checks.
        throw ApiError.Decoding(e.message ?: "decode")
    }

    /** Friendly texts for the transport failures a person can do something about. */
    private fun transportMessage(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "Can’t find the server. Check the address in Server settings."
        is java.net.ConnectException -> "Can’t reach the server. Check that it is running and the address is right."
        // Read/connect timeouts (SocketTimeoutException) and the media call timeout.
        is InterruptedIOException -> "The server took too long to answer. Try again."
        is javax.net.ssl.SSLException -> "A secure connection to the server failed."
        else -> "The connection to the server failed. Try again."
    }

    companion object {
        private const val ACCEPT = "application/json, application/octet-stream, */*"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** `Content-Length: 0`, no `Content-Type`: the body-less POST of iOS (`APIClient.swift:358-363`). */
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private const val SEGMENT_BYTES = 64L * 1024
        private const val MEDIA_CALL_TIMEOUT_HOURS = 1L

        /**
         * One dynamic path segment (a username, a share code): every byte outside
         * `[A-Za-z0-9_-]` percent-encoded as UTF-8 (`ContactsService.swift:71-88` encodes with
         * `.urlPathAllowed`; contacts §2.1), so `/`, `?`, `#` and `%` can never change the route.
         */
        fun pathSegment(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '_'.code || c == '-'.code
                if (plain) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }

        private const val HEX = "0123456789ABCDEF"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // No plaintext HTTP cache on disk (`APIClient.swift:39-43`): contact lists, envelopes
            // and share-code lookups must never land in an unsealed file.
            .cache(null)
            // A redirect would skip the local-only cleartext check and could replay a password
            // to another host. The API never redirects.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** A status and body exactly as the server answered ([ApiClient.raw]). */
data class RawResponse(val status: Int, val body: String)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}

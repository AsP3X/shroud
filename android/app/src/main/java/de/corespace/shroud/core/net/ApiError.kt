package de.corespace.shroud.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** `{ "error": { "code", "message" } }` — every API error (`APIError.swift:3-11`; server `error.rs:268-306`). */
@Serializable
data class ErrorEnvelope(val error: Body) {
    @Serializable
    data class Body(val code: String, val message: String)
}

/**
 * A failed API call (`APIError.swift:13-60`, api-realtime §2.4). What a screen shows is
 * [userMessage]; what the session counts is [isUnauthorized] / [isDeviceRemoved].
 */
sealed class ApiError(message: String) : Exception(message) {
    /**
     * The server answered with its error envelope, or a bare 401 (`APIError.swift:16, 43-57`).
     *
     * @property retryAfterSeconds the `Retry-After` header in seconds when the answer carried one
     *   (the server sends it with `429 RATE_LIMITED`, `error.rs:136-146`).
     * @property body the raw error body, for answers that carry data beside the envelope (a
     *   reaction write's `409` holds the current record, `MessagesService.swift:120-129`).
     */
    class Server(
        val code: String,
        val serverMessage: String,
        val status: Int,
        val retryAfterSeconds: Long? = null,
        val body: String? = null,
    ) : ApiError("$status $code: $serverMessage")

    /**
     * No usable answer: offline, timeout, TLS, a refused address, or a status without an envelope
     * (`APIError.swift:15, 58`; `APIClient.swift:369-372`).
     */
    class Transport(val detail: String) : ApiError(detail)

    /** A 2xx whose body did not match the expected shape (`APIError.swift:17`, `APIClient.swift:404-410`). */
    class Decoding(val detail: String) : ApiError(detail)

    /**
     * The account removed this device: `401 DEVICE_REMOVED` (`APIError.swift:30-37`). A 403 with
     * that code is not a removal (`SessionAuthFailureTests.onlyDeviceRemovedCodeIsARemoval`).
     */
    val isDeviceRemoved: Boolean get() = this is Server && status == 401 && code == ErrorCodes.DEVICE_REMOVED

    /**
     * HTTP 401 — the token was rejected (`APIError.isAuthenticationFailure`, `APIError.swift:19-28`).
     * Transport errors and other statuses never count, so being offline never signs anyone out.
     */
    val isUnauthorized: Boolean get() = this is Server && status == 401

    /** `429 RATE_LIMITED`; [Server.retryAfterSeconds] says when to try again. */
    val isRateLimited: Boolean get() = this is Server && status == 429

    val isNotFound: Boolean get() = this is Server && status == 404

    /**
     * A login's `409 DEVICE_LIMIT` with what it carries ([DeviceLimitDto]): every slot is signed
     * in, and a retry with `replace_device_id` takes the oldest device's place. Null for every other
     * error; its fields are null when the server didn't send them (an older server, or no published
     * identity key to check the phrase against).
     */
    val deviceLimit: DeviceLimitDto?
        get() = (this as? Server)
            ?.takeIf { it.status == 409 && it.code == ErrorCodes.DEVICE_LIMIT }
            ?.body
            ?.let(DeviceLimitDto::fromErrorBody)

    /**
     * What a screen shows: the server's own message, the transport text, or the decoding text
     * (`SessionController.swift:203-218`).
     */
    val userMessage: String
        get() = when (this) {
            is Server -> serverMessage
            is Transport -> detail
            is Decoding -> "Could not read the server response."
        }

    companion object {
        /** The envelope parser: unknown keys beside `error` (a reaction's `current`) are ignored, like `JSONDecoder()`. */
        private val envelopeJson = Json { ignoreUnknownKeys = true }

        /**
         * The error for a non-2xx answer (`APIError.from(data:statusCode:)`, `APIError.swift:41-59`):
         * the envelope → [Server]; a 401 without one → [Server] `UNAUTHORIZED` "Unauthorized" (so the
         * session policy still sees it; iOS writes the code lower-case, nothing compares it);
         * anything else → [Transport] "Request failed with status N". Axum's own rejections (400/415/
         * 422 plain text, 404/405 for unknown routes) land in the last case (api-realtime §2.4).
         *
         * @param retryAfter the raw `Retry-After` header, if any.
         */
        fun from(status: Int, body: String, retryAfter: String? = null): ApiError {
            val envelope = try {
                envelopeJson.decodeFromString(ErrorEnvelope.serializer(), body)
            } catch (_: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                null
            }
            val retrySeconds = retryAfter?.let(::parseRetryAfter)
            return when {
                envelope != null -> Server(envelope.error.code, envelope.error.message, status, retrySeconds, body)
                status == 401 -> Server(ErrorCodes.UNAUTHORIZED, "Unauthorized", 401, retrySeconds)
                else -> Transport("Request failed with status $status")
            }
        }

        /**
         * `Retry-After` as seconds (RFC 9110 §10.2.3): delay-seconds as the server sends it
         * (`error.rs:297-304`), or an HTTP date measured from [now]. Null when unreadable; never negative.
         */
        internal fun parseRetryAfter(value: String, now: Instant = Instant.now()): Long? {
            val text = value.trim()
            text.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return try {
                val date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(now, date).seconds.coerceAtLeast(0)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Every error code a client can see (api-realtime §3.2; server `error.rs`). Screens compare
 * against these, never against message text.
 */
object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val USERNAME_RESERVED = "USERNAME_RESERVED"
    const val PASSWORD_TOO_SHORT = "PASSWORD_TOO_SHORT"
    const val PASSWORD_TOO_COMMON = "PASSWORD_TOO_COMMON"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val DEVICE_LIMIT = "DEVICE_LIMIT"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val DEVICE_REMOVED = "DEVICE_REMOVED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val KEYS_REQUIRED = "KEYS_REQUIRED"
    const val PREKEY_POOL_FULL = "PREKEY_POOL_FULL"
    const val ALREADY_EXISTS = "ALREADY_EXISTS"
    const val CALL_BUSY = "CALL_BUSY"
    const val CALL_ENDED = "CALL_ENDED"
    const val CALL_NOT_ANSWERED = "CALL_NOT_ANSWERED"

    /** Mapped by iOS (`CallController.swift:2078`) and web (`calls/logic.ts:162`); no server emits it today. */
    const val CALL_IN_PROGRESS = "CALL_IN_PROGRESS"
    const val REACTION_CHANGED = "REACTION_CHANGED"
    const val MEDIA_UNAVAILABLE = "MEDIA_UNAVAILABLE"

    /** Web PIN guard only (api-realtime §4.3); Android never calls those routes. */
    const val PIN_INCORRECT = "PIN_INCORRECT"

    /** Web PIN guard only (api-realtime §4.3). */
    const val PIN_GUARD_GONE = "PIN_GUARD_GONE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

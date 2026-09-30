package de.corespace.shroud.core.net

import kotlinx.serialization.Serializable

/** `{ "error": { "code", "message" } }` — every API error (`APIError.swift`). */
@Serializable
data class ErrorEnvelope(val error: Body) {
    @Serializable
    data class Body(val code: String, val message: String)
}

sealed class ApiError(message: String) : Exception(message) {
    /** The server answered with its error envelope (or a bare 401). */
    class Server(val code: String, val serverMessage: String, val status: Int) : ApiError("$status $code: $serverMessage")

    /** No usable answer: offline, timeout, TLS, or a status without an envelope. */
    class Transport(val detail: String) : ApiError(detail)

    /** The body did not match the expected shape. */
    class Decoding(val detail: String) : ApiError(detail)

    val isDeviceRemoved: Boolean get() = this is Server && code == ErrorCodes.DEVICE_REMOVED
    val isUnauthorized: Boolean get() = this is Server && status == 401

    /** What a screen shows: the server's own message, as the iPhone app does. */
    val userMessage: String
        get() = when (this) {
            is Server -> serverMessage
            is Transport -> detail
            is Decoding -> "Could not read the server response."
        }
}

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
    const val RATE_LIMITED = "RATE_LIMITED"
    const val KEYS_REQUIRED = "KEYS_REQUIRED"
}

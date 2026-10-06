package de.corespace.shroud.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ApiError.from` and the derived flags (api-realtime §13 row *ApiErrorTest*). Ports iOS
 * `APIErrorTests` (4), `AuthModelsTests.apiErrorEnvelopeDecodesServerCodes` and
 * `SessionAuthFailureTests.onlyDeviceRemovedCodeIsARemoval`; vectors verbatim.
 */
class ApiErrorTest {
    private fun server(error: ApiError): ApiError.Server = error as ApiError.Server

    /** `APIErrorTests.decodesServerErrorEnvelope` (`APIErrorTests.swift:6-14`). */
    @Test
    fun decodesServerErrorEnvelope() {
        val error = server(ApiError.from(404, """{"error":{"code":"not_found","message":"resource missing"}}"""))
        assertEquals("not_found", error.code)
        assertEquals("resource missing", error.serverMessage)
        assertEquals(404, error.status)
        assertEquals("resource missing", error.userMessage)
    }

    /** `APIErrorTests.authenticationFailureIsOnlyHTTP401` (`APIErrorTests.swift:16-23`). */
    @Test
    fun authenticationFailureIsOnlyHttp401() {
        assertTrue(ApiError.Server("unauthorized", "nope", 401).isUnauthorized)
        assertFalse(ApiError.Server("forbidden", "nope", 403).isUnauthorized)
        assertFalse(ApiError.Server("not_found", "nope", 404).isUnauthorized)
        assertFalse(ApiError.Transport("The Internet connection appears to be offline.").isUnauthorized)
        assertFalse(ApiError.Decoding("decode").isUnauthorized)
    }

    /** `APIErrorTests.fromPreservesUnauthorizedWithoutEnvelope` (`APIErrorTests.swift:25-30`); Android keeps the code upper-case. */
    @Test
    fun fromPreservesUnauthorizedWithoutEnvelope() {
        val error = ApiError.from(401, "plain")
        assertTrue(error.isUnauthorized)
        val server = server(error)
        assertEquals(ErrorCodes.UNAUTHORIZED, server.code)
        assertEquals("Unauthorized", server.serverMessage)
        assertEquals(401, server.status)
    }

    /** `APIErrorTests.fromNon401WithoutEnvelopeStaysTransport` (`APIErrorTests.swift:32-37`). */
    @Test
    fun fromNon401WithoutEnvelopeStaysTransport() {
        val error = ApiError.from(502, "")
        assertFalse(error.isUnauthorized)
        assertTrue(error is ApiError.Transport)
        assertEquals("Request failed with status 502", (error as ApiError.Transport).detail)
        assertEquals("Request failed with status 502", error.userMessage)
        // Axum's own rejections are plain text, never an envelope (api-realtime §2.4).
        assertEquals("Request failed with status 415", (ApiError.from(415, "Expected request with `Content-Type: application/json`") as ApiError.Transport).detail)
    }

    /** `AuthModelsTests.apiErrorEnvelopeDecodesServerCodes` (`AuthModelsTests.swift:29-42`). */
    @Test
    fun apiErrorEnvelopeDecodesServerCodes() {
        val json = """
        {
          "error": { "code": "USERNAME_TAKEN", "message": "That username is already taken." }
        }
        """
        val error = server(ApiError.from(409, json))
        assertEquals("USERNAME_TAKEN", error.code)
        assertEquals("That username is already taken.", error.serverMessage)
        assertEquals(409, error.status)
    }

    /** `SessionAuthFailureTests.onlyDeviceRemovedCodeIsARemoval` (`SessionAuthFailureTests.swift:112-121`). */
    @Test
    fun onlyDeviceRemovedCodeIsARemoval() {
        val removed = """{"error":{"code":"DEVICE_REMOVED","message":"x"}}"""
        val plain = """{"error":{"code":"UNAUTHORIZED","message":"x"}}"""
        assertTrue(ApiError.from(401, removed).isDeviceRemoved)
        assertTrue(ApiError.from(401, removed).isUnauthorized)
        assertFalse(ApiError.from(401, plain).isDeviceRemoved)
        assertFalse(ApiError.from(401, "").isDeviceRemoved)
        assertFalse(ApiError.from(403, removed).isDeviceRemoved)
    }

    @Test
    fun theEnvelopeIgnoresKeysBesideItAndKeepsTheBody() {
        // A reaction write's 409 carries `current` beside the envelope (MessageReactionTests.swift:131).
        val body = """{"error":{"code":"REACTION_CHANGED","message":"Try again."},"current":null,"x":1}"""
        val error = server(ApiError.from(409, body))
        assertEquals(ErrorCodes.REACTION_CHANGED, error.code)
        assertEquals(body, error.body)
        // A half envelope is no envelope.
        assertTrue(ApiError.from(400, """{"error":{"code":"VALIDATION_ERROR"}}""") is ApiError.Transport)
    }

    @Test
    fun retryAfterBecomesSeconds() {
        val limited = server(ApiError.from(429, """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}""", retryAfter = "60"))
        assertTrue(limited.isRateLimited)
        assertEquals(60L, limited.retryAfterSeconds)
        assertEquals("Too many requests. Try again later.", limited.userMessage)
        assertNull(server(ApiError.from(429, """{"error":{"code":"RATE_LIMITED","message":"m"}}""")).retryAfterSeconds)
        assertNull(server(ApiError.from(429, """{"error":{"code":"RATE_LIMITED","message":"m"}}""", retryAfter = "soon")).retryAfterSeconds)
        // RFC 9110 also allows an HTTP date; the server sends seconds.
        val now = Instant.parse("2026-10-01T10:00:00Z")
        assertEquals(90L, ApiError.parseRetryAfter("Thu, 1 Oct 2026 10:01:30 GMT", now))
        assertEquals(0L, ApiError.parseRetryAfter("Thu, 1 Oct 2026 09:00:00 GMT", now))
        assertEquals(0L, ApiError.parseRetryAfter("-5", now))
        assertEquals(30L, ApiError.parseRetryAfter(" 30 ", now))
    }

    @Test
    fun derivedFlagsAndUserMessages() {
        assertTrue(ApiError.Server(ErrorCodes.NOT_FOUND, "User not found.", 404).isNotFound)
        assertFalse(ApiError.Server(ErrorCodes.NOT_FOUND, "User not found.", 404).isRateLimited)
        assertFalse(ApiError.Transport("x").isNotFound)
        assertEquals("Could not read the server response.", ApiError.Decoding("Unexpected JSON token").userMessage)
        assertEquals("The connection to the server failed. Try again.", ApiError.Transport("The connection to the server failed. Try again.").userMessage)
    }

    @Test
    fun aFullAccountsLoginAnswerNamesItsOldestDeviceAndKey() {
        // Server `error.rs` `ErrorBody`: `oldest_device` and `identity_key` beside the envelope, `last_seen_at` omitted when null.
        val body = """
        {
          "error": { "code": "DEVICE_LIMIT", "message": "This account already has the maximum number of devices (5). Remove a device and try again." },
          "oldest_device": { "id": "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE", "created_at": "2025-03-12T08:30:00Z", "last_seen_at": "2026-10-01T17:05:42.123456Z" },
          "identity_key": "mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="
        }
        """
        val limit = ApiError.from(409, body).deviceLimit!!
        val oldest = limit.oldestDevice!!
        assertEquals(java.util.UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), oldest.id)
        assertEquals(Instant.parse("2025-03-12T08:30:00Z"), oldest.createdAt)
        assertEquals(Instant.parse("2026-10-01T17:05:42.123456Z"), oldest.lastSeenAt)
        assertEquals("mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ=", limit.identityKey)

        // Never active, and an account without published keys: no `last_seen_at`, no `identity_key`.
        val bare = """{"error":{"code":"DEVICE_LIMIT","message":"x"},"oldest_device":{"id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","created_at":"2025-03-12T08:30:00Z"}}"""
        assertNull(ApiError.from(409, bare).deviceLimit!!.oldestDevice!!.lastSeenAt)
        assertNull(ApiError.from(409, bare).deviceLimit!!.identityKey)
    }

    @Test
    fun onlyALoginsDeviceLimitCarriesIt() {
        // An older server sends only the envelope: nothing beside it, so Log In keeps its inline message.
        val old = ApiError.from(409, """{"error":{"code":"DEVICE_LIMIT","message":"This account already has the maximum number of devices (5). Remove a device and try again."}}""")
        assertNull(old.deviceLimit!!.oldestDevice)
        assertNull(old.deviceLimit!!.identityKey)
        assertEquals("This account already has the maximum number of devices (5). Remove a device and try again.", old.userMessage)
        val extra = ""","oldest_device":{"id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","created_at":"2025-03-12T08:30:00Z"},"identity_key":"a2V5"}"""
        // Only a 409 DEVICE_LIMIT counts; an unreadable device is none.
        assertNull(ApiError.from(409, """{"error":{"code":"USERNAME_TAKEN","message":"x"}""" + extra).deviceLimit)
        assertNull(ApiError.from(400, """{"error":{"code":"DEVICE_LIMIT","message":"x"}""" + extra).deviceLimit)
        assertNull(ApiError.from(409, """{"error":{"code":"DEVICE_LIMIT","message":"x"},"oldest_device":{"id":"nope"}}""").deviceLimit)
        assertNull(ApiError.Transport("offline").deviceLimit)
    }

    /** The catalogue of api-realtime §3.2, spelled as the server spells it (`error.rs`). */
    @Test
    fun errorCodesMatchTheServer() {
        val codes = listOf(
            ErrorCodes.VALIDATION_ERROR, ErrorCodes.USERNAME_TAKEN, ErrorCodes.USERNAME_RESERVED, ErrorCodes.PASSWORD_TOO_SHORT,
            ErrorCodes.PASSWORD_TOO_COMMON, ErrorCodes.INVALID_CREDENTIALS, ErrorCodes.DEVICE_LIMIT, ErrorCodes.UNAUTHORIZED,
            ErrorCodes.DEVICE_REMOVED, ErrorCodes.FORBIDDEN, ErrorCodes.NOT_FOUND, ErrorCodes.RATE_LIMITED, ErrorCodes.KEYS_REQUIRED,
            ErrorCodes.PREKEY_POOL_FULL, ErrorCodes.ALREADY_EXISTS, ErrorCodes.CALL_BUSY, ErrorCodes.CALL_ENDED,
            ErrorCodes.CALL_NOT_ANSWERED, ErrorCodes.CALL_IN_PROGRESS, ErrorCodes.REACTION_CHANGED, ErrorCodes.MEDIA_UNAVAILABLE,
            ErrorCodes.PIN_INCORRECT, ErrorCodes.PIN_GUARD_GONE, ErrorCodes.INTERNAL_ERROR,
        )
        assertEquals(24, codes.toSet().size)
        codes.forEach { assertTrue(it, it.matches(Regex("[A-Z_]+"))) }
    }
}

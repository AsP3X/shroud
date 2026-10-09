package de.corespace.shroud.core.auth

import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.DeleteAccountRequest
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.NotedAuthOutcome
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.notedAuthOutcome
import de.corespace.shroud.testing.takeApiRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.2 answers, and which of them the session hears. */
class DeleteAccountTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Test
    fun thePasswordIsTheBodyAndNotTheString() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 204))
            val api = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json))
            val started = ArrayList<WipeReason>()
            val deletion = AccountDeletion(api, token = { "tok" }, startWipe = { started += it })
            assertEquals(DeleteAccountAnswer.Deleted, deletion.delete("s3cret"))
            assertEquals(listOf(WipeReason.AccountDeleted), started)
            val sent = server.takeApiRequest()
            assertEquals("DELETE", sent.method)
            assertEquals("/api/v1/auth/account", sent.url.encodedPath)
            assertEquals("""{"password":"s3cret"}""", sent.body!!.utf8())
            assertEquals("Bearer tok", sent.headers["Authorization"])
            assertEquals("DeleteAccountRequest", DeleteAccountRequest("s3cret").toString())
            assertFalse(DeleteAccountRequest("s3cret").toString().contains("s3cret"))
        } finally {
            server.close()
        }
    }

    @Test
    fun answersMapOntoTheScreen() {
        assertEquals(DeleteAccountAnswer.Deleted, DeleteAccountMapping.of(null))
        assertEquals(
            DeleteAccountAnswer.WrongPassword,
            DeleteAccountMapping.of(ApiError.Server(ErrorCodes.INVALID_CREDENTIALS, "no", 401)),
        )
        assertEquals(
            DeleteAccountAnswer.RemovedAsDeleted,
            DeleteAccountMapping.of(ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401)),
        )
        assertEquals(
            DeleteAccountAnswer.RemovedAsDeleted,
            DeleteAccountMapping.of(ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401, reason = "other")),
        )
        assertEquals(
            DeleteAccountAnswer.RemovedAsDeleted,
            DeleteAccountMapping.of(ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401, reason = ApiError.ACCOUNT_DELETED_REASON)),
        )
        assertEquals(DeleteAccountAnswer.RateLimited, DeleteAccountMapping.of(ApiError.Server(ErrorCodes.RATE_LIMITED, "later", 429)))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Server(ErrorCodes.INTERNAL_ERROR, "x", 500)))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Server(ErrorCodes.INTERNAL_ERROR, "x", 502)))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Transport("The server took too long to answer. Try again.")))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Server(ErrorCodes.VALIDATION_ERROR, "x", 400)))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 403)))
        assertEquals(DeleteAccountAnswer.Unreachable, DeleteAccountMapping.of(ApiError.Decoding("bad")))
    }

    @Test
    fun aWrongPasswordIsNotASessionFailureAndThisCallsRemovalIsAccountDeleted() {
        val invalid = ApiError.Server(ErrorCodes.INVALID_CREDENTIALS, "no", 401)
        val removed = ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401)
        val deleted = ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401, reason = ApiError.ACCOUNT_DELETED_REASON)
        assertEquals(NotedAuthOutcome.Ignored, notedAuthOutcome(401, invalid, "auth/account"))
        assertEquals(NotedAuthOutcome.Failure, notedAuthOutcome(401, invalid, "auth/me"))
        assertEquals(NotedAuthOutcome.AccountDeleted, notedAuthOutcome(401, removed, "auth/account"))
        assertEquals(NotedAuthOutcome.AccountDeleted, notedAuthOutcome(401, deleted, "/auth/account"))
        assertEquals(NotedAuthOutcome.DeviceRemoved, notedAuthOutcome(401, removed, "auth/me"))
        assertEquals(NotedAuthOutcome.AccountDeleted, notedAuthOutcome(401, deleted, "auth/me"))
        assertEquals(NotedAuthOutcome.DeviceRemoved, notedAuthOutcome(401, ApiError.Server(ErrorCodes.DEVICE_REMOVED, "x", 401, reason = "other"), "contacts"))
        assertEquals(NotedAuthOutcome.Success, notedAuthOutcome(204, ApiError.Transport("unused"), "auth/account"))
        assertEquals(NotedAuthOutcome.Ignored, notedAuthOutcome(429, ApiError.Server(ErrorCodes.RATE_LIMITED, "x", 429), "auth/account"))
        assertEquals(NotedAuthOutcome.Ignored, notedAuthOutcome(500, ApiError.Server(ErrorCodes.INTERNAL_ERROR, "x", 502), "auth/me"))
        assertEquals(NotedAuthOutcome.Ignored, notedAuthOutcome(403, removed, "auth/account"))
    }

    @Test
    fun theCallStartsAWipeOnlyWhenTheAccountIsGone() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json))
            val started = ArrayList<WipeReason>()
            val deletion = AccountDeletion(api, token = { "tok" }, startWipe = { started += it })

            server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"""))
            assertEquals(DeleteAccountAnswer.WrongPassword, deletion.delete("nope"))
            server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"removed"}}"""))
            assertEquals(DeleteAccountAnswer.RemovedAsDeleted, deletion.delete("pw"))
            server.enqueue(MockResponse(code = 429, body = """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later."}}"""))
            assertEquals(DeleteAccountAnswer.RateLimited, deletion.delete("pw"))
            server.enqueue(MockResponse(code = 500, body = """{"error":{"code":"INTERNAL_ERROR","message":"An unexpected error occurred."}}"""))
            assertEquals(DeleteAccountAnswer.Unreachable, deletion.delete("pw"))
            assertEquals(listOf(WipeReason.AccountDeleted), started)

            val offline = AccountDeletion(
                ShroudApi(ApiClient({ "http://127.0.0.1:1/api/v1" }, json)),
                token = { "tok" },
                startWipe = { started += it },
            )
            assertEquals(DeleteAccountAnswer.Unreachable, offline.delete("pw"))
            assertEquals(DeleteAccountAnswer.Unreachable, AccountDeletion(api, token = { null }, startWipe = { started += it }).delete("pw"))
            assertEquals(listOf(WipeReason.AccountDeleted), started)
            assertTrue(ApiError.Server(ErrorCodes.DEVICE_REMOVED, "removed", 401).message!!.contains("pw").not())
        } finally {
            server.close()
        }
    }
}

package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CryptoControllerTest {
    private lateinit var server: MockWebServer
    private lateinit var crypto: CryptoController
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val bip39 = TestWordlist.bip39
    private val words = List(11) { "abandon" } + "about"
    private val session = Session("tok", "u1", "noah", null, "d1")
    private val abandonKey = "mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        crypto = CryptoController(ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)), bip39)
    }

    @After
    fun tearDown() = server.close()

    private fun identity(key: String) = """{"user_id":"u1","device_id":"d0","registration_id":1,"identity_key":"$key"}"""

    @Test
    fun firstDevicePublishesAndUnlocks() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"KEYS_REQUIRED","message":"none"}}"""))
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals("u1", crypto.unlockedUserId.value)
        server.takeRequest()
        val put = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(abandonKey, put["identity_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun matchingPhraseOnALaterDeviceUnlocks() = runTest {
        server.enqueue(MockResponse(code = 200, body = identity(abandonKey)))
        server.enqueue(MockResponse(code = 204))
        crypto.unlockWithPhrase(words, session)
        assertEquals("u1", crypto.unlockedUserId.value)
    }

    @Test
    fun wrongPhraseIsRefusedAndNothingIsPublished() = runTest {
        server.enqueue(MockResponse(code = 200, body = identity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))
        val error = runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull()
        assertTrue(error is CryptoException.PhraseDoesNotMatchAccount)
        assertNull(crypto.unlockedUserId.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun signUpNeverReplacesAnotherPhrasesPublishedKey() = runTest {
        server.enqueue(MockResponse(code = 200, body = identity("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")))
        val error = runCatching { crypto.establishFromSignup(words, session) }.exceptionOrNull()
        assertTrue(error is CryptoException.PhraseDoesNotMatchAccount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun badChecksumFailsBeforeAnyRequest() = runTest {
        val error = runCatching { crypto.unlockWithPhrase(List(12) { "abandon" }, session) }.exceptionOrNull()
        assertTrue(error is Bip39.PhraseException.InvalidChecksum)
        assertEquals("That phrase isn’t valid. Check the words and order.", CryptoController.userMessage(error!!))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun lockDuringUnlockDropsTheKeysAndSaysSo() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"KEYS_REQUIRED","message":"none"}}"""))
        server.enqueue(MockResponse(code = 204).newBuilder().headersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { crypto.unlockWithPhrase(words, session) }.exceptionOrNull() }
        Thread.sleep(150)
        crypto.lock()
        val error = job.await()
        assertTrue(error.toString(), error is CryptoException.LockedWhileUnlocking)
        assertNull(crypto.unlockedUserId.value)
    }
}

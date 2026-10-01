package de.corespace.shroud.core.auth

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `DeviceNameSync` (`ios/shroud/Services/Auth/DeviceNameSync.swift`; settings-lock §18.1 row
 * `knownModelsHaveMarketingNames` → Android cases; web-parity §22.1 `labelToWrite` table).
 */
class DeviceNameSyncTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())

    /** The `/auth/me` answer: this device with [sealedName], or another device. */
    @Volatile private var meDevice: String = DEVICE_ID
    @Volatile private var sealedName: String? = null
    @Volatile private var meStatus = 200
    @Volatile private var putStatus = 204
    @Volatile private var meGate: CountDownLatch? = null

    private val api get() = ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                    "/api/v1/auth/me" -> {
                        meGate?.await(5, TimeUnit.SECONDS)
                        if (meStatus != 200) {
                            MockResponse(code = meStatus, body = """{"error":{"code":"INTERNAL","message":"x"}}""")
                        } else {
                            val name = sealedName?.let { ""","sealed_name":"$it"""" } ?: ""
                            MockResponse(code = 200, body = """{"user":{"id":"$USER_ID","username":"alice"},"device":{"id":"$meDevice"$name}}""")
                        }
                    }
                    else -> MockResponse(code = putStatus, body = if (putStatus == 204) "" else """{"error":{"code":"RATE_LIMITED","message":"x"}}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun puts() = requests.filter { it.method == "PUT" }

    private fun putLabel(): DeviceNameSeal.Label? {
        val put = puts().single()
        assertEquals("/api/v1/devices/$DEVICE_ID/name", put.url.encodedPath)
        val sealed = json.parseToJsonElement(put.body!!.utf8()).jsonObject["sealed_name"]!!.jsonPrimitive.content
        return DeviceNameSeal.open(sealed, UUID.fromString(DEVICE_ID), historyKey())
    }

    private fun seal(label: DeviceNameSeal.Label): String = DeviceNameSeal.seal(label, UUID.fromString(DEVICE_ID), historyKey())

    // ---- labelToWrite (web-parity §22.1) ----

    @Test
    fun labelToWriteTable() {
        val wanted = DeviceNameSeal.Label("Pixel 9 Pro", DeviceNameSeal.Kind.Android)
        assertEquals(wanted, DeviceNameSync.labelToWrite(null, wanted))
        assertNull(DeviceNameSync.labelToWrite(wanted, wanted))
        assertNull(DeviceNameSync.labelToWrite(DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Android, custom = true), wanted))
        assertEquals(
            DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Android, custom = true),
            DeviceNameSync.labelToWrite(DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Other, custom = true), wanted),
        )
        assertEquals(wanted, DeviceNameSync.labelToWrite(DeviceNameSeal.Label("old", DeviceNameSeal.Kind.Android), wanted))
        // An older build's kind 0 is re-sealed as kind 4.
        assertEquals(wanted, DeviceNameSync.labelToWrite(DeviceNameSeal.Label("Pixel 9 Pro", DeviceNameSeal.Kind.Other), wanted))
    }

    // ---- currentLabel ----

    @Test
    fun theNameTheUserGaveThePhoneWins() {
        val label = DeviceNameSync.label("  Niklas’s\nPixel \u0007", "Google", "Pixel 9a")
        assertEquals(DeviceNameSeal.Label("Niklas’s Pixel", DeviceNameSeal.Kind.Android), label)
    }

    @Test
    fun withoutANameTheModelIsUsed() {
        assertEquals("Samsung SM-S918B", DeviceNameSync.label(null, "samsung", "SM-S918B").name)
        assertEquals("Samsung SM-S918B", DeviceNameSync.label(" \n ", "samsung", "SM-S918B").name)
        // A model that already starts with its maker is not doubled.
        assertEquals("Xiaomi 14", DeviceNameSync.label(null, "Xiaomi", "Xiaomi 14").name)
        assertEquals("motorola edge 50", DeviceNameSync.label("", "Motorola", "motorola edge 50").name)
        assertEquals("Google Pixel 9a", DeviceNameSync.label(null, "Google", "Pixel 9a").name)
        assertEquals("Pixel 9a", DeviceNameSync.label(null, "", "Pixel 9a").name)
        assertEquals("Android", DeviceNameSync.label(null, null, null).name)
        assertEquals(DeviceNameSeal.Kind.Android, DeviceNameSync.label(null, "samsung", "SM-S918B").kind)
        assertTrue(!DeviceNameSync.label(null, "samsung", "SM-S918B").custom)
    }

    @Test
    fun aLongNameIsCutLikeEveryClientCutsIt() {
        assertEquals("x".repeat(DeviceNameSeal.MAX_NAME_BYTES), DeviceNameSync.label("x".repeat(300), "Google", "Pixel").name)
    }

    // ---- syncIfNeeded ----

    @Test
    fun anUnnamedPhoneSealsItsName() = runTest {
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(WANTED, putLabel())
        assertEquals("Bearer tok", puts().single().headers["Authorization"])
    }

    @Test
    fun anUpToDateNameIsNotWrittenAgain() = runTest {
        sealedName = seal(WANTED)
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(1, requests.size)
        assertTrue(puts().isEmpty())
    }

    @Test
    fun aChangedNameIsWritten() = runTest {
        sealedName = seal(DeviceNameSeal.Label("Old Pixel", DeviceNameSeal.Kind.Android))
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(WANTED, putLabel())
    }

    @Test
    fun aNameSomebodyTypedIsKept() = runTest {
        sealedName = seal(DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Android, custom = true))
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertTrue(puts().isEmpty())
    }

    @Test
    fun aTypedNameWhoseKindWasDroppedGetsKindFourBack() = runTest {
        sealedName = seal(DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Other, custom = true))
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(DeviceNameSeal.Label("Work", DeviceNameSeal.Kind.Android, custom = true), putLabel())
    }

    @Test
    fun aNameOfAnotherAccountKeyIsReplaced() = runTest {
        // Sealed under another history key (`bytes 255−i`): it does not open, so the phone names itself.
        sealedName = DeviceNameSeal.seal(WANTED, UUID.fromString(DEVICE_ID), ByteArray(32) { (255 - it).toByte() })
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(WANTED, putLabel())
    }

    @Test
    fun anotherDeviceInTheAnswerWritesNothing() = runTest {
        meDevice = "33333333-3333-3333-3333-333333333333"
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertTrue(puts().isEmpty())
    }

    @Test
    fun errorsAreSwallowed() = runTest {
        meStatus = 500
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertTrue(puts().isEmpty())
        meStatus = 200
        putStatus = 429
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        assertEquals(1, puts().size)
        // Offline: nothing listens there.
        val offline = ShroudApi(ApiClient({ "http://127.0.0.1:1/api/v1" }, json))
        DeviceNameSync.syncIfNeeded(offline, SESSION, historyKey(), WANTED)
    }

    @Test
    fun oneSyncAtATime() = runTest {
        val gate = CountDownLatch(1)
        meGate = gate
        val first = async { DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED) }
        // The first sync takes the flight and waits for its /auth/me.
        runCurrent()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (requests.none { it.url.encodedPath == "/api/v1/auth/me" } && System.nanoTime() < deadline) Thread.sleep(5)
        // A second one meanwhile returns at once, without a request.
        DeviceNameSync.syncIfNeeded(api, SESSION, historyKey(), WANTED)
        gate.countDown()
        first.await()
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/auth/me" })
        assertEquals(1, puts().size)
    }

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"

        /** `DeviceNameSealTests` device id (`0F8FAD5B-…`), lower-case as Android stores it. */
        const val DEVICE_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val SESSION = Session("tok", USER_ID, "alice", null, DEVICE_ID)
        val WANTED = DeviceNameSeal.Label("Pixel 9 Pro", DeviceNameSeal.Kind.Android)

        /** `DeviceNameSealTests` history key: bytes 0x00…0x1F. */
        fun historyKey(): ByteArray = ByteArray(32) { it.toByte() }
    }
}

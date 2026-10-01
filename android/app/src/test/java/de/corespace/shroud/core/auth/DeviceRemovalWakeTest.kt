package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The removal push (`ios/shroud/Services/Auth/DeviceRemovalWake.swift`; settings-lock §14.6;
 * `SessionAuthFailureTests.removalPushIsRecognisedByType`): it never deletes on its own, only on the
 * server's `DEVICE_REMOVED`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRemovalWakeTest {
    @get:Rule val temp = TempDirRule()

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse(code = 204)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun removalPushIsRecognisedByType() {
        assertTrue(DeviceRemovalWake.isRemoval(mapOf("aps" to """{"content-available":1}""", "type" to "device_removed")))
        // The Web Push payload over UnifiedPush: {"v":1,"kind":"device_removed"}.
        assertTrue(DeviceRemovalWake.isRemoval(mapOf("v" to "1", "kind" to "device_removed")))
        assertFalse(DeviceRemovalWake.isRemoval(mapOf("aps" to """{"badge":3}""")))
        assertFalse(DeviceRemovalWake.isRemoval(mapOf("type" to "message")))
        assertFalse(DeviceRemovalWake.isRemoval(mapOf("v" to "1", "kind" to "message")))
    }

    private inner class Harness(
        scope: TestScope,
        signedIn: Boolean = true,
        hasUi: Boolean = true,
        private val confirm: suspend (Session) -> Unit,
    ) {
        val fixture = WipeFixture(temp.root, FakeSystemWipe())
        val hooks = RecordingWipeHooks()
        val seal = StorageSeal()
        private val scheduler = scope.testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)
        val clock = object : AppClock {
            override fun nowMillis(): Long = scheduler.currentTime
            override fun elapsedMillis(): Long = scheduler.currentTime
            override fun now(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
        }
        val store = SessionStore(
            SealedFile(File(temp.root, "no_backup/session.sealed"), XorSealer()),
            SealedFile(File(temp.root, "no_backup/device-anchor.sealed"), XorSealer()),
            json,
            seal,
        ).also { if (signedIn) it.save(SAMPLE) }
        lateinit var controller: DeviceWipeController
        val session = SessionController(
            ShroudApi(ApiClient({ server.url("/api/v1").toString() }, json)),
            store,
            scope.backgroundScope,
            wipeMarker = fixture.wipe,
            isWipePresented = { controller.isPresented.value },
            io = dispatcher,
        )
        val confirmed = ArrayList<String>()
        val wake: RemovalWake

        init {
            controller = DeviceWipeController(
                dataWipe = fixture.wipe,
                session = session,
                hooks = hooks,
                storageSeal = seal,
                // Not backgroundScope: advanceUntilIdle() leaves background work alone.
                appScope = CoroutineScope(SupervisorJob() + dispatcher),
                clock = clock,
                endServerSession = { throw REMOVED },
                io = dispatcher,
            )
            if (hasUi) controller.router = WipeRouter { hooks.calls += "router" }
            wake = RemovalWake(
                storedSession = { store.session },
                confirm = { stored ->
                    confirmed += stored.token
                    confirm(stored)
                },
                session = session,
                deviceWipe = controller,
                dataWipe = fixture.wipe,
                hooks = hooks,
                storageSeal = seal,
                hasUi = { controller.router != null },
                clock = clock,
                io = dispatcher,
            )
        }
    }

    @Test
    fun noSessionNoWork() = runTest {
        val h = Harness(this, signedIn = false, confirm = { error("never asked") })
        assertEquals(WakeResult.NoData, h.wake.handle())
        assertTrue(h.confirmed.isEmpty())
    }

    @Test
    fun aDeviceStillOnTheAccountIgnoresThePush() = runTest {
        val h = Harness(this, confirm = {})
        h.fixture.seedAccount()
        assertEquals(WakeResult.NoData, h.wake.handle())
        assertEquals(listOf("test-token"), h.confirmed)
        assertTrue(h.fixture.exists("no_backup/shroud/messages"))
        assertFalse(h.controller.isPresented.value)
    }

    @Test
    fun anotherRefusalIsNotARemoval() = runTest {
        val h = Harness(this, confirm = { throw ApiError.Server("UNAUTHORIZED", "Unauthorized", 401) })
        assertEquals(WakeResult.NoData, h.wake.handle())
        assertFalse(h.session.pendingFullLocalWipe.value)
    }

    @Test
    fun noAnswerLeavesItToTheSocketOrTheNextLaunch() = runTest {
        val offline = Harness(this, confirm = { throw ApiError.Transport("The server can’t be reached.") })
        assertEquals(WakeResult.Failed, offline.wake.handle())
        val slow = Harness(this, confirm = { delay(60_000) })
        val started = testScheduler.currentTime
        assertEquals(WakeResult.Failed, slow.wake.handle())
        assertEquals(DeviceRemovalWake.CONFIRM_TIMEOUT_MS, testScheduler.currentTime - started)
        assertFalse(slow.controller.isPresented.value)
    }

    @Test
    fun aConfirmedRemovalWithTheUiAliveRunsTheOverlayWipe() = runTest {
        val h = Harness(this, confirm = { throw REMOVED })
        h.fixture.seedAccount()
        h.store.save(SAMPLE)

        assertEquals(WakeResult.NewData, h.wake.handle())
        advanceUntilIdle()
        assertEquals(WipeReason.Removed, h.controller.reason.value)
        assertFalse(h.controller.isPresented.value)
        assertNull(h.session.session.value)
        assertFalse(h.session.pendingFullLocalWipe.value)
        assertTrue("router" in h.hooks.calls)
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", h.fixture.exists(gone))
        assertFalse(h.fixture.wipe.isPending)
    }

    @Test
    fun aReplacedLoginIsLeftAlone() = runTest {
        // The app no longer holds the removed session when the answer comes (the user logged out
        // meanwhile, or logged in again with another token): whatever it holds now is left alone.
        lateinit var h: Harness
        h = Harness(this, confirm = {
            h.session.logout()
            throw REMOVED
        })
        h.fixture.write("no_backup/shroud/plaintext/e09da208.sealed")
        assertEquals(WakeResult.NoData, h.wake.handle())
        assertFalse(h.controller.isPresented.value)
        assertTrue(h.fixture.exists("no_backup/shroud/plaintext/e09da208.sealed"))
    }

    @Test
    fun withoutUiTheStoresAreEmptiedAndTheNextLaunchFinishes() = runTest {
        val h = Harness(this, hasUi = false, confirm = { throw REMOVED })
        h.fixture.seedAccount()
        h.store.save(SAMPLE)

        assertEquals(WakeResult.NewData, h.wake.handle())
        // Emptied at once, writers halted and storage sealed; no overlay.
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", h.fixture.exists(gone))
        // What controllers built in this process hold goes too (review W2: call secrets in memory).
        assertEquals(listOf("haltWriters", "stopMessaging(true)", "clearCalls", "forgetNotifications", "lockCrypto(true)"), h.hooks.calls)
        assertTrue(h.seal.isSealed)
        assertFalse(h.controller.isPresented.value)
        // The marker stays for the next launch, which ends the session and says so.
        assertTrue(h.fixture.wipe.isPending)
        assertFalse(h.session.pendingFullLocalWipe.value)
        assertEquals("test-token", h.session.session.value?.token)

        assertTrue(h.controller.finishInterruptedWipeIfNeeded())
        assertNull(h.session.session.value)
        assertFalse(h.fixture.wipe.isPending)
        assertFalse(h.seal.isSealed)
        // A late report of the removal (the socket) starts no second wipe.
        h.session.recordDeviceRemoved("test-token")
        assertFalse(h.session.pendingFullLocalWipe.value)
    }

    private companion object {
        val SAMPLE = Session("test-token", "11111111-1111-1111-1111-111111111111", "tester", null, "22222222-2222-2222-2222-222222222222")
        val REMOVED = ApiError.Server("DEVICE_REMOVED", "This device was removed from your account.", 401)
    }
}

package de.corespace.shroud.core.auth

import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Haptic
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * The wipe controller on virtual time with a fake data directory (settings-lock §18.3
 * `DeviceWipeControllerTest`; iOS `DeviceWipeControllerTests.swift` for the reason and outcome tables).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceWipeControllerTest {
    @get:Rule val temp = TempDirRule()

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        // The session's own background revoke after the wipe: always heard.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse(code = 204)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** One wipe on [scope]'s virtual time: every collaborator a fake, the session stored in the fixture. */
    private inner class Harness(
        scope: TestScope,
        signedIn: Boolean = true,
        reduce: Boolean = false,
        private val endServer: suspend Harness.(String) -> Unit = {},
    ) {
        val system = FakeSystemWipe()
        val fixture = WipeFixture(temp.root, system)
        val hooks = RecordingWipeHooks()
        val seal = StorageSeal()
        val serverCalls = ArrayList<String>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val scheduler = scope.testScheduler
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
        val timeline = ArrayList<Pair<Long, Map<WipeStep, String>>>()
        val feedback = ArrayList<WipeFeedback>()
        val phases = ArrayList<Pair<Long, WipePhase>>()

        init {
            controller = DeviceWipeController(
                dataWipe = fixture.wipe,
                session = session,
                hooks = hooks,
                storageSeal = seal,
                // Not backgroundScope: advanceUntilIdle() leaves background work alone.
                appScope = CoroutineScope(SupervisorJob() + dispatcher),
                clock = clock,
                endServerSession = { token ->
                    serverCalls += token
                    endServer(token)
                },
                reduceMotion = { reduce },
                deviceNoun = { "phone" },
                io = dispatcher,
            )
            controller.router = WipeRouter { hooks.calls += "router" }
            val watch = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(watch) { controller.details.collect { timeline += scheduler.currentTime to it } }
            scope.backgroundScope.launch(watch) { controller.feedback.collect { feedback += it } }
            scope.backgroundScope.launch(watch) { controller.phase.collect { phases += scheduler.currentTime to it } }
        }

        fun detailTimes(): Map<WipeStep, Long> {
            val out = LinkedHashMap<WipeStep, Long>()
            for ((time, details) in timeline) for (step in details.keys) out.putIfAbsent(step, time)
            return out
        }
    }

    // ---- A whole run ----

    @Test
    fun aLogOutRunsTheSixStepsAtReadablePaceAndEndsTheSession() = runTest {
        val h = Harness(this)
        h.fixture.seedAccount()

        h.controller.start(WipeReason.Logout)
        assertEquals("@tester", h.controller.handle.value)
        advanceUntilIdle()

        assertEquals(listOf("test-token"), h.serverCalls)
        assertEquals(
            linkedMapOf(
                WipeStep.Session to 420L, WipeStep.Messages to 840L, WipeStep.Media to 1260L,
                WipeStep.Keys to 1680L, WipeStep.Settings to 2100L, WipeStep.Verify to 2660L,
            ),
            h.detailTimes(),
        )
        assertEquals(
            listOf(
                WipeFeedback("Signing out: Session ended"),
                WipeFeedback("Messages: 4 removed"),
                WipeFeedback("Photos, videos & voice: 4 files · 7 KB"),
                WipeFeedback("Encryption keys: 6 removed"),
                WipeFeedback("Settings & caches: Cleared"),
                WipeFeedback("Checking nothing is left: Nothing left"),
                WipeFeedback("This phone is clear. Nothing from your account is left on it.", Haptic.Success),
            ),
            h.feedback,
        )
        // Done after the check, then a 1.4 s hold before the overlay leaves.
        assertEquals(listOf(0L to WipePhase.Idle, 0L to WipePhase.Running, 2660L to WipePhase.Done, 4060L to WipePhase.Idle), h.phases)
        assertFalse(h.controller.isPresented.value)
        // Writers halted at start and again before the inventory; the end of the session in iOS order.
        assertEquals(
            listOf(
                "haltWriters", "haltWriters", "lockCrypto(true)", "stopMessaging(true)", "clearCalls",
                "forgetPush", "forgetNotifications", "forgetAppearance", "router",
            ),
            h.hooks.calls,
        )
        assertNull(h.session.session.value)
        assertFalse(h.fixture.wipe.isPending)
        assertFalse(h.seal.isSealed)
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", h.fixture.exists(gone))
        for (kept in WipeFixture.KEPT_PATHS) assertTrue("$kept was not kept", h.fixture.exists(kept))
    }

    @Test
    fun reduceMotionShortensEveryStepTo120Ms() = runTest {
        val h = Harness(this, reduce = true)
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()
        assertEquals(listOf(120L, 240L, 360L, 480L, 600L, 720L), h.detailTimes().values.toList())
        assertEquals(720L + 900L, h.phases.last().first)
        // An empty phone reports nothing stored.
        assertEquals("None stored", h.controller.details.value.getValue(WipeStep.Media))
        assertEquals("None stored", h.controller.details.value.getValue(WipeStep.Messages))
    }

    @Test
    fun writersHaltBeforeTheFirstSuspension() = runTest {
        val h = Harness(this)
        h.controller.start(WipeReason.Logout)
        // Nothing ran yet but start() itself.
        assertEquals(listOf("haltWriters"), h.hooks.calls)
        assertTrue(h.seal.isSealed)
        assertEquals(WipePhase.Running, h.controller.phase.value)
        assertTrue(h.controller.isPresented.value)
        advanceUntilIdle()
    }

    @Test
    fun startingTwiceRunsOnce() = runTest {
        val h = Harness(this)
        h.controller.start(WipeReason.Logout)
        h.controller.start(WipeReason.SessionEnded)
        assertEquals(WipeReason.Logout, h.controller.reason.value)
        advanceUntilIdle()
        assertEquals(1, h.serverCalls.size)
        assertEquals(2, h.hooks.count("haltWriters"))
    }

    // ---- The session step ----

    @Test
    fun anOfflineServerEndsTheSessionHereOnly() = runTest {
        val h = Harness(this, endServer = { throw ApiError.Transport("The server can’t be reached.") })
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()
        assertEquals("Ended here · server offline", h.controller.details.value[WipeStep.Session])
    }

    @Test
    fun a401OnTheLogoutMeansTheServerHeardIt() = runTest {
        val h = Harness(this, endServer = { throw ApiError.Server("UNAUTHORIZED", "This session was signed out.", 401) })
        h.controller.start(WipeReason.SessionEnded)
        advanceUntilIdle()
        assertEquals("Session ended", h.controller.details.value[WipeStep.Session])
        assertEquals(WipeReason.SessionEnded, h.controller.reason.value)
    }

    @Test
    fun theServerGetsFourSeconds() = runTest {
        val h = Harness(this, endServer = { delay(60_000) })
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()
        assertEquals("Ended here · server offline", h.controller.details.value[WipeStep.Session])
        assertEquals(4_000L, h.detailTimes()[WipeStep.Session])
    }

    @Test
    fun aRemovalAnswerTurnsAnEndedSessionIntoARemoval() = runTest {
        val h = Harness(this, endServer = { throw REMOVED })
        h.controller.start(WipeReason.SessionEnded)
        advanceUntilIdle()
        assertEquals(WipeReason.Removed, h.controller.reason.value)
        assertEquals("Session ended", h.controller.details.value[WipeStep.Session])
    }

    @Test
    fun aRemovalAnswerToItsOwnLogOutQueuesNoSecondWipe() = runTest {
        // Log Out on a removed phone: the logout's DEVICE_REMOVED reaches the session while the wipe runs.
        val h = Harness(this, endServer = { token ->
            session.recordDeviceRemoved(token)
            throw REMOVED
        })
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()
        assertEquals(WipeReason.Logout, h.controller.reason.value)
        assertFalse(h.session.pendingFullLocalWipe.value)
        assertEquals(WipePhase.Idle, h.controller.phase.value)
        assertNull(h.session.session.value)
        assertEquals(1, h.serverCalls.size)
    }

    @Test
    fun theServerOutcomeTable() {
        // `DeviceWipeControllerTests.theLogoutAnswerSaysWhetherThisDeviceWasRemoved`.
        val revoked = ApiError.Server("UNAUTHORIZED", "This session was signed out.", 401)
        val serverError = ApiError.Server("INTERNAL", "Something went wrong.", 500)
        assertEquals(ServerSessionOutcome.Removed, DeviceWipeController.serverSessionOutcome(REMOVED))
        assertEquals(ServerSessionOutcome.Ended, DeviceWipeController.serverSessionOutcome(revoked))
        assertEquals(ServerSessionOutcome.Ended, DeviceWipeController.serverSessionOutcome(serverError))
        assertEquals(ServerSessionOutcome.Offline, DeviceWipeController.serverSessionOutcome(ApiError.Transport("offline")))
        assertEquals(ServerSessionOutcome.Ended, DeviceWipeController.serverSessionOutcome(ApiError.Decoding("bad")))
    }

    // ---- A failed check ----

    @Test
    fun aCheckThatFindsLeftoversFailsAndNamesThem() = runTest {
        val h = Harness(this)
        h.system.stuck = STUCK
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()
        assertEquals(WipePhase.Failed, h.controller.phase.value)
        assertEquals(STUCK, h.controller.leftovers.value)
        assertEquals("media and cached files, settings", DeviceWipeController.labels(STUCK))
        assertEquals(WipeFeedback("Some data could not be removed: media and cached files, settings.", Haptic.Error), h.feedback.last())
        assertNull(h.controller.active.value)
        assertFalse(WipeStep.Verify in h.controller.details.value)
        // Still pending and sealed; the session is not ended until Continue or a passing retry.
        assertTrue(h.fixture.wipe.isPending)
        assertTrue(h.seal.isSealed)
        assertEquals("test-token", h.session.session.value?.token)
    }

    @Test
    fun tryAgainFinishesWhenTheCheckPasses() = runTest {
        val h = Harness(this)
        h.system.stuck = STUCK
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()

        h.system.stuck = emptyList()
        h.controller.retry()
        assertEquals(setOf(WipeStep.Media, WipeStep.Settings), h.controller.retrying.value)
        assertTrue(h.controller.leftovers.value.isEmpty())
        assertEquals(WipePhase.Running, h.controller.phase.value)
        advanceUntilIdle()
        assertEquals("Nothing left", h.controller.details.value[WipeStep.Verify])
        assertTrue(h.controller.retrying.value.isEmpty())
        assertEquals(WipePhase.Idle, h.controller.phase.value)
        assertNull(h.session.session.value)
        assertFalse(h.fixture.wipe.isPending)
        assertFalse(h.seal.isSealed)
    }

    @Test
    fun continueEndsTheSessionAndKeepsTheMarker() = runTest {
        val h = Harness(this)
        h.system.stuck = STUCK
        h.controller.start(WipeReason.Logout)
        advanceUntilIdle()

        h.controller.continueAfterFailure()
        advanceUntilIdle()
        assertEquals(WipePhase.Idle, h.controller.phase.value)
        assertNull(h.session.session.value)
        // The next launch tries again.
        assertTrue(h.fixture.wipe.isPending)
        // Sign-in can write again.
        assertFalse(h.seal.isSealed)
        assertTrue("lockCrypto(true)" in h.hooks.calls)
        assertTrue("router" in h.hooks.calls)
    }

    @Test
    fun retryAndContinueOnlyAfterAFailure() = runTest {
        val h = Harness(this)
        h.controller.retry()
        h.controller.continueAfterFailure()
        advanceUntilIdle()
        assertEquals(WipePhase.Idle, h.controller.phase.value)
        assertTrue(h.hooks.calls.isEmpty())
    }

    // ---- The root's reaction to an ended session ----

    @Test
    fun anEndedSessionStartsTheWipeOnceWithItsReason() = runTest {
        val h = Harness(this)
        assertFalse(h.controller.startIfSessionEnded())
        h.session.recordDeviceRemoved("test-token")
        assertTrue(h.controller.startIfSessionEnded())
        assertEquals(WipeReason.Removed, h.controller.reason.value)
        assertFalse(h.session.pendingFullLocalWipe.value)
        assertFalse(h.controller.startIfSessionEnded())
        advanceUntilIdle()
        assertNull(h.session.session.value)
        assertFalse(h.session.sessionEndedByDeviceRemoval.value)
    }

    @Test
    fun theStreakStartsTheWipeAsAnEndedSession() = runTest {
        val h = Harness(this)
        repeat(3) { h.session.recordAuthenticationFailure() }
        assertTrue(h.fixture.wipe.isPending)
        assertTrue(h.controller.startIfSessionEnded())
        assertEquals(WipeReason.SessionEnded, h.controller.reason.value)
        advanceUntilIdle()
    }

    // ---- Launch ----

    @Test
    fun aWipeKilledMidWayIsFinishedAtLaunch() = runTest {
        val h = Harness(this, endServer = { token ->
            // The interrupted wipe's logout answers DEVICE_REMOVED for a removed phone: no second wipe.
            session.recordDeviceRemoved(token)
            throw REMOVED
        })
        h.fixture.seedAccount()
        h.store.save(SAMPLE)
        h.fixture.wipe.markPending()

        assertTrue(h.controller.finishInterruptedWipeIfNeeded())
        assertEquals(listOf("test-token"), h.serverCalls)
        assertFalse(h.session.pendingFullLocalWipe.value)
        assertNull(h.session.session.value)
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", h.fixture.exists(gone))
        assertFalse(h.fixture.wipe.isPending)
        assertEquals(listOf("forgetNotifications", "forgetAppearance", "lockCrypto(true)"), h.hooks.calls)
        assertFalse(h.seal.isSealed)
    }

    @Test
    fun aSignedOutLaunchSweepsSilently() = runTest {
        val h = Harness(this, signedIn = false)
        h.fixture.seedAccount()
        File(temp.root, "no_backup/session.sealed").delete()

        assertFalse(h.controller.finishInterruptedWipeIfNeeded())
        assertTrue(h.serverCalls.isEmpty())
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", h.fixture.exists(gone))
        for (kept in WipeFixture.KEPT_PATHS) assertTrue("$kept was not kept", h.fixture.exists(kept))
        assertEquals(listOf("forgetNotifications", "forgetAppearance"), h.hooks.calls)
    }

    @Test
    fun aDeadSessionFileCountsAsSignedOut() = runTest {
        File(temp.root, "no_backup").mkdirs()
        File(temp.root, "no_backup/session.sealed").writeBytes(byteArrayOf(9, 9, 9))
        val h = Harness(this, signedIn = false)
        h.fixture.write("files/export.txt")

        assertFalse(h.controller.finishInterruptedWipeIfNeeded())
        assertFalse(h.fixture.exists("no_backup/session.sealed"))
        assertFalse(h.fixture.exists("files/export.txt"))
    }

    @Test
    fun aSignedInLaunchWithoutAPendingWipeTouchesNothing() = runTest {
        val h = Harness(this)
        h.fixture.seedAccount()
        h.store.save(SAMPLE)
        assertFalse(h.controller.finishInterruptedWipeIfNeeded())
        assertTrue(h.fixture.exists("no_backup/shroud/messages"))
        assertEquals("test-token", h.session.session.value?.token)
        assertTrue(h.hooks.calls.isEmpty())
    }

    @Test
    fun theOverlayStaysUpWhileTheWipeRuns() = runTest {
        val h = Harness(this)
        h.controller.start(WipeReason.Logout)
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(WipeStep.Media, h.controller.active.value)
        assertTrue(h.controller.isPresented.value)
        advanceUntilIdle()
    }

    private companion object {
        val SAMPLE = Session("test-token", "11111111-1111-1111-1111-111111111111", "tester", null, "22222222-2222-2222-2222-222222222222")
        val REMOVED = ApiError.Server("DEVICE_REMOVED", "This device was removed from your account.", 401)
        val STUCK = listOf(
            DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"),
            DeviceDataWipe.Leftover(WipeStep.Settings, "settings"),
            DeviceDataWipe.Leftover(WipeStep.Media, "media and cached files"),
        )
    }
}

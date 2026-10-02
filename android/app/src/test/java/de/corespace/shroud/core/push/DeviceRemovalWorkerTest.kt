package de.corespace.shroud.core.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import de.corespace.shroud.core.auth.DeviceWipeController
import de.corespace.shroud.core.auth.FakeSystemWipe
import de.corespace.shroud.core.auth.RecordingWipeHooks
import de.corespace.shroud.core.auth.RemovalWake
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.auth.SessionStore
import de.corespace.shroud.core.auth.WipeFixture
import de.corespace.shroud.core.model.SystemAppClock
import de.corespace.shroud.core.net.ApiClient
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The worker confirms with `GET /auth/me` through [RemovalWake]. A phone the server still knows
 * is left alone. `DEVICE_REMOVED` runs the same headless wipe Log Out uses (`hasUi` false).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DeviceRemovalWorkerTest {
    @get:Rule val temp = TempDirRule()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        DeviceRemovalWorker.wakeOverride = null
        server?.close()
    }

    @Test
    fun aDeviceTheServerStillKnowsIsNotWiped() = runBlocking {
        val harness = harness(removed = false)
        harness.fixture.seedAccount()
        DeviceRemovalWorker.wakeOverride = { harness.wake.handle() }
        val worker = TestListenableWorkerBuilder<DeviceRemovalWorker>(context()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(harness.fixture.exists("no_backup/shroud/messages"))
        assertEquals("test-token", harness.session.session.value?.token)
        assertFalse(harness.hooks.calls.contains("haltWriters"))
    }

    @Test
    fun aConfirmedRemovalWipes() = runBlocking {
        val harness = harness(removed = true)
        harness.fixture.seedAccount()
        harness.store.save(SAMPLE)
        DeviceRemovalWorker.wakeOverride = { harness.wake.handle() }
        val worker = TestListenableWorkerBuilder<DeviceRemovalWorker>(context()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        for (gone in WipeFixture.ACCOUNT_PATHS) assertFalse("$gone survived", harness.fixture.exists(gone))
        assertTrue(harness.hooks.calls.contains("haltWriters"))
        assertEquals("test-token", harness.session.session.value?.token)
    }

    private fun context(): Application = ApplicationProvider.getApplicationContext()

    private fun harness(removed: Boolean): Harness {
        val started = MockWebServer()
        server = started
        started.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.endsWith("/auth/me")) return MockResponse(code = 500)
                return if (removed) {
                    MockResponse(code = 401, body = """{"error":{"code":"DEVICE_REMOVED","message":"removed"}}""")
                } else {
                    MockResponse(
                        code = 200,
                        body = """{"user":{"id":"11111111-1111-1111-1111-111111111111","username":"tester","share_code":"abc"},"device":{"id":"22222222-2222-2222-2222-222222222222"}}""",
                    )
                }
            }
        }
        started.start()
        val fixture = WipeFixture(temp.root, FakeSystemWipe())
        val hooks = RecordingWipeHooks()
        val seal = StorageSeal()
        val store = SessionStore(
            SealedFile(File(temp.root, "no_backup/session.sealed"), XorSealer()),
            SealedFile(File(temp.root, "no_backup/device-anchor.sealed"), XorSealer()),
            json,
            seal,
        ).also { it.save(SAMPLE) }
        val api = ShroudApi(ApiClient({ started.url("/api/v1").toString() }, json))
        lateinit var controller: DeviceWipeController
        val session = SessionController(
            api = api,
            store = store,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            wipeMarker = fixture.wipe,
            isWipePresented = { controller.isPresented.value },
            io = Dispatchers.IO,
        )
        controller = DeviceWipeController(
            dataWipe = fixture.wipe,
            session = session,
            hooks = hooks,
            storageSeal = seal,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = SystemAppClock,
            endServerSession = { error("the headless removal does not call logout") },
            io = Dispatchers.IO,
        )
        val wake = RemovalWake(
            storedSession = { store.session },
            confirm = { stored -> api.me(stored.token) },
            session = session,
            deviceWipe = controller,
            dataWipe = fixture.wipe,
            hooks = hooks,
            storageSeal = seal,
            hasUi = { false },
            clock = SystemAppClock,
            io = Dispatchers.IO,
        )
        return Harness(fixture, hooks, store, session, wake)
    }

    private class Harness(
        val fixture: WipeFixture,
        val hooks: RecordingWipeHooks,
        val store: SessionStore,
        val session: SessionController,
        val wake: RemovalWake,
    )

    private companion object {
        val SAMPLE = Session(
            "test-token",
            "11111111-1111-1111-1111-111111111111",
            "tester",
            null,
            "22222222-2222-2222-2222-222222222222",
        )
    }
}

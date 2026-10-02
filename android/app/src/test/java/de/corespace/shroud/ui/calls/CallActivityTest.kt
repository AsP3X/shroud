package de.corespace.shroud.ui.calls

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.system.CallIntents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.UUID

/**
 * `CallActivity` (K8; Grok §2.3, §4): the system e2e's bare start finishes without touching
 * anything, a ring's intents show the call, Answer accepts it once, and the screen hooks follow
 * resume and pause.
 *
 * Runs on a plain [Application]: the app's container (and with it the session) cannot be reached,
 * so a `CallActivity` that touched it would fail with a `ClassCastException`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallActivityTest {
    private var portsBuilt = 0

    @Before
    fun setUp() {
        // Reduce motion stops the screen's endless loops on Robolectric's clock (ComposeHarness).
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Application>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        InCallPresentation.restore()
    }

    @After
    fun tearDown() {
        CallActivity.portsOverride = null
    }

    private fun use(ports: FakeCallPorts) {
        CallActivity.portsOverride = {
            portsBuilt++
            ports
        }
    }

    private fun intent(callId: UUID?, action: String?): Intent =
        Intent(ApplicationProvider.getApplicationContext(), CallActivity::class.java).apply {
            if (callId != null) putExtra(CallIntents.EXTRA_CALL_ID, callId.toString())
            if (action != null) putExtra(CallIntents.EXTRA_ACTION, action)
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    @Test
    fun aBareStartFinishesWithoutTouchingTheSessionOrTheContainer() {
        // `am start -n de.corespace.shroud/.ui.calls.CallActivity` (system-e2e.sh): no extras.
        CallActivity.portsOverride = { error("the bare start must not reach the call controller or the container") }
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse("the container is out of reach in this test", app is ShroudApplication)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(null, null)).setup()
        idle()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun aMalformedIntentFinishesQuietly() {
        CallActivity.portsOverride = { error("not for a malformed intent") }
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "dial")).setup()
        assertTrue(controller.get().isFinishing)
        val noId = Robolectric.buildActivity(CallActivity::class.java, intent(null, "answer")).setup()
        assertTrue(noId.get().isFinishing)
    }

    @Test
    fun aRingThatIsOverFinishes() {
        val ports = FakeCallPorts(active = null)
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        assertTrue(controller.get().isFinishing)
        assertTrue(ports.calls.toString(), ports.calls.none { it.startsWith("shown") || it == "acceptIncoming" })
    }

    @Test
    fun answerAcceptsTheRingingCallOnce() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "answer")).setup()
        idle()
        assertFalse(controller.get().isFinishing)
        assertEquals(ports.calls.toString(), 1, ports.calls.count { it == "acceptIncoming" })
        assertEquals(1, portsBuilt)
    }

    @Test
    fun answerForAnotherCallDoesNotAccept() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        Robolectric.buildActivity(CallActivity::class.java, intent(UUID.randomUUID(), "answer")).setup()
        idle()
        assertFalse(ports.calls.toString(), "acceptIncoming" in ports.calls)
    }

    @Test
    fun showOnlyShows() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        assertFalse(controller.get().isFinishing)
        assertFalse(ports.calls.toString(), "acceptIncoming" in ports.calls)
    }

    @Test
    fun answerArrivingWhileTheScreenShowsAccepts() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        controller.newIntent(intent(CallFixtures.callId, "answer"))
        idle()
        assertEquals(ports.calls.toString(), 1, ports.calls.count { it == "acceptIncoming" })
    }

    @Test
    fun theScreenHooksFollowResumeAndPause() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        assertEquals(listOf("shown:${CallFixtures.callId}"), ports.calls.filter { it.startsWith("shown") || it == "hidden" })
        controller.pause()
        assertEquals("hidden", ports.calls.last { it.startsWith("shown") || it == "hidden" })
        controller.resume()
        assertEquals("shown:${CallFixtures.callId}", ports.calls.last { it.startsWith("shown") || it == "hidden" })
    }

    @Test
    fun itFinishesOnceTheCallIsGone() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.Active))
        use(ports)
        val controller = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        assertFalse(controller.get().isFinishing)
        ports.ui.value = ports.ui.value.copy(active = CallFixtures.call(CallPhase.Ending))
        idle()
        assertFalse("the ending text still shows", controller.get().isFinishing)
        ports.ui.value = ports.ui.value.copy(active = null)
        idle()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun itRegistersThePermissionPromptForAnsweringOverTheKeyguard() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup()
        idle()
        assertTrue(ports.permissionPrompt != null)
    }

    @Test
    fun itShowsOverTheLockScreen() {
        val ports = FakeCallPorts(CallFixtures.call(CallPhase.IncomingRinging))
        use(ports)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent(CallFixtures.callId, "show")).setup().get()
        val shadow = shadowOf(activity)
        assertTrue(shadow.showWhenLocked)
        assertTrue(shadow.turnScreenOn)
    }
}

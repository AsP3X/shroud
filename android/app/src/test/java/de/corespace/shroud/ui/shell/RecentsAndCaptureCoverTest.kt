package de.corespace.shroud.ui.shell

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import de.corespace.shroud.core.lifecycle.AppPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recents and capture covers per P5 on a real activity window at API 30, 33 and 35 (Robolectric at
 * those SDKs; the API 30 and 37 device checks are in the report): the shell's protection flows into
 * the window exactly as `MainActivity` wires it ([WindowProtectionGuard] over [WindowControls.of]).
 *
 * | API | unlocked chats | "Hide chats during screen recording" off | locked |
 * | --- | --- | --- | --- |
 * | 30–32 | `FLAG_SECURE` (the only blank Recents thumbnail there) | still `FLAG_SECURE` | nothing |
 * | 33–34 | Recents screenshot off + `FLAG_SECURE` | Recents screenshot off only | nothing |
 * | 35+ | Recents screenshot off; captures get the in-app cover | Recents screenshot off, no cover | nothing |
 *
 * The privacy cover itself arms when the unlocked chats leave the front (`RootView.swift:255, 347-358`)
 * and never covers the lock screen (`AppShellControllerTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentsAndCaptureCoverTest {
    @After
    fun tearDown() = ShellUiHarness.settleMainThread()

    /** The real window, plus a record of the Recents switch (Robolectric keeps no state for it). */
    private class Window(val activity: ComponentActivity) : WindowControls {
        private val real = WindowControls.of(activity)
        var recentsScreenshots: Boolean? = null

        val secure: Boolean get() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

        override fun setSecure(secure: Boolean) = real.setSecure(secure)

        override fun setRecentsScreenshotEnabled(enabled: Boolean) {
            recentsScreenshots = enabled
            real.setRecentsScreenshotEnabled(enabled)
        }
    }

    private class Rig(val env: FakeShellEnvironment, val shell: AppShellController, val window: Window, val guard: WindowProtectionGuard)

    /** `MainActivity.onCreate`: the shell's protection held on the window under [WindowProtectionGuard.SHELL]. */
    private fun TestScope.rig(): Rig {
        val env = FakeShellEnvironment()
        env.signIn()
        env.phase.value = AppPhase.Inactive
        env.phase.value = AppPhase.Active
        val sdk = Build.VERSION.SDK_INT
        val shell = AppShellController(env, backgroundScope, UnconfinedTestDispatcher(testScheduler), sdk).also { it.start() }
        val window = Window(Robolectric.buildActivity(ComponentActivity::class.java).setup().get())
        val guard = WindowProtectionGuard(window, sdk)
        backgroundScope.launch { shell.windowProtection.collect { guard.hold(WindowProtectionGuard.SHELL, it) } }
        return Rig(env, shell, window, guard)
    }

    private fun Rig.unlock() {
        env.unlockKeys()
        shell.router.unlockMessages()
        assertTrue(shell.router.isUnlocked)
    }

    @Test
    @Config(sdk = [30])
    fun api30BlanksTheChatsWithFlagSecureWhateverTheSwitchSays() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        assertFalse("the lock screen shows nothing to hide", rig.window.secure)
        rig.unlock()
        assertTrue(rig.window.secure)
        rig.env.hidesDuringScreenCapture.value = false
        assertTrue("on API 30–32 the switch changes nothing (settings-lock §7.4)", rig.window.secure)
        assertEquals("no Recents switch below 33", null, rig.window.recentsScreenshots)
        rig.shell.actions.lockChatsNow()
        assertFalse(rig.window.secure)
    }

    @Test
    @Config(sdk = [33])
    fun api33SkipsTheRecentsThumbnailAndBlocksCapturesWhileTheSwitchIsOn() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        assertEquals(true, rig.window.recentsScreenshots)
        rig.unlock()
        assertEquals(false, rig.window.recentsScreenshots)
        assertTrue("no recording callback before 35: FLAG_SECURE covers captures", rig.window.secure)
        rig.env.hidesDuringScreenCapture.value = false
        assertFalse(rig.window.secure)
        assertEquals(false, rig.window.recentsScreenshots)
        rig.shell.actions.lockChatsNow()
        assertEquals(true, rig.window.recentsScreenshots)
        assertFalse(rig.window.secure)
    }

    @Test
    @Config(sdk = [35])
    fun api35SkipsTheRecentsThumbnailAndCoversRecordingsInTheApp() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        val monitor = ScreenCaptureMonitor()
        backgroundScope.launch { monitor.captured.collect(rig.shell::setScreenCaptured) }
        // The platform callback is registered while MainActivity is started; Robolectric has none to call.
        monitor.attach(rig.window.activity).close()
        rig.unlock()
        assertEquals(false, rig.window.recentsScreenshots)
        assertFalse("screenshots stay allowed on 35+, as on iOS", rig.window.secure)
        monitor.setRecording(true)
        assertTrue(rig.shell.coversForScreenCapture.value)
        assertTrue(rig.shell.showsPrivacyCover.value)
        assertFalse(rig.window.secure)
        rig.env.hidesDuringScreenCapture.value = false
        assertFalse(rig.shell.showsPrivacyCover.value)
        rig.env.hidesDuringScreenCapture.value = true
        monitor.setRecording(false)
        // A presentation display counts as mirroring on every API level.
        monitor.setPresenting(true)
        assertTrue(rig.shell.showsPrivacyCover.value)
        monitor.setPresenting(false)
        assertFalse(rig.shell.showsPrivacyCover.value)
        rig.shell.actions.lockChatsNow()
        assertEquals(true, rig.window.recentsScreenshots)
    }

    @Test
    @Config(sdk = [33])
    fun aPhraseScreensHoldOutlivesTheShellsRelease() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        rig.unlock()
        rig.guard.hold("phrase", WindowProtection.phrase(Build.VERSION.SDK_INT))
        rig.shell.actions.lockChatsNow()
        assertEquals("one release does not undo the other (shell-chats §3.8)", false, rig.window.recentsScreenshots)
        rig.guard.hold("phrase", WindowProtection.None)
        assertEquals(true, rig.window.recentsScreenshots)
    }
}

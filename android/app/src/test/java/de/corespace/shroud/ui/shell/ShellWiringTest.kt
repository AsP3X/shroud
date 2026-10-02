package de.corespace.shroud.ui.shell

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.MainActivity
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationTap
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The W2 rules the shell keeps on the real container (handover C1 done-when; W2 review):
 *
 * - the shell's lock path is `AppContainer.lockChatsInMemory()` — messaging memory, the keys **and**
 *   the ten-minute temp-file sweep, never only the first two;
 * - a notification tap reaches the shell only through the `.NotificationTapEntry` alias
 *   ([NotificationTap.from]); `MainActivity` is exported for App Links;
 * - the shell does not run with the process: a push-, boot- or `CallActivity`-started process never
 *   runs the launch sequence (`finishInterruptedWipeIfNeeded`), only the first `MainActivity` does.
 *
 * The device-name sync staying detached is `AppShellControllerTest` + [ContainerShellEnvironment.syncDeviceName]'s
 * `appScope.launch` (the controller never awaits it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellWiringTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()
    private val peer = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff")

    @Test
    fun theShellLockIsTheContainersFullLockWithTheTempFileSweep() {
        val temp = app.container.keys.sensitiveTempFiles
        val stale = temp.create("voice", "m4a")
        val fresh = temp.create("voice", "m4a")
        try {
            assertTrue(stale.setLastModified(System.currentTimeMillis() - SensitiveTempFiles.STALE_AGE_MS - 60_000))
            runBlocking { ContainerShellEnvironment(app.container).lockChatsInMemory() }
            assertFalse("a stale shroud-* file outlived the shell's lock", stale.exists())
            assertTrue("a fresh take was swept", fresh.exists())
            assertNull(app.container.keys.cryptoController.unlockedUserId.value)
        } finally {
            stale.delete()
            fresh.delete()
        }
    }

    @Test
    fun aNotificationTapCountsOnlyThroughTheAlias() {
        val ours = NotificationTap.intent(app, NotificationKind.Message, peer)
        assertEquals(LaunchIntent.Tap(NotificationTap(NotificationKind.Message, peer)), LaunchIntent.of(ours))
        // Another app's intent with our action and extras, straight at the exported activity.
        val forged = Intent(ours).setClassName(app.packageName, MainActivity::class.java.name)
        assertNull(LaunchIntent.of(forged))
        assertNull(LaunchIntent.of(Intent(ours).setClassName(app.packageName, "de.corespace.shroud.LauncherDetailed")))
        assertNull(LaunchIntent.of(null))
        assertNull(LaunchIntent.of(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun onlyTheInviteAppLinkPrefillsAddContact() {
        fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        assertEquals(LaunchIntent.Invite("https://shroud.corespace.de/u/Ab3dE"), LaunchIntent.of(view("https://shroud.corespace.de/u/Ab3dE")))
        assertNull(LaunchIntent.of(view("http://shroud.corespace.de/u/Ab3dE")))
        assertNull(LaunchIntent.of(view("https://evil.example/u/Ab3dE")))
        assertNull(LaunchIntent.of(view("https://shroud.corespace.de/settings")))
        assertNull(LaunchIntent.of(Intent(Intent.ACTION_SEND, Uri.parse("https://shroud.corespace.de/u/Ab3dE"))))
    }

    @Test
    fun theProcessStartAloneDoesNotRunTheShell() {
        // `ShroudApplication` ran `AppContainer.onProcessStart` (the shell module's included) when
        // Robolectric made the app; no MainActivity exists here.
        assertFalse("a process without MainActivity started the shell", app.container.shell.isStarted)
        assertFalse(app.container.shell.controller.launchCompleted.value)
    }
}

package de.corespace.shroud

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.notifications.NotificationTap
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.shell.LaunchIntent
import de.corespace.shroud.ui.shell.LocalWindowProtectionGuard
import de.corespace.shroud.ui.shell.RootScreen
import de.corespace.shroud.ui.shell.WindowControls
import de.corespace.shroud.ui.shell.WindowProtectionGuard
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.ThemeCrossfade
import de.corespace.shroud.ui.theme.ThemedSystemBars
import kotlinx.coroutines.launch

/**
 * The app's one activity, iOS `WindowGroup { RootView() }` (`ShroudApp.swift:8-12`; shell-chats
 * addendum *ShroudApp.swift*). Launched through the `.LauncherDetailed` / `.LauncherSimple` aliases
 * (settings-lock §8.4) and by App Links for `https://shroud.corespace.de/u/<code>` (contacts §5.10).
 * `singleTask`: links and notification taps arrive in [onNewIntent] while the app runs. The
 * manifest's `configChanges` keep rotation, folding and dark-mode switches from recreating it, as
 * SwiftUI never recreates `RootView`.
 *
 * Controllers live in the process-wide [AppContainer], never here: the shell's
 * `AppShellController` (W3-SHELL) is started by the first `MainActivity` of the process
 * (`ShellModule.startShell`, the launch iOS's `RootView` runs) and then runs process-wide, so an
 * activity recreation changes nothing about the lock, the session or the pushes. A process a push,
 * the boot receiver or `CallActivity` started never runs it. This activity only:
 *
 * - hosts [RootScreen] under the theme picked in Settings › Appearance, and keeps the system bar
 *   icons on the effective theme rather than the system's (`WindowColorTheme`, `RootView.swift:118-119`;
 *   shell-chats §3.12);
 * - applies the shell's window protection — Recents thumbnail and `FLAG_SECURE` per API level (P5;
 *   [WindowProtectionGuard]) — and listens to its screen-recording state on API 35+ while started
 *   (`ScreenCaptureMonitor`, shell-chats §3.9);
 * - hands a notification tap — only one that came through the non-exported `.NotificationTapEntry`
 *   alias, see [NotificationTap.ENTRY_ALIAS] — to `NotificationsController.handleTap` (its
 *   `pendingOpen` is what the shell opens once the chats are unlocked, notifications-push §5.7.5)
 *   and an invite link to `Contacts.pendingInvite` (the Add Contact prefill, P10c), then clears the
 *   intent so a recreation does not replay it.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as ShroudApplication).container
    private lateinit var protection: WindowProtectionGuard
    private var screenRecording: AutoCloseable? = null

    /**
     * Status and navigation bar icons follow the theme the app shows, not the system's (shell-chats
     * §3.12): a Dark choice on a light phone needs light icons, also after a rotation. The bars stay
     * edge to edge.
     */
    private val systemBars = ThemedSystemBars { container.auth.colorTheme.theme.value }

    override fun onCreate(savedInstanceState: Bundle?) {
        systemBars.apply(this)
        super.onCreate(savedInstanceState)
        val container = container
        // A recreated activity (process death) must not replay the tap or link that launched it.
        // Handed over before the shell starts: the launch sequence then drops a tap that cold-started
        // a signed-out app (`RootView.swift:169-170`) — the app scope runs it at once, up to its
        // first suspension, which on a signed-out phone is past that check.
        if (savedInstanceState == null) handleIntent(intent)
        // The shell starts with the first MainActivity, not with the process (`ShellModule.startShell`).
        val shell = container.shell.startShell()
        protection = WindowProtectionGuard(WindowControls.of(this))
        lifecycleScope.launch {
            shell.windowProtection.collect { protection.hold(WindowProtectionGuard.SHELL, it) }
        }
        setContent {
            val theme by container.auth.colorTheme.theme.collectAsState()
            val dark = when (theme) {
                ColorTheme.Light -> false
                ColorTheme.Dark -> true
                ColorTheme.System -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) { systemBars.apply(this@MainActivity) }
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalWindowProtectionGuard provides protection,
            ) {
                // A switch cross-fades the window over 0.3 s (`ColorThemePreference.swift:113-126`).
                ThemeCrossfade(dark, Modifier.fillMaxSize()) { shown ->
                    ShroudTheme(dark = shown) {
                        RootScreen(shell)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The platform wants the recording callback registered while the activity is visible (API 35+).
        screenRecording = container.shell.screenCapture.attach(this)
    }

    override fun onStop() {
        screenRecording?.close()
        screenRecording = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the newest link or notification tap is the one the shell handles.
        setIntent(intent)
        handleIntent(intent)
    }

    /** A notification tap (only through the `.NotificationTapEntry` alias) or an invite link ([LaunchIntent]). */
    private fun handleIntent(intent: Intent?) {
        when (val launch = LaunchIntent.of(intent)) {
            is LaunchIntent.Tap -> container.notifications.controller.handleTap(launch.tap)
            is LaunchIntent.Invite -> container.contacts.controller.pendingInvite.value = launch.url
            null -> return
        }
        setIntent(Intent())
    }
}

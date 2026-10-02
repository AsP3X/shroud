package de.corespace.shroud

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.notifications.NotificationTap
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.shell.LocalWindowProtectionGuard
import de.corespace.shroud.ui.shell.RootScreen
import de.corespace.shroud.ui.shell.WindowControls
import de.corespace.shroud.ui.shell.WindowProtectionGuard
import de.corespace.shroud.ui.theme.ShroudTheme
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
 * `AppShellController` (W3-SHELL) runs from process start, so an activity recreation (or none at
 * all) changes nothing about the lock, the session or the pushes. This activity only:
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
    private var systemBarsDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = container
        val shell = container.shell.controller
        protection = WindowProtectionGuard(WindowControls.of(this))
        lifecycleScope.launch {
            shell.windowProtection.collect { protection.hold(WindowProtectionGuard.SHELL, it) }
        }
        // A recreated activity (process death) must not replay the tap or link that launched it.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme by container.auth.colorTheme.theme.collectAsState()
            val dark = when (theme) {
                ColorTheme.Light -> false
                ColorTheme.Dark -> true
                ColorTheme.System -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) { applySystemBars(dark) }
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalWindowProtectionGuard provides protection,
            ) {
                ShroudTheme(dark = dark) {
                    RootScreen(shell)
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

    /**
     * Status and navigation bar icons follow the theme the app shows, not the system's (shell-chats
     * §3.12): a Dark choice on a light phone needs light icons. The bars stay edge to edge.
     */
    private fun applySystemBars(dark: Boolean) {
        if (systemBarsDark == dark) return
        systemBarsDark = dark
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { dark },
        )
    }

    private fun handleIntent(intent: Intent?) {
        val tap = NotificationTap.from(intent)
        val invite = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf(::isInviteLink)
        when {
            tap != null -> container.notifications.controller.handleTap(tap)
            invite != null -> container.contacts.controller.pendingInvite.value = invite.toString()
            else -> return
        }
        setIntent(Intent())
    }

    /** The App Links filter of the manifest: `https://shroud.corespace.de/u/…` (contacts §5.10). */
    private fun isInviteLink(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == INVITE_HOST && uri.path?.startsWith("/u/") == true

    private companion object {
        const val INVITE_HOST = "shroud.corespace.de"

        /** `enableEdgeToEdge`'s own scrims for three-button navigation (androidx.activity `EdgeToEdge.kt`). */
        val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

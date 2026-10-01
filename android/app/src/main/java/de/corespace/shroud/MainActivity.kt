package de.corespace.shroud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import de.corespace.shroud.core.notifications.NotificationTap
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.ShroudApp
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.launch

/**
 * The app's one activity, iOS `WindowGroup { RootView() }` (`ShroudApp.swift:8-12`). Launched
 * through the `.LauncherDetailed` / `.LauncherSimple` aliases (settings-lock §8.4) and by App
 * Links for `https://shroud.corespace.de/u/<code>` (contacts §5.10). `singleTask`: links and
 * notification taps arrive in [onNewIntent] while the app runs. [handleIntent] hands a notification
 * tap to `NotificationsController.handleTap` (its `pendingOpen` is what the shell opens once the
 * chats are unlocked) and an invite link to `Contacts.pendingInvite` (the Add Contact prefill,
 * P10c), then clears the intent so a recreation does not replay it (W2-INT). The manifest's
 * `configChanges` keep rotation, folding and dark-mode switches from recreating it, as SwiftUI
 * never recreates `RootView`.
 *
 * Controllers live in the process-wide [AppContainer], never in the activity; the container is
 * provided to the composition through [LocalAppContainer].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ShroudApplication).container
        // A recreated activity (process death) must not replay the tap or link that launched it.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ShroudTheme {
                    ShroudApp(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the newest link or notification tap is the one the shell handles.
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the permission meanwhile (`RootView.swift:268`, scene active).
        val container = (application as ShroudApplication).container
        container.appScope.launch { container.notifications.controller.refreshAuthorization() }
    }

    private fun handleIntent(intent: Intent?) {
        val container = (application as ShroudApplication).container
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
    }
}

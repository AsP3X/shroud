package de.corespace.shroud.ui.shell

import android.content.Intent
import android.net.Uri
import de.corespace.shroud.core.notifications.NotificationTap

/**
 * What an intent that reached `MainActivity` asks of the shell (K12; notifications-push §5.7.5,
 * contacts §5.10):
 *
 * - [Tap]: a notification tap — only one that came through the non-exported `.NotificationTapEntry`
 *   alias, which [NotificationTap.from] alone decides ([NotificationTap.ENTRY_ALIAS]). `MainActivity`
 *   is exported for App Links, so another app's intent with our action and extras is not a tap (W2
 *   review). The tap goes to `NotificationsController.handleTap`; the shell opens its `pendingOpen`
 *   once the chats are unlocked.
 * - [Invite]: an invite App Link `https://shroud.corespace.de/u/…` (the manifest's filter) — the Add
 *   Contact prefill (`contacts.controller.pendingInvite`, P10c), which never sends by itself.
 *
 * Anything else is null.
 */
sealed interface LaunchIntent {
    data class Tap(val tap: NotificationTap) : LaunchIntent

    data class Invite(val url: String) : LaunchIntent

    companion object {
        /** The App Links host of the manifest's filter (contacts §5.10). */
        const val INVITE_HOST = "shroud.corespace.de"

        fun of(intent: Intent?): LaunchIntent? {
            NotificationTap.from(intent)?.let { return Tap(it) }
            val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
            return if (isInviteLink(uri)) Invite(uri.toString()) else null
        }

        /** `https://shroud.corespace.de/u/…` only. */
        fun isInviteLink(uri: Uri): Boolean = uri.scheme == "https" && uri.host == INVITE_HOST && uri.path?.startsWith("/u/") == true
    }
}

package de.corespace.shroud.core.notifications

import android.content.Context
import android.content.Intent
import de.corespace.shroud.core.model.Ids
import java.util.UUID

/**
 * A tap on a system notification, as its intent carries it (notifications-push §5.7.5): the kind
 * and the peer id — **never a name** (the task's base intent can be persisted with Recents; the
 * username comes from local data once the chats are unlocked, shell-chats §4.6).
 *
 * `MainActivity.onCreate` / `onNewIntent` (W2-INT, then W3-SHELL) call [from] and hand the result
 * to `NotificationsController.handleTap`, then clear the intent (`setIntent(Intent())`) so a
 * recreation does not replay it.
 *
 * Taps arrive only through [ENTRY_ALIAS], a non-exported `activity-alias` of `MainActivity`: the
 * activity itself is exported for App Links, so any installed app could otherwise start it with
 * this action and a peer of its choosing, and Shroud would open that chat right after the unlock.
 * Our own immutable `PendingIntent`s run as our UID and may start the non-exported alias; [from]
 * accepts nothing else.
 *
 * @property kind null for a notification that only opens the app (the signed-out notice).
 */
data class NotificationTap(val kind: NotificationKind?, val peerUserId: UUID?) {
    companion object {
        const val ACTION_OPEN_NOTIFICATION = "de.corespace.shroud.OPEN_NOTIFICATION"
        const val EXTRA_KIND = "shroud.kind"
        const val EXTRA_PEER = "shroud.peer"

        /** The non-exported alias of `MainActivity` that notification taps start (`AndroidManifest.xml`). */
        const val ENTRY_ALIAS = "de.corespace.shroud.NotificationTapEntry"

        /**
         * The tap [intent] stands for, or null when it is not a notification tap — including an
         * intent with our action that did not come through [ENTRY_ALIAS] (another app's).
         */
        fun from(intent: Intent?): NotificationTap? {
            if (intent == null || intent.component?.className != ENTRY_ALIAS) return null
            return parse(intent.action, intent.getStringExtra(EXTRA_KIND), intent.getStringExtra(EXTRA_PEER))
        }

        /** [from] on the raw values. A malformed peer is dropped; an unknown kind reads as null. */
        fun parse(action: String?, kind: String?, peer: String?): NotificationTap? {
            if (action != ACTION_OPEN_NOTIFICATION) return null
            return NotificationTap(kind?.let(NotificationKind::fromWire), Ids.parse(peer))
        }

        /**
         * The intent a notification opens: `MainActivity` (`singleTask`) through [ENTRY_ALIAS], with
         * the tap's kind and peer as extras, brought to the front of its task.
         */
        fun intent(context: Context, kind: NotificationKind?, peerUserId: UUID?): Intent =
            Intent().setClassName(context, ENTRY_ALIAS)
                .setAction(ACTION_OPEN_NOTIFICATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    kind?.let { putExtra(EXTRA_KIND, it.wire) }
                    peerUserId?.let { putExtra(EXTRA_PEER, Ids.wire(it)) }
                }
    }
}

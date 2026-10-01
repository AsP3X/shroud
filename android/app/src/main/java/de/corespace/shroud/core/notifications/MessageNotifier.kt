package de.corespace.shroud.core.notifications

import java.util.UUID

/**
 * What messaging tells the notification layer, at the iOS call sites (notifications-push §6.2,
 * messaging-core §25.2). Implemented by `NotificationsController` (W2-NOTIF); published by W1-INT.
 *
 * Names and text reach it only while the user may see them (chats unlocked, names on); it never
 * logs either.
 */
interface MessageNotifier {
    /** The chat on screen: no banner and no system notification for it. */
    var activePeerId: UUID?

    /** In-app banner or system notification for one event; [muted] chats stay silent. */
    fun announce(kind: NotificationKind, peerUserId: UUID?, username: String?, conversationId: UUID?, text: String?, muted: Boolean)

    /** The chat was read here or elsewhere: its delivered notifications go. */
    fun clearDelivered(conversationId: UUID)

    /** Launcher badge (where the launcher supports one). */
    fun setBadge(count: Int)

    /** A push path (UnifiedPush or the background connection) covers the background, so local announcements stop there. */
    fun setPushCoversBackground(covers: Boolean)

    /** Settings › Notifications: the badge counts muted chats too. */
    val badgeIncludesMuted: Boolean
}

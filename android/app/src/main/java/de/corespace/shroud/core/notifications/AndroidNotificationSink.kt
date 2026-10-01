package de.corespace.shroud.core.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.corespace.shroud.R
import java.util.UUID

/**
 * [NotificationSink] on the platform (notifications-push §5.7.1).
 *
 * - Small icon: the monochrome veil (`ic_stat_shroud`), tinted with the accent.
 * - Visibility PRIVATE with a public version titled "Shroud" (same body, no avatar): the system's
 *   "hide sensitive content" lock-screen setting hides the name; Android's default shows it, as iOS.
 * - Large icon: the sender's gradient avatar, only when a name is shown ([largeIcon]).
 * - No conversation shortcuts and no `MessagingStyle` persons: Android persists those labels outside
 *   the vault (invariant 13, memory "No plaintext unless required").
 * - Every arrival alerts again (`setOnlyAlertOnce(false)`, the web's `renotify: true`).
 *
 * Taps open `MainActivity` through its non-exported alias ([NotificationTap.ENTRY_ALIAS]).
 *
 * @param largeIcon draws the 40 dp avatar for a name (`AvatarBitmap`), or null.
 */
class AndroidNotificationSink(
    private val context: Context,
    private val largeIcon: (name: String, peerUserId: UUID?) -> Bitmap?,
) : NotificationSink {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun areEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun post(spec: PostSpec) {
        val publicVersion = base(spec)
            .setContentTitle(SystemNotifier.APP_TITLE)
            .build()
        val builder = base(spec)
            .setContentTitle(spec.title)
            .setPublicVersion(publicVersion)
            .addExtras(android.os.Bundle().apply { putInt(SystemNotifier.EXTRA_COUNT, spec.count) })
        spec.name?.let { name -> largeIcon(name, spec.peerUserId)?.let(builder::setLargeIcon) }
        try {
            manager.notify(spec.tag, spec.id, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post (13+): nothing to show.
        }
    }

    override fun cancel(tag: String, id: Int) {
        manager.cancel(tag, id)
    }

    override fun cancelAll() {
        manager.cancelAll()
    }

    override fun activeCount(tag: String, id: Int): Int? {
        val shown = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id } ?: return null
        return shown.notification.extras.getInt(SystemNotifier.EXTRA_COUNT, 1).coerceAtLeast(1)
    }

    override fun activeKeys(): List<Pair<String, Int>> = manager.activeNotifications.mapNotNull { shown -> shown.tag?.let { it to shown.id } }

    private fun base(spec: PostSpec): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_shroud)
            .setColor(ACCENT)
            .setContentText(spec.body)
            .setCategory(spec.category)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(false)
            .setNumber(spec.number)
            .setContentIntent(contentIntent(spec))
        spec.timeoutMs?.let(builder::setTimeoutAfter)
        return builder
    }

    /** One request code per tag and id, so each notification keeps its own extras. */
    private fun contentIntent(spec: PostSpec): PendingIntent = PendingIntent.getActivity(
        context,
        "${spec.tag}#${spec.id}".hashCode(),
        NotificationTap.intent(context, spec.kind, spec.peerUserId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** The light accent (`Colors.kt` accent, design `$accent`). */
        const val ACCENT = 0xFF5E5CE6.toInt()
    }
}

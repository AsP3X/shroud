package de.corespace.shroud.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings

/**
 * One notification channel as this app creates it (notifications-push §5.6).
 *
 * @property sound null = silent (`setSound(null, null)`).
 */
data class ChannelSpec(
    val id: String,
    val name: String,
    val description: String?,
    val importance: Int,
    val sound: NotificationSound?,
    val badge: Boolean,
    val vibrate: Boolean,
)

/** The system's channel list (`NotificationManager`), behind a seam so the id rules run on the JVM. */
interface ChannelStore {
    /** Ids of every channel the app has now. */
    fun channelIds(): List<String>

    /** The channel's importance as the user left it, or null when there is no such channel. */
    fun importance(id: String): Int?

    /** Creates [spec] (a no-op for most properties of an existing id — the user owns them). */
    fun create(spec: ChannelSpec)

    /** Deletes the channel and with it every notification posted on it. */
    fun delete(id: String)
}

/**
 * The app's notification channels (Android only; notifications-push §5.6, decisions N8, N9).
 *
 * A channel's sound and badge belong to the user once it exists, so the preference's sound and
 * badge are part of the id and a change of either creates new channels and deletes the old ones
 * (Signal does the same; web-parity §7.3) — the in-app sound picker then keeps working like iOS's
 * (`NotificationsSettingsView.swift:505-507`). Deleting a channel cancels its notifications.
 *
 * | family | id | importance | sound, badge |
 * | --- | --- | --- | --- |
 * | Messages | `messages.<sound>.<b\|n>` | HIGH | from the preferences |
 * | Contact requests | `contact_requests.<sound>.<b\|n>` | HIGH | from the preferences |
 * | Missed calls | `calls.missed.<sound>.<b\|n>` | HIGH | from the preferences (N9); W3-CALLS-SYSTEM posts on it |
 * | Background connection | `push.background` | MIN | none (00-plan §1.7.10; W3-PUSH posts its permanent notification on it) |
 * | Background tasks | `system` | MIN, created on demand | none (the removal worker's foreground info below API 31, §5.17) |
 *
 * `calls.incoming` / `calls.ongoing` are W3-CALLS-SYSTEM's; [ensure] prunes only its own three
 * families by prefix, so it never deletes them (§5.18).
 *
 * The system channel page stays authoritative: a sound the user picks there wins for pushes; when a
 * new id replaces a channel whose importance the user lowered, the new one starts at that importance.
 */
class NotificationChannels(
    private val store: ChannelStore,
    private val preferences: () -> NotificationPrefsState,
) {
    /** The three families whose ids carry the sound and badge. */
    enum class Family(val prefix: String, val title: String, val description: String) {
        Messages("messages", "Messages", "New messages and reactions"),
        ContactRequests("contact_requests", "Contact requests", "People who want to add you"),
        MissedCalls("calls.missed", "Missed calls", "Calls you didn’t answer"),
    }

    /** Current id of the Messages channel (messages, reactions, the test, the local posts). */
    fun messages(): String = idFor(Family.Messages, preferences())

    /** Current id of the Contact requests channel. */
    fun contactRequests(): String = idFor(Family.ContactRequests, preferences())

    /** Current id of the Missed calls channel (W3-CALLS-SYSTEM posts missed calls on it, N9). */
    fun missedCalls(): String = idFor(Family.MissedCalls, preferences())

    /** The background connection's channel (W3-PUSH's permanent "Connected to receive messages"). */
    fun backgroundConnection(): String = BACKGROUND_CONNECTION

    /**
     * The low-importance channel of background work that must show something below API 31 (the
     * removal check's foreground info, notifications-push §5.17). Created here on first use.
     */
    fun backgroundTasks(): String {
        if (store.importance(BACKGROUND_TASKS) == null) store.create(backgroundTasksSpec())
        return BACKGROUND_TASKS
    }

    /**
     * Creates the current channels and deletes the stale ones of our three families
     * (notifications-push §5.6). Runs at process start, after a sound or badge change and after a
     * reset. Idempotent and cheap when nothing changed.
     */
    fun ensure() {
        val prefs = preferences()
        val existing = store.channelIds()
        for (family in Family.entries) {
            val id = idFor(family, prefs)
            if (id in existing) continue
            // A user who lowered (or turned off) the old channel keeps that choice on the new one.
            val lowered = existing.filter { familyOf(it) == family }.mapNotNull(store::importance).minOrNull()
            val importance = lowered?.coerceAtMost(NotificationManager.IMPORTANCE_HIGH) ?: NotificationManager.IMPORTANCE_HIGH
            store.create(familySpec(family, prefs, importance))
        }
        if (BACKGROUND_CONNECTION !in existing) store.create(backgroundConnectionSpec())
        for (id in staleIds(existing, prefs)) store.delete(id)
    }

    /**
     * The Messages channel is turned off in Android Settings (importance NONE) while the app itself
     * may notify (notifications-push §5.14.2, N14): the test notification would be swallowed.
     */
    fun messagesChannelBlocked(): Boolean = store.importance(messages()) == NotificationManager.IMPORTANCE_NONE

    /**
     * Deletes every channel of our three families (Log Out / removal, notifications-push §5.10.3);
     * the next [ensure] creates the defaults. The background channels stay: the push layer stops its
     * service first.
     */
    fun deleteFamilies() {
        for (id in store.channelIds()) if (familyOf(id) != null) store.delete(id)
    }

    private fun familySpec(family: Family, prefs: NotificationPrefsState, importance: Int) = ChannelSpec(
        id = idFor(family, prefs),
        name = family.title,
        description = family.description,
        importance = importance,
        sound = prefs.sound.takeIf { it != NotificationSound.None },
        badge = prefs.badge,
        vibrate = true,
    )

    companion object {
        const val BACKGROUND_CONNECTION = "push.background"
        const val BACKGROUND_TASKS = "system"

        /** The id of [family] for [prefs]: `<prefix>.<sound raw>.<b|n>` (`messages.default.b`). */
        fun idFor(family: Family, prefs: NotificationPrefsState): String =
            "${family.prefix}.${prefs.sound.raw}.${if (prefs.badge) "b" else "n"}"

        /** The family [id] belongs to, or null for channels that are not ours to prune (calls, background). */
        fun familyOf(id: String): Family? {
            for (family in Family.entries) {
                val rest = id.removePrefix(family.prefix + ".").takeIf { it != id } ?: continue
                val parts = rest.split('.')
                if (parts.size == 2 && parts[1] in setOf("b", "n") && NotificationSound.entries.any { it.raw == parts[0] }) return family
            }
            return null
        }

        /** Channels of our families in [existing] that are not the current ones for [prefs]. */
        fun staleIds(existing: List<String>, prefs: NotificationPrefsState): List<String> {
            val current = Family.entries.map { idFor(it, prefs) }.toSet()
            return existing.filter { familyOf(it) != null && it !in current }
        }

        fun backgroundConnectionSpec() = ChannelSpec(
            id = BACKGROUND_CONNECTION,
            name = "Background connection",
            description = "Shows while Shroud keeps the connection to your server open to receive messages and calls.",
            importance = NotificationManager.IMPORTANCE_MIN,
            sound = null,
            badge = false,
            vibrate = false,
        )

        fun backgroundTasksSpec() = ChannelSpec(
            id = BACKGROUND_TASKS,
            name = "Background tasks",
            description = null,
            importance = NotificationManager.IMPORTANCE_MIN,
            sound = null,
            badge = false,
            vibrate = false,
        )
    }
}

/** [ChannelStore] on the platform `NotificationManager` (API 26+; the app's minimum is 30). */
class AndroidChannelStore(private val context: Context) : ChannelStore {
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun channelIds(): List<String> = manager.notificationChannels.map { it.id }

    override fun importance(id: String): Int? = manager.getNotificationChannel(id)?.importance

    override fun create(spec: ChannelSpec) {
        val channel = NotificationChannel(spec.id, spec.name, spec.importance).apply {
            spec.description?.let { description = it }
            setShowBadge(spec.badge)
            enableVibration(spec.vibrate)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val uri = spec.sound?.let { soundUri(context, it) }
            if (uri == null) setSound(null, null) else setSound(uri, SOUND_ATTRIBUTES)
        }
        manager.createNotificationChannel(channel)
    }

    override fun delete(id: String) {
        manager.deleteNotificationChannel(id)
    }

    companion object {
        /** How notification sounds play (notifications-push §5.6). */
        val SOUND_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /**
         * The URI a channel plays for [sound]: the phone's notification sound for Default, the bundled
         * WAV by resource id for the tones (`android.resource://<package>/raw/<name>`, built from the
         * id so resource shrinking keeps the file, §5.5), null for None.
         */
        fun soundUri(context: Context, sound: NotificationSound): Uri? = when {
            sound == NotificationSound.None -> null
            sound.resId == null -> Settings.System.DEFAULT_NOTIFICATION_URI
            else -> Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName)
                .appendPath(context.resources.getResourceTypeName(sound.resId))
                .appendPath(context.resources.getResourceEntryName(sound.resId))
                .build()
        }
    }
}

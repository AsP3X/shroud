package de.corespace.shroud.core.notifications

import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.net.NotificationSettingsPatch
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every notification preference at once (one value for Compose and for the channel ids). */
data class NotificationPrefsState(
    val enabled: Boolean = true,
    val showSender: Boolean = true,
    val showPreview: Boolean = true,
    val reactions: Boolean = true,
    val contactRequests: Boolean = true,
    val sound: NotificationSound = NotificationSound.Standard,
    val inAppBanners: Boolean = true,
    val inAppSounds: Boolean = true,
    val inAppVibrate: Boolean = true,
    val badge: Boolean = true,
    val badgeIncludesMuted: Boolean = false,
)

/**
 * How this phone notifies — Settings › Notifications and Sounds (iOS `NotificationPreferences`,
 * `ios/shroud/Services/Notifications/NotificationPreferences.swift:12-111`; notifications-push §5.4,
 * settings-lock §6.2, web-parity §7.2).
 *
 * Per device, like the server keeps them. The fields the server acts on while the app is closed
 * (whether to push at all, names, reactions, requests, sound, badge) are mirrored to it
 * ([serverPatch], `:76-87`); the in-app ones only shape what the open app does. Stored in the
 * `shroud.notifications` file ([de.corespace.shroud.core.storage.PrefsFiles.NOTIFICATIONS]) under
 * the iOS key names — preferences, not secrets; the Log Out wipe clears them (00-plan §1.5).
 *
 * | property | key | default |
 * | --- | --- | --- |
 * | [enabled] | `notifications.enabled` | true |
 * | [showSender] | `notifications.showSender` | true |
 * | [showPreview] | `notifications.showPreview` | true (banners and the system notification; the text stays on this phone) |
 * | [reactions] | `notifications.reactions` | true |
 * | [contactRequests] | `notifications.contactRequests` | true |
 * | [sound] | `notifications.sound` | `default` (an unknown value reads as `default`, `:63`) |
 * | [inAppBanners] / [inAppSounds] / [inAppVibrate] | `notifications.inApp*` | true |
 * | [badge] | `notifications.badge` | true |
 * | [badgeIncludesMuted] | `notifications.badgeIncludesMuted` | false |
 *
 * An absent key reads as its default, a stored value wins (`:53-69`). Setters persist at once
 * (`:18-33, 71-74`), only when the value changes, and are dropped while [StorageSeal.isSealed] (a
 * wipe is running). [reset] stores nothing (`:89-110`). [state] follows the file: a `clear()` by
 * the wipe resets it to the defaults. Thread-safe.
 */
class NotificationPreferences(private val prefs: SharedPreferences, private val seal: StorageSeal) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(read())

    /** Held strongly: the platform keeps preference listeners in a weak map. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(PREFIX)) reload()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val state: StateFlow<NotificationPrefsState> = mutableState.asStateFlow()

    /** Pushes to this phone at all (the server's `enabled`, `:17-18`). */
    var enabled: Boolean
        get() = state.value.enabled
        set(value) = write(KEY_ENABLED, value) { copy(enabled = value) }

    /** Name the sender. Off, a notification only says that something arrived (`:19-20`). */
    var showSender: Boolean
        get() = state.value.showSender
        set(value) = write(KEY_SHOW_SENDER, value) { copy(showSender = value) }

    /** The decrypted message text in banners and system notifications. The server is not sent the text. */
    var showPreview: Boolean
        get() = state.value.showPreview
        set(value) = write(KEY_SHOW_PREVIEW, value) { copy(showPreview = value) }

    var reactions: Boolean
        get() = state.value.reactions
        set(value) = write(KEY_REACTIONS, value) { copy(reactions = value) }

    var contactRequests: Boolean
        get() = state.value.contactRequests
        set(value) = write(KEY_CONTACT_REQUESTS, value) { copy(contactRequests = value) }

    /** The sound of notifications (their channel's sound, [NotificationChannels]) and of in-app alerts. */
    var sound: NotificationSound
        get() = state.value.sound
        set(value) = write(KEY_SOUND, value.raw) { copy(sound = value) }

    /** A banner at the top of the screen when a message arrives in another chat (`:27-28`). */
    var inAppBanners: Boolean
        get() = state.value.inAppBanners
        set(value) = write(KEY_IN_APP_BANNERS, value) { copy(inAppBanners = value) }

    var inAppSounds: Boolean
        get() = state.value.inAppSounds
        set(value) = write(KEY_IN_APP_SOUNDS, value) { copy(inAppSounds = value) }

    var inAppVibrate: Boolean
        get() = state.value.inAppVibrate
        set(value) = write(KEY_IN_APP_VIBRATE, value) { copy(inAppVibrate = value) }

    /** The unread count on notifications (launcher dots/numbers, notifications-push §5.7.3; iOS the app icon, `:31-32`). */
    var badge: Boolean
        get() = state.value.badge
        set(value) = write(KEY_BADGE, value) { copy(badge = value) }

    var badgeIncludesMuted: Boolean
        get() = state.value.badgeIncludesMuted
        set(value) = write(KEY_BADGE_INCLUDES_MUTED, value) { copy(badgeIncludesMuted = value) }

    /**
     * What the server needs to decide this phone's pushes: the seven server fields (`serverPatch`,
     * `:76-87`). Android rule (notifications-push §5.10.4): the server's `enabled` is the preference
     * **and** [systemAllows] (`NotificationManagerCompat.areNotificationsEnabled()`), so a phone that
     * blocks Shroud stops getting pushes it would drop; the stored preference is not changed.
     */
    fun serverPatch(systemAllows: Boolean = true): NotificationSettingsPatch {
        val s = state.value
        return NotificationSettingsPatch(
            enabled = s.enabled && systemAllows,
            showSender = s.showSender,
            reactions = s.reactions,
            contactRequests = s.contactRequests,
            sound = s.sound.serverName,
            badge = s.badge,
            badgeIncludesMuted = s.badgeIncludesMuted,
        )
    }

    /**
     * Back to how a fresh install notifies, which stores nothing, so the wipe's verify finds no
     * `notifications.*` key (`reset()`, `:89-110`; `NotificationPayloadTests.swift:131-140`). Later
     * changes persist again. Removing is the wipe's own direction, so it is not held back by the seal.
     */
    fun reset() {
        synchronized(lock) {
            prefs.edit {
                for (key in ALL_KEYS) remove(key)
            }
            mutableState.value = NotificationPrefsState()
        }
    }

    /** Re-reads every value from the file (after the wipe cleared it). */
    fun reload() {
        synchronized(lock) {
            mutableState.value = read()
        }
    }

    private inline fun write(key: String, value: Any, crossinline transform: NotificationPrefsState.() -> NotificationPrefsState) {
        synchronized(lock) {
            if (seal.isSealed) return
            val next = mutableState.value.transform()
            if (next == mutableState.value) return
            prefs.edit {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> error("unsupported preference type")
                }
            }
            mutableState.value = next
        }
    }

    private fun read(): NotificationPrefsState {
        fun flag(key: String, fallback: Boolean): Boolean = if (prefs.contains(key)) prefs.getBoolean(key, fallback) else fallback
        val defaults = NotificationPrefsState()
        return NotificationPrefsState(
            enabled = flag(KEY_ENABLED, defaults.enabled),
            showSender = flag(KEY_SHOW_SENDER, defaults.showSender),
            showPreview = flag(KEY_SHOW_PREVIEW, defaults.showPreview),
            reactions = flag(KEY_REACTIONS, defaults.reactions),
            contactRequests = flag(KEY_CONTACT_REQUESTS, defaults.contactRequests),
            sound = NotificationSound.fromRaw(prefs.getString(KEY_SOUND, null)),
            inAppBanners = flag(KEY_IN_APP_BANNERS, defaults.inAppBanners),
            inAppSounds = flag(KEY_IN_APP_SOUNDS, defaults.inAppSounds),
            inAppVibrate = flag(KEY_IN_APP_VIBRATE, defaults.inAppVibrate),
            badge = flag(KEY_BADGE, defaults.badge),
            badgeIncludesMuted = flag(KEY_BADGE_INCLUDES_MUTED, defaults.badgeIncludesMuted),
        )
    }

    companion object {
        /** Every key of this class starts with it (the wipe's leftovers check looks for it). */
        const val PREFIX = "notifications."

        const val KEY_ENABLED = "notifications.enabled"
        const val KEY_SHOW_SENDER = "notifications.showSender"
        const val KEY_SHOW_PREVIEW = "notifications.showPreview"
        const val KEY_REACTIONS = "notifications.reactions"
        const val KEY_CONTACT_REQUESTS = "notifications.contactRequests"
        const val KEY_SOUND = "notifications.sound"
        const val KEY_IN_APP_BANNERS = "notifications.inAppBanners"
        const val KEY_IN_APP_SOUNDS = "notifications.inAppSounds"
        const val KEY_IN_APP_VIBRATE = "notifications.inAppVibrate"
        const val KEY_BADGE = "notifications.badge"
        const val KEY_BADGE_INCLUDES_MUTED = "notifications.badgeIncludesMuted"

        /** The eleven keys, in iOS order (`:35-47`). */
        val ALL_KEYS: List<String> = listOf(
            KEY_ENABLED, KEY_SHOW_SENDER, KEY_SHOW_PREVIEW, KEY_REACTIONS, KEY_CONTACT_REQUESTS, KEY_SOUND,
            KEY_IN_APP_BANNERS, KEY_IN_APP_SOUNDS, KEY_IN_APP_VIBRATE, KEY_BADGE, KEY_BADGE_INCLUDES_MUTED,
        )
    }
}

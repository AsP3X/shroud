package de.corespace.shroud.ui.settings.notifications

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.notifications.NotificationAuthorization
import de.corespace.shroud.core.notifications.NotificationPreferences
import de.corespace.shroud.core.notifications.NotificationPrefsState
import de.corespace.shroud.core.notifications.NotificationSound
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The switches of Notifications and Sounds (`NotificationsSettingsView.swift:144-228`): which
 * preference each one is, whether the server acts on it while the app is closed ([server], saved
 * there debounced), and whether it changes the badge count ([badge]).
 */
enum class NotificationSwitch(val server: Boolean, val badge: Boolean = false) {
    Enabled(server = true),
    ShowSender(server = true),
    Reactions(server = true),
    ContactRequests(server = true),
    InAppBanners(server = false),
    ShowPreview(server = false),
    InAppSounds(server = false),
    InAppVibrate(server = false),
    Badge(server = true, badge = true),
    BadgeIncludesMuted(server = true, badge = true),
    ;

    fun value(state: NotificationPrefsState): Boolean = when (this) {
        Enabled -> state.enabled
        ShowSender -> state.showSender
        Reactions -> state.reactions
        ContactRequests -> state.contactRequests
        InAppBanners -> state.inAppBanners
        ShowPreview -> state.showPreview
        InAppSounds -> state.inAppSounds
        InAppVibrate -> state.inAppVibrate
        Badge -> state.badge
        BadgeIncludesMuted -> state.badgeIncludesMuted
    }

    fun write(preferences: NotificationPreferences, value: Boolean) {
        when (this) {
            Enabled -> preferences.enabled = value
            ShowSender -> preferences.showSender = value
            Reactions -> preferences.reactions = value
            ContactRequests -> preferences.contactRequests = value
            InAppBanners -> preferences.inAppBanners = value
            ShowPreview -> preferences.showPreview = value
            InAppSounds -> preferences.inAppSounds = value
            InAppVibrate -> preferences.inAppVibrate = value
            Badge -> preferences.badge = value
            BadgeIncludesMuted -> preferences.badgeIncludesMuted = value
        }
    }
}

/**
 * The one card above the switches that says why notifications cannot reach the user (iOS
 * permission card, `NotificationsSettingsView.swift:84-140`; Android system blocks,
 * notifications-push §5.14.2, N14). One at a time, in this order; the switches below keep their
 * saved values in every case. "Notifications can't reach this phone" (no delivery path) is the
 * Delivery section's (W3-PUSH) at the top of the screen, not a card here.
 */
enum class SystemNotice {
    /** Android 13+ and the permission not asked yet: "Allow notifications". */
    AllowNotifications,

    /** Android keeps Shroud's notifications away: "Notifications are off for Shroud". */
    AppBlocked,

    /** The app may notify, but its Messages channel is turned off. */
    MessagesChannelOff,

    /** The user restricted Shroud in the background (§5.21): notifications may arrive late. */
    BackgroundRestricted,
    ;

    companion object {
        fun of(authorization: NotificationAuthorization, messagesChannelBlocked: Boolean, backgroundRestricted: Boolean): SystemNotice? = when {
            authorization == NotificationAuthorization.Denied -> AppBlocked
            authorization == NotificationAuthorization.NotDetermined -> AllowNotifications
            messagesChannelBlocked -> MessagesChannelOff
            backgroundRestricted -> BackgroundRestricted
            else -> null
        }
    }
}

/**
 * The words of Notifications and Sounds and the Sound picker (iOS `NotificationsSettingsView.swift`;
 * settings-lock §6, notifications-push §5.14–5.15) with the Android wording [A]: "this phone" /
 * "this tablet", Android Settings, the notification shade, and no Apple or Google — pushes come
 * through UnifiedPush or the background connection (decision record 2026-10-01).
 */
object NotificationsCopy {
    const val TITLE = "Notifications and Sounds"
    const val SOUND_TITLE = "Sound"

    const val ALLOW_TITLE = "Allow notifications"
    const val ALLOW_BODY = "So you hear about new messages while Shroud is closed."
    const val ALLOW_BUTTON = "Allow"
    const val BLOCKED_TITLE = "Notifications are off for Shroud"
    const val BLOCKED_BODY = "Turn them on in Android Settings › Apps › Shroud › Notifications."
    const val CHANNEL_OFF_TITLE = "Message notifications are off"
    const val CHANNEL_OFF_BODY = "Android is set to hide Shroud's message notifications. Turn them back on in Settings."
    const val RESTRICTED_TITLE = "Notifications may arrive late"
    const val RESTRICTED_BODY = "Android is restricting Shroud in the background. Allow background use so messages and calls arrive on time."
    const val OPEN_SETTINGS = "Open Settings"

    const val SHOW_NOTIFICATIONS = "Show Notifications"
    const val SHOW_SENDER = "Show Sender"
    const val SHOW_SENDER_DETAIL = "Off, a notification only says that something arrived."
    const val SOUND = "Sound"
    const val ALSO_NOTIFY = "Also Notify Me About"
    const val REACTIONS = "Reactions to My Messages"
    const val CONTACT_REQUESTS = "Contact Requests"
    const val IN_APP = "In-App Notifications"
    const val BANNERS = "Banners"
    const val MESSAGE_PREVIEW = "Message Preview"
    const val MESSAGE_PREVIEW_DETAIL = "Show the text in banners."
    const val SOUNDS = "Sounds"
    const val VIBRATE = "Vibrate"
    const val IN_APP_FOOTER =
        "While Shroud is open, a banner, sound or vibration tells you about messages in other chats. Banners can show the text: they never reach the notification shade."
    const val BADGE_COUNTER = "Badge Counter"
    const val SHOW_BADGE = "Show Badge"
    const val SHOW_BADGE_DETAIL = "Unread messages on the app icon."
    const val INCLUDE_MUTED = "Include Muted Chats"
    const val MUTED_CHATS = "Muted Chats"
    const val NO_MUTED_CHATS = "No muted chats. Long-press a chat, or open its profile, to mute it."
    const val MUTED = "Muted"
    const val UNMUTE = "Unmute"
    const val SEND_TEST = "Send a Test Notification"
    const val SENDING = "Sending…"
    const val RESET = "Reset Notification Settings"
    const val RESET_TITLE = "Reset notification settings?"
    const val RESET_MESSAGE = "Every switch on this screen goes back to how a new install notifies. Muted chats stay muted."
    const val RESET_BUTTON = "Reset"
    const val RESET_DONE = "Notification settings reset"
    const val SOUND_DIFFERS = "Android Settings has a different sound for message notifications."

    /** [A] "New messages on this phone while Shroud is closed or locked." (`:148`). */
    fun showNotificationsDetail(noun: String): String = "New messages on this $noun while Shroud is closed or locked."

    /**
     * The footer under the first card (`:37-39`). The first sentence holds for every path; the
     * second only for a UnifiedPush distributor, whose relay would otherwise be the one party that
     * could read a name (notifications-push §5.14): the background connection has no relay, and
     * without any path no push arrives at all.
     */
    fun pushFooter(noun: String, unifiedPush: Boolean): String {
        val first = "Notifications that arrive while Shroud is closed or locked never contain message text — the server can't read it."
        return if (unifiedPush) {
            "$first With Show Sender on, the sender's name travels encrypted to this $noun, so your UnifiedPush service can't read it either."
        } else {
            first
        }
    }

    /** "Sound, Chime" for TalkBack (`:179`). */
    fun soundLabel(sound: NotificationSound): String = "Sound, ${sound.title}"

    /** TalkBack on a muted chat's button. */
    fun unmuteLabel(username: String): String = "Unmute $username"

    /** [A] The info toast when the server did not take a change (`:386`), 2.4 s. */
    fun savedLocally(noun: String): String = "Saved on this $noun — the server gets it next time"

    /** [A] The Sound picker's footer (`:486`). */
    fun soundFooter(noun: String): String =
        "Plays for notifications, and for banners while Shroud is open (unless the $noun is on silent)."
}

/** What the screen shows besides the preferences: the test row and the unmutes in flight. */
data class NotificationsSettingsState(
    val testResult: String? = null,
    val isTesting: Boolean = false,
    val unmuting: Set<UUID> = emptySet(),
)

/**
 * Notifications and Sounds' actions (iOS `NotificationsSettingsView`,
 * `ios/shroud/Features/Main/NotificationsSettingsView.swift:13-458`; settings-lock §6.4,
 * notifications-push §5.14): the switch binding, the debounced server save, the test notification,
 * the reset and unmuting a chat. The preferences themselves are W2-NOTIF's [preferences]; the
 * controller recreates the channels when the sound or the badge changes.
 *
 * Main-confined ([scope] is the screen's). [noun] is "phone" or "tablet".
 */
class NotificationsSettingsModel(
    private val preferences: NotificationPreferences,
    private val pushSettings: suspend (token: String) -> Unit,
    private val sendTest: suspend (token: String) -> String,
    private val token: () -> String?,
    private val updateBadge: () -> Unit,
    private val unmuteChat: suspend (peer: UUID) -> String?,
    private val noun: String,
    private val scope: CoroutineScope,
    private val haptic: (Haptic) -> Unit = {},
    private val toast: (Toast) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(NotificationsSettingsState())
    val state: StateFlow<NotificationsSettingsState> = mutableState.asStateFlow()

    private var saveJob: Job? = null

    /**
     * A switch flipped (`binding`, `:391-402`): an unchanged value does nothing; else write it, a
     * light haptic, recount the badge for the badge switches, save the server's ones.
     */
    fun set(switch: NotificationSwitch, value: Boolean) {
        if (switch.value(preferences.state.value) == value) return
        switch.write(preferences, value)
        haptic(Haptic.Light)
        if (switch.badge) updateBadge()
        if (switch.server) saveToServer()
    }

    /**
     * The server acts on some settings while the app is closed: saved there 500 ms after the last
     * change, so a burst of flips sends one request (`saveToServer`, `:375-389`). A failure says the
     * change stays on this phone (info toast, 2.4 s: a long sentence).
     */
    fun saveToServer() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val bearer = token() ?: return@launch
            try {
                pushSettings(bearer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(Toast.info(NotificationsCopy.savedLocally(noun), Toast.FAILURE_MS))
            }
        }
    }

    /**
     * Sends the test notification (`sendTest`, `:363-373`): the settings first — errors ignored —
     * so the test goes out with what was just picked, then the result sentence under the row.
     */
    fun sendTestNotification() {
        val bearer = token() ?: return
        if (mutableState.value.isTesting) return
        mutableState.update { it.copy(isTesting = true, testResult = null) }
        scope.launch {
            try {
                try {
                    pushSettings(bearer)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The test still says what happened.
                }
                val result = sendTest(bearer)
                mutableState.update { it.copy(testResult = result) }
            } finally {
                mutableState.update { it.copy(isTesting = false) }
            }
        }
    }

    /**
     * Every switch back to a fresh install's (`:68-75`): the badge may have changed, the server
     * hears it, "Notification settings reset" and a success haptic. Muted chats stay muted.
     */
    fun reset() {
        preferences.reset()
        updateBadge()
        saveToServer()
        toast(Toast.success(NotificationsCopy.RESET_DONE))
        haptic(Haptic.Success)
    }

    /** Unmutes [peer] (`unmute`, `:349-361`): an error is a failure toast and an error haptic, else a light haptic. */
    fun unmute(peer: UUID) {
        if (peer in mutableState.value.unmuting) return
        mutableState.update { it.copy(unmuting = it.unmuting + peer) }
        scope.launch {
            val error = try {
                unmuteChat(peer)
            } finally {
                mutableState.update { it.copy(unmuting = it.unmuting - peer) }
            }
            if (error != null) {
                toast(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                haptic(Haptic.Light)
            }
        }
    }

    companion object {
        /** `Task.sleep(for: .milliseconds(500))` (`:380`, `:533`). */
        const val SAVE_DEBOUNCE_MS = 500L
    }
}

/**
 * The Sound picker's action (iOS `NotificationSoundPicker`, `NotificationsSettingsView.swift:460-538`;
 * notifications-push §5.15): the choice is stored (the controller recreates the channels with the
 * new sound, N8), played once, and saved to the server 500 ms later — errors ignored.
 */
class NotificationSoundModel(
    private val preferences: NotificationPreferences,
    private val play: (NotificationSound) -> Unit,
    private val pushSettings: suspend (token: String) -> Unit,
    private val token: () -> String?,
    private val scope: CoroutineScope,
) {
    private var saveJob: Job? = null

    fun pick(sound: NotificationSound) {
        preferences.sound = sound
        play(sound)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(NotificationsSettingsModel.SAVE_DEBOUNCE_MS)
            val bearer = token() ?: return@launch
            try {
                pushSettings(bearer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next change or the next start sends it again.
            }
        }
    }
}

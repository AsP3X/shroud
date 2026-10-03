package de.corespace.shroud.ui.settings.privacy

import android.os.Build
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The server-backed switches of Privacy and Security (iOS `VisibilitySwitch`,
 * `PrivacySecurityView.swift:645-682`, plus "Find me by username", `:470-509`, and "Let contacts
 * clear chats for me", `:581-624`; settings-lock §7.5–7.7). Only the server can enforce them, so the
 * switch shows the server's value — or, while a write runs, where the user flipped it.
 */
enum class PrivacySwitch(val title: String, val detail: String) {
    ReadReceipts("Read receipts", "Contacts see when you've read their messages."),
    Typing("Typing indicators", "Contacts see when you're typing or recording a voice message."),
    Presence("Online and last seen", "Contacts see when you're online and when you were last here."),
    Discoverable(
        "Find me by username",
        "People who know your username can find you and send a request. Off, they need your QR code or share code; your contacts can still find you.",
    ),
    ChatDelete(
        "Let contacts clear chats for me",
        "When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way. You stay contacts.",
    ),
    ;

    fun value(settings: PrivacySettingsDto): Boolean = when (this) {
        ReadReceipts -> settings.sendReadReceipts
        Typing -> settings.sendTyping
        Presence -> settings.sharePresence
        Discoverable -> settings.discoverableByUsername
        ChatDelete -> settings.allowPeerChatDelete
    }

    /** The partial `PUT /privacy/settings` body for [value]: only this switch goes on the wire. */
    fun change(value: Boolean): UpdatePrivacySettingsBody = when (this) {
        ReadReceipts -> UpdatePrivacySettingsBody(sendReadReceipts = value)
        Typing -> UpdatePrivacySettingsBody(sendTyping = value)
        Presence -> UpdatePrivacySettingsBody(sharePresence = value)
        Discoverable -> UpdatePrivacySettingsBody(discoverableByUsername = value)
        ChatDelete -> UpdatePrivacySettingsBody(allowPeerChatDelete = value)
    }

    companion object {
        /** The Visibility card's three, in iOS order (`:646-649`). */
        val visibility: List<PrivacySwitch> = listOf(ReadReceipts, Typing, Presence)
    }
}

/**
 * The words of Privacy and Security (iOS `PrivacySecurityView.swift`; settings-lock §7) with the
 * Android wording [A]: "this phone", the screen lock and the enrolled biometric, the Android
 * Keystore, and the device-protection switch of P5 (no "Only Apple keyboards" on Android).
 */
object PrivacyCopy {
    const val TITLE = "Privacy and Security"
    const val AUTO_LOCK = "Auto-lock"
    const val HIDE_CAPTURE = "Hide chats during screen recording"
    const val LOAD_FAILED = "Couldn't load these settings."
    const val TRY_AGAIN = "Try Again"
    const val LOADING = "Loading your privacy settings…"
    const val VISIBILITY = "Visibility"
    const val VISIBILITY_FOOTNOTE = "These work both ways: when you hide yours, you won't see your contacts' either."
    const val FINDING_YOU =
        "People add you with your QR code or share code. Your username is shared only with people you have both added, and the server never sees it."
    const val RESET_QR = "Reset QR code"
    const val RESET_QR_DETAIL = "Makes a new QR code and invite link. The old ones stop working."
    const val RESET_QR_TITLE = "Reset your QR code?"
    const val RESET_QR_MESSAGE =
        "Your current QR code and invite link stop working. Anyone who wants to add you will need the new one. Your contacts aren't affected."
    const val RESET = "Reset"
    const val NEW_QR_READY = "New QR code ready"
    const val LINK_PREVIEWS = "Link previews"
    const val RELAY_CALLS = "Always relay calls"
    const val LOCK_NOW = "Lock chats now"
    const val LOCK_NOW_DETAIL = "Clears messages from memory until you unlock again."
    const val CHATS_LOCKED = "Chats locked"
    const val BLOCKED = "Blocked"
    const val UNBLOCK = "Unblock"
    const val ENCRYPTED_TITLE = "Encrypted on this device"
    const val ENCRYPTED_BODY =
        "Chat history is sealed with a key from your encryption phrase. That key is not kept in plain storage — the Android Keystore wraps it and only unwraps it after you authenticate with biometrics, screen lock, or your 12-word phrase."

    /** Android 15 (API 35) has a screen-recording callback: the shell covers the chats with its logo (P5). */
    const val RECORDING_CALLBACK_API = 35

    /** Android 13 (API 33) can keep a window out of Recents without `FLAG_SECURE` (P5). */
    const val RECENTS_SCREENSHOT_API = 33

    /**
     * [A] The Auto-lock footnote (`PrivacySecurityView.swift:307-312`): names the strong biometric
     * the phone has, like the lock screen; without one, the screen lock and the phrase.
     */
    fun autoLockFootnote(biometric: BiometricLabel?): String {
        val ways = biometric?.let { "your ${it.word}, screen lock," } ?: "your screen lock"
        return "When you leave the app, decrypted messages are cleared from memory — right away, or once the time you pick has passed. Re-open with $ways or your encryption phrase."
    }

    /**
     * [A] The device-protection switch's subtitle per API level (settings-lock §7.4; P5, the shell's
     * `WindowProtection` table), saying what the switch really does there (iOS
     * `PrivacySecurityView.swift:323` says "recorded, mirrored or shared"):
     *
     * - API 35+: the recording callback lets the shell cover the chats with the logo; screenshots
     *   stay allowed, as on iOS.
     * - API 33–34: no recording signal, so the switch is `FLAG_SECURE`: captures show black and
     *   screenshots are blocked too.
     * - API 30–32: `FLAG_SECURE` is on whenever the chats are unlocked (the only way to keep them out
     *   of Recents), so captures and screenshots are blocked whatever the switch says.
     */
    fun hideCaptureDetail(sdk: Int = Build.VERSION.SDK_INT): String = when {
        sdk >= RECORDING_CALLBACK_API -> "While the screen is recorded, cast or shared, Shroud shows only its logo."
        sdk >= RECENTS_SCREENSHOT_API ->
            "While the screen is recorded, cast or shared, your chats show black. On this Android version, screenshots of your chats are blocked too."
        else ->
            "Your chats show black while the screen is recorded, cast or shared, and screenshots of them are blocked. On this Android version, that stays on even with this switch off, to keep your chats out of the recent apps screen."
    }

    /** [A] (`PrivacySecurityView.swift:447-449`). */
    fun linkPreviewsDetail(noun: String): String =
        "When you send a link, this $noun loads the page to build a preview and seals it into the message. The website sees your IP address, as if you had opened the link. People you send it to never contact the website."

    /** [A] (`PrivacySecurityView.swift:564-566`). */
    fun relayCallsDetail(noun: String): String =
        "Calls from this $noun go through the Shroud server's relay, so the person you call never sees your IP address. Calls may lag slightly. If the server has no relay, calls won't connect until you turn this off."

    /**
     * P3c: the vault's wrap key landed in a Keystore without secure hardware — allowed, and said
     * once here (and on the lock screen, W3-LOCK-ONBOARD). Wording pending W3-DESIGN's frame.
     */
    fun softwareKeystoreNotice(noun: String): String =
        "This $noun's Android Keystore has no secure hardware, so it protects that key in software only."

    fun unblockLabel(username: String): String = "Unblock $username"

    fun unblocked(username: String): String = "$username unblocked"
}

/** The in-flight state of Privacy and Security's server switches and actions. */
data class PrivacyState(
    /** Where a server switch was flipped to while its write runs (`:27-32`). */
    val pending: Map<PrivacySwitch, Boolean> = emptyMap(),
    val saving: Set<PrivacySwitch> = emptySet(),
    /** The last load of the server switches failed; they stay disabled until a retry works (`:33-34`). */
    val loadFailed: Boolean = false,
    val resettingShareCode: Boolean = false,
    val unblocking: Set<UUID> = emptySet(),
) {
    /** The switch as shown: the flip while it saves, else the server's value (`:399`). */
    fun displayed(switch: PrivacySwitch, settings: PrivacySettingsDto): Boolean = pending[switch] ?: switch.value(settings)

    /** Off while its write runs and until the server's values arrived (`:430`, `:506`, `:621`). */
    fun isEnabled(switch: PrivacySwitch, hasLoaded: Boolean): Boolean = hasLoaded && switch !in saving
}

/**
 * Privacy and Security's actions (iOS `PrivacySecurityView`,
 * `ios/shroud/Features/Main/PrivacySecurityView.swift:4-643`; settings-lock §7).
 *
 * The server switches follow one pattern (`:397-433`): a flip to the value the server already has
 * does nothing; else it shows where the user flipped it, writes only that field, and when the
 * request ends the server's value takes over — which reverts the switch if the write failed (a
 * failure toast and an error haptic; success a light haptic). The local switches write
 * [security] at once with a light haptic.
 *
 * Main-confined like the iOS view: every member runs on the main thread. [scope] is the screen's
 * (the loads); the server writes run on [actionScope], so one keeps going when the screen closes
 * meanwhile (an iOS `Task` started in a binding is not cancelled with the view) and a switch never
 * ends up out of step with what the server stored.
 *
 * @param refreshBlocks `Contacts.refreshBlocks` (silent on failure).
 * @param unblockUser `Contacts.unblock`: null on success, else the error.
 * @param lockChatsNow the shell's `AppActions.lockChatsNow` (messaging memory, the socket, the key).
 */
class PrivacySecurityModel(
    private val privacy: Privacy,
    private val security: SecurityPreferences,
    private val refreshBlocks: suspend () -> Unit,
    private val unblockUser: suspend (UUID) -> String?,
    private val lockChatsNow: () -> Unit,
    private val scope: CoroutineScope,
    private val actionScope: CoroutineScope = scope,
    private val haptic: (Haptic) -> Unit = {},
    private val toast: (Toast) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(PrivacyState())
    val state: StateFlow<PrivacyState> = mutableState.asStateFlow()

    /**
     * Loads the server switches and the blocked list (`loadServerSettings`, `:160-168`). Both
     * refreshes are silent on failure — an unreachable server never flips a consent switch — so
     * whether the values arrived is read from [Privacy.hasLoaded].
     */
    suspend fun loadServerSettings() {
        mutableState.update { it.copy(loadFailed = false) }
        privacy.refresh()
        mutableState.update { it.copy(loadFailed = !privacy.hasLoaded.value) }
        refreshBlocks()
    }

    /** "Try Again" under a failed load (`:184-186`), on [scope]: the line leaving does not cancel it. */
    fun retry() {
        scope.launch { loadServerSettings() }
    }

    /** A server switch flipped to [value] (`:397-433`, `:472-493`, `:587-606`). */
    fun flip(switch: PrivacySwitch, value: Boolean) {
        val current = mutableState.value
        if (switch in current.saving) return
        if (value == switch.value(privacy.settings.value)) return
        mutableState.update { it.copy(pending = it.pending + (switch to value), saving = it.saving + switch) }
        actionScope.launch {
            val error = try {
                if (switch == PrivacySwitch.ChatDelete) privacy.setAllowsPeerChatDelete(value) else privacy.update(switch.change(value))
            } finally {
                mutableState.update { it.copy(pending = it.pending - switch, saving = it.saving - switch) }
            }
            if (error != null) {
                toast(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                haptic(Haptic.Light)
            }
        }
    }

    /**
     * Retires the QR code and invite link (`resetShareCode`, `:537-550`): "New QR code ready" and a
     * success haptic, or the reason as a failure toast and an error haptic.
     */
    fun resetShareCode() {
        if (mutableState.value.resettingShareCode) return
        mutableState.update { it.copy(resettingShareCode = true) }
        actionScope.launch {
            val error = try {
                privacy.rotateShareCode()
            } finally {
                mutableState.update { it.copy(resettingShareCode = false) }
            }
            if (error != null) {
                toast(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                toast(Toast.success(PrivacyCopy.NEW_QR_READY))
                haptic(Haptic.Success)
            }
        }
    }

    /** Lifts a block (`unblock`, `:257-270`); the contact is not restored. */
    fun unblock(blocked: BlockItemDto) {
        if (blocked.userId in mutableState.value.unblocking) return
        mutableState.update { it.copy(unblocking = it.unblocking + blocked.userId) }
        actionScope.launch {
            val error = try {
                unblockUser(blocked.userId)
            } finally {
                mutableState.update { it.copy(unblocking = it.unblocking - blocked.userId) }
            }
            if (error != null) {
                toast(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                toast(Toast.success(PrivacyCopy.unblocked(blocked.username)))
                haptic(Haptic.Light)
            }
        }
    }

    /** The Auto-lock picker (`:301-304`). */
    fun setAutoLockDelay(delay: AutoLockDelay) = local(security.autoLockDelay.value != delay) { security.setAutoLockDelay(delay) }

    /** "Hide chats during screen recording" (`:332-335`); the shell applies it (P5). */
    fun setHidesDuringScreenCapture(value: Boolean) =
        local(security.hidesDuringScreenCapture.value != value) { security.setHidesDuringScreenCapture(value) }

    /** "Link previews" (`:458-461`). */
    fun setGeneratesLinkPreviews(value: Boolean) =
        local(security.generatesLinkPreviews.value != value) { security.setGeneratesLinkPreviews(value) }

    /** "Always relay calls" (`:575-578`). */
    fun setAlwaysRelayCalls(value: Boolean) = local(security.alwaysRelayCalls.value != value) { security.setAlwaysRelayCalls(value) }

    /**
     * "Lock chats now" (`lockChatsNow`, `:627-634`): a warning haptic, the shell locks the chats
     * (the lock screen takes over), "Chats locked". settings-lock §7.10 puts that toast on the lock
     * screen's host; until the shell's `AppActions.lockChatsNow` shows it there (change request to
     * W3-SHELL), it goes to this screen's host, which is gone or covered once the lock screen is up.
     */
    fun lockNow() {
        haptic(Haptic.Warning)
        lockChatsNow()
        toast(Toast.success(PrivacyCopy.CHATS_LOCKED))
    }

    private inline fun local(changed: Boolean, write: () -> Unit) {
        if (!changed) return
        write()
        haptic(Haptic.Light)
    }
}

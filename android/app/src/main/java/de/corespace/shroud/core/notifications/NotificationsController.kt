package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.wire.WireText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Something that arrived while the app is open, shown as a banner at the top of the screen
 * (iOS `InAppNotification`, `NotificationsController.swift:6-14`). [id] is new for every banner,
 * which re-keys the host's animation; [username] is the real username even when [title] hides it
 * (the banner then shows the brand mark, `InAppNotificationBanner.swift:59-71`).
 */
data class InAppNotification(
    val id: String = UUID.randomUUID().toString(),
    val kind: NotificationKind,
    val peerUserId: UUID?,
    val username: String?,
    val title: String,
    val body: String,
)

/** A tap (system notification or banner) waiting for the chats to open it (`NotificationsController.swift:16-21`). */
data class NotificationOpenRequest(val kind: NotificationKind, val peerUserId: UUID?, val username: String?)

/**
 * What the test notification needs from the push layer (W3-PUSH's `PushRegistration`, wired by
 * W2-INT/W3-INT through `NotificationsModule.pushHooks`).
 */
interface PushDeliveryHooks {
    /** Registers this phone again now: the test said `not_registered` or `rejected` (iOS `registerForRemoteNotifications`, `:275, :283`). */
    fun register()

    /**
     * Why no push can reach this phone while Shroud is closed — W3-PUSH's delivery copy (no
     * distributor, refused host, …) — or null when UnifiedPush or the background connection covers
     * it. A test push would never arrive in the first case, so the test says this instead.
     */
    fun noDeliveryReason(): String?
}

/**
 * Notifications while the app runs: permission, in-app banners, sounds and haptics, taps, the badge
 * count and closing a chat's notifications (iOS `NotificationsController`,
 * `ios/shroud/Services/Notifications/NotificationsController.swift:31-291`; notifications-push §5.12).
 *
 * Human: pushes (UnifiedPush) and the background connection cover the app closed or locked; this
 * covers it open. The open app reads messages, so its banner can show the text; the notification
 * shade never gets it — anything posted there is worded like a push (a name at most, `:26-29`).
 *
 * One instance per process (`NotificationsModule.controller`, iOS `.shared`). Main-confined like
 * iOS's `@MainActor` class: every member is called on the main thread (callers off main hop first —
 * the iOS crash of memory "async notification delegate crash" came from finishing notification work
 * off-main). Implements the [MessageNotifier] seam messaging calls at the iOS call sites.
 *
 * @param isResumed iOS `applicationState == .active`: one of our activities is resumed (`AppPhaseMonitor.isResumed`).
 * @param systemAllows `NotificationManagerCompat.areNotificationsEnabled()` (the server's effective `enabled`, §5.10.4).
 * @param io where file and Keystore work runs (the name cache's deletion).
 */
class NotificationsController(
    val preferences: NotificationPreferences,
    private val permission: Permission,
    private val sounds: SoundPlayer,
    val systemNotifier: SystemNotifier,
    val channels: NotificationChannels,
    private val nameCache: NotificationNameCache?,
    private val isResumed: () -> Boolean,
    private val accessibility: AccessibilityState,
    private val api: () -> ShroudApi,
    private val systemAllows: () -> Boolean,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val pushHooks: () -> PushDeliveryHooks? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MessageNotifier {
    /** The permission as the platform reports it (`NotificationPermission` with the top activity). */
    interface Permission {
        fun current(): NotificationAuthorization
        val wasRequested: Boolean
        fun markRequested()
    }

    private val mutableAuthorization = MutableStateFlow(runCatching { permission.current() }.getOrDefault(NotificationAuthorization.NotDetermined))
    private val mutableBanner = MutableStateFlow<InAppNotification?>(null)
    private val mutableHaptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Refreshed by [refreshAuthorization] (on every return to the app and on the settings screen). */
    val authorization: StateFlow<NotificationAuthorization> = mutableAuthorization.asStateFlow()

    /** The banner on screen; the shell's banner host draws it (W3-SHELL, plan C17). */
    val banner: StateFlow<InAppNotification?> = mutableBanner.asStateFlow()

    /** Set by a tap; the chats open it once they are on screen, after an unlock if need be (`:39-40`). */
    val pendingOpen = MutableStateFlow<NotificationOpenRequest?>(null)

    /**
     * One-shot haptics for the root composable to perform with its `View` (iOS `Haptics.impact(.medium)`,
     * `:112`); collected outside the banner host, since vibrate works with banners off (§5.12.2).
     */
    val haptics: SharedFlow<Haptic> = mutableHaptics.asSharedFlow()

    /** The chats are unlocked and on screen: arrivals get banners instead of system alerts (`:42-43`). */
    var isUnlocked: Boolean = false

    /** An account is signed in. Signed out, a push that still arrives is not shown in the app (`:44-46`). */
    var isSignedIn: Boolean = false

    /** The chat on screen: its messages need no banner (`:47-48`); messaging mirrors its active peer here. */
    override var activePeerId: UUID? = null

    /**
     * The server was told this phone is not in front, so it pushes (UnifiedPush) or the background
     * connection announces: a local notification on top would show the same message twice (`:49-51`).
     */
    private var pushCoversBackground = false

    private var bannerDismissJob: Job? = null

    /** `SystemClock.elapsedRealtime()` of the last alert (the quiet window, `:54-56`). */
    private var lastAlertAt: Long? = null

    /** The unread total for the next local post's `setNumber` (iOS sets the icon badge, `:222-225`; §5.12.6). */
    private var lastBadge = 0

    init {
        // A changed sound or badge is a new channel id (notifications-push §5.6, N8): the picker and
        // the badge toggles take effect at once. Show Sender off: no cached name may outlive it.
        scope.launch {
            preferences.state.map { it.sound to it.badge }.distinctUntilChanged().drop(1).collect { channels.ensure() }
        }
        scope.launch {
            preferences.state.map { it.showSender }.distinctUntilChanged().collect { on ->
                if (!on) withContext(io) { nameCache?.deleteAll() }
            }
        }
    }

    // ---- Permission (`:63-78`; §5.11) ----

    /** Re-reads the permission (iOS `refreshAuthorization`, `:65-67`): on `ON_RESUME` and on the settings screen. */
    suspend fun refreshAuthorization() {
        val current = runCatching { permission.current() }.getOrElse { return }
        if (mutableAuthorization.value != current) mutableAuthorization.value = current
    }

    /**
     * P6a (decided 2026-10-01): the shell shows the system dialog once, the first time the chats are
     * unlocked with notifications on and the dialog has never shown (iOS asks at the first unlock,
     * `PushNotificationService.swift:61-67`). Call after [refreshAuthorization].
     */
    fun shouldRequestPermissionAfterUnlock(): Boolean =
        preferences.enabled && mutableAuthorization.value == NotificationAuthorization.NotDetermined && !permission.wasRequested

    /** The system dialog is about to show (from the shell or the Allow card): kept across Log Out (N16). */
    fun markPermissionRequested() = permission.markRequested()

    // ---- Arrivals while the app runs (`:80-176`; §5.12.2) ----

    /**
     * A message, reaction or contact request arrived over the socket (`announce`, `:88-130`).
     *
     * The server does not push to a phone with a live, focused socket, so this is the only notice the
     * user gets. Open app: a banner (unless it is about the chat on screen), a sound, a vibration.
     * App in the background with the socket still open (a call keeps it): a system notification
     * worded like a push. A muted chat stays quiet; a contact request ignores chat mutes. The order
     * matters: the quiet window is stamped even when nothing plays.
     */
    override fun announce(kind: NotificationKind, peerUserId: UUID?, username: String?, conversationId: UUID?, text: String?, muted: Boolean) {
        if (muted && kind != NotificationKind.ContactRequest) return
        val prefs = preferences.state.value
        if (kind == NotificationKind.Reaction && !prefs.reactions) return
        if (kind == NotificationKind.ContactRequest && !prefs.contactRequests) return

        val name = if (prefs.showSender) username else null
        if (name != null && peerUserId != null) nameCache?.remember(peerUserId, name)
        if (!isResumed()) {
            if (prefs.enabled && !pushCoversBackground) {
                systemNotifier.postLocal(kind, name, peerUserId, conversationId, lastBadge)
            }
            return
        }
        if (peerUserId != null && peerUserId == activePeerId && (kind == NotificationKind.Message || kind == NotificationKind.Reaction)) return

        val now = clock.elapsedMillis()
        val quiet = lastAlertAt?.let { now - it < QUIET_INTERVAL_MS } ?: false
        lastAlertAt = now
        if (!quiet) {
            if (prefs.inAppSounds) sounds.play(prefs.sound)
            if (prefs.inAppVibrate) mutableHaptics.tryEmit(Haptic.Medium)
        }
        if (!prefs.inAppBanners) return
        val preview = if (prefs.showPreview) text?.let(WireText::trimWhitespacesAndNewlines) else null
        val body = if (kind == NotificationKind.Message && !preview.isNullOrEmpty()) preview else kind.bodyLine
        show(InAppNotification(kind = kind, peerUserId = peerUserId, username = username, title = name ?: SystemNotifier.APP_TITLE, body = body))
    }

    /**
     * Shows [notification] as the banner for 4 s — 10 s with TalkBack, which announces it and then
     * needs time to reach its open and Dismiss actions (`show`, `:132-141`) — or longer when the
     * user's "Time to take action" setting asks for it (§5.12.3). A new banner restarts the timer.
     */
    fun show(notification: InAppNotification) {
        mutableBanner.value = notification
        bannerDismissJob?.cancel()
        val base = if (accessibility.isTouchExplorationEnabled) TALKBACK_BANNER_LIFETIME_MS else BANNER_LIFETIME_MS
        val lifetime = maxOf(base, accessibility.recommendedTimeoutMillis(base))
        bannerDismissJob = scope.launch {
            delay(lifetime)
            dismissBanner(notification.id)
        }
    }

    override fun setPushCoversBackground(covers: Boolean) {
        pushCoversBackground = covers
    }

    /** Hides the banner; with [id], only if that banner is still the one showing (`:147-151`). */
    fun dismissBanner(id: String? = null) {
        if (id != null && mutableBanner.value?.id != id) return
        bannerDismissJob?.cancel()
        bannerDismissJob = null
        mutableBanner.value = null
    }

    /** A tap on the banner: open what it is about (`:153-160`). */
    fun openBanner(notification: InAppNotification) {
        dismissBanner(notification.id)
        pendingOpen.value = NotificationOpenRequest(notification.kind, notification.peerUserId, notification.username)
    }

    // ---- Pushes that reach the running app, and taps (`:178-217`; §5.12.4–5.12.5) ----

    /**
     * A push (or a background-connection event) reached the living process (iOS `presentation(for:)`,
     * `:182-205`): `true` = handled here, post nothing; `false` = the caller posts the system
     * notification. W3-PUSH's dispatcher calls it after the call kinds went to Calls.
     *
     * Deliberate iOS parity: no quiet window, no vibration, no mute or preference checks (the server
     * applied them), the body is always the kind's line (a push has no text). The test is always
     * shown by the system, so the user sees it while still on the settings screen (`:186`).
     */
    fun onPushWhileRunning(contents: PushContents, name: String?): Boolean {
        if (!isSignedIn) return true
        if (!isResumed()) return false
        val kind = contents.kind ?: return false
        if (kind == NotificationKind.Test) return false
        if (!isUnlocked) return false
        if ((kind == NotificationKind.Message || kind == NotificationKind.Reaction) &&
            contents.peerUserId != null && contents.peerUserId == activePeerId
        ) {
            return true
        }
        val prefs = preferences.state.value
        if (prefs.inAppBanners) {
            show(InAppNotification(kind = kind, peerUserId = contents.peerUserId, username = name, title = name ?: SystemNotifier.APP_TITLE, body = kind.bodyLine))
        }
        if (prefs.inAppSounds) sounds.play(prefs.sound)
        return true
    }

    /**
     * The user tapped a system notification (`handleTap`, `:208-217`). Not gated on [isSignedIn]: a
     * tap that cold-starts the app arrives before the session is read; the shell drops [pendingOpen]
     * when the launch finds no session and [forgetAccount] clears it. Android carries no name in
     * the intent (§5.7.5), so the chats look the peer up themselves.
     */
    fun handleTap(kind: NotificationKind?, peerUserId: UUID?) {
        if (kind == null || kind == NotificationKind.Test) return
        pendingOpen.value = NotificationOpenRequest(kind, peerUserId, username = null)
    }

    /** [handleTap] for a parsed [NotificationTap]. */
    fun handleTap(tap: NotificationTap) = handleTap(tap.kind, tap.peerUserId)

    // ---- Badge and delivered notifications (`:219-241`; §5.12.6–5.12.7) ----

    /**
     * The unread count (0 when the badge is off, `:222-225`). Android has no launcher-count API
     * outside notifications: the value is the next local post's `setNumber` (§5.7.3).
     */
    override fun setBadge(count: Int) {
        lastBadge = if (preferences.badge) maxOf(0, count) else 0
    }

    /** Closes a chat's notifications once it has been read, here or on another device (`:227-241`). */
    override fun clearDelivered(conversationId: UUID) {
        systemNotifier.cancelChat(conversationId)
    }

    override val badgeIncludesMuted: Boolean get() = preferences.badgeIncludesMuted

    /** The requests list is on screen: its notifications go (web-parity §7.6). */
    fun clearContactRequestNotifications() = systemNotifier.cancelContactRequests()

    /**
     * After the first chat list following an unlock: chats with nothing unread and no unseen
     * reactions lose their notifications (web-parity §7.6, `AppShell.tsx:802-812`).
     */
    fun closeSettledChats(readChats: Collection<UUID>, reactionsSeenChats: Collection<UUID>) =
        systemNotifier.closeSettledChats(readChats, reactionsSeenChats)

    /**
     * The Settings root row's value (iOS `notificationsSummary`, `SettingsView.swift:177-180`;
     * notifications-push §5.14.1): "Off" when Android keeps notifications away, the switch is off,
     * or — Android only (N14) — the Messages channel is turned off; else "On".
     */
    fun notificationsSummary(): String {
        val off = mutableAuthorization.value == NotificationAuthorization.Denied || !preferences.enabled ||
            runCatching { channels.messagesChannelBlocked() }.getOrDefault(false)
        return if (off) "Off" else "On"
    }

    /**
     * Log Out / removal: nothing of the account stays in memory either (`forgetAccount`, `:243-251`).
     * The preferences go back to a fresh install's (storing nothing), the channels to the defaults'
     * ids, the cached names and every posted notification go (earlier ones may name a contact; the
     * wipe's verify finds none, 00-plan §2.3 W2-NOTIF acceptance).
     */
    fun forgetAccount() {
        pendingOpen.value = null
        activePeerId = null
        lastAlertAt = null
        lastBadge = 0
        pushCoversBackground = false
        dismissBanner()
        preferences.reset()
        nameCache?.deleteAll()
        systemNotifier.cancelAll()
        runCatching { channels.ensure() }
    }

    // ---- Server (`:253-290`; §5.12.9) ----

    /** Sends the settings the server pushes by (`pushSettings`, `:256-258`). Errors are the caller's to show. */
    suspend fun pushSettings(token: String) {
        api().updateNotificationSettings(token, preferences.serverPatch(systemAllows()))
    }

    /**
     * Sends a test notification through the server, even with the app open, and returns the sentence
     * the settings screen shows under the row (`sendTest`, `:261-290`; copy: notifications-push §5.12.9
     * with the transport-neutral wording of web-parity §7.8 — no FCM, decision record 2026-10-01).
     */
    suspend fun sendTest(token: String): String {
        // A push for a phone that blocks Shroud is dropped there: "sent" would be a promise nothing keeps.
        refreshAuthorization()
        when (mutableAuthorization.value) {
            NotificationAuthorization.Denied -> return TestCopy.DENIED
            NotificationAuthorization.NotDetermined -> return TestCopy.NOT_DETERMINED
            NotificationAuthorization.Authorized -> Unit
        }
        if (runCatching { channels.messagesChannelBlocked() }.getOrDefault(false)) return TestCopy.CHANNEL_BLOCKED
        pushHooks()?.noDeliveryReason()?.let { return it }
        return try {
            val outcome = api().sendTestPush(token)
            when (outcome.status) {
                "sent" -> TestCopy.SENT
                "not_registered" -> {
                    pushHooks()?.register()
                    TestCopy.NOT_REGISTERED
                }
                "not_configured" -> TestCopy.NOT_CONFIGURED
                "misconfigured" -> TestCopy.misconfigured(outcome.detail)
                "rejected" -> {
                    pushHooks()?.register()
                    TestCopy.REJECTED
                }
                else -> TestCopy.UNREACHABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionController.userMessage(e)
        }
    }

    /** The test row's sentences (§5.12.9; web-parity §7.8). */
    object TestCopy {
        const val DENIED = "Notifications are off for Shroud. Turn them on in Android Settings, then try again."
        const val NOT_DETERMINED = "Shroud has not been allowed to notify yet. Tap Allow notifications above first."
        const val CHANNEL_BLOCKED = "Message notifications are turned off for Shroud in Android Settings. Turn them on, then try again."
        const val SENT = "Sent. It should arrive in a moment."
        const val NOT_REGISTERED = "This phone was not registered for notifications. Shroud registered it now — try again in a moment."
        const val NOT_CONFIGURED = "This server is not set up to send notifications to Android phones."
        const val REJECTED = "The push service refused this phone's registration. Shroud registered again — try again in a moment."
        const val UNREACHABLE = "The push service could not be reached. Try again in a moment."

        /** The server's push setup was refused; [detail] in parentheses with a leading space only when present. */
        fun misconfigured(detail: String?): String {
            val reason = detail?.let { " ($it)" } ?: ""
            return "The push service refused this server's setup$reason. Whoever runs the server needs to check its push settings."
        }
    }

    companion object {
        /** Banners that come this soon after another replace it silently (`quietInterval`, `:55-56`). */
        const val QUIET_INTERVAL_MS = 1_200L

        /** `bannerLifetime` (`:57`). */
        const val BANNER_LIFETIME_MS = 4_000L

        /** `voiceOverBannerLifetime` (`:58-59`). */
        const val TALKBACK_BANNER_LIFETIME_MS = 10_000L
    }
}

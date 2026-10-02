package de.corespace.shroud.ui.shell

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeRouter
import de.corespace.shroud.core.keys.IdentityPresence
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.storage.AutoLockDelay
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the root drives, as ports: what iOS `RootView` reaches through its `@State`
 * controllers (`RootView.swift:36-44`) — session, crypto, messaging, calls, the device wipe,
 * notifications, push, the security preferences. [AppShellController] and [AppRouter] only talk to
 * these, so their rules have JVM tests with fakes (`AppShellControllerTest`); production is
 * [ContainerShellEnvironment], built by `di/ShellModule`.
 *
 * Main-confined, like the controllers behind it (00-plan §1.1 rule 3).
 */
interface ShellEnvironment {
    val clock: AppClock

    /** Our activities' phase (`AppPhaseMonitor`, plan §1.4), iOS `scenePhase`. */
    val phase: StateFlow<AppPhase>

    // ---- Session (`SessionController`) ----

    val session: StateFlow<Session?>

    /** The server ended the session (3×401 or `DEVICE_REMOVED`); the token is kept (SC:50-52). */
    val pendingFullLocalWipe: StateFlow<Boolean>

    /** `GET /auth/me` (`validateSessionIfNeeded`); 401s count through the auth bridge, offline never does. */
    suspend fun validateSession()

    /** One-shot read-and-clear of [pendingFullLocalWipe]. */
    fun consumePendingFullLocalWipe(): Boolean

    /** Ends the local session without the wipe (`sessionController.logout()`; only the orphan reconcile). */
    suspend fun endLocalSession()

    // ---- Crypto (`CryptoController`) ----

    /** The account whose keys are in memory, or null. */
    val unlockedUserId: StateFlow<String?>

    /** The vault's system prompt is up: a stop under it is not a departure (crypto §10.7). */
    val vaultPromptInFlight: StateFlow<Boolean>

    /**
     * Present / Absent / Unavailable (`identityPresence(for:)`; Present is `hasLocalIdentity(for:)`);
     * Unavailable while the phone itself is locked. Reads the sealed record — call off main.
     */
    fun identityPresence(userId: String): IdentityPresence

    /** `cryptoController.lock(wipeStore:)`. */
    fun lockCrypto(wipeStore: Boolean)

    /**
     * `lockChatsInMemory` (`RootView.swift:315-320`): messaging memory, the keys, then the
     * ten-minute temp-file sweep — `AppContainer.lockChatsInMemory()`, never only the first two (W2 review).
     */
    suspend fun lockChatsInMemory()

    // ---- Device wipe (`DeviceWipeController`) ----

    val wipePresented: StateFlow<Boolean>

    /** A wipe the app was killed in is finished at launch; true when one was (`RootView.swift:153-155`). */
    suspend fun finishInterruptedWipeIfNeeded(): Boolean

    fun startWipe(reason: WipeReason)

    /** Consumes [pendingFullLocalWipe] and starts the wipe with its reason unless one runs (`RootView.swift:187-194`). */
    fun startWipeIfSessionEnded(): Boolean

    /** `deviceWipe.router = router` while the root is alive (`RootView.swift:143`); null detaches. */
    fun attachWipeRouter(router: WipeRouter?)

    // ---- Messaging (`MessagingController`) ----

    /** `messagingController.start()` (builds it on first use). */
    fun startMessaging()

    /** `messagingController.stop(wipeDisk:)`; nothing when messaging was never built. */
    suspend fun stopMessaging(wipeDisk: Boolean)

    // ---- Calls (`CallController`) ----

    /** A call screen is up (`callController.active != nil`), the ending one included. */
    val callActive: StateFlow<Boolean>

    /** Ringing, connecting or in a call (`callController.isInCall`). */
    val isInCall: Boolean

    /** `callController.clearLocalState()`. */
    fun clearCalls()

    // ---- Notifications (`NotificationsController`) ----

    fun setNotificationsUnlocked(unlocked: Boolean)
    fun setNotificationsSignedIn(signedIn: Boolean)

    /** `notifications.pendingOpen = nil`. */
    fun clearPendingOpen()
    fun dismissBanner()
    suspend fun refreshNotificationAuthorization()

    // ---- Push (`PushRegistration`, W3-PUSH) ----

    fun startPush()
    fun stopPush()

    /** Back in front: the notification permission or battery optimisation may have changed (notifications-push §5.19). */
    fun pushSettingsMaybeChanged()

    // ---- Preferences and device ----

    val autoLockDelay: StateFlow<AutoLockDelay>
    val hidesDuringScreenCapture: StateFlow<Boolean>

    /** iOS `isProtectedDataAvailable`: credential-encrypted storage is readable (`UserManager.isUserUnlocked`). */
    fun isUserUnlocked(): Boolean

    /** `syncDeviceName()` (`RootView.swift:307-313`), detached: it never holds up the caller. */
    fun syncDeviceName()

    fun currentServer(): ServerConfiguration
    fun saveServer(configuration: ServerConfiguration)

    /** "phone" or "tablet" (Platform Notes: "This iPhone" is "this phone"). */
    fun deviceNoun(): String

    /**
     * While the chats are unlocked, feed the sealed notification name cache from the chat list,
     * requests and contacts (W2-NOTIF), so the background connection can name a sender while the
     * chats are locked. [on] false stops the feed.
     */
    fun feedNotificationNames(on: Boolean)
}

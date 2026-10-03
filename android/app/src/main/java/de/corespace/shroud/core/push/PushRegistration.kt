package de.corespace.shroud.core.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * How notifications reach this phone while Shroud is closed (plan §1.7.10). UnifiedPush carries
 * RFC 8030 Web Push + RFC 8291 ciphertext, decrypted on the phone. On a phone with Play Services
 * the embedded FCM distributor is the default handoff: Google sees that a push arrived and its
 * size, not the message. A distributor the user installs (ntfy, one they run) replaces it,
 * including on that phone. With neither, the opt-in background connection keeps the socket and
 * shows its permanent notification. No Play Services SDK is linked.
 *
 * **Seam (W2-INT), owner W3-PUSH** (`PushRegistrar` + `BackgroundConnectionController` implement
 * [PushRegistration]; `PushModule.registration` exposes it).
 */

/** The UnifiedPush path. */
sealed interface UnifiedPushState {
    /** Not looked at yet (process start before the first check). */
    data object Unknown : UnifiedPushState

    data class Registered(val distributorPackage: String, val distributorLabel: String) : UnifiedPushState

    data class Registering(val distributorPackage: String) : UnifiedPushState

    data class Unavailable(val reason: NoPushReason) : UnifiedPushState
}

/** Why UnifiedPush cannot deliver (the Delivery screen's copy for each is W3-PUSH's). */
enum class NoPushReason {
    NoDistributorInstalled,
    NoneChosen,
    DistributorFailed,

    /** The distributor gave an endpoint without the RFC 8291 keys. */
    DistributorCannotEncrypt,

    /** The server does not send to the distributor's host (`UNIFIEDPUSH_ALLOWED_HOSTS`, X1-SRV-UP). */
    ServerRefusedHost,
    ServerHasNoWebPush,

    /** The push key or the subscription could not be reached: offline, a timeout, or the server answered 5xx. */
    ServerUnreachable,
    NotificationsOff,
}

/**
 * The embedded FCM distributor (UnifiedPush's library, in this package). It is offered only
 * when Play Services is installed. Its endpoint is `https://fcm.googleapis.com/fcm/send/…`.
 */
object EmbeddedFcm {
    /**
     * Our receiver, not the library's. The library reads the sender after `onReceive` returns,
     * which is empty on API 34+, so this class registers with Play Services itself.
     * See [de.corespace.shroud.core.push.unifiedpush.EmbeddedFcmReceiver].
     */
    const val RECEIVER = "de.corespace.shroud.core.push.unifiedpush.EmbeddedFcmReceiver"
    const val LABEL = "Google Play"
    const val PLAY_SERVICES = "com.google.android.gms"
}

/** An app answering `org.unifiedpush.android.distributor.REGISTER`. [embedded] is [EmbeddedFcm]. */
data class Distributor(val packageName: String, val label: String, val embedded: Boolean = false)

/**
 * Both delivery paths at once.
 *
 * - [coversBackground]: some path delivers while Shroud is closed (the notification test, the
 *   Delivery screen).
 * - [suppressesLocalAnnouncements]: the only input of `MessageNotifier.setPushCoversBackground`
 *   (through `MessagingDependencies.pushCovers`, together with the server having heard
 *   `focus:false`). Only a registered distributor qualifies: the server's push then announces, so
 *   the app stays quiet locally. The background connection does not — events reaching its socket
 *   are announced by the app itself (the normal engines with the vault open, W3-PUSH's dispatcher
 *   from ids while chats are locked, plan §1.4).
 */
data class PushDelivery(val unifiedPush: UnifiedPushState, val backgroundConnection: Boolean, val batteryUnrestricted: Boolean) {
    val coversBackground: Boolean get() = unifiedPush is UnifiedPushState.Registered || backgroundConnection

    val suppressesLocalAnnouncements: Boolean get() = unifiedPush is UnifiedPushState.Registered
}

/**
 * Registration with a distributor, the background connection switch and the Log Out forget.
 * Main-confined.
 */
interface PushRegistration {
    val delivery: StateFlow<PushDelivery>

    /** Signed in: restore and register (also from `onProcessStart`); restarts the background connection when it is on. */
    fun start()

    /** Signed out or the session ended: stop delivering (the registration stays until [forgetRegistration]). */
    fun stop()

    /** Register again now (a test push said `not_registered`). */
    fun register()

    /** Back from system settings (notification permission, battery optimisation): re-read what changed. */
    fun onSystemSettingsMaybeChanged()

    /** Receivers of `org.unifiedpush.android.distributor.REGISTER`. */
    fun distributors(): List<Distributor>

    /** The user's choice; null = none (unregisters). */
    fun chooseDistributor(packageName: String?)

    /** Starts or stops `BackgroundConnectionService`. */
    fun setBackgroundConnection(enabled: Boolean)

    /** Log Out / removal: UNREGISTER, DELETE the subscription while the token is valid, stop the service, wipe the plan §1.5 rows. */
    suspend fun forgetRegistration()

    /** Until W3-PUSH lands: no path is active and nothing is registered. */
    object Inactive : PushRegistration {
        override val delivery: StateFlow<PushDelivery> =
            MutableStateFlow(PushDelivery(UnifiedPushState.Unknown, backgroundConnection = false, batteryUnrestricted = false))
        override fun start() = Unit
        override fun stop() = Unit
        override fun register() = Unit
        override fun onSystemSettingsMaybeChanged() = Unit
        override fun distributors(): List<Distributor> = emptyList()
        override fun chooseDistributor(packageName: String?) = Unit
        override fun setBackgroundConnection(enabled: Boolean) = Unit
        override suspend fun forgetRegistration() = Unit
    }
}

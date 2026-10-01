package de.corespace.shroud.core.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * How notifications reach this phone while Shroud is closed (decision record 2026-10-01: never
 * Google code; plan §1.7.10). Two independent paths — a UnifiedPush distributor the user installed
 * (RFC 8030 Web Push + RFC 8291, decrypted on the phone) and the opt-in background connection (a
 * foreground service keeping the socket) — either, both or neither may be active. No FCM state exists.
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
    NotificationsOff,
}

/** An installed app answering `org.unifiedpush.android.distributor.REGISTER`. */
data class Distributor(val packageName: String, val label: String)

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

package de.corespace.shroud.ui.settings.push

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.push.Distributor
import de.corespace.shroud.core.push.NoPushReason
import de.corespace.shroud.core.push.PushDelivery
import de.corespace.shroud.core.push.PushRegistration
import de.corespace.shroud.core.push.UnifiedPushState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The words of the Delivery section and screen (plan §2.4 W3-PUSH "Delivery UI copy"; K6's copy
 * split: these are the UI's, mapped from [NoPushReason]; core keeps the notification texts and the
 * test row's `PushDeliveryHooks.noDeliveryReason` sentence). No Apple, Google or FCM wording
 * (decision record 2026-10-01). [noun] is "phone" or "tablet" (`DeviceNoun.current`).
 *
 * Every reason sentence is the one the notification test shows for the same state (core
 * `PushCopy`), so the Delivery section and the test row never disagree; "this phone" follows the
 * device noun here.
 */
object DeliveryCopy {
    const val TITLE = "Delivery"
    const val ROW = "Delivery"
    const val BACKGROUND_CONNECTION = "Background connection"
    const val OFF_WHILE_CLOSED = "Off while Shroud is closed"
    const val PUSH_DISTRIBUTOR = "Push distributor"
    const val NONE = "None"

    /** The picker's value when core reports a problem without the distributor it chose (contract gap, see the report). */
    const val NOT_CONNECTED = "Not connected"

    const val BACKGROUND_FOOTER =
        "Keeps a connection to your server open so messages and calls arrive without a push distributor. Uses more battery and shows a permanent notification."

    /** The empty state: no app answers the UnifiedPush register intent. */
    const val NO_DISTRIBUTOR = "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."

    /** The server's `UNIFIEDPUSH_ALLOWED_HOSTS` refused the distributor's endpoint (X1-SRV-UP). */
    const val REFUSED_HOST = "This server doesn’t send to that UnifiedPush distributor."

    const val NONE_CHOSEN = "Choose a UnifiedPush distributor, or turn on Background connection."
    const val CANNOT_ENCRYPT = "That UnifiedPush distributor cannot encrypt notifications. Choose another, or turn on Background connection."
    const val NO_WEB_PUSH = "This server is not set up to send notifications to Android phones."
    const val NOTIFICATIONS_OFF = "Notifications are off for Shroud. Turn them on in Android Settings."

    const val BATTERY = "Battery use"
    const val BATTERY_UNRESTRICTED = "Unrestricted"
    const val BATTERY_OPTIMIZED = "Optimized"
    const val BATTERY_WARNING =
        "Android may pause the connection to save battery, so messages and calls can arrive late. In Settings, let Shroud use the battery without restrictions."

    /** The line under the screen's title. */
    fun intro(noun: String): String =
        "While Shroud is closed, notifications reach this $noun through a UnifiedPush distributor you install, such as ntfy, or through a background connection to your server."

    /** Under the distributor while it delivers. */
    fun connected(label: String, noun: String): String =
        "Connected through $label. Each push is encrypted for this $noun, so $label can’t read it."

    /** Under the distributor while it registers. */
    fun connecting(label: String): String = "Connecting to $label…"

    fun distributorFailed(noun: String): String =
        "The UnifiedPush distributor could not register this $noun. Try again, or turn on Background connection."

    /** Why UnifiedPush cannot deliver: one sentence for every [NoPushReason]. */
    fun reason(reason: NoPushReason, noun: String): String = when (reason) {
        NoPushReason.NoDistributorInstalled -> NO_DISTRIBUTOR
        NoPushReason.NoneChosen -> NONE_CHOSEN
        NoPushReason.DistributorFailed -> distributorFailed(noun)
        NoPushReason.DistributorCannotEncrypt -> CANNOT_ENCRYPT
        NoPushReason.ServerRefusedHost -> REFUSED_HOST
        NoPushReason.ServerHasNoWebPush -> NO_WEB_PUSH
        NoPushReason.NotificationsOff -> NOTIFICATIONS_OFF
    }

    /**
     * Whether [reason] is something going wrong (drawn in `dangerText`) rather than a choice or an
     * empty state: no distributor installed, or none chosen, is how a phone starts out.
     */
    fun isProblem(reason: NoPushReason): Boolean =
        reason != NoPushReason.NoDistributorInstalled && reason != NoPushReason.NoneChosen

    /** TalkBack on the section row: "Delivery, ntfy". */
    fun rowLabel(value: String): String = "$ROW, $value"
}

/** A distributor in the picker, or no distributor. */
sealed interface DistributorChoice {
    val label: String

    data class App(val packageName: String, override val label: String) : DistributorChoice

    /** UnifiedPush off (`chooseDistributor(null)`). */
    data object None : DistributorChoice {
        override val label: String = DeliveryCopy.NONE
    }

    /** Shown only, never offered: core reports a problem and not which distributor it was. */
    data object NotConnected : DistributorChoice {
        override val label: String = DeliveryCopy.NOT_CONNECTED
    }
}

/** A footer line and whether it reports a problem. */
data class DeliveryLine(val text: String, val problem: Boolean)

/**
 * What the Delivery section and screen show (plan §1.7.10 `PushDelivery`, contract K6): core's
 * [delivery] and the [distributors] installed now. Pure, so the section, the screen and the tests
 * share one set of rules.
 *
 * The two paths are independent (decision record 1): a registered UnifiedPush distributor, the
 * background connection, both, or neither.
 */
data class DeliveryState(
    val delivery: PushDelivery,
    val distributors: List<Distributor> = emptyList(),
) {
    private val unifiedPush: UnifiedPushState get() = delivery.unifiedPush

    /** A distributor's label from the installed list, else the package name. */
    private fun labelOf(packageName: String): String =
        distributors.firstOrNull { it.packageName == packageName }?.label ?: packageName

    /**
     * Nothing brings notifications while Shroud is closed, and nothing is on its way: no
     * registered distributor, no background connection, and no registration running.
     */
    val isOff: Boolean get() = !delivery.coversBackground && unifiedPush !is UnifiedPushState.Registering

    /**
     * The section row's value: the distributor delivering (or registering), else "Background
     * connection", else "Off while Shroud is closed". A registered distributor names itself even
     * with the background connection on too: it is the path the server pushes to.
     */
    val summary: String
        get() = when (val up = unifiedPush) {
            is UnifiedPushState.Registered -> summaryLabel(up)
            else -> when {
                delivery.backgroundConnection -> DeliveryCopy.BACKGROUND_CONNECTION
                up is UnifiedPushState.Registering -> labelOf(up.distributorPackage)
                else -> DeliveryCopy.OFF_WHILE_CLOSED
            }
        }

    /** The line under the section while nothing delivers: why UnifiedPush cannot (null while not known yet). */
    fun sectionFooter(noun: String): String? {
        if (!isOff) return null
        val reason = (unifiedPush as? UnifiedPushState.Unavailable)?.reason ?: return null
        return DeliveryCopy.reason(reason, noun)
    }

    /** The picker's value. */
    val choice: DistributorChoice
        get() = when (val up = unifiedPush) {
            is UnifiedPushState.Registered -> DistributorChoice.App(up.distributorPackage, summaryLabel(up))
            is UnifiedPushState.Registering -> DistributorChoice.App(up.distributorPackage, labelOf(up.distributorPackage))
            is UnifiedPushState.Unavailable -> when (up.reason) {
                NoPushReason.NoDistributorInstalled, NoPushReason.NoneChosen -> DistributorChoice.None
                else -> DistributorChoice.NotConnected
            }
            UnifiedPushState.Unknown -> DistributorChoice.None
        }

    /** A registered distributor's label: the installed list's (the picker's options), else core's. */
    private fun summaryLabel(up: UnifiedPushState.Registered): String =
        distributors.firstOrNull { it.packageName == up.distributorPackage }?.label ?: up.distributorLabel

    /** What the picker offers: every installed distributor, then None. Empty: nothing to choose. */
    val options: List<DistributorChoice>
        get() = if (distributors.isEmpty()) {
            emptyList()
        } else {
            distributors.map { DistributorChoice.App(it.packageName, it.label) } + DistributorChoice.None
        }

    /** A distributor is installed, so the value is a picker (otherwise plain "None"). */
    val canChoose: Boolean get() = distributors.isNotEmpty()

    /** The line under the distributor card. */
    fun distributorFooter(noun: String): DeliveryLine? = when (val up = unifiedPush) {
        is UnifiedPushState.Registered -> DeliveryLine(DeliveryCopy.connected(summaryLabel(up), noun), problem = false)
        is UnifiedPushState.Registering -> DeliveryLine(DeliveryCopy.connecting(labelOf(up.distributorPackage)), problem = false)
        is UnifiedPushState.Unavailable -> DeliveryLine(DeliveryCopy.reason(up.reason, noun), DeliveryCopy.isProblem(up.reason))
        UnifiedPushState.Unknown -> null
    }

    val backgroundConnection: Boolean get() = delivery.backgroundConnection

    /** The battery row shows while the background connection is on: it is what Android may pause. */
    val showsBattery: Boolean get() = delivery.backgroundConnection

    val batteryUnrestricted: Boolean get() = delivery.batteryUnrestricted
}

/**
 * The Delivery screen's actions on K6 (`push.registration`): the distributor choice, the
 * background-connection switch, and re-reading what the system may have changed. Registration
 * itself, the battery prompt and the permanent notification are core's.
 *
 * Main-confined (R1), like [PushRegistration].
 */
class PushDeliveryModel(
    private val registration: PushRegistration,
    private val haptic: (Haptic) -> Unit = {},
) {
    private val mutableDistributors = MutableStateFlow(emptyList<Distributor>())

    /** The installed distributors as last read ([onResume]). */
    val distributors: StateFlow<List<Distributor>> = mutableDistributors.asStateFlow()

    val delivery: StateFlow<PushDelivery> get() = registration.delivery

    /**
     * The screen is (back) in front — first shown, or back from Android Settings, an app store or
     * the battery dialog: re-read the installed distributors, and let core re-read the battery and
     * notification state (K6 `onSystemSettingsMaybeChanged`).
     */
    fun onResume() {
        mutableDistributors.value = registration.distributors()
        registration.onSystemSettingsMaybeChanged()
    }

    /** A pick in the distributor menu (the menu only reports a change). */
    fun choose(choice: DistributorChoice) {
        when (choice) {
            is DistributorChoice.App -> registration.chooseDistributor(choice.packageName)
            DistributorChoice.None -> registration.chooseDistributor(null)
            DistributorChoice.NotConnected -> return
        }
        haptic(Haptic.Light)
    }

    /** The "Background connection" switch: core starts or stops the service (and asks once about the battery). */
    fun setBackgroundConnection(enabled: Boolean) {
        if (registration.delivery.value.backgroundConnection == enabled) return
        registration.setBackgroundConnection(enabled)
        haptic(Haptic.Light)
    }
}

package de.corespace.shroud.ui.settings.devices

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.devices.DevicesController
import de.corespace.shroud.core.devices.DevicesState
import de.corespace.shroud.core.devices.RemoveOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** The removals the screen has running: their rows show a spinner (`DevicesView.swift:25-26, 284-285`). */
data class DeviceRemovals(
    val revokingIds: Set<UUID> = emptySet(),
    val isRevokingAll: Boolean = false,
)

/**
 * What Settings › Devices shows (iOS `DevicesView` state, `DevicesView.swift:21-46`; settings-lock
 * §4.1): core's K3 [DevicesState] (`auth.devices`: rows sorted this device first, then by last
 * activity; names opened only while the chats are unlocked) plus the removals this screen runs.
 *
 * Before the first list [rows] is null: a [loadError] then replaces the list, otherwise the loading
 * card shows. Once loaded, core's error is the line under the list ([actionError]) and the rows stay.
 */
data class DevicesUiState(
    val devices: DevicesState = DevicesState(),
    val removals: DeviceRemovals = DeviceRemovals(),
) {
    /** The loaded list, or null before the first one. */
    val rows: List<DeviceRow>? get() = devices.rows.takeIf { devices.hasLoaded }

    /** No list yet and loading it failed ("Sign in to see your devices.", an offline sentence, …). */
    val loadError: String? get() = devices.error.takeIf { !devices.hasLoaded }

    /** A failed reload or removal under the loaded list (`DevicesView.swift:23-24, 531-536`). */
    val actionError: String? get() = devices.error.takeIf { devices.hasLoaded }

    /** This phone's row; null when the server list lacks it (a stale list, `:157-166`). */
    val current: DeviceRow? get() = rows?.firstOrNull { it.isThisDevice }

    /** Every other device, most recently active first (core's order, `:41-46`). */
    val others: List<DeviceRow> get() = rows.orEmpty().filterNot { it.isThisDevice }

    val isRevokingAll: Boolean get() = removals.isRevokingAll

    /** A removal is running: Remove All waits (`DevicesView.swift:354`). */
    val isRemoving: Boolean get() = removals.isRevokingAll || removals.revokingIds.isNotEmpty()

    /** [row]'s removal is running (its own, or all of them, `:284-285`). */
    fun isRevoking(row: DeviceRow): Boolean = removals.isRevokingAll || row.id in removals.revokingIds

    fun displayName(row: DeviceRow): String = DevicesCopy.displayName(row)
}

/**
 * The words of Settings › Devices (iOS `DevicesView.swift`; settings-lock §4 with the Android
 * wording [A]: "this phone" / "this tablet" from `DeviceNoun.current`). Pure, so the screen and the
 * tests share one source.
 */
object DevicesCopy {
    const val TITLE = "Devices"
    const val UNNAMED = "Unnamed device"
    const val LOADING = "Loading devices…"
    const val LOAD_ERROR_TITLE = "Can't load devices"
    const val THIS_DEVICE = "This device"
    const val OTHER_DEVICES = "Other devices"
    const val DEVICE_LIMIT_HEADER = "Device limit"
    const val LINKED_DEVICES = "Linked devices"
    const val NO_OTHER_DEVICES = "No other devices"
    const val NO_OTHER_DEVICES_HINT =
        "To add one, sign in on the web or another phone with your username and password, then unlock with your 12-word phrase."
    const val REMOVE = "Remove"
    const val REMOVE_ALL = "Remove All Other Devices"
    const val REMOVING_ALL = "Removing other devices…"
    const val INFO_TITLE = "Your phrase stays on each device"
    const val INFO_BODY =
        "Every device unlocks with your 12-word phrase, which never leaves it. Device names are encrypted with it too, so only your own devices can read them. The server records when each device was linked and when it was last active — both shown here. A removed device erases everything of your account on it as soon as it is online or next opened."
    const val CONFIRM_MESSAGE_ONE =
        "It is signed out right away and erases everything of your account on it: messages, keys and files, as soon as it is online or next opened. What it already sent stays in your chats. Signing in there again takes your password and 12-word phrase."
    const val CONFIRM_MESSAGE_ALL =
        "They are signed out right away and erase everything of your account on them: messages, keys and files, as soon as they are online or next opened. What they already sent stays in your chats. Signing in there again takes your password and 12-word phrase."
    const val CONFIRM_ALL_TITLE = "Remove all other devices?"

    // Device Details (`DevicesView.swift:590-780`).
    const val DETAILS_PANE = "Device details"
    const val NAME = "Name"
    const val RENAME = "Rename"
    const val RENAME_TITLE = "Rename Device"
    const val RENAME_MESSAGE = "The name is encrypted — only your devices can read it."
    const val SAVE = "Save"
    const val TYPE = "Type"
    const val LINKED = "Linked"
    const val LAST_ACTIVE = "Last active"
    const val NOW = "Now"
    const val NEVER = "Never"
    const val DEVICE_ID = "Device ID"
    const val COPY_DEVICE_ID = "Copy device ID"
    const val DEVICE_ID_COPIED = "Device ID copied"
    const val REMOVE_DEVICE = "Remove Device"
    const val REMOVING = "Removing…"

    /** A label's trimmed name, or "Unnamed device" (`DevicesView.swift:450-453`). */
    fun displayName(label: DeviceNameSeal.Label?): String = label?.name?.trim()?.ifEmpty { null } ?: UNNAMED

    /**
     * A row's name: its label's, else — the chats locked or the name unreadable, so core's
     * [DeviceRow.label] is null — the kind noun core still knows ("Android app"), else "Unnamed device".
     */
    fun displayName(row: DeviceRow): String =
        row.label?.name?.trim()?.ifEmpty { null } ?: row.kind.takeIf { it != DeviceKind.Unknown }?.label ?: UNNAMED

    /** "Last active 9:37" / "Linked Yesterday" (`DevicesView.swift:455-460`); [timeLabel] = `ChatListFormatting.timeLabel`. */
    fun lastActive(device: DeviceRow, timeLabel: (Instant) -> String): String =
        device.lastSeenAt?.let { "Last active ${timeLabel(it)}" } ?: "Linked ${timeLabel(device.createdAt)}"

    /** "OTHER DEVICES" or "OTHER DEVICES — n" before upper-casing (`DevicesView.swift:176`). */
    fun otherDevicesHeader(count: Int): String = if (count == 0) OTHER_DEVICES else "$OTHER_DEVICES — $count"

    /** "3 of 5" (`DevicesView.swift:366`). */
    fun capacityValue(count: Int): String = "$count of $DEVICE_LIMIT"

    /** The account is at the device limit (`DevicesView.swift:359`). */
    fun isFull(count: Int): Boolean = count >= DEVICE_LIMIT

    /** The footer under the capacity meter (`DevicesView.swift:385-394`). */
    fun capacityFooter(count: Int): String {
        if (isFull(count)) {
            return "Your account is at the limit. A new sign-in takes over a device that has been logged out; if every device is still signed in, it is refused until you remove one here."
        }
        val left = DEVICE_LIMIT - count
        return "You can sign in on $left more ${if (left == 1) "device" else "devices"}. A logged-out device stays listed until it signs in again or you remove it."
    }

    /** [A] "Active now · This phone" (`DevicesView.swift:315`). */
    fun activeNow(noun: String): String = "Active now · This $noun"

    /** [A] "This phone · Active now" in the details hero (`DevicesView.swift:616`). */
    fun detailsActiveNow(noun: String): String = "This $noun · Active now"

    /** [A] The current device is missing from the server's list (`DevicesView.swift:160`). */
    fun missingFromList(noun: String): String = "This $noun is missing from the list. Pull to refresh."

    /** [A] Footer of Remove All (`DevicesView.swift:173`). */
    fun removeAllFooter(noun: String): String = "Signs out every device except this $noun."

    /** [A] The note instead of Remove on this device's details (`DevicesView.swift:694-696`). */
    fun currentDeviceNote(noun: String): String =
        "To remove this $noun from your account, use Log Out in Settings. It also erases everything Shroud keeps here."

    /** The single removal's title (`DevicesView.swift:86`). */
    fun confirmTitle(name: String?): String = "Remove ${name ?: "this device"}?"

    /** [A] The message of Remove All (`DevicesView.swift:105-108`). */
    fun confirmAllMessage(noun: String): String = "Only this $noun stays signed in. $CONFIRM_MESSAGE_ALL"

    /** "Remove 3" (`DevicesView.swift:102`). */
    fun confirmAllButton(count: Int): String = "Remove $count"

    /** TalkBack on a row's Remove button (`DevicesView.swift:301`). */
    fun removeLabel(name: String): String = "Remove $name"

    /** TalkBack on the Name row (`DevicesView.swift:647`). */
    fun renameLabel(name: String): String = "Rename $name"

    fun removed(name: String): String = "$name removed"

    /** The toast after Remove All (`DevicesView.swift:540`). */
    fun removedCount(count: Int): String = if (count == 1) "1 device removed" else "$count devices removed"
}

/**
 * Settings › Devices' actions (iOS `DevicesView`, `ios/shroud/Features/Main/DevicesView.swift:462-579`;
 * settings-lock §4.1, §4.4, §4.6) over core's [DevicesController] (K3, `auth.devices`). The list, its
 * order, this phone, the sealed names, the kind kept on a rename, the 404-is-removed rule and every
 * failure sentence are core's; this adds only what the screen does around them — the row spinners,
 * the haptics and the confirmation toasts.
 *
 * Main-confined like the iOS view. [haptic] and [toast] are the screen's. A removal keeps running on
 * [actionScope] if the screen closes meanwhile (an iOS `Task` in a button action is not cancelled
 * with the view).
 */
class DevicesViewModel(
    private val devices: DevicesController,
    private val actionScope: CoroutineScope,
    private val haptic: (Haptic) -> Unit = {},
    private val toast: (Toast) -> Unit = {},
) {
    private val mutableRemovals = MutableStateFlow(DeviceRemovals())

    /** The removals running now; the screen combines them with [devicesState]. */
    val removals: StateFlow<DeviceRemovals> = mutableRemovals.asStateFlow()

    /** Core's list (`auth.devices.state`). */
    val devicesState: StateFlow<DevicesState> get() = devices.state

    /** What the screen shows now. */
    val state: DevicesUiState get() = DevicesUiState(devices.state.value, mutableRemovals.value)

    /**
     * Loads the list (`load`, `DevicesView.swift:462-483`); a pull starts clean (`:73-77`). Core keeps
     * the rows on a failure and shares a load already running.
     */
    suspend fun refresh() = devices.refresh()

    /**
     * Removes [row] (`revoke`, `DevicesView.swift:485-506`): never this phone (that is Log Out).
     * Removed → success haptic and "<name> removed"; a failure → error haptic, and core's sentence
     * under the list. The row's spinner lasts until core has reloaded the list.
     */
    fun revoke(row: DeviceRow) {
        if (row.isThisDevice || row.id in mutableRemovals.value.revokingIds) return
        val name = DevicesCopy.displayName(row)
        mutableRemovals.update { it.copy(revokingIds = it.revokingIds + row.id) }
        actionScope.launch {
            try {
                when (devices.remove(row.id)) {
                    RemoveOutcome.Removed -> {
                        haptic(Haptic.Success)
                        toast(Toast.success(DevicesCopy.removed(name)))
                    }
                    is RemoveOutcome.Partial, is RemoveOutcome.Failed -> haptic(Haptic.Error)
                }
            } finally {
                mutableRemovals.update { it.copy(revokingIds = it.revokingIds - row.id) }
            }
        }
    }

    /**
     * Removes every other device (`revokeAllOthers`, `DevicesView.swift:508-544`): all removed →
     * success haptic and "n devices removed"; any failure → error haptic and core's "1 of 3 devices
     * could not be removed. …" line under the list.
     */
    fun revokeAllOthers() {
        val count = state.others.size
        if (count == 0 || mutableRemovals.value.isRevokingAll) return
        mutableRemovals.update { it.copy(isRevokingAll = true) }
        actionScope.launch {
            try {
                when (devices.removeAllOthers()) {
                    RemoveOutcome.Removed -> {
                        haptic(Haptic.Success)
                        toast(Toast.success(DevicesCopy.removedCount(count)))
                    }
                    is RemoveOutcome.Partial, is RemoveOutcome.Failed -> haptic(Haptic.Error)
                }
            } finally {
                mutableRemovals.update { it.copy(isRevokingAll = false) }
            }
        }
    }

    /**
     * Renames [row] (`rename`, `DevicesView.swift:553-579`): core seals the name keeping the stored
     * kind and reloads. Null once saved (a light haptic), else core's sentence for the sheet.
     */
    suspend fun rename(row: DeviceRow, name: String): String? {
        val error = devices.rename(row.id, name)
        if (error == null) haptic(Haptic.Light)
        return error
    }

    /** The newest copy of [row] (the open details show the new name, `DevicesView.swift:575-576`). */
    fun latest(row: DeviceRow): DeviceRow = devices.state.value.rows.firstOrNull { it.id == row.id } ?: row
}

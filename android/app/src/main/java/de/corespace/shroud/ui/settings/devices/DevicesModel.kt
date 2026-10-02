package de.corespace.shroud.ui.settings.devices

import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.core.net.LinkedDeviceDto
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** The server calls of Settings › Devices (iOS `DevicesService`; api-realtime §5.2, settings-lock §4.4). */
interface DevicesBackend {
    /** `GET /devices`. */
    suspend fun list(token: String): List<LinkedDeviceDto>

    /** `DELETE /devices/{id}`; a 404 means it is gone already (`DevicesView.swift:142-146`). */
    suspend fun revoke(token: String, deviceId: UUID)

    /** `PUT /devices/{id}/name` with the sealed name (settings-lock §5.2). */
    suspend fun putName(token: String, deviceId: UUID, sealedName: String)
}

/** [DevicesBackend] on the app's [ShroudApi]. */
class ShroudApiDevicesBackend(private val api: ShroudApi) : DevicesBackend {
    override suspend fun list(token: String): List<LinkedDeviceDto> = api.devices(token)

    override suspend fun revoke(token: String, deviceId: UUID) = api.revokeDevice(token, deviceId)

    override suspend fun putName(token: String, deviceId: UUID, sealedName: String) {
        api.putDeviceName(token, deviceId, sealedName)
    }
}

/**
 * Device names opened and sealed with this account's history key (iOS `DevicesView.label`,
 * `DevicesView.swift:440-444`, and `rename`, `:556-568`; settings-lock §5.1). Names never leave this
 * object in the clear except as the label the screen shows; nothing here logs them (invariant 13).
 */
interface DeviceNames {
    /** The label sealed with [device], or null while the chats are locked or it does not open. */
    fun open(device: LinkedDeviceDto): DeviceNameSeal.Label?

    /**
     * [label] sealed for [deviceId], or null while the chats are locked. Throws
     * [DeviceNameSeal.SealError.EmptyName] when nothing is left of the name.
     */
    fun seal(label: DeviceNameSeal.Label, deviceId: UUID): String?
}

/**
 * [DeviceNames] on the unlocked key material. Sealing refuses keys of another account than the
 * session's (a sign-in in between): other devices could not open that name (as
 * `AuthModule.syncDeviceName` does).
 */
class CryptoDeviceNames(
    private val crypto: CryptoController,
    private val sessionUserId: () -> String?,
) : DeviceNames {
    override fun open(device: LinkedDeviceDto): DeviceNameSeal.Label? =
        crypto.withMaterial { DeviceNameSeal.open(device.sealedName, device.id, it.historyKey) }

    override fun seal(label: DeviceNameSeal.Label, deviceId: UUID): String? {
        val user = sessionUserId() ?: return null
        if (!user.equals(crypto.unlockedUserId.value, ignoreCase = true)) return null
        return crypto.withMaterial { DeviceNameSeal.seal(label, deviceId, it.historyKey) }
    }
}

/**
 * What Settings › Devices shows (iOS `DevicesView` state, `DevicesView.swift:21-46`; settings-lock
 * §4.1). [devices] null = not loaded yet; [labels] holds the names that opened (a device without one
 * reads "Unnamed device").
 */
data class DevicesState(
    val devices: List<LinkedDeviceDto>? = null,
    val labels: Map<UUID, DeviceNameSeal.Label> = emptyMap(),
    val loadError: String? = null,
    /** The last removal failure, under the list; the toast only confirms successes (`:23-24`). */
    val actionError: String? = null,
    val revokingIds: Set<UUID> = emptySet(),
    val isRevokingAll: Boolean = false,
    /** This phone's device id from the session (`isCurrent`, `:436-438`). */
    val currentDeviceId: UUID? = null,
) {
    /** The server's flag, or this session's device id (`DevicesView.swift:436-438`). */
    fun isCurrent(device: LinkedDeviceDto): Boolean = device.isCurrent || device.id == currentDeviceId

    /** This phone's row; null when the server list lacks it (a stale list, `:157-166`). */
    val current: LinkedDeviceDto? get() = devices?.firstOrNull(::isCurrent)

    /** Every other device, most recently active first, so a stale one sinks (`:41-46`). */
    val others: List<LinkedDeviceDto>
        get() = devices.orEmpty().filterNot(::isCurrent).sortedByDescending { it.lastSeenAt ?: it.createdAt }

    fun label(device: LinkedDeviceDto): DeviceNameSeal.Label? = labels[device.id]

    fun displayName(device: LinkedDeviceDto): String = DevicesCopy.displayName(labels[device.id])

    /** A removal is running: Remove All waits (`DevicesView.swift:354`). */
    val isRemoving: Boolean get() = isRevokingAll || revokingIds.isNotEmpty()

    /** [device]'s removal is running (its own, or all of them, `:284-285`). */
    fun isRevoking(device: LinkedDeviceDto): Boolean = isRevokingAll || device.id in revokingIds
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
    const val SIGN_IN = "Sign in to see your devices."
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
    const val UNLOCK_TO_RENAME = "Unlock Shroud to rename devices."
    const val ENTER_A_NAME = "Enter a name."

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

    /** "Last active 9:37" / "Linked Yesterday" (`DevicesView.swift:455-460`); [timeLabel] = `ChatListFormatting.timeLabel`. */
    fun lastActive(device: LinkedDeviceDto, timeLabel: (Instant) -> String): String =
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

    fun alreadyRemoved(name: String): String = "$name was already removed"

    /** The toast after Remove All (`DevicesView.swift:540`). */
    fun removedCount(count: Int): String = if (count == 1) "1 device removed" else "$count devices removed"

    /** The line under the list when Remove All partly failed (`DevicesView.swift:531-536`). */
    fun removeAllFailure(failed: Int, total: Int, message: String): String {
        val lead = if (total == 1) "The device could not be removed. " else "$failed of $total devices could not be removed. "
        return lead + message
    }
}

/**
 * Settings › Devices (iOS `DevicesView`, `ios/shroud/Features/Main/DevicesView.swift:12-586`;
 * settings-lock §4.1, §4.4, §4.6): loads the linked devices, opens their sealed names, removes one or
 * all of the others and renames a device.
 *
 * Main-confined like the iOS view: every member runs on the main thread ([scope] is the screen's).
 * [haptic] and [toast] are the screen's; [onCount] tells the Settings row the new count after every
 * load or removal (`DevicesView.swift:13-14`). A removal or rename keeps running on [actionScope] if
 * the screen closes meanwhile (an iOS `Task` in a button action is not cancelled with the view).
 */
class DevicesViewModel(
    private val backend: DevicesBackend,
    private val token: () -> String?,
    private val currentDeviceId: () -> UUID?,
    private val names: DeviceNames,
    private val currentKind: () -> DeviceNameSeal.Kind,
    private val scope: CoroutineScope,
    private val actionScope: CoroutineScope = scope,
    private val haptic: (Haptic) -> Unit = {},
    private val toast: (Toast) -> Unit = {},
    private val onCount: (Int) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(DevicesState(currentDeviceId = currentDeviceId()))
    val state: StateFlow<DevicesState> = mutableState.asStateFlow()

    /**
     * Loads the list (`load`, `DevicesView.swift:462-483`): assigned only when it changed; a failure
     * before anything loaded is the screen's error, after it a line under the list. A cancelled load
     * (the pull released early, the screen left) shows nothing.
     */
    suspend fun load() {
        val bearer = token()
        if (bearer == null) {
            mutableState.update { it.copy(loadError = DevicesCopy.SIGN_IN) }
            return
        }
        try {
            val list = backend.list(bearer)
            val labels = buildMap { for (device in list) names.open(device)?.let { put(device.id, it) } }
            mutableState.update {
                it.copy(
                    devices = if (it.devices == list) it.devices else list,
                    labels = labels,
                    loadError = null,
                    currentDeviceId = currentDeviceId() ?: it.currentDeviceId,
                )
            }
            onCount(list.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = SessionController.userMessage(e)
            mutableState.update { if (it.devices == null) it.copy(loadError = message) else it.copy(actionError = message) }
        }
    }

    /** A pull to refresh starts clean; a failure sets the error again (`DevicesView.swift:73-77`). */
    suspend fun refresh() {
        mutableState.update { it.copy(actionError = null) }
        load()
    }

    /**
     * Removes [device] (`revoke`, `DevicesView.swift:485-506`): never this phone (that is Log Out).
     * Success → removed here, success haptic, "<name> removed"; 404 → it was gone already, removed
     * here, "<name> was already removed"; else the error under the list and an error haptic. Then
     * the list reloads; the row's spinner lasts until it has.
     */
    fun revoke(device: LinkedDeviceDto) {
        if (mutableState.value.isCurrent(device)) return
        val bearer = token() ?: return
        mutableState.update { it.copy(actionError = null, revokingIds = it.revokingIds + device.id) }
        actionScope.launch {
            try {
                try {
                    backend.revoke(bearer, device.id)
                    val name = mutableState.value.displayName(device)
                    removeLocally(listOf(device.id))
                    haptic(Haptic.Success)
                    toast(Toast.success(DevicesCopy.removed(name)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isAlreadyRemoved(e)) {
                        val name = mutableState.value.displayName(device)
                        removeLocally(listOf(device.id))
                        toast(Toast.info(DevicesCopy.alreadyRemoved(name)))
                    } else {
                        mutableState.update { it.copy(actionError = SessionController.userMessage(e)) }
                        haptic(Haptic.Error)
                    }
                }
                load()
            } finally {
                mutableState.update { it.copy(revokingIds = it.revokingIds - device.id) }
            }
        }
    }

    /**
     * Removes every other device (`revokeAllOthers`, `DevicesView.swift:508-544`). There is no bulk
     * endpoint: one `DELETE` per device, carrying on past failures so one stale row does not leave
     * the rest signed in; a 404 counts as removed.
     */
    fun revokeAllOthers() {
        val bearer = token() ?: return
        val targets = mutableState.value.others
        if (targets.isEmpty()) return
        mutableState.update { it.copy(actionError = null, isRevokingAll = true) }
        actionScope.launch {
            val removed = ArrayList<UUID>()
            var lastError: Exception? = null
            try {
                for (device in targets) {
                    try {
                        backend.revoke(bearer, device.id)
                        removed += device.id
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isAlreadyRemoved(e)) removed += device.id else lastError = e
                    }
                }
            } finally {
                removeLocally(removed)
                mutableState.update { it.copy(isRevokingAll = false) }
            }
            val error = lastError
            if (error != null) {
                val message = DevicesCopy.removeAllFailure(targets.size - removed.size, targets.size, SessionController.userMessage(error))
                mutableState.update { it.copy(actionError = message) }
                haptic(Haptic.Error)
            } else {
                haptic(Haptic.Success)
                toast(Toast.success(DevicesCopy.removedCount(removed.size)))
            }
            load()
        }
    }

    /**
     * Seals [name] for [device] — marked as typed by a person, so a phone keeps it instead of its own —
     * and reloads (`rename`, `DevicesView.swift:553-579`). The stored kind is kept, so renaming an
     * Android device keeps kind 4; with no label, this phone's own kind, else "other". Null once
     * saved, else the line to show under the details.
     */
    suspend fun rename(device: LinkedDeviceDto, name: String): String? {
        val bearer = token() ?: return DevicesCopy.UNLOCK_TO_RENAME
        val current = mutableState.value
        val kind = current.label(device)?.kind
            ?: if (current.isCurrent(device)) currentKind() else DeviceNameSeal.Kind.Other
        try {
            val sealed = names.seal(DeviceNameSeal.Label(name, kind, custom = true), device.id)
                ?: return DevicesCopy.UNLOCK_TO_RENAME
            backend.putName(bearer, device.id, sealed)
        } catch (_: DeviceNameSeal.SealError.EmptyName) {
            return DevicesCopy.ENTER_A_NAME
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SessionController.userMessage(e)
        }
        load()
        haptic(Haptic.Light)
        return null
    }

    /** The newest copy of [device] after a load (the open details show the new name, `DevicesView.swift:575-576`). */
    fun latest(device: LinkedDeviceDto): LinkedDeviceDto = mutableState.value.devices?.firstOrNull { it.id == device.id } ?: device

    private fun removeLocally(ids: List<UUID>) {
        if (ids.isEmpty()) return
        var count: Int? = null
        mutableState.update { state ->
            val list = state.devices ?: return@update state
            val kept = list.filterNot { it.id in ids }
            count = kept.size
            state.copy(devices = kept)
        }
        count?.let(onCount)
    }

    companion object {
        /** A 404 means the device is gone already (removed elsewhere meanwhile): the goal is met (`DevicesView.swift:142-146`). */
        fun isAlreadyRemoved(error: Throwable): Boolean = (error as? ApiError)?.isNotFound == true
    }
}

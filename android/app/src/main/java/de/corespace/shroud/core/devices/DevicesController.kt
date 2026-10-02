package de.corespace.shroud.core.devices

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.core.net.LinkedDeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * One linked device, as Settings › Devices shows it (iOS `DevicesView`, `DevicesView.swift:436-453`).
 * [label] is null while the chats are locked — the sealed name stays sealed — and [kind] is then
 * [DeviceKind.Unknown] until a load runs with the chats open.
 */
data class DeviceRow(
    val id: UUID,
    val label: DeviceNameSeal.Label?,
    val kind: DeviceKind,
    val isThisDevice: Boolean,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
)

/**
 * The device list. Before the first successful load: [rows] empty, [hasLoaded] false, [capacity] null.
 * [capacity] is the account's device limit ([DEVICE_LIMIT]) once a list has loaded.
 * [error] is an iOS sentence: a failed refresh, a removal that did not fully succeed, or
 * "Sign in to see your devices." A failed refresh keeps the previous [rows].
 */
data class DevicesState(
    val rows: List<DeviceRow> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
    val capacity: Int? = null,
)

/** What a removal did. A 404 counts as [Removed]. [Partial] is some devices removed and some not. */
sealed interface RemoveOutcome {
    data object Removed : RemoveOutcome

    data class Partial(val removed: Int, val failed: Int) : RemoveOutcome

    data class Failed(val message: String) : RemoveOutcome
}

/**
 * Settings › Devices (iOS `DevicesView.swift:462-579`; the w3-settings-b model). Main-confined:
 * every suspend member hops to [main] itself. Needs a session; without one, [refresh] sets
 * [DevicesState.error] to the sign-in sentence and the removals return [RemoveOutcome.Failed]
 * with that sentence, and nothing is asked of the server. Names are opened and sealed only while
 * the chats are unlocked for this session's user; otherwise [DeviceRow.label] is null and a rename
 * returns [UNLOCK_TO_RENAME].
 *
 * [state] is this device first, then the others by last activity descending (`lastSeenAt`, or
 * `createdAt` when the server has no last-seen — `DevicesView.swift:41-46`).
 */
interface DevicesController {
    val state: StateFlow<DevicesState>

    /** Reloads the list. Overlapping callers share one request. */
    suspend fun refresh()

    /**
     * Seals [name] for [id], keeping the stored kind (this phone's kind is Android when the old
     * name cannot be opened; any other device falls back to "other"), and marks it as typed by a
     * person. Null once saved; otherwise the sentence to show.
     */
    suspend fun rename(id: UUID, name: String): String?

    /** Removes [id]. A 404 counts as [RemoveOutcome.Removed]. This device is never removed here. */
    suspend fun remove(id: UUID): RemoveOutcome

    /** One DELETE per other device, carrying on past failures. A 404 counts as removed. */
    suspend fun removeAllOthers(): RemoveOutcome

    companion object {
        const val SIGN_IN = "Sign in to see your devices."
        const val UNLOCK_TO_RENAME = "Unlock Shroud to rename devices."
        const val ENTER_A_NAME = "Enter a name."
    }
}

/**
 * [DevicesController] on `GET /devices`, `DELETE /devices/{id}` and `PUT /devices/{id}/name`.
 * The name callbacks are the crypto controller's history key; they return null while the chats
 * are locked and must not seal with another account's key.
 */
class ShroudDevicesController(
    private val listDevices: suspend (token: String) -> List<LinkedDeviceDto>,
    private val revokeDevice: suspend (token: String, id: UUID) -> Unit,
    private val putDeviceName: suspend (token: String, id: UUID, sealedName: String) -> Unit,
    private val session: () -> Session?,
    private val unlockedUserId: () -> String?,
    private val openName: (sealed: String?, id: UUID) -> DeviceNameSeal.Label?,
    private val sealName: (DeviceNameSeal.Label, UUID) -> String?,
    private val deviceNoun: () -> String,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
) : DevicesController {
    private val mutableState = MutableStateFlow(DevicesState())
    override val state: StateFlow<DevicesState> = mutableState.asStateFlow()

    /** Sealed names from the last successful list, so a rename can keep the kind after an unlock. */
    private val sealedNames = HashMap<UUID, String?>()

    /** The one [refresh] in flight. Set and cleared on [main], before any suspend after the set. */
    private var inFlight: CompletableDeferred<Unit>? = null

    override suspend fun refresh() = refresh(force = false)

    override suspend fun rename(id: UUID, name: String): String? = withContext(main) {
        val current = session() ?: return@withContext DevicesController.UNLOCK_TO_RENAME
        if (!chatsOpen(current)) return@withContext DevicesController.UNLOCK_TO_RENAME
        val kind = openName(sealedNames[id], id)?.kind
            ?: if (isThisDevice(id, current)) DeviceNameSeal.Kind.Android else DeviceNameSeal.Kind.Other
        val label = DeviceNameSeal.Label(name, kind, custom = true)
        try {
            val sealed = sealName(label, id) ?: return@withContext DevicesController.UNLOCK_TO_RENAME
            putDeviceName(current.token, id, sealed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DeviceNameSeal.SealError.EmptyName) {
            return@withContext DevicesController.ENTER_A_NAME
        } catch (e: Exception) {
            return@withContext SessionController.userMessage(e)
        }
        mutableState.update { state ->
            state.copy(rows = state.rows.map { row ->
                if (row.id == id) row.copy(label = label, kind = DeviceKind.of(label)) else row
            })
        }
        reloadAfterAction(null)
        null
    }

    override suspend fun remove(id: UUID): RemoveOutcome = withContext(main) {
        val current = session() ?: return@withContext signedOut()
        if (isThisDevice(id, current)) return@withContext RemoveOutcome.Failed(currentDeviceNote())
        val outcome = try {
            revokeDevice(current.token, id)
            drop(listOf(id))
            RemoveOutcome.Removed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isAlreadyRemoved(e)) {
                drop(listOf(id))
                RemoveOutcome.Removed
            } else {
                RemoveOutcome.Failed(SessionController.userMessage(e))
            }
        }
        reloadAfterAction((outcome as? RemoveOutcome.Failed)?.message)
        outcome
    }

    override suspend fun removeAllOthers(): RemoveOutcome = withContext(main) {
        val current = session() ?: return@withContext signedOut()
        val targets = mutableState.value.rows.filterNot { it.isThisDevice }
        if (targets.isEmpty()) return@withContext RemoveOutcome.Removed
        var removed = 0
        var failed = 0
        var lastError: Throwable? = null
        for (row in targets) {
            try {
                revokeDevice(current.token, row.id)
                drop(listOf(row.id))
                removed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAlreadyRemoved(e)) {
                    drop(listOf(row.id))
                    removed++
                } else {
                    failed++
                    lastError = e
                }
            }
        }
        val sentence = lastError?.let { removeAllFailure(failed, targets.size, it) }
        val outcome = when {
            failed == 0 -> RemoveOutcome.Removed
            removed == 0 -> RemoveOutcome.Failed(sentence!!)
            else -> RemoveOutcome.Partial(removed, failed)
        }
        reloadAfterAction(sentence)
        outcome
    }

    /**
     * Shares an in-flight load. [force] waits that one out and then loads again, so a caller that
     * just wrote sees its own change and not a list fetched before the write.
     */
    private suspend fun refresh(force: Boolean): Unit = withContext(main) {
        while (true) {
            val existing = inFlight ?: break
            existing.await()
            if (!force) return@withContext
        }
        val deferred = CompletableDeferred<Unit>()
        inFlight = deferred
        try {
            performRefresh()
            if (inFlight === deferred) inFlight = null
            deferred.complete(Unit)
        } catch (t: Throwable) {
            if (inFlight === deferred) inFlight = null
            deferred.completeExceptionally(t)
            throw t
        }
    }

    private suspend fun performRefresh() {
        val current = session()
        if (current == null) {
            mutableState.update { it.copy(isLoading = false, error = DevicesController.SIGN_IN) }
            return
        }
        val token = current.token
        val showsLoading = !mutableState.value.hasLoaded
        mutableState.update { it.copy(isLoading = showsLoading, error = null) }
        try {
            val list = listDevices(token)
            val open = chatsOpen(current)
            val rows = ArrayList<DeviceRow>(list.size)
            val sealed = HashMap<UUID, String?>()
            for (device in list) {
                sealed[device.id] = device.sealedName
                rows += toRow(device, current, open)
            }
            sealedNames.clear()
            sealedNames.putAll(sealed)
            mutableState.value = DevicesState(
                rows = sorted(rows),
                isLoading = false,
                hasLoaded = true,
                error = null,
                capacity = DEVICE_LIMIT,
            )
        } catch (e: CancellationException) {
            mutableState.update { it.copy(isLoading = false) }
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = SessionController.userMessage(e)) }
        }
    }

    /** A successful reload clears [DevicesState.error]; a removal sentence is put back afterwards. */
    private suspend fun reloadAfterAction(actionError: String?) {
        refresh(force = true)
        if (actionError != null && mutableState.value.error == null) {
            mutableState.update { it.copy(error = actionError) }
        }
    }

    private fun signedOut(): RemoveOutcome {
        mutableState.update { it.copy(isLoading = false, error = DevicesController.SIGN_IN) }
        return RemoveOutcome.Failed(DevicesController.SIGN_IN)
    }

    private fun chatsOpen(session: Session): Boolean = session.userId.equals(unlockedUserId(), ignoreCase = true)

    private fun isThisDevice(id: UUID, session: Session): Boolean {
        if (Ids.parse(session.deviceId) == id) return true
        return mutableState.value.rows.any { it.id == id && it.isThisDevice }
    }

    private fun toRow(device: LinkedDeviceDto, session: Session, chatsOpen: Boolean): DeviceRow {
        val label = if (chatsOpen) openName(device.sealedName, device.id) else null
        return DeviceRow(
            id = device.id,
            label = label,
            kind = DeviceKind.of(label),
            isThisDevice = device.isCurrent || device.id == Ids.parse(session.deviceId),
            createdAt = device.createdAt,
            lastSeenAt = device.lastSeenAt,
        )
    }

    private fun drop(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        ids.forEach { sealedNames.remove(it) }
        mutableState.update { state -> state.copy(rows = state.rows.filterNot { it.id in ids }) }
    }

    /** "To remove this phone…" (`DevicesView.swift:694-696`). Log Out is the only way. */
    private fun currentDeviceNote(): String =
        "To remove this ${deviceNoun()} from your account, use Log Out in Settings. It also erases everything Shroud keeps here."

    private companion object {
        /** This phone first, then most recently active (`DevicesView.swift:41-46`). No last-seen sorts as linked-at. */
        fun sorted(rows: List<DeviceRow>): List<DeviceRow> {
            val mine = rows.filter { it.isThisDevice }
            val others = rows.filterNot { it.isThisDevice }.sortedWith(
                compareByDescending<DeviceRow> { it.lastSeenAt ?: it.createdAt }.thenBy { it.id.toString() },
            )
            return mine + others
        }

        fun isAlreadyRemoved(error: Throwable): Boolean = (error as? ApiError)?.isNotFound == true

        /** `DevicesView.swift:531-536`. [total] 1 uses the singular sentence; the message is [SessionController.userMessage]. */
        fun removeAllFailure(failed: Int, total: Int, error: Throwable): String {
            val lead = if (total == 1) {
                "The device could not be removed. "
            } else {
                "$failed of $total devices could not be removed. "
            }
            return lead + SessionController.userMessage(error)
        }
    }
}

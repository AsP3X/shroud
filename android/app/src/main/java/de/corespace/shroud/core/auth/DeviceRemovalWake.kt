package de.corespace.shroud.core.auth

import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a removal wake did (iOS `UIBackgroundFetchResult`, `DeviceRemovalWake.swift:32`). */
enum class WakeResult { NoData, NewData, Failed }

/**
 * Wipes this phone when the account removes it while the app is not in front
 * (`ios/shroud/Services/Auth/DeviceRemovalWake.swift`; settings-lock §14.6; web-parity §5.3).
 *
 * Removing a device in Settings › Devices sends it one last push before the server forgets its
 * subscription — Web Push `{"v":1,"kind":"device_removed"}` over UnifiedPush (decision record
 * 2026-10-01; X1-SRV-UP) — and closes its sockets with `auth.error DEVICE_REMOVED` (which reaches
 * the background connection too). **The push alone never deletes anything**: [handle] asks
 * `/auth/me`, and only the server's `DEVICE_REMOVED` answer starts the wipe — the same wipe Log Out
 * runs. W3-PUSH's `PushDispatcher` recognises the payload ([isRemoval]) and runs [handle] from an
 * expedited `DeviceRemovalWorker` (which must not carry [DeviceDataWipe.ACCOUNT_WORK_TAG]).
 */
object DeviceRemovalWake {
    /** `payloadType` (`DeviceRemovalWake.swift:20`). */
    const val PAYLOAD_TYPE = "device_removed"

    /** Everything, the server check included (iOS gives a silent push about 30 s; `:21-22`). */
    const val BUDGET_MS = 25_000L

    /** `confirmTimeout` (`:23`). */
    const val CONFIRM_TIMEOUT_MS = 8_000L

    /** Poll interval while the overlay wipe runs (`:55-57`). */
    const val POLL_MS = 200L

    /**
     * Whether a push's data is the removal wake (`isRemoval`, `:25-27`): the APNs/FCM-style
     * `type` or the Web Push payload's `kind` (`push/payload.rs`) is `device_removed`.
     */
    fun isRemoval(data: Map<String, String>): Boolean = data["type"] == PAYLOAD_TYPE || data["kind"] == PAYLOAD_TYPE

    /** Confirms the removal with the server, then wipes; see [RemovalWake.handle]. */
    suspend fun handle(container: AppContainer): WakeResult = container.auth.removalWake.handle()
}

/**
 * The work of [DeviceRemovalWake.handle], with its dependencies passed in (`handle()`,
 * `DeviceRemovalWake.swift:32-69`).
 *
 * With the UI alive (the shell attached its [WipeRouter], [hasUi]) the overlay wipe runs, so
 * returning to the app shows it finishing. Without UI the stores are emptied directly — writers
 * halted and storage sealed first, since Android's engines may live in the process without an
 * activity — and the pending marker stays, so the next launch's
 * [DeviceWipeController.finishInterruptedWipeIfNeeded] verifies it, ends the session and says
 * "Signed out · this phone was cleared". Unlike iOS a locked phone deletes everything at once:
 * Keystore deletions and app files need no unlock (web-parity §5.3).
 *
 * @param storedSession the session as stored on disk (iOS reads the Keychain, not the controller).
 * @param confirm `GET /auth/me` with that session's token; throws [ApiError] like `ApiClient`.
 */
class RemovalWake(
    private val storedSession: () -> Session?,
    private val confirm: suspend (Session) -> Unit,
    private val session: SessionController,
    private val deviceWipe: DeviceWipeController,
    private val dataWipe: DeviceDataWipe,
    private val hooks: WipeHooks,
    private val storageSeal: StorageSeal,
    private val hasUi: () -> Boolean,
    private val clock: AppClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Returns once the wipe is done, or out of time: the pending marker finishes anything left at
     * the next launch. Main-confined (the session and the wipe controller are).
     */
    suspend fun handle(): WakeResult {
        val deadline = clock.elapsedMillis() + DeviceRemovalWake.BUDGET_MS
        val stored = storedSession() ?: return WakeResult.NoData
        when (confirmRemoved(stored)) {
            // Still part of the account (or refused for another reason): a stale or misdirected push.
            false -> return WakeResult.NoData
            // No answer in time. The socket, the next request or the next launch will tell.
            null -> return WakeResult.Failed
            true -> Unit
        }

        if (hasUi()) {
            // The app holds another login now (the removed session was replaced): leave it (`:47-48`).
            if (session.session.value?.token != stored.token) return WakeResult.NoData
            session.recordDeviceRemoved(stored.token)
            if (!deviceWipe.isPresented.value) {
                session.consumePendingFullLocalWipe()
                // The confirm already saw DEVICE_REMOVED for this session.
                deviceWipe.start(WipeReason.Removed)
            }
            while (deviceWipe.isPresented.value && deviceWipe.phase.value != WipePhase.Failed && clock.elapsedMillis() < deadline) {
                delay(DeviceRemovalWake.POLL_MS)
            }
            return WakeResult.NewData
        }

        // No UI. Nothing may write while the stores are emptied; the marker stays set (`:61-68`).
        storageSeal.seal()
        runCatching { hooks.haltWriters() }
        if (session.session.value?.token == stored.token) session.recordDeviceRemoved(stored.token)
        withContext(io) {
            dataWipe.markPending()
            dataWipe.wipeEverything()
        }
        runCatching { hooks.forgetNotifications() }
        // The next UI start finishes through the marker, not with a second (overlay) wipe.
        session.consumePendingFullLocalWipe()
        return WakeResult.NewData
    }

    /**
     * True when the server says `DEVICE_REMOVED` for this session, false when it still takes it (or
     * refuses it for another reason), null without an answer in time (`confirmRemoved`, `:71-97`).
     */
    private suspend fun confirmRemoved(stored: Session): Boolean? = withTimeoutOrNull(DeviceRemovalWake.CONFIRM_TIMEOUT_MS) {
        try {
            confirm(stored)
            false
        } catch (e: ApiError) {
            when {
                e.isDeviceRemoved -> true
                e is ApiError.Transport -> null
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

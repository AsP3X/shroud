package de.corespace.shroud.core.auth

import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the device wipe is (`DeviceWipeController.Phase`, `DeviceWipeController.swift:41-43`). */
enum class WipePhase { Idle, Running, Done, Failed }

/**
 * The rows of the wipe overlay, in order (`DeviceDataWipe.Step`, `DeviceDataWipe.swift:27-29`;
 * titles `DeviceWipeController.title(for:)`, `DeviceWipeController.swift:344-353`).
 */
enum class WipeStep(val title: String) {
    Session("Signing out"),
    Messages("Messages"),
    Media("Photos, videos & voice"),
    Keys("Encryption keys"),
    Settings("Settings & caches"),
    Verify("Checking nothing is left"),
}

/**
 * Something the overlay says aloud or feels (iOS posts `AccessibilityNotification.Announcement` and
 * `Haptics.notification` from the controller, `DeviceWipeController.swift:187, 198-201, 215-216`).
 * The overlay (W3-LOCK-ONBOARD) announces [announcement] for TalkBack and plays [haptic].
 */
data class WipeFeedback(val announcement: String, val haptic: Haptic = Haptic.None)

/**
 * The shell's side of the end of a wipe (`router?.postAuthToast = nil`, `router?.hasUnlockedMessaging
 * = false`, `router?.path = []`, `DeviceWipeController.swift:234-236`): forget the post-auth toast, mark
 * messaging locked, put the onboarding stack back to Welcome. The shell sets
 * [DeviceWipeController.router] while its composition lives (iOS `deviceWipe.router = router`,
 * `RootView.swift:143`) and clears it on dispose; "a router is attached" is also how the removal wake
 * knows the UI is alive.
 */
fun interface WipeRouter {
    fun onLocalSessionEnded()
}

/**
 * Runs the Log Out / forced sign-out / removal wipe one step at a time behind the wipe overlay
 * (`ios/shroud/Services/Auth/DeviceWipeController.swift`; settings-lock §14.2).
 *
 * Every row the overlay ticks off is real work in [DeviceDataWipe], and the last row proves the
 * result. Only then does the app forget the session and return to Welcome. A step never finishes
 * faster than it can be read (420 ms, verify 560 ms, 120 ms under Reduce Motion); if something
 * survives, the overlay says what and offers Try Again / Continue.
 *
 * Writers stop before the first suspension: [start] seals [storageSeal] (crypto §14) and halts every
 * package through [hooks] synchronously. The run lives in [appScope], so it survives the screens being
 * torn down; it also runs headless (no overlay), e.g. for a removal while the app is in the background.
 *
 * State is main-confined; file and Keystore work runs on [io]. One run at a time.
 *
 * @param endServerSession `POST auth/logout` with the session's token (`ShroudApi.logout`).
 * @param reduceMotion Android "Remove animations" (animator duration scale 0), read when a run starts.
 * @param deviceNoun "phone" or "tablet" ([DeviceNoun]).
 * @param isUserUnlocked credential-encrypted storage is readable (always true once the container exists).
 */
class DeviceWipeController(
    private val dataWipe: DeviceDataWipe,
    private val session: SessionController,
    private val hooks: WipeHooks,
    private val storageSeal: StorageSeal,
    private val appScope: CoroutineScope,
    private val clock: AppClock,
    private val endServerSession: suspend (token: String) -> Unit,
    private val reduceMotion: () -> Boolean = { false },
    private val deviceNoun: () -> String = { DeviceNoun.PHONE },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val isUserUnlocked: () -> Boolean = { true },
) {
    private val phaseState = MutableStateFlow(WipePhase.Idle)
    private val reasonState = MutableStateFlow(WipeReason.Logout)
    private val activeState = MutableStateFlow<WipeStep?>(null)
    private val detailsState = MutableStateFlow<Map<WipeStep, String>>(emptyMap())
    private val leftoversState = MutableStateFlow<List<DeviceDataWipe.Leftover>>(emptyList())
    private val retryingState = MutableStateFlow<Set<WipeStep>>(emptySet())
    private val handleState = MutableStateFlow("")
    private val presented = MutableStateFlow(false)
    private val feedbackFlow = MutableSharedFlow<WipeFeedback>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val phase: StateFlow<WipePhase> = phaseState.asStateFlow()
    val reason: StateFlow<WipeReason> = reasonState.asStateFlow()

    /** The step whose row is spinning. */
    val active: StateFlow<WipeStep?> = activeState.asStateFlow()

    /** Finished steps and what they report ("214 removed"). */
    val details: StateFlow<Map<WipeStep, String>> = detailsState.asStateFlow()
    val leftovers: StateFlow<List<DeviceDataWipe.Leftover>> = leftoversState.asStateFlow()

    /** Rows that failed and are running again under "Try Again". */
    val retrying: StateFlow<Set<WipeStep>> = retryingState.asStateFlow()

    /** "@name", captured before the session goes; empty when it was already gone. */
    val handle: StateFlow<String> = handleState.asStateFlow()

    /** `phase != Idle`: the overlay is up (`isPresented`, `:57`). */
    val isPresented: StateFlow<Boolean> = presented.asStateFlow()

    /** Announcements and haptics for the overlay. */
    val feedback: SharedFlow<WipeFeedback> = feedbackFlow.asSharedFlow()

    /** The shell's router while its composition lives; see [WipeRouter]. Main-confined. */
    @Volatile
    var router: WipeRouter? = null

    private var inventory = DeviceDataWipe.Inventory()

    // ---- Running ----

    /**
     * Starts the wipe for [reason] (`start(reason:)`, `:81-95`); ignored unless idle. Seals storage and
     * halts every writer before anything suspends: a poll landing between here and the run would
     * refill a store the wipe is about to delete.
     */
    fun start(reason: WipeReason) {
        if (phaseState.value != WipePhase.Idle) return
        reasonState.value = reason
        handleState.value = session.session.value?.username?.let { "@$it" } ?: ""
        detailsState.value = emptyMap()
        leftoversState.value = emptyList()
        retryingState.value = emptySet()
        storageSeal.seal()
        haltWriters()
        setPhase(WipePhase.Running)
        appScope.launch { run() }
    }

    /**
     * The root's reaction to a session the server ended (`RootView.swift:187-194`): when
     * [SessionController.pendingFullLocalWipe] is set and no wipe runs, consume it and start the wipe
     * with [SessionController.pendingWipeReason]. Returns whether it started one.
     */
    fun startIfSessionEnded(): Boolean {
        if (!session.pendingFullLocalWipe.value || presented.value) return false
        session.consumePendingFullLocalWipe()
        start(session.pendingWipeReason)
        return true
    }

    /** "Try Again" after a failed check (`retry()`, `:97-108`): runs the check again. */
    fun retry() {
        if (phaseState.value != WipePhase.Failed) return
        retryingState.value = leftoversState.value.map { it.step }.toSet()
        leftoversState.value = emptyList()
        setPhase(WipePhase.Running)
        appScope.launch {
            val reduce = reduceMotion()
            if (perform(WipeStep.Verify, reduce, token = null)) complete(reduce)
        }
    }

    /**
     * "Continue" after a failed check (`continueAfterFailure()`, `:110-115`): the session is gone
     * either way, and the next launch tries the wipe again (the pending marker is still set).
     */
    fun continueAfterFailure() {
        if (phaseState.value != WipePhase.Failed) return
        appScope.launch { finish(reduce = true) }
    }

    private suspend fun run() {
        val reduce = reduceMotion()
        // Every reason still holds the token: Log Out has not cleared it, and a forced sign-out
        // leaves it in place so the session step can revoke it and report an offline server (`:119-121`).
        val token = session.session.value?.token
        // Nothing may write while the stores are emptied (`:122-125`).
        haltWriters()
        inventory = try {
            withContext(io) { dataWipe.inventory() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DeviceDataWipe.Inventory()
        }
        onIo { dataWipe.markPending() }
        for (step in WipeStep.entries) {
            if (!perform(step, reduce, token)) return
        }
        complete(reduce)
    }

    /** Runs one step and fills in its row; false when Verify found something it cannot remove (`perform`, `:134-189`). */
    private suspend fun perform(step: WipeStep, reduce: Boolean, token: String?): Boolean {
        activeState.value = step
        val started = clock.elapsedMillis()
        val detail: String = when (step) {
            WipeStep.Session -> {
                val outcome = endServerSessionOutcome(token)
                val said = WipeReason.after(reasonState.value, outcome)
                if (said != reasonState.value) reasonState.value = said
                when (outcome) {
                    ServerSessionOutcome.Offline -> "Ended here · server offline"
                    ServerSessionOutcome.UpdateRequired -> "Ended here · app update needed"
                    ServerSessionOutcome.Ended, ServerSessionOutcome.Removed -> "Session ended"
                }
            }
            WipeStep.Messages -> {
                onIo { dataWipe.wipeMessages() }
                removed(inventory.messages)
            }
            WipeStep.Media -> {
                onIo { dataWipe.wipeMedia() }
                if (inventory.mediaFiles == 0) "None stored" else inventory.mediaSummary
            }
            WipeStep.Keys -> {
                onIo { dataWipe.wipeKeys() }
                removed(inventory.keys)
            }
            WipeStep.Settings -> {
                onIo { dataWipe.wipeSettings() }
                "Cleared"
            }
            WipeStep.Verify -> {
                var found = checkLeftovers()
                if (found.isNotEmpty()) {
                    onIo { dataWipe.wipeEverything() }
                    found = checkLeftovers()
                }
                // No wait for data protection as on iOS (`:165-172`): credential-encrypted storage and
                // Keystore deletions are available after the first unlock, and nothing runs before it.
                pace(started, step, reduce)
                if (found.isNotEmpty()) {
                    fail(found)
                    return false
                }
                onIo { dataWipe.clearPending() }
                "Nothing left"
            }
        }
        if (step != WipeStep.Verify) pace(started, step, reduce)
        detailsState.value = detailsState.value + (step to detail)
        retryingState.value = if (step == WipeStep.Verify) emptySet() else retryingState.value - step
        feedbackFlow.tryEmit(WipeFeedback("${step.title}: $detail"))
        return true
    }

    private fun fail(found: List<DeviceDataWipe.Leftover>) {
        activeState.value = null
        retryingState.value = emptySet()
        leftoversState.value = found
        setPhase(WipePhase.Failed)
        feedbackFlow.tryEmit(WipeFeedback("Some data could not be removed: ${labels(found)}.", Haptic.Error))
    }

    private suspend fun complete(reduce: Boolean) {
        activeState.value = null
        setPhase(WipePhase.Done)
        feedbackFlow.tryEmit(WipeFeedback("This ${deviceNoun()} is clear. Nothing from your account is left on it.", Haptic.Success))
        endLocalSession()
        delay(if (reduce) REDUCED_DONE_HOLD_MS else DONE_HOLD_MS)
        finish(reduce)
    }

    /**
     * The app forgets the session only now, behind the overlay, so Welcome is what it reveals
     * (`endLocalSession()`, `:222-237`). Each package's hook runs even if another failed.
     */
    private suspend fun endLocalSession() {
        // A 401 the wipe's own logout got may have asked for another wipe; this one covers it.
        session.consumePendingFullLocalWipe()
        step { session.logout() }
        step { hooks.lockCrypto(wipeStore = true) }
        step { hooks.stopMessaging(wipeDisk = true) }
        step { hooks.clearCalls() }
        step { withTimeoutOrNull(HOOK_TIMEOUT_MS) { hooks.forgetPush() } }
        step { hooks.forgetNotifications() }
        step { hooks.forgetAppearance() }
        step { router?.onLocalSessionEnded() }
    }

    private suspend fun finish(reduce: Boolean) {
        if (phaseState.value == WipePhase.Failed) endLocalSession()
        // After leftovers came back empty (or Continue), with Welcome showing: sign-in may write again (crypto §14).
        storageSeal.unseal()
        activeState.value = null
        setPhase(WipePhase.Idle)
    }

    private suspend fun pace(started: Long, step: WipeStep, reduce: Boolean) {
        val floor = when {
            reduce -> REDUCED_PACE_MS
            step == WipeStep.Verify -> VERIFY_PACE_MS
            else -> STEP_PACE_MS
        }
        val remaining = floor - (clock.elapsedMillis() - started)
        if (remaining > 0) delay(remaining)
    }

    /**
     * Revokes the token on the server (`endServerSession(token:)`, `:259-283`), racing
     * [SERVER_TIMEOUT_MS]. No token: nothing to end. The request goes to the server the session
     * belongs to even when a server change is pending (`ApiClient` resolves the URL before suspending).
     */
    private suspend fun endServerSessionOutcome(token: String?): ServerSessionOutcome {
        if (token == null) return ServerSessionOutcome.Ended
        return withTimeoutOrNull(SERVER_TIMEOUT_MS) {
            try {
                endServerSession(token)
                ServerSessionOutcome.Ended
            } catch (e: ApiError) {
                serverSessionOutcome(e)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ServerSessionOutcome.Offline
            }
        } ?: ServerSessionOutcome.Offline
    }

    /**
     * Runs a deleting step on [io]. An unexpected failure must neither crash the app half-way nor
     * leave the overlay spinning: the run goes on, and the verify pass finds what is left.
     */
    private suspend fun onIo(block: suspend () -> Unit) {
        step { withContext(io) { block() } }
    }

    /** The verify scan; a scan that fails is not "clean". */
    private suspend fun checkLeftovers(): List<DeviceDataWipe.Leftover> = try {
        withContext(io) { dataWipe.leftovers() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        listOf(DeviceDataWipe.Leftover(WipeStep.Settings, DeviceDataWipe.LABEL_SETTINGS))
    }

    private fun haltWriters() {
        runCatching { hooks.haltWriters() }
    }

    private fun setPhase(next: WipePhase) {
        phaseState.value = next
        presented.value = next != WipePhase.Idle
    }

    /** Runs one end-of-session hook; a failing package does not stop the others. */
    private suspend fun step(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing to show: the verify pass already ran, and the next launch sweeps again.
        }
    }

    // ---- Launch (`finishInterruptedWipeIfNeeded()`, `:303-333`) ----

    /**
     * Called once at launch, before the session is used. Finishes a wipe the app was killed in the
     * middle of (the pending marker), and silently sweeps what is left on a signed-out phone. A
     * signed-in phone is only touched when a wipe was pending, and an unreadable session file only
     * counts as signed out when it can never open again (ST1).
     *
     * @return true when a pending wipe was finished, so the shell can say
     *   "Signed out · this phone was cleared".
     */
    suspend fun finishInterruptedWipeIfNeeded(): Boolean {
        val pending = withContext(io) { dataWipe.isPending }
        val signedOut = session.session.value == null && isUserUnlocked() &&
            withContext(io) { session.hasNoSession() || session.hasUnreadableSession() }
        if (!pending && !signedOut) return false
        // Killed before the server heard about it: the token is still here, so try again.
        val token = session.session.value?.token
        if (pending && token != null) {
            session.beginInterruptedWipe()
            endServerSessionOutcome(token)
        }
        onIo { dataWipe.wipeEverything() }
        // The call secrets' in-memory map, or a later save would write them back (review W2).
        step { hooks.clearCalls() }
        step { hooks.forgetNotifications() }
        step { hooks.forgetAppearance() }
        if (checkLeftovers().isEmpty()) onIo { dataWipe.clearPending() }
        if (!pending) return false
        if (session.session.value != null) step { session.logout() }
        step { hooks.lockCrypto(wipeStore = true) }
        // A removal wake without UI sealed storage and halted writers (DeviceRemovalWake): the session is over now.
        storageSeal.unseal()
        return true
    }

    companion object {
        const val STEP_PACE_MS = 420L
        const val VERIFY_PACE_MS = 560L
        const val REDUCED_PACE_MS = 120L
        const val DONE_HOLD_MS = 1_400L
        const val REDUCED_DONE_HOLD_MS = 900L
        const val SERVER_TIMEOUT_MS = 4_000L

        /** Bound on the push forget (UNREGISTER, subscription delete) so a hung request cannot hold the overlay. */
        const val HOOK_TIMEOUT_MS = 4_000L

        /**
         * 401: already over — `DEVICE_REMOVED` says why. Anything but "could not connect" or
         * `426 UPDATE_REQUIRED` (refused before the logout ran) means the server heard us
         * (`serverSessionOutcome(of:)`, `:285-291`).
         */
        fun serverSessionOutcome(error: ApiError): ServerSessionOutcome = when {
            error.isDeviceRemoved -> ServerSessionOutcome.Removed
            error is ApiError.Transport -> ServerSessionOutcome.Offline
            error.isUpdateRequired -> ServerSessionOutcome.UpdateRequired
            else -> ServerSessionOutcome.Ended
        }

        /** "media and cached files, settings" — each kind once, in the order found (`labels(of:)`, `:204-208`). */
        fun labels(found: List<DeviceDataWipe.Leftover>): String = found.map { it.label }.distinct().joinToString(", ")

        private fun removed(count: Int): String = if (count == 0) "None stored" else "$count removed"
    }
}

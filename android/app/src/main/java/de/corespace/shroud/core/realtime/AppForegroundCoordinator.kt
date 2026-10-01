package de.corespace.shroud.core.realtime

import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The socket half of iOS's scene-phase handling (`RootView.swift:246-300, 330-343`; api-realtime
 * §11.13, plan §1.4, §1.7.3), driven by [AppPhaseMonitor] instead of `ProcessLifecycleOwner`
 * (plan C13): no 700 ms delay before the "away" focus frame.
 *
 * - The app comes to the front ([AppPhase.Active]) with the chats unlocked →
 *   [MessagingForeground.handleAppBecameActive] (`RootView.swift:275-294`): hold the socket,
 *   `focus:true`, catch up. Every arrival counts, also back from a system dialog, as on iOS.
 * - The app leaves ([AppPhase.Background]) with the chats unlocked →
 *   [MessagingForeground.leaveForeground] (`RootView.swift:257-262`, `stepAway` `:332-343`):
 *   `focus:false`, then release messaging's hold unless something keeps the socket — a call
 *   (`:334`) or the background connection (plan §1.4).
 * - [AppPhase.Inactive] changes nothing for the socket.
 * - A reason to keep the socket that ends while the app is away — a call ([onCallEnded]) or the
 *   background connection ([onBackgroundConnectionEnded]) — steps away again, so the socket
 *   closes once nothing needs it (`RootView.swift:244-253`).
 *
 * No `beginBackgroundTask` equivalent is needed: Android does not suspend the process at stop
 * (api-realtime §11.13). [messaging] and [calls] return null until W2-INT wires the controllers;
 * nothing happens then. All on [scope] (`AppContainer.appScope`, main).
 *
 * @param isUnlocked iOS `router.isUnlocked`: signed in, no wipe running, chats unlocked.
 * @param backgroundConnectionHeld whether the opt-in background connection holds the socket
 *   (`RealtimeClient.isHeld(Holder.Background)`); then leaving keeps messaging's hold too, so its
 *   engines keep running on the socket's events while the vault is open (plan §1.4).
 */
class AppForegroundCoordinator(
    private val messaging: () -> MessagingForeground?,
    private val calls: () -> ActiveCallProbe?,
    private val isUnlocked: () -> Boolean,
    private val phase: AppPhaseMonitor,
    private val scope: CoroutineScope,
    private val backgroundConnectionHeld: () -> Boolean = { false },
) {
    private var started = false

    /** Starts following the app phase; once per process (`RealtimeModule.onProcessStart`). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: AppPhase? = null
            phase.phase.collect { next ->
                val before = previous
                previous = next
                when (next) {
                    AppPhase.Active -> becameActive()
                    // The phase a process starts in is not a departure: nothing to leave yet.
                    AppPhase.Background -> if (before != null) stepAway()
                    AppPhase.Inactive -> Unit
                }
            }
        }
    }

    /**
     * A call ended (`RootView.swift:244-253`): while the app is in the background nothing but the
     * call needed the socket, so step away again and let it close (unless the background
     * connection still holds it). The call controller calls this after releasing its hold.
     */
    fun onCallEnded() {
        if (phase.phase.value == AppPhase.Background) stepAway()
    }

    /**
     * The background connection was switched off or its service stopped (W3-PUSH calls this after
     * `release(Holder.Background)`). Leaving the app kept messaging's hold for it (plan §1.4), so
     * in the background this steps away again exactly as [onCallEnded] does
     * (`RootView.swift:244-253`): the socket closes unless a call still needs it.
     */
    fun onBackgroundConnectionEnded() {
        if (phase.phase.value == AppPhase.Background) stepAway()
    }

    private fun becameActive() {
        val messaging = messaging() ?: return
        if (isUnlocked()) messaging.handleAppBecameActive()
    }

    /** `RootView.swift:332-343`. */
    private fun stepAway() {
        val messaging = messaging() ?: return
        if (!isUnlocked()) return
        val keepSocket = calls()?.hasActiveCall == true || backgroundConnectionHeld()
        scope.launch { messaging.leaveForeground(keepSocket) }
    }
}

package de.corespace.shroud.core.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The messages poll (`startPollingFallback`, `MessagingController.swift:705-741`; messaging-core §6.1):
 * every [TICK_MS] it notices connectivity changes, and while the network is up
 *
 * - the socket is **down** (some reverse proxies block it): refresh the chat list, reconcile the open
 *   chat and flush the outbox — every tick;
 * - the socket is **up**: a slower safety net, every [SAFETY_EVERY]th tick (≈ 15 s), refreshes the
 *   list and the open chat without a reconcile (the socket already delivered deletes).
 *
 * The contacts poll is the contacts controller's (plan C6, contacts §4.10). Main-confined.
 */
class PollingLoop(private val scope: CoroutineScope, private val host: Host) {
    /** What one tick asks of the controller. */
    interface Host {
        val isOnline: Boolean
        val isRealtimeConnected: Boolean

        /** The network came or went since the last tick (`handleConnectivityChanged`). */
        fun onConnectivityChanged(online: Boolean)

        /** No change, but the published offline flag may lag (`MessagingController.swift:720-722`). */
        fun syncOffline(online: Boolean)

        /** Socket down: list, reconcile of the open chat, outbox (`:725-730`). */
        suspend fun pollWithoutSocket()

        /** Socket up, every [SAFETY_EVERY]th tick: list and the open chat (`:731-737`). */
        suspend fun safetyPoll()
    }

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** (Re)starts the loop from tick 0. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            var tick = 0
            var lastOnline = true
            while (isActive) {
                delay(TICK_MS)
                tick++
                val online = host.isOnline
                if (online != lastOnline) {
                    lastOnline = online
                    host.onConnectivityChanged(online)
                } else {
                    host.syncOffline(online)
                }
                if (!online) continue
                if (!host.isRealtimeConnected) {
                    host.pollWithoutSocket()
                } else if (tick % SAFETY_EVERY == 0) {
                    host.safetyPoll()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** `MessagingController.swift:713`. */
        const val TICK_MS = 3_000L

        /** `MessagingController.swift:731`. */
        const val SAFETY_EVERY = 5
    }
}

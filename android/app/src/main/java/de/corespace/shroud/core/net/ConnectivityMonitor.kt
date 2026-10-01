package de.corespace.shroud.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reachability for offline-first messaging — the port of `ConnectivityMonitor.swift`
 * (`Services/Messaging/ConnectivityMonitor.swift:1-37`; api-realtime §11.14, plan C5).
 *
 * iOS publishes `isOnline = path.status == .satisfied` from an `NWPathMonitor`, starting
 * optimistic (`:11`). Android follows the system **default network**
 * (`ConnectivityManager.registerDefaultNetworkCallback`): online while it exists and has
 * `NET_CAPABILITY_INTERNET`. `NET_CAPABILITY_VALIDATED` is not required — "satisfied" does not
 * require it either, and a captive or slow-to-validate network must not read as offline.
 *
 * [networkAvailable] fires whenever a default network (re)appears, network switches included —
 * the hook for `RealtimeClient.onNetworkAvailable()` (a failed socket reconnects at once,
 * api-realtime §17-4). Callbacks arrive on a ConnectivityManager thread; both flows are
 * thread-safe. Needs `ACCESS_NETWORK_STATE` (in the manifest).
 */
class ConnectivityMonitor internal constructor(private val source: NetworkSource) {
    constructor(context: Context) : this(SystemNetworkSource(context.applicationContext))

    private val tracker = DefaultNetworkTracker()
    private val online = MutableStateFlow(true)
    private val available = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false

    /** Whether the phone has a network that reaches the internet. True until [start] learns otherwise (iOS `:11`). */
    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    /** A default network became available (also a switch, e.g. Wi-Fi → mobile). No replay. */
    val networkAvailable: SharedFlow<Unit> = available.asSharedFlow()

    /** Starts listening; idempotent (`:17-30`). Never throws: a failed registration leaves the monitor optimistic. */
    @Synchronized
    fun start() {
        if (started) return
        // The current state first; the callbacks that follow registration keep it up to date.
        online.value = source.currentHasInternet()
        started = source.register(object : NetworkSource.Listener {
            override fun onAvailable(network: Any, hasInternet: Boolean?) = publish(tracker.onAvailable(network, hasInternet))
            override fun onCapabilitiesChanged(network: Any, hasInternet: Boolean) = publish(tracker.onCapabilitiesChanged(network, hasInternet))
            override fun onLost(network: Any) = publish(tracker.onLost(network))
        })
        // Untracked, a snapshot would never change again: stay optimistic instead.
        if (!started) online.value = true
    }

    /** Stops listening; idempotent (`:32-36`). [isOnline] keeps its last value. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        source.unregister()
        tracker.reset()
    }

    private fun publish(change: DefaultNetworkTracker.Change) {
        change.online?.let { online.value = it }
        if (change.becameAvailable) available.tryEmit(Unit)
    }

    /** The platform side, behind an interface so the tracking logic runs in JVM tests. */
    internal interface NetworkSource {
        /** Registers [listener] for the default network; false when the system refused. */
        fun register(listener: Listener): Boolean
        fun unregister()

        /** The current default network has `NET_CAPABILITY_INTERNET`. */
        fun currentHasInternet(): Boolean

        interface Listener {
            /** [hasInternet] null: capabilities not known yet (they follow in [onCapabilitiesChanged]). */
            fun onAvailable(network: Any, hasInternet: Boolean?)
            fun onCapabilitiesChanged(network: Any, hasInternet: Boolean)
            fun onLost(network: Any)
        }
    }
}

/**
 * Which network is the default and whether it reaches the internet, from the default-network
 * callbacks. Pure logic: every event returns what changed.
 *
 * - `onAvailable(n)`: n is the default now (a switch replaces the old one). Online unless its
 *   capabilities are known to lack internet; announces availability.
 * - `onCapabilitiesChanged(n)` for the current default: online = has internet; gaining it
 *   announces availability. Events for an older network are ignored.
 * - `onLost(n)` for the current default: offline until the next `onAvailable`. A late loss of a
 *   network that was already replaced is ignored.
 */
internal class DefaultNetworkTracker {
    /** [online]: the new value, or null when unchanged by this event. */
    data class Change(val online: Boolean?, val becameAvailable: Boolean)

    private var current: Any? = null
    private var currentHasInternet = false

    @Synchronized
    fun onAvailable(network: Any, hasInternet: Boolean?): Change {
        current = network
        currentHasInternet = hasInternet != false
        return Change(online = currentHasInternet, becameAvailable = currentHasInternet)
    }

    @Synchronized
    fun onCapabilitiesChanged(network: Any, hasInternet: Boolean): Change {
        if (network != current) return Change(online = null, becameAvailable = false)
        val gained = hasInternet && !currentHasInternet
        currentHasInternet = hasInternet
        return Change(online = hasInternet, becameAvailable = gained)
    }

    @Synchronized
    fun onLost(network: Any): Change {
        if (network != current) return Change(online = null, becameAvailable = false)
        current = null
        currentHasInternet = false
        return Change(online = false, becameAvailable = false)
    }

    @Synchronized
    fun reset() {
        current = null
        currentHasInternet = false
    }
}

/** [ConnectivityMonitor.NetworkSource] on `ConnectivityManager`. */
private class SystemNetworkSource(context: Context) : ConnectivityMonitor.NetworkSource {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun register(listener: ConnectivityMonitor.NetworkSource.Listener): Boolean {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) =
                listener.onAvailable(network, manager.getNetworkCapabilities(network)?.hasInternet())

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                listener.onCapabilitiesChanged(network, capabilities.hasInternet())

            override fun onLost(network: Network) = listener.onLost(network)
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            this.callback = callback
            true
        } catch (_: RuntimeException) {
            // SecurityException without ACCESS_NETWORK_STATE, TooManyRequestsException past the
            // per-app callback limit: stay optimistic rather than fail the process start.
            false
        }
    }

    override fun unregister() {
        val registered = callback ?: return
        callback = null
        try {
            manager.unregisterNetworkCallback(registered)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    override fun currentHasInternet(): Boolean {
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasInternet() == true
    }

    private fun NetworkCapabilities.hasInternet() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

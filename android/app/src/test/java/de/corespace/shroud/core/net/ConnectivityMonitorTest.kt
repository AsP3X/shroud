package de.corespace.shroud.core.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ConnectivityMonitor` (api-realtime §11.14; iOS `ConnectivityMonitor.swift`) over a fake
 * default-network source: optimistic until started, then the default network's internet
 * capability; switches, late callbacks for replaced networks and a refused registration.
 */
class ConnectivityMonitorTest {
    private class FakeSource(var internet: Boolean = true, var accept: Boolean = true) : ConnectivityMonitor.NetworkSource {
        var listener: ConnectivityMonitor.NetworkSource.Listener? = null
        var registrations = 0
        var unregistrations = 0

        override fun register(listener: ConnectivityMonitor.NetworkSource.Listener): Boolean {
            registrations++
            if (!accept) return false
            this.listener = listener
            return true
        }

        override fun unregister() {
            unregistrations++
            listener = null
        }

        override fun currentHasInternet() = internet
    }

    @Test
    fun optimisticUntilStartedLikeIos() {
        // ConnectivityMonitor.swift:11 — `isOnline = true` before the first path update.
        val monitor = ConnectivityMonitor(FakeSource(internet = false))
        assertTrue(monitor.isOnline.value)
        monitor.start()
        assertFalse(monitor.isOnline.value)
    }

    @Test
    fun followsTheDefaultNetwork() = runTest {
        val source = FakeSource(internet = false)
        val monitor = ConnectivityMonitor(source)
        val announced = mutableListOf<Unit>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            monitor.networkAvailable.toList(announced)
        }
        monitor.start()
        val listener = source.listener!!
        assertFalse(monitor.isOnline.value)

        listener.onAvailable("wifi", hasInternet = null)       // capabilities follow
        assertTrue(monitor.isOnline.value)
        assertEquals(1, announced.size)
        listener.onCapabilitiesChanged("wifi", hasInternet = true)
        assertEquals("no new announcement without a change", 1, announced.size)

        listener.onCapabilitiesChanged("wifi", hasInternet = false)
        assertFalse(monitor.isOnline.value)
        listener.onCapabilitiesChanged("wifi", hasInternet = true)
        assertTrue(monitor.isOnline.value)
        assertEquals("regaining internet is news for the socket", 2, announced.size)

        // A switch: the new default arrives, then the old one's loss is late news.
        listener.onAvailable("mobile", hasInternet = true)
        assertEquals(3, announced.size)
        listener.onLost("wifi")
        assertTrue(monitor.isOnline.value)
        listener.onCapabilitiesChanged("wifi", hasInternet = false)
        assertTrue(monitor.isOnline.value)

        listener.onLost("mobile")
        assertFalse(monitor.isOnline.value)
        listener.onAvailable("vpn", hasInternet = false)
        assertFalse(monitor.isOnline.value)
        assertEquals("a network without internet is not available", 3, announced.size)
        collector.cancel()
    }

    @Test
    fun startAndStopAreIdempotent() {
        val source = FakeSource()
        val monitor = ConnectivityMonitor(source)
        monitor.start()
        monitor.start()
        assertEquals(1, source.registrations)
        monitor.stop()
        monitor.stop()
        assertEquals(1, source.unregistrations)
        assertNull(source.listener)
        monitor.start()
        assertEquals(2, source.registrations)
    }

    @Test
    fun aRefusedRegistrationStaysOptimistic() {
        val source = FakeSource(internet = false, accept = false)
        val monitor = ConnectivityMonitor(source)
        monitor.start()
        assertTrue(monitor.isOnline.value)
        monitor.stop()
        assertEquals("nothing to unregister", 0, source.unregistrations)
    }

    @Test
    fun theTrackerIgnoresEventsForOtherNetworks() {
        val tracker = DefaultNetworkTracker()
        assertEquals(DefaultNetworkTracker.Change(online = null, becameAvailable = false), tracker.onLost("a"))
        assertEquals(DefaultNetworkTracker.Change(online = true, becameAvailable = true), tracker.onAvailable("a", null))
        assertEquals(DefaultNetworkTracker.Change(online = null, becameAvailable = false), tracker.onCapabilitiesChanged("b", false))
        assertEquals(DefaultNetworkTracker.Change(online = false, becameAvailable = false), tracker.onLost("a"))
        tracker.reset()
        assertEquals(DefaultNetworkTracker.Change(online = null, becameAvailable = false), tracker.onCapabilitiesChanged("a", true))
    }
}

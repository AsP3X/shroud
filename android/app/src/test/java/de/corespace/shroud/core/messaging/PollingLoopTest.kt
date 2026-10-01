package de.corespace.shroud.core.messaging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The messages poll (`startPollingFallback`, `MessagingController.swift:705-741`; messaging-core §6.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingLoopTest {
    private val scopes = EngineScopes()

    @After
    fun tearDown() = scopes.cancelAll()

    /**
     * `runTest` drains the scheduler after the body (`advanceUntilIdleOr { false }`): an engine loop
     * still running then (the poll, a prefetch) would spin forever, so the engine scopes end with
     * the body.
     */
    private fun engineTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            scopes.cancelAll()
        }
    }

    private class Host : PollingLoop.Host {
        override var isOnline = true
        override var isRealtimeConnected = false
        val log = ArrayList<String>()
        override fun onConnectivityChanged(online: Boolean) {
            log += "changed:$online"
        }
        override fun syncOffline(online: Boolean) {
            log += "sync:$online"
        }
        override suspend fun pollWithoutSocket() {
            log += "poll"
        }
        override suspend fun safetyPoll() {
            log += "safety"
        }
    }

    @Test
    fun everyTickWithoutTheSocketAndEveryFifthWithIt() = engineTest {
        val host = Host()
        val loop = PollingLoop(scopes.create(testScheduler), host)
        loop.start()
        assertTrue(loop.isRunning)
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(host.log.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("sync:true", "poll"), host.log)

        host.log.clear()
        host.isRealtimeConnected = true
        // Ticks 2…5: the fifth is the ≈15 s safety poll.
        advanceTimeBy(4 * PollingLoop.TICK_MS)
        runCurrent()
        assertEquals(listOf("sync:true", "sync:true", "sync:true", "sync:true", "safety"), host.log)
        loop.stop()
        assertFalse(loop.isRunning)
    }

    @Test
    fun aConnectivityChangeIsReportedOnceAndOfflineTicksDoNothing() = engineTest {
        val host = Host()
        val loop = PollingLoop(scopes.create(testScheduler), host)
        loop.start()
        host.isOnline = false
        advanceTimeBy(2 * PollingLoop.TICK_MS + 1)
        runCurrent()
        assertEquals(listOf("changed:false", "sync:false"), host.log)

        host.log.clear()
        host.isOnline = true
        advanceTimeBy(PollingLoop.TICK_MS)
        runCurrent()
        assertEquals(listOf("changed:true", "poll"), host.log)
        loop.stop()
    }
}

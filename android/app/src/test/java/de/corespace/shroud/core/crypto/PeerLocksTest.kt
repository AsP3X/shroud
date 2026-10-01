package de.corespace.shroud.core.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Per-peer serialization of decrypt/seal sequences (plan §1.4; messaging-core §23 item 2 and D4;
 * iOS gets it from the main actor, `ios/shroud/Services/Messaging/MessageDecoder.swift:186-213`;
 * web `withPeerLock`, `web/src/messaging.ts:147-161`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLocksTest {
    private val locks = PeerLocks()

    @Test
    fun onePeerRunsOneBlockAtATime() = runBlocking(Dispatchers.Default) {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        var counter = 0 // deliberately unsynchronised: the lock is what protects it
        (1..8).map {
            async {
                repeat(100) {
                    locks.withPeer(ALICE) {
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        val read = counter
                        yield() // a suspension inside the critical section, like a ratchet save
                        counter = read + 1
                        active.decrementAndGet()
                    }
                }
            }
        }.awaitAll()
        assertEquals(1, maxActive.get())
        assertEquals(800, counter)
        assertEquals(0, locks.trackedPeerCount)
    }

    @Test
    fun differentPeersDoNotWaitForEachOther() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder = launch { locks.withPeer(ALICE) { release.await() } }
        runCurrent()
        var bobRan = false
        val bob = launch { locks.withPeer(BOB) { bobRan = true } }
        runCurrent()
        assertTrue("Bob's block runs while Alice's lock is held", bobRan)
        assertTrue(bob.isCompleted)
        assertFalse(holder.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(holder.isCompleted)
    }

    @Test
    fun aSecondCallerWaitsUntilTheFirstReleases() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { locks.withPeer(ALICE) { order += "first in"; release.await(); order += "first out" } }
        runCurrent()
        launch { locks.withPeer(ALICE) { order += "second" } }
        runCurrent()
        assertEquals(listOf("first in"), order)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first in", "first out", "second"), order)
    }

    @Test
    fun waitersAreServedFirstComeFirstServed() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        launch { locks.withPeer(ALICE) { release.await() } }
        runCurrent()
        for (i in 1..5) {
            launch { locks.withPeer(ALICE) { order += i; yield() } }
            runCurrent() // each waiter is queued before the next one starts
        }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun theBlockResultIsReturned() = runTest {
        assertEquals("opened", locks.withPeer(ALICE) { "opened" })
        assertEquals(42, locks.withPeer(BOB) { 42 })
    }

    @Test
    fun theSamePeerIsReentrant() = runTest {
        val result = locks.withPeer(ALICE) {
            locks.withPeer(ALICE) {
                locks.withPeer(UUID.fromString(ALICE.toString().uppercase())) { "nested" }
            }
        }
        assertEquals("nested", result)
        assertEquals(0, locks.trackedPeerCount)
    }

    @Test
    fun reentryWorksAcrossWithContextAndJoinedChildren() = runTest {
        val result = locks.withPeer(ALICE) {
            val viaDispatcher = withContext(Dispatchers.Default) { locks.withPeer(ALICE) { "default" } }
            // A child started inside the block (structured, so it inherits the held lock) and joined.
            val child = coroutineScope { async { locks.withPeer(ALICE) { "child" } }.await() }
            viaDispatcher + "+" + child
        }
        assertEquals("default+child", result)
    }

    @Test
    fun nestingAnotherPeerFailsInsteadOfRiskingADeadlock() = runTest {
        val error = assertThrowsSuspending<IllegalStateException> {
            locks.withPeer(ALICE) { locks.withPeer(BOB) { fail("must not run") } }
        }
        assertTrue(error.message!!.contains("one peer at a time"))
        // Both locks are free again.
        assertEquals("alice", locks.withPeer(ALICE) { "alice" })
        assertEquals("bob", locks.withPeer(BOB) { "bob" })
        assertEquals(0, locks.trackedPeerCount)
    }

    @Test
    fun anotherInstanceIsIndependent() = runTest {
        val other = PeerLocks()
        val release = CompletableDeferred<Unit>()
        launch { locks.withPeer(ALICE) { release.await() } }
        runCurrent()
        assertEquals("free", other.withPeer(ALICE) { "free" })
        release.complete(Unit)
    }

    @Test
    fun anExceptionReleasesTheLockAndIsRethrownUnchanged() = runTest {
        val thrown = assertThrowsSuspending<CryptoError.OpenFailed> {
            locks.withPeer(ALICE) { throw CryptoError.OpenFailed }
        }
        assertSame(CryptoError.OpenFailed, thrown)
        assertEquals("again", locks.withPeer(ALICE) { "again" })
        assertEquals(0, locks.trackedPeerCount)
    }

    @Test
    fun cancellingAWaiterReleasesItsPlace() = runTest {
        val release = CompletableDeferred<Unit>()
        val holder = launch { locks.withPeer(ALICE) { release.await() } }
        runCurrent()
        var waiterRan = false
        val waiter = launch { locks.withPeer(ALICE) { waiterRan = true } }
        runCurrent()
        assertEquals(1, locks.trackedPeerCount)
        waiter.cancel()
        runCurrent()
        assertTrue(waiter.isCancelled)
        release.complete(Unit)
        runCurrent()
        assertTrue(holder.isCompleted)
        assertFalse(waiterRan)
        assertEquals(0, locks.trackedPeerCount)
        assertEquals("next", locks.withPeer(ALICE) { "next" })
    }

    @Test
    fun cancellingTheHolderReleasesTheLock() = runTest {
        val holder = launch { locks.withPeer(ALICE) { delay(Long.MAX_VALUE) } }
        runCurrent()
        var waiterRan = false
        launch { locks.withPeer(ALICE) { waiterRan = true } }
        runCurrent()
        assertFalse(waiterRan)
        holder.cancel()
        runCurrent()
        assertTrue(waiterRan)
        assertEquals(0, locks.trackedPeerCount)
    }

    @Test
    fun aCoroutineThatEscapedTheBlockGetsNoFreePass() = runTest {
        // A coroutine started with the block's context after the block returned must queue like
        // anyone else: re-entry is granted only while the lock is still held.
        var escapedContext: CoroutineContext? = null
        locks.withPeer(ALICE) { escapedContext = currentCoroutineContext().minusKey(Job) }

        val release = CompletableDeferred<Unit>()
        launch { locks.withPeer(ALICE) { release.await() } }
        runCurrent()
        var escapedRan = false
        launch(escapedContext!!) { locks.withPeer(ALICE) { escapedRan = true } }
        runCurrent()
        assertFalse("must wait for the current holder", escapedRan)
        release.complete(Unit)
        runCurrent()
        assertTrue(escapedRan)
    }

    @Test
    fun entriesAreDroppedWhenNobodyHoldsOrWaits() = runTest {
        val release = CompletableDeferred<Unit>()
        val peers = List(10) { UUID.randomUUID() }
        val jobs = peers.map { peer -> launch { locks.withPeer(peer) { release.await() } } }
        runCurrent()
        assertEquals(10, locks.trackedPeerCount)
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(0, locks.trackedPeerCount)
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspending(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing was thrown")
    }

    private companion object {
        val ALICE: UUID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        val BOB: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    }
}

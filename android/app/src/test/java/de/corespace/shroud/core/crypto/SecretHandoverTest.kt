package de.corespace.shroud.core.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [withContextHandingOver]: a key produced on another dispatcher reaches the caller or is wiped —
 * plain `withContext` drops it unwiped when the caller is cancelled while the block runs (its
 * prompt-cancellation guarantee), which is how a cancelled unlock left the history key or the
 * restored identity on the heap (plan §1.4, invariant 5).
 */
class SecretHandoverTest {
    /** Runs [produce] on IO while the caller (on Default) is cancelled, then lets the block finish. */
    private fun cancelWhileTheBlockRuns(produce: suspend (block: suspend () -> ByteArray) -> Unit): Boolean {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reachedCaller = AtomicBoolean(false)
        runBlocking {
            val job = launch(Dispatchers.Default) {
                produce {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS) // blocking: the cancel cannot interrupt the block
                    key
                }
                reachedCaller.set(true)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
        }
        return reachedCaller.get()
    }

    private val key = ByteArray(32) { 7 }

    @Test
    fun plainWithContextDropsAFinishedResultWhenTheCallerWasCancelled() {
        val reached = cancelWhileTheBlockRuns { block -> withContext(Dispatchers.IO) { block() } }
        assertFalse(reached)
        assertFalse("the key was left on the heap unwiped", key.all { it == 0.toByte() })
    }

    @Test
    fun aResultFinishedAfterTheCallerWasCancelledIsWiped() {
        val reached = cancelWhileTheBlockRuns { block -> withContextHandingOver<ByteArray>(Dispatchers.IO, wipe = { it.fill(0) }) { block() } }
        assertFalse(reached)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun aHandedOverResultIsNotWiped() = runBlocking {
        val produced = withContextHandingOver(Dispatchers.IO, wipe = { it.fill(0) }) { key }
        assertSame(key, produced)
        assertArrayEquals(ByteArray(32) { 7 }, produced)
    }

    @Test
    fun aNullResultOrAFailureHasNothingToWipe() = runBlocking {
        var wiped = 0
        val none: ByteArray? = withContextHandingOver(Dispatchers.IO, wipe = { wiped++ }) { null }
        assertTrue(none == null)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { withContextHandingOver<ByteArray>(Dispatchers.IO, wipe = { wiped++ }) { error("boom") } }
        }
        assertTrue(wiped == 0)
    }

    @Test
    fun aCancelledCallerStillSeesTheCancellation() {
        var thrown: Throwable? = null
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val job = launch(Dispatchers.Default) {
                try {
                    withContextHandingOver(Dispatchers.IO, wipe = { it.fill(0) }) {
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        ByteArray(32) { 1 }
                    }
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            started.await(5, TimeUnit.SECONDS)
            job.cancel()
            release.countDown()
            job.join()
        }
        assertTrue(thrown is CancellationException)
    }
}

package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/** The serial writer of the message store (messaging-core §23.3): coalescing, order, drain, failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalWriteQueueTest {
    @get:Rule
    val temp = TempDirRule()

    private val scheduler = TestCoroutineScheduler()
    private val queue = LocalWriteQueue(ReentrantLock(), CoroutineScope(StandardTestDispatcher(scheduler)))
    private val log = ArrayList<String>()

    @Test
    fun theWriterRunsQueuedWritesInOrder() {
        queue.enqueue("a") { log += "a1" }
        queue.enqueue("b") { log += "b1" }
        assertTrue(log.isEmpty())
        assertFalse(queue.isIdle)

        scheduler.advanceUntilIdle()

        assertEquals(listOf("a1", "b1"), log)
        assertTrue(queue.isIdle)
        assertEquals(2, queue.completedWrites.get())
    }

    @Test
    fun aNewerWriteOfAFileReplacesThePendingOneAndMovesToTheEnd() {
        queue.enqueue("thread") { log += "thread v1" }
        queue.enqueue("cleanup") { log += "cleanup" }
        queue.enqueue("thread") { log += "thread v2" }

        queue.drain()

        assertEquals(listOf("cleanup", "thread v2"), log)
    }

    @Test
    fun drainRunsEverythingOnTheCallerAndTheWorkerFindsNothingLeft() {
        queue.enqueue("a") { log += "a" }
        queue.drain()
        scheduler.advanceUntilIdle()
        assertEquals(listOf("a"), log)
    }

    @Test
    fun clearDropsPendingWrites() {
        queue.enqueue("a") { log += "a" }
        queue.clear()
        scheduler.advanceUntilIdle()
        queue.drain()
        assertTrue(log.isEmpty())
        assertTrue(queue.isIdle)
    }

    @Test
    fun aFailedWriteIsDroppedAndTheRestStillRun() {
        queue.enqueue("bad") { throw IOException("disk full") }
        queue.enqueue("good") { log += "good" }
        queue.drain()
        assertEquals(listOf("good"), log)
        assertEquals(1, queue.completedWrites.get())
    }

    @Test
    fun atomicWriteReplacesTheFileAndLeavesNoTempFile() {
        val file = File(temp.root, "gone/again/roster.sealed")
        LocalFiles.write(file, byteArrayOf(1, 2, 3))
        LocalFiles.write(file, byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), LocalFiles.read(file))
        assertEquals(listOf("roster.sealed"), file.parentFile!!.list()!!.toList())
        assertEquals(null, LocalFiles.read(File(temp.root, "missing")))
    }
}

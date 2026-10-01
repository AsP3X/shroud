package de.corespace.shroud.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The wipe's write stop (web `storageSeal.ts`, `web/src/storageSeal.ts:1-16`; crypto spec §14; web-parity §3.3). */
class StorageSealTest {
    @Test
    fun startsUnsealed() {
        assertFalse(StorageSeal().isSealed)
    }

    @Test
    fun sealAndUnsealAreIdempotent() {
        val seal = StorageSeal()
        seal.seal()
        seal.seal()
        assertTrue(seal.isSealed)
        seal.unseal()
        seal.unseal()
        assertFalse(seal.isSealed)
        seal.seal()
        assertTrue(seal.isSealed)
    }

    @Test
    fun instancesAreIndependent() {
        val a = StorageSeal()
        val b = StorageSeal()
        a.seal()
        assertTrue(a.isSealed)
        assertFalse(b.isSealed)
    }

    @Test
    fun aSealOnAnotherThreadIsSeenAtOnce() {
        val seal = StorageSeal()
        val sealed = CountDownLatch(1)
        val seen = CountDownLatch(1)
        val writer = Thread {
            sealed.await()
            // A writer spinning on the flag must observe the wipe's seal (volatile read).
            while (!seal.isSealed) Thread.onSpinWait()
            seen.countDown()
        }
        writer.start()
        seal.seal()
        sealed.countDown()
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        writer.join(5_000)
    }
}

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
        // The writer starts spinning on an unsealed flag *before* the seal, with no other
        // synchronisation after that point: only the flag's own (volatile) visibility can end the
        // loop. Without @Volatile, HotSpot hoists the read out of the empty loop once it compiles
        // it, and this times out (checked by removing the annotation).
        val seal = StorageSeal()
        val spinning = CountDownLatch(1)
        val seen = CountDownLatch(1)
        val writer = Thread {
            spinning.countDown()
            while (!seal.isSealed) {
                // Deliberately empty: no call or barrier that would re-read the flag for us.
            }
            seen.countDown()
        }
        writer.isDaemon = true // a hoisted read would spin forever; do not keep the JVM alive
        writer.start()
        assertTrue(spinning.await(5, TimeUnit.SECONDS))
        Thread.sleep(300) // let the loop run hot (and get compiled) on the unsealed flag
        assertFalse(seal.isSealed)
        seal.seal()
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        writer.join(5_000)
    }
}

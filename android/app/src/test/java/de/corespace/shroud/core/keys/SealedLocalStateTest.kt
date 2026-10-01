package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.testing.SealedTestKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The history key for the stores outside `CryptoController` (iOS
 * `ios/shroud/Services/Crypto/SealedLocalState.swift`; crypto spec §8, §1.6): copies in, zeroes on
 * lock, never hands the key out of its lock.
 */
class SealedLocalStateTest {
    private val key = SealedTestKey.bytes()

    @Test
    fun lockedByDefault() {
        val state = SealedLocalState()
        assertFalse(state.isUnlocked)
        assertFalse(state.unlocked.value)
        assertNull(state.withKey { it })
        assertNull(state.withSubkey(LocalHistoryCrypto.Context.LanguageStats) { it })
        assertNull(state.names())
    }

    @Test
    fun unlockKeepsACopyAndLockZeroesIt() {
        val state = SealedLocalState()
        val given = key.copyOf()
        state.unlock(given)
        given.fill(0)
        assertTrue(state.isUnlocked)
        assertTrue(state.unlocked.value)
        assertArrayEquals(key, state.withKey { it.copyOf() })
        var held: ByteArray? = null
        state.withKey { held = it } // tests only: peek at the internal array
        state.lock()
        assertTrue("zeroed on lock", held!!.all { it == 0.toByte() })
        assertFalse(state.unlocked.value)
        assertNull(state.withKey { it })
    }

    @Test
    fun subkeysAreDerivedOncePerUnlockAndZeroedOnLock() {
        val state = SealedLocalState()
        state.unlock(key)
        val expected = LocalHistoryCrypto.subkey(key, LocalHistoryCrypto.Context.LanguageStats)
        var first: ByteArray? = null
        state.withSubkey(LocalHistoryCrypto.Context.LanguageStats) { first = it }
        assertArrayEquals(expected, first)
        state.withSubkey(LocalHistoryCrypto.Context.LanguageStats) { assertTrue("cached", it === first) }
        state.lock()
        assertTrue(first!!.all { it == 0.toByte() })
    }

    @Test
    fun namesFollowTheLock() {
        val state = SealedLocalState()
        state.unlock(key)
        val names = state.names()!!
        assertEquals(LocalNames.derive(key).name("ratchet", "x"), names.name("ratchet", "x"))
        state.lock()
        assertNull(state.names())
        assertTrue(runCatching { names.name("ratchet", "x") }.exceptionOrNull() === CryptoError.Locked)
        state.withKeyAndNames { _, _ -> throw AssertionError("locked") }
    }

    @Test
    fun aNewUnlockReplacesTheOldKey() {
        val state = SealedLocalState()
        state.unlock(key)
        var old: ByteArray? = null
        state.withKey { old = it }
        val other = ByteArray(32) { 1 }
        state.unlock(other)
        assertTrue(old!!.all { it == 0.toByte() })
        assertArrayEquals(other, state.withKey { it.copyOf() })
    }

    @Test
    fun listenersHearUnlockWithAZeroedAfterwardsCopyAndLockOnce() {
        val state = SealedLocalState()
        val events = mutableListOf<String>()
        var seen: ByteArray? = null
        val registration = state.addListener(
            object : SealedLocalState.Listener {
                override fun onUnlock(historyKey: ByteArray) {
                    events += "unlock"
                    assertArrayEquals(key, historyKey)
                    seen = historyKey
                }

                override fun onLock() {
                    events += "lock"
                }
            },
        )
        state.lock() // nothing to lock: not announced
        state.unlock(key)
        assertTrue("the listener's copy is zeroed after the call", seen!!.all { it == 0.toByte() })
        state.lock()
        state.lock()
        assertEquals(listOf("unlock", "lock"), events)
        registration.close()
        state.unlock(key)
        assertEquals(2, events.size)
    }

    @Test
    fun testKeyAloneNotifiesNobody() {
        val state = SealedLocalState()
        state.addListener(
            object : SealedLocalState.Listener {
                override fun onUnlock(historyKey: ByteArray) = throw AssertionError("no listeners for the test key")

                override fun onLock() = throw AssertionError("no listeners for the test key")
            },
        )
        state.setHistoryKeyForTesting(key)
        assertTrue(state.isUnlocked)
        assertTrue(state.names() != null)
        state.setHistoryKeyForTesting(null)
        assertFalse(state.isUnlocked)
    }

    @Test
    fun theKeyMustBe32Bytes() {
        assertTrue(runCatching { SealedLocalState().unlock(ByteArray(16)) }.isFailure)
    }

    /** Crypto spec §1.6: a lock waits for readers, so no reader ever sees a half-zeroed key. */
    @Test
    fun lockWaitsForAReaderInsideTheBlock() {
        val state = SealedLocalState()
        state.unlock(key)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var sawIntact = false
        val reader = thread {
            state.withKey {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                sawIntact = it.contentEquals(key)
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val locker = thread { state.lock() }
        Thread.sleep(100)
        assertTrue("lock is still waiting for the reader", locker.isAlive)
        release.countDown()
        reader.join(5_000)
        locker.join(5_000)
        assertTrue(sawIntact)
        assertFalse(state.isUnlocked)
    }
}

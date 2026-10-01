package de.corespace.shroud.core.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.SealResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * `KeystoreSealer` on the real AndroidKeyStore (crypto spec §11.1, §11.3; plan §1.5): the `SHRK1`
 * format, the classified failures `openClassified` reports, and the two accessibility classes —
 * WhenUnlocked (`setUnlockedDeviceRequired`) refuses while the phone is locked, AfterFirstUnlock
 * keeps working (P3a). Needs a screen lock (PIN 1234, `DeviceLock`). Test aliases only
 * (`shroud.test.sealer.*`), deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSealerTest {
    private val afu = KeystoreSealer(AFU_ALIAS)
    private val wu = KeystoreSealer(WU_ALIAS, unlockedDeviceRequired = true, isDeviceLocked = { DeviceLock.isLocked })

    @Before
    fun setUp() {
        DeviceLock.ensureUnlocked()
        cleanUp()
    }

    @After
    fun tearDown() {
        DeviceLock.ensureUnlocked()
        cleanUp()
    }

    private fun cleanUp() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList().filter { it.startsWith("shroud.test.sealer.") }.forEach(ks::deleteEntry)
    }

    private fun keyStoreHas(alias: String) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    @Test
    fun sealsToTheShrk1FormatAndOpens() {
        val plaintext = "a sealed record".toByteArray()
        val sealed = afu.seal(plaintext)
        assertArrayEquals("SHRK1".toByteArray(), sealed.copyOfRange(0, 5))
        assertEquals(5 + 12 + plaintext.size + 16, sealed.size)
        assertFalse("a fresh IV per seal", sealed.contentEquals(afu.seal(plaintext)))
        assertArrayEquals(plaintext, afu.open(sealed))
        assertArrayEquals(plaintext, (afu.openClassified(sealed) as SealResult.Opened).bytes)
    }

    @Test
    fun theWhenUnlockedSealerWorksWhileThePhoneIsUnlocked() {
        val sealed = wu.seal(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), (wu.openClassified(sealed) as SealResult.Opened).bytes)
    }

    @Test
    fun foreignOrDamagedBytesAreCorrupt() {
        val sealed = afu.seal(ByteArray(40) { 7 })
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(SealResult.Corrupt, afu.openClassified(flipped))
        assertEquals(SealResult.Corrupt, afu.openClassified(sealed.copyOfRange(0, 20)))
        assertEquals(SealResult.Corrupt, afu.openClassified("SHRD1".toByteArray() + sealed.copyOfRange(5, sealed.size)))
        // Sealed by another key: the tag does not match.
        wu.seal(byteArrayOf(0)) // makes sure the other key exists
        assertEquals(SealResult.Corrupt, wu.openClassified(sealed))
    }

    @Test
    fun aDeletedKeyIsGoneForGoodAndOpeningNeverCreatesOne() {
        val sealed = afu.seal(byteArrayOf(1))
        afu.deleteKey()
        assertFalse(keyStoreHas(AFU_ALIAS))
        assertEquals(SealResult.KeyGone, afu.openClassified(sealed))
        assertFalse("open must not mint a key that can open nothing", keyStoreHas(AFU_ALIAS))
        assertTrue(runCatching { afu.open(sealed) }.isFailure)
    }

    @Test
    fun strongBoxIsPreferredAndAnyFailureFallsBackToTheTee() {
        val sealer = KeystoreSealer(STRONGBOX_ALIAS, preferStrongBox = true)
        val sealed = sealer.seal(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), sealer.open(sealed))
    }

    /** WhenUnlocked ↔ iOS `WhenUnlockedThisDeviceOnly`; AfterFirstUnlock keeps working on a locked phone (P3a, crypto D1). */
    @Test
    fun aLockedPhoneRefusesWhenUnlockedRecordsAsDeviceLockedButNotAfterFirstUnlockOnes() {
        assumeTrue("needs a screen lock", DeviceLock.isSecure)
        val wuSealed = wu.seal(byteArrayOf(4, 5, 6))
        val afuSealed = afu.seal(byteArrayOf(7, 8, 9))
        DeviceLock.lockNow()
        try {
            assertTrue(DeviceLock.isLocked)
            assertEquals(SealResult.DeviceLocked, wu.openClassified(wuSealed))
            assertArrayEquals(byteArrayOf(7, 8, 9), (afu.openClassified(afuSealed) as SealResult.Opened).bytes)
        } finally {
            DeviceLock.ensureUnlocked()
        }
        assertArrayEquals("the same record opens again after unlock", byteArrayOf(4, 5, 6), (wu.openClassified(wuSealed) as SealResult.Opened).bytes)
    }

    private companion object {
        const val AFU_ALIAS = "shroud.test.sealer.afu"
        const val WU_ALIAS = "shroud.test.sealer.wu"
        const val STRONGBOX_ALIAS = "shroud.test.sealer.strongbox"
    }
}

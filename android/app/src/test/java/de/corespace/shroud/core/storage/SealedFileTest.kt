package de.corespace.shroud.core.storage

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Sealed records and their classified reads (crypto spec §11.3): the Android counterpart of the
 * Keychain statuses iOS branches on (`ios/shroud/Services/Crypto/IdentityKeyStore.swift:68-89`).
 */
class SealedFileTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()

    @Test
    fun writesSealedBytesAtomicallyAndReadsThemBack() {
        val file = SealedFile(temp.noBackupFilesDir.resolve("keys/identity.v1"), sealer)
        assertFalse(file.exists())
        file.write("hello".toByteArray())
        assertTrue(file.exists())
        // The parent directory was created; no temp file is left behind.
        assertEquals(listOf("no_backup/keys/identity.v1"), temp.listFiles())
        val onDisk = temp.noBackupFilesDir.resolve("keys/identity.v1").readBytes()
        assertFalse(onDisk.contentEquals("hello".toByteArray()))
        assertArrayEquals("hello".toByteArray(), (file.readClassified() as RecordRead.Found).bytes)
        assertArrayEquals("hello".toByteArray(), file.read())
    }

    @Test
    fun aMissingFileIsNotFound() {
        val file = SealedFile(temp.file("none.v1"), sealer)
        assertEquals(RecordRead.NotFound, file.readClassified())
        assertNull(file.read())
    }

    @Test
    fun eachKeystoreOutcomeMapsToItsReadClass() {
        val file = SealedFile(temp.file("record.v1"), sealer)
        file.write(byteArrayOf(1, 2, 3))
        // A locked phone is not definitive (iOS errSecInteractionNotAllowed).
        sealer.readFailure = SealResult.DeviceLocked
        assertEquals(RecordRead.DeviceLocked, file.readClassified())
        assertNull(file.read())
        // A vanished key or bytes that are not ours can never open again: as good as missing.
        sealer.readFailure = SealResult.KeyGone
        assertEquals(RecordRead.NotFound, file.readClassified())
        sealer.readFailure = SealResult.Corrupt
        assertEquals(RecordRead.NotFound, file.readClassified())
        // Anything else stays unknown.
        sealer.readFailure = SealResult.Failed
        assertEquals(RecordRead.Failed, file.readClassified())
        sealer.readFailure = null
        assertArrayEquals(byteArrayOf(1, 2, 3), file.read())
    }

    @Test
    fun aFailedWriteKeepsTheOldRecord() {
        val file = SealedFile(temp.file("record.v1"), sealer)
        file.write("old".toByteArray())
        sealer.sealFails = true
        try {
            file.write("new".toByteArray())
            fail("the write should throw")
        } catch (_: IllegalStateException) {
        }
        sealer.sealFails = false
        assertArrayEquals("old".toByteArray(), file.read())
        assertEquals(listOf("record.v1"), temp.listFiles())
    }

    @Test
    fun deleteRemovesTheRecord() {
        val file = SealedFile(temp.file("record.v1"), sealer)
        file.write("x".toByteArray())
        file.delete()
        assertFalse(file.exists())
        assertEquals(RecordRead.NotFound, file.readClassified())
        file.delete() // idempotent
    }

    @Test
    fun theDefaultClassifiedOpenOfASealerReportsFailed() {
        val refusing = object : Sealer {
            override fun seal(plaintext: ByteArray) = plaintext

            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("no")
        }
        assertEquals(SealResult.Failed, refusing.openClassified(byteArrayOf(1)))
    }
}

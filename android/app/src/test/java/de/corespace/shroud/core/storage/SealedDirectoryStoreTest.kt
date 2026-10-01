package de.corespace.shroud.core.storage

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * One sealed file per name in a directory — the stand-in for a Keychain service with one item per
 * account (crypto spec §11.3; `keys/ratchets`, `keys/sender-tags`, 00-plan §1.5).
 */
class SealedDirectoryStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val dir: File get() = File(temp.noBackupFilesDir, "keys/ratchets")
    private val store by lazy { SealedDirectoryStore(dir, sealer) }

    @Test
    fun recordsRoundTripByName() {
        assertEquals(RecordRead.NotFound, store.read("0a1b2c3d4e5f60718293a4b5c6d7e8f9"))
        store.write("0a1b2c3d4e5f60718293a4b5c6d7e8f9", byteArrayOf(1))
        store.write("ffeeddccbbaa99887766554433221100", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), (store.read("0a1b2c3d4e5f60718293a4b5c6d7e8f9") as RecordRead.Found).bytes)
        assertEquals(listOf("0a1b2c3d4e5f60718293a4b5c6d7e8f9", "ffeeddccbbaa99887766554433221100"), store.names())
        store.delete("0a1b2c3d4e5f60718293a4b5c6d7e8f9")
        assertEquals(listOf("ffeeddccbbaa99887766554433221100"), store.names())
    }

    @Test
    fun readsAreClassifiedLikeASingleRecord() {
        store.write("abc", byteArrayOf(1))
        sealer.readFailure = SealResult.DeviceLocked
        assertEquals(RecordRead.DeviceLocked, store.read("abc"))
        sealer.readFailure = SealResult.KeyGone
        assertEquals(RecordRead.NotFound, store.read("abc"))
    }

    @Test
    fun deleteAllRemovesTheDirectoryWithoutAnyKey() {
        store.write("a", byteArrayOf(1))
        store.write("b", byteArrayOf(2))
        sealer.readFailure = SealResult.DeviceLocked
        store.deleteAll()
        assertFalse(dir.exists())
        assertEquals(emptyList<String>(), store.names())
        store.deleteAll() // idempotent
    }

    @Test
    fun namesSkipTempFilesAndForeignEntries() {
        store.write("a", byteArrayOf(1))
        File(dir, "a.1234.tmp").writeBytes(byteArrayOf(9))
        File(dir, "UPPER").writeBytes(byteArrayOf(9))
        File(dir, "sub").mkdirs()
        assertEquals(listOf("a"), store.names())
    }

    @Test
    fun onlyKeyedNamesAreAccepted() {
        for (bad in listOf("", ".", "..", "../escape", "a/b", "UPPER", "with space", "x".repeat(65), "a.tmp", "8f14e45f-CEEA")) {
            try {
                store.write(bad, byteArrayOf(1))
                throw AssertionError("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(dir.exists())
        // Lower-case hex, digits, '.', '_' and '-' up to 64 characters are fine.
        store.write("x".repeat(64), byteArrayOf(1))
        store.write("a_b-c.d", byteArrayOf(1))
        assertTrue(store.names().containsAll(listOf("x".repeat(64), "a_b-c.d")))
    }
}

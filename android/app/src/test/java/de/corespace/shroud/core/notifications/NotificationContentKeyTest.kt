package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Show Content's sealed history key: kept while the switch is on, gone when it is turned off. */
class NotificationContentKeyTest {
    @get:Rule
    val temp = TempDirRule()

    private val seal = StorageSeal()
    private var deletedKeys = 0

    private fun key() = NotificationContentKey(
        file = SealedFile(temp.noBackupFilesDir.resolve("notification-content-key.sealed"), XorSealer()),
        seal = seal,
        deleteKey = { deletedKeys++ },
    )

    @Test
    fun aSavedKeyOpensAndASecondSaveOfTheSameKeyIsANoOp() {
        val store = key()
        val history = ByteArray(32) { it.toByte() }
        store.save(history)
        val opened = store.open()
        assertArrayEquals(history, opened)
        opened!!.fill(0)
        store.save(history)
        assertTrue(temp.noBackupFilesDir.resolve("notification-content-key.sealed").exists())
        store.clear()
        assertFalse(temp.noBackupFilesDir.resolve("notification-content-key.sealed").exists())
        assertEquals(1, deletedKeys)
        assertNull(store.open())
    }

    @Test
    fun aWipeDropsTheSave() {
        seal.seal()
        key().save(ByteArray(32) { 7 })
        assertNull(key().open())
    }
}

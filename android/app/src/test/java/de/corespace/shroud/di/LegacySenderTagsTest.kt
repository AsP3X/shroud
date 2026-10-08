package de.corespace.shroud.di

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The sign-out cleanup of the sender-tag watermarks older builds left in `keys/sender-tags/`
 * (`MessagingController.clearLocalData` → `wipeKeyRecords` → [KeysModule.deleteLegacySenderTags]).
 */
class LegacySenderTagsTest {
    @get:Rule val temp = TempDirRule()

    @Test
    fun signOutRemovesTheOldWatermarksAndNothingElse() {
        val keysDir = File(temp.noBackupFilesDir, KeysModule.KEYS_DIR)
        val watermarks = File(keysDir, KeysModule.LEGACY_SENDER_TAGS_DIR).apply { mkdirs() }
        File(watermarks, "bd1b63ed7b582512bf24e91524c6699d").writeBytes(ByteArray(48) { 1 })
        File(watermarks, "5099ebed1c8bf720e879384183c613ef").writeBytes(ByteArray(48) { 2 })
        val ratchet = File(File(keysDir, KeysModule.RATCHETS_DIR).apply { mkdirs() }, "0a1b").apply { writeBytes(ByteArray(8)) }
        val identity = File(keysDir, KeysModule.IDENTITY_FILE).apply { writeBytes(ByteArray(8)) }

        KeysModule.deleteLegacySenderTags(keysDir)

        assertFalse(watermarks.exists())
        // The ratchets go by their own store's deleteAll; the identity stays for the next unlock.
        assertTrue(ratchet.exists())
        assertTrue(identity.exists())
        assertEquals("sender-tags", KeysModule.LEGACY_SENDER_TAGS_DIR)
    }

    @Test
    fun nothingToDeleteIsFine() {
        val keysDir = File(temp.noBackupFilesDir, KeysModule.KEYS_DIR)
        KeysModule.deleteLegacySenderTags(keysDir)
        assertFalse(File(keysDir, KeysModule.LEGACY_SENDER_TAGS_DIR).exists())
    }
}

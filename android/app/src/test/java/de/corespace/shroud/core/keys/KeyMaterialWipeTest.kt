package de.corespace.shroud.core.keys

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The wipe's "Encryption keys" step (crypto spec §14; iOS `DeviceDataWipe.swift:152-158`, "Deleting
 * never asks for Face ID"): everything under `keys/`, every `shroud.` alias, and the verify pass's
 * leftovers list.
 */
class KeyMaterialWipeTest {
    @get:Rule
    val temp = TempDirRule()

    private class FakeAliases(vararg initial: String) : KeyMaterialWipe.KeystoreAliases {
        val aliases = initial.toMutableList()
        var failOn: String? = null

        override fun list(): List<String> = aliases.toList()

        override fun delete(alias: String) {
            if (alias == failOn) throw IllegalStateException("keystore busy")
            aliases -= alias
        }
    }

    private val keysDir: File get() = temp.noBackupFilesDir.resolve("keys")

    private fun plant() {
        for (path in listOf("identity.v1", "history-vault.v1", "peer-identity.v1", "ratchets/0a1b", "sender-tags/ffee")) {
            File(keysDir, path).apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
        }
        File(temp.noBackupFilesDir, "session.sealed").writeBytes(byteArrayOf(1))
    }

    @Test
    fun leftoversListWhatAWipeMustRemove() {
        plant()
        val aliases = FakeAliases("shroud.local.v1", "shroud.vault.wrap.3f9a0c1d", "shroud.session.v1", "other.app.key")
        val wipe = KeyMaterialWipe(keysDir, aliases)
        assertEquals(
            listOf(
                "keys/history-vault.v1",
                "keys/identity.v1",
                "keys/peer-identity.v1",
                "keys/ratchets/0a1b",
                "keys/sender-tags/ffee",
                "keystore:shroud.local.v1",
                "keystore:shroud.session.v1",
                "keystore:shroud.vault.wrap.3f9a0c1d",
            ),
            wipe.leftovers(),
        )
    }

    @Test
    fun wipeAllRemovesKeysAndShroudAliasesOnlyAndRunsTheHook() {
        plant()
        val aliases = FakeAliases("shroud.local.v1", "shroud.vault.wrap.3f9a0c1d", "other.app.key")
        var hooked = 0
        val wipe = KeyMaterialWipe(keysDir, aliases, afterWipe = { hooked++ })
        wipe.wipeAll()
        assertFalse(keysDir.exists())
        assertEquals(listOf("other.app.key"), aliases.aliases)
        assertEquals(emptyList<String>(), wipe.leftovers())
        assertEquals(1, hooked)
        // Outside keys/ is another step's (the session is the auth area's).
        assertTrue(File(temp.noBackupFilesDir, "session.sealed").exists())
    }

    @Test
    fun oneFailedDeletionDoesNotStopTheOthers() {
        plant()
        val aliases = FakeAliases("shroud.a", "shroud.b", "shroud.c").apply { failOn = "shroud.b" }
        val wipe = KeyMaterialWipe(keysDir, aliases, afterWipe = { throw IllegalStateException("hook") })
        wipe.wipeAll()
        assertEquals(listOf("keystore:shroud.b"), wipe.leftovers())
    }

    @Test
    fun aCleanDeviceHasNoLeftovers() {
        assertEquals(emptyList<String>(), KeyMaterialWipe(keysDir, FakeAliases()).leftovers())
        KeyMaterialWipe(keysDir, FakeAliases()).wipeAll()
    }
}

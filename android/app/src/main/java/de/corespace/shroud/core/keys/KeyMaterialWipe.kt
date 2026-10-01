package de.corespace.shroud.core.keys

import java.io.File
import java.security.KeyStore

/**
 * The "Encryption keys" step of the Log Out / removal wipe (crypto spec §14; plan §1.5; iOS
 * `ios/shroud/Services/Auth/DeviceDataWipe.swift:152-158`, "Deleting never asks for Face ID"):
 * deletes everything under `noBackupFilesDir/keys/` (identity, vault record, ratchets, sender tags,
 * peer pins) and every AndroidKeyStore alias starting with `shroud.` (the WhenUnlocked sealer, the
 * vault wrap keys, the session and other after-first-unlock sealers). `DeviceWipeController`
 * (W2-AUTH-WIPE) calls [wipeAll] after `StorageSeal.seal()` and the session step, and [leftovers]
 * in its verify pass.
 *
 * Nothing here needs authentication or an unlocked phone. Never logs.
 */
class KeyMaterialWipe(
    private val keysDir: File,
    private val aliases: KeystoreAliases = AndroidKeystoreAliases(),
    private val afterWipe: () -> Unit = {},
) {
    /** The AndroidKeyStore aliases, behind an interface for JVM tests. */
    interface KeystoreAliases {
        fun list(): List<String>

        fun delete(alias: String)
    }

    /**
     * Deletes everything under `keys/` and every `shroud.` alias, then runs [afterWipe] (in-memory caches of the
     * deleted records). Each deletion is attempted even if another failed; never throws.
     */
    fun wipeAll() {
        runCatching { keysDir.deleteRecursively() }
        for (alias in runCatching { aliases.list() }.getOrDefault(emptyList())) {
            if (alias.startsWith(ALIAS_PREFIX)) runCatching { aliases.delete(alias) }
        }
        runCatching { afterWipe() }
    }

    /**
     * What [wipeAll] should have removed and is still there, empty when clean: files as
     * `keys/<relative path>` (keyed names only, no ids), aliases as `keystore:<alias>`.
     */
    fun leftovers(): List<String> {
        val files = if (keysDir.exists()) {
            keysDir.walkTopDown().filter { it.isFile }.map { "keys/" + it.relativeTo(keysDir).invariantSeparatorsPath }.sorted().toList()
        } else {
            emptyList()
        }
        val keys = runCatching { aliases.list() }.getOrDefault(emptyList())
            .filter { it.startsWith(ALIAS_PREFIX) }
            .sorted()
            .map { "keystore:$it" }
        return files + keys
    }

    /** [KeystoreAliases] on the AndroidKeyStore. */
    class AndroidKeystoreAliases : KeystoreAliases {
        override fun list(): List<String> = keyStore().aliases().toList()

        override fun delete(alias: String) = keyStore().deleteEntry(alias)

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    companion object {
        /** Every alias the app creates starts with this (plan §1.5). */
        const val ALIAS_PREFIX = "shroud."
    }
}

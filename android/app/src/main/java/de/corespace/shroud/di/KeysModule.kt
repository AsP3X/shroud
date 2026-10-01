package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController

/**
 * Crypto and key storage (00-plan §1.7.4). Owner: W1-KEYS — also builds the W1-CRYPTO objects:
 * the one [CryptoController], the vault, the sealed key stores, `SecurityPreferences`,
 * `DeviceSecurity` and `SensitiveTempFiles` (prepared in [onProcessStart]). W0-A moved the
 * existing [Bip39] and [CryptoController] wiring here unchanged.
 */
class KeysModule(container: AppContainer) : AppModule(container) {
    val bip39: Bip39 by lazy {
        Bip39(container.appContext.assets.open("bip39-english.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() })
    }

    val cryptoController: CryptoController by lazy { CryptoController(container.net.api, bip39) }

    /** Filled by the owner: `SensitiveTempFiles.prepareAtLaunch()` (W1-KEYS, crypto §15). */
    override fun onProcessStart() = Unit
}

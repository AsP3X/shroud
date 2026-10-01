package de.corespace.shroud.di

import android.content.Context
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.keys.AndroidVaultKeyStore
import de.corespace.shroud.core.keys.DeviceSecurity
import de.corespace.shroud.core.keys.HistoryKeyVault
import de.corespace.shroud.core.keys.IdentityKeyStore
import de.corespace.shroud.core.keys.KeyMaterialWipe
import de.corespace.shroud.core.keys.PeerIdentityStore
import de.corespace.shroud.core.keys.RatchetSessionStore
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.keys.SenderTagStore
import de.corespace.shroud.core.keys.SystemBiometricAuthenticator
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedDirectoryStore
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.SecurityPreferences
import de.corespace.shroud.core.storage.SensitiveTempFiles
import java.io.File

/**
 * Crypto and key storage (00-plan §1.3, §1.5, §1.7.4). Owner: W1-KEYS. Builds the process's one
 * [CryptoController], the history-key vault, the sealed key stores under `noBackupFilesDir/keys/`,
 * [SecurityPreferences], [DeviceSecurity], [SensitiveTempFiles] (prepared in [onProcessStart]) and
 * the [PeerLocks] every engine shares. Nobody else constructs these classes (§2.0 rule 3).
 *
 * Storage (00-plan §1.5; crypto spec §11.2): every record under `keys/` is sealed by one
 * WhenUnlocked Keystore key, `shroud.local.v1` ([localSealer], `setUnlockedDeviceRequired`) —
 * iOS `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. Inside it, private values, ratchets and sender
 * tags are sealed again under the history key (`LocalHistoryCrypto`), so they are exactly as
 * locked as the chats. The vault's wrap keys are separate auth-bound aliases
 * (`shroud.vault.wrap.<hex8>`). Every writer gets [AppContainer.storageSeal].
 *
 * W1-CRYPTO's `MessageCrypto(ratchetSessions, senderTags)` is built here too once both packages
 * are merged (W1-INT): [ratchetSessions] and [senderTags] are its seams (`core/keys/KeyRecords.kt`).
 */
class KeysModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    /** `noBackupFilesDir/keys/` — identity, vault record, ratchets, sender tags, peer pins (00-plan §1.5). */
    val keysDir: File by lazy { File(app.noBackupFilesDir, KEYS_DIR) }

    val bip39: Bip39 by lazy {
        Bip39(app.assets.open("bip39-english.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() })
    }

    /** Screen lock, keyguard, StrongBox, enrolled biometric — read live (crypto §10.2, §10.4). */
    val deviceSecurity: DeviceSecurity by lazy { DeviceSecurity(app) }

    /**
     * The shared WhenUnlocked sealer of every record under `keys/` (00-plan §1.5, C16): unusable
     * while the phone is locked, which [KeystoreSealer.openClassified] reports as "device locked".
     */
    private val localSealer: KeystoreSealer by lazy {
        KeystoreSealer(
            KeystoreSealer.LOCAL_ALIAS,
            unlockedDeviceRequired = true,
            isDeviceLocked = { deviceSecurity.isDeviceLocked },
        )
    }

    /** The history key for the stores outside [cryptoController] (crypto §8). */
    val sealedLocalState: SealedLocalState by lazy { SealedLocalState() }

    val identityStore: IdentityKeyStore by lazy {
        IdentityKeyStore(SealedFile(File(keysDir, IDENTITY_FILE), localSealer), container.storageSeal)
    }

    val historyVault: HistoryKeyVault by lazy {
        HistoryKeyVault(
            record = SealedFile(File(keysDir, VAULT_FILE), localSealer),
            keys = AndroidVaultKeyStore(preferStrongBox = { deviceSecurity.hasStrongBox }),
            isDeviceSecure = { deviceSecurity.isDeviceSecure },
            // The resumed (else started) activity of ours hosts BiometricPrompt (00-plan §1.4).
            authenticator = SystemBiometricAuthenticator { container.appPhase.topActivity },
            seal = container.storageSeal,
        )
    }

    /** Double Ratchet sessions (`keys/ratchets/`), the `RatchetSessionRecords` seam of `MessageCrypto`. */
    val ratchetSessions: RatchetSessionStore by lazy {
        RatchetSessionStore(SealedDirectoryStore(File(keysDir, RATCHETS_DIR), localSealer), sealedLocalState, container.storageSeal)
    }

    /** Sender-tag watermarks (`keys/sender-tags/`), the `SenderTagWatermarks` seam of `MessageCrypto`. */
    val senderTags: SenderTagStore by lazy {
        SenderTagStore(SealedDirectoryStore(File(keysDir, SENDER_TAGS_DIR), localSealer), sealedLocalState, container.storageSeal)
    }

    /** Pinned peer identity keys and verified flags (`keys/peer-identity.v1`); logic in W2-CONTACTS. */
    val peerIdentities: PeerIdentityStore by lazy {
        PeerIdentityStore(SealedFile(File(keysDir, PEER_IDENTITY_FILE), localSealer), container.storageSeal)
    }

    /** Per-peer serialisation of decrypt/seal sequences, one per process (00-plan §1.4). */
    val peerLocks: PeerLocks by lazy { PeerLocks() }

    /** Auto-lock delay and the privacy switches, prefs `shroud.preferences` (crypto §17.4, C11). */
    val securityPreferences: SecurityPreferences by lazy {
        SecurityPreferences(app.getSharedPreferences(PrefsFiles.PREFERENCES, Context.MODE_PRIVATE), container.storageSeal)
    }

    /** `cacheDir/shroud-*` plaintext that must exist as a file for a moment (crypto §15, C25). */
    val sensitiveTempFiles: SensitiveTempFiles by lazy { SensitiveTempFiles(app.cacheDir) }

    /**
     * The wipe's "Encryption keys" step (crypto §14): everything under `keys/` and every `shroud.` Keystore alias.
     * Afterwards the peer pins' in-memory cache is dropped too, so a wiped pin cannot answer from RAM.
     */
    val keyMaterialWipe: KeyMaterialWipe by lazy {
        KeyMaterialWipe(keysDir, afterWipe = { peerIdentities.forgetCache() })
    }

    val cryptoController: CryptoController by lazy {
        CryptoController(
            api = container.net.api,
            bip39 = bip39,
            identityStore = identityStore,
            vault = historyVault,
            sealedLocalState = sealedLocalState,
            storageSeal = container.storageSeal,
        )
    }

    /** Leftover `shroud-*` plaintext from an earlier process is deleted at once (`RootView.swift:129`). */
    override fun onProcessStart() {
        sensitiveTempFiles.prepareAtLaunch()
    }

    companion object {
        const val KEYS_DIR = "keys"
        const val IDENTITY_FILE = "identity.v1"
        const val VAULT_FILE = "history-vault.v1"
        const val PEER_IDENTITY_FILE = "peer-identity.v1"
        const val RATCHETS_DIR = "ratchets"
        const val SENDER_TAGS_DIR = "sender-tags"
    }
}

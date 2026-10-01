package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.SealedLocalState
import java.util.UUID

/**
 * The history key and the keyed file names, as the message stores see them (plan §1.5, C10;
 * messaging-core §22.1). Everything goes through [SealedLocalState] (W1-KEYS), so the stores follow
 * the chat lock exactly: while chats are locked every [name] is null, [seal] is null and [open]
 * fails — nothing is ever read or written in the clear instead.
 *
 * Records are [LocalHistoryCrypto] blobs (`"SHRD1" ‖ nonce ‖ ciphertext ‖ tag`) under the store's
 * subkey with the record's location as AAD (web-parity §3.2), so a thread file moved to another
 * peer's name, or a roster copied into another account's directory, does not open. The subkey is
 * derived once per unlock by [SealedLocalState.withSubkey] (W1-KEYS) and used under its read lock;
 * a lock racing a seal never sees a half-zeroed key. Never logs keys, names or plaintext.
 */
class LocalStoreKeys(private val state: SealedLocalState, private val entropy: Entropy = SystemEntropy) {
    /** True while the history key is in memory. */
    val isUnlocked: Boolean get() = state.isUnlocked

    /**
     * `LocalNames.name(kind, id)` of this unlock (plan §1.5), or null while chats are locked —
     * also when a lock wiped the names between the lookup and the hash.
     */
    fun name(kind: String, id: UUID): String? = try {
        state.names()?.name(kind, id)
    } catch (_: CryptoError.Locked) {
        null
    }

    /** Seals [plaintext] under the [context] subkey with [aad]; null while locked. */
    fun seal(context: LocalHistoryCrypto.Context, plaintext: ByteArray, aad: String): ByteArray? =
        state.withSubkey(context) { subkey -> HistoryBlob.seal(subkey, plaintext, utf8(aad), entropy) }

    /**
     * Opens a blob [seal] wrote at the location [aad]; null while locked or when it does not open
     * (wrong key, wrong location, damaged) — fail closed (`LocalHistoryCrypto.swift:56-72`).
     */
    fun open(context: LocalHistoryCrypto.Context, blob: ByteArray, aad: String): ByteArray? = try {
        state.withSubkey(context) { subkey -> HistoryBlob.open(subkey, blob, utf8(aad)) }
    } catch (_: CryptoError) {
        null
    }
}

/**
 * [LocalHistoryCrypto]'s blob with a subkey derived beforehand (`LocalHistoryCrypto.swift:39-72`):
 * `LocalHistoryCrypto.seal(p, historyKey, ctx, aad)` equals `seal(subkey(historyKey, ctx), p, aad)`
 * byte for byte (pinned by `HistoryBlobTest` in both directions), so the stores never hand the root
 * history key down per record (the advice in `LocalHistoryCrypto.subkey`).
 */
object HistoryBlob {
    /** `"SHRD1"` (`LocalHistoryCrypto.swift:36`). */
    private val MAGIC = byteArrayOf(0x53, 0x48, 0x52, 0x44, 0x31)

    /** `open` needs strictly more than magic + nonce + tag (`LocalHistoryCrypto.swift:61`). */
    private const val MIN_BLOB_EXCLUSIVE = 5 + Primitives.GCM_NONCE_BYTES + Primitives.GCM_TAG_BYTES

    fun seal(subkey: ByteArray, plaintext: ByteArray, aad: ByteArray, entropy: Entropy = SystemEntropy): ByteArray =
        MAGIC + Primitives.aesGcmSeal(subkey, entropy.bytes(Primitives.GCM_NONCE_BYTES), plaintext, aad)

    /** @throws CryptoError.OpenFailed unless the blob is longer than 33 bytes, has the magic and authenticates. */
    fun open(subkey: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size <= MIN_BLOB_EXCLUSIVE || !LocalHistoryCrypto.isSealedBlob(blob)) throw CryptoError.OpenFailed
        return Primitives.aesGcmOpen(subkey, blob.copyOfRange(MAGIC.size, blob.size), aad)
    }
}

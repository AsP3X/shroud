package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.crypto.utf8
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Keyed local file names: every per-user, per-peer and per-message record on disk is named by a
 * keyed hash of its id, so the list of files says nothing about who talks to whom (plan §1.5 and
 * C10; web-parity §3.1, adopting the web's `vaultName`, `web/src/crypto/vault.ts:293-301`; crypto
 * spec §6.3, §7.2). iOS names its files and Keychain items by the plain ids
 * (`ios/shroud/Services/Crypto/RatchetSessionStore.swift:22-24`) or a plain SHA-256 of a public
 * key (`SenderTagStore.swift:97-99`); Android's layout is local-only, so this costs no interop.
 *
 * ```
 * namesKey = LocalHistoryCrypto.subkey(historyKey, RecordNames)   // HKDF info "shroud-local-names-v1"
 * name     = hex(HMAC-SHA256(namesKey, kind + ":" + id.lowercase()))[0 until 32]
 * ```
 *
 * 32 hex characters = 128 bits: collision-free for any realistic number of records, short enough
 * for every file system. Directory names that only say *what kind* of data a file holds
 * (`threads/`, `media/`, `plaintext/`) stay readable — the wipe counts by them.
 *
 * A name needs the history key, as every store access already does (reads report locked first,
 * writes are dropped while locked). Owned by `SealedLocalState` (W1-KEYS), which hands out the
 * instance while chats are unlocked and calls [wipe] on lock. After [wipe] every [name] call
 * throws [CryptoError.Locked] instead of hashing under a zeroed key — a name computed under the
 * wrong key would silently point a write at the wrong file.
 *
 * Thread-safe: [name] runs under a read lock, [wipe] zeroes the key under the write lock, so a
 * wipe racing a name never lets HMAC read a half-zeroed key (crypto spec §1.6). Never logs ids,
 * names or the key.
 */
class LocalNames(namesKey: ByteArray) {
    private val lock = ReentrantReadWriteLock()
    private var key: ByteArray? // guarded by `lock`

    init {
        require(namesKey.size == KEY_BYTES) { "the names key is $KEY_BYTES bytes" }
        key = namesKey.copyOf()
    }

    /**
     * The file name of the record [id] of [kind] (one of [Kind], or another stable lower-case
     * word). [id] is lower-cased (`Locale.ROOT`) first, so `0F8FAD5B-…` and `0f8fad5b-…` name the
     * same file. [kind] must be non-empty and free of `:` so `kind:id` stays unambiguous.
     *
     * @throws CryptoError.Locked after [wipe].
     */
    fun name(kind: String, id: String): String {
        require(kind.isNotEmpty() && ':' !in kind) { "a name kind is a non-empty word without ':'" }
        val message = utf8(kind + ":" + id.lowercase())
        val mac = lock.read {
            val k = key ?: throw CryptoError.Locked
            Primitives.hmacSha256(k, message)
        }
        return mac.hex().substring(0, NAME_LENGTH)
    }

    /** [name] of a user, peer, message or media id, in its wire form (lower-case 8-4-4-4-12). */
    fun name(kind: String, id: UUID): String = name(kind, id.toString())

    /**
     * [name] of a byte id — the sender's identity public key for [Kind.SENDER_TAG] — as its
     * lower-case hex (crypto spec §6.3: keyed by the key, not by a user id).
     */
    fun name(kind: String, id: ByteArray): String = name(kind, id.hex())

    /** Zeroes the key; every later [name] throws [CryptoError.Locked]. Idempotent. */
    fun wipe() {
        lock.write {
            key?.fill(0)
            key = null
        }
    }

    /** True once [wipe] ran. */
    val isWiped: Boolean
        get() = lock.read { key == null }

    /** The kinds the stores of plan §1.5 use. A new store adds its own stable word. */
    object Kind {
        /** `keys/ratchets/<name ratchet:peer>` — Double Ratchet session per peer user id (W1-KEYS). */
        const val RATCHET = "ratchet"

        /** `keys/sender-tags/<name sender-tag:ik>` — watermark per sender identity key, as hex (W1-KEYS). */
        const val SENDER_TAG = "sender-tag"

        /** `shroud/messages/<name user:id>/` — one directory per signed-in account (W2-MSG-STORE). */
        const val USER = "user"

        /** `…/threads/<name thread:peer>.sealed` — one thread file per peer (W2-MSG-STORE). */
        const val THREAD = "thread"

        /** `shroud/plaintext/<name msg:id>.sealed` — decrypted plaintext cache per message (W2-MSG-STORE). */
        const val MESSAGE = "msg"

        /** `shroud/media/<name media:id>.sealed` — SHRM1 media file per message (W2-MEDIA-STORE). */
        const val MEDIA = "media"
    }

    companion object {
        const val KEY_BYTES = 32

        /** Length of every name: the first 32 lower-case hex characters of the HMAC. */
        const val NAME_LENGTH = 32

        /**
         * Derives the names key from the 32-byte [historyKey] (plan §1.7.4). The intermediate
         * subkey is zeroed; the instance keeps its own copy.
         */
        fun derive(historyKey: ByteArray): LocalNames {
            val namesKey = LocalHistoryCrypto.subkey(historyKey, LocalHistoryCrypto.Context.RecordNames)
            try {
                return LocalNames(namesKey)
            } finally {
                namesKey.fill(0)
            }
        }
    }
}

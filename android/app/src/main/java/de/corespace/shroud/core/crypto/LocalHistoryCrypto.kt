package de.corespace.shroud.core.crypto

/**
 * AES-256-GCM at-rest sealing of local history, media, plaintext caches and key records under
 * per-store subkeys of the phrase-derived `historyKey` (iOS
 * `ios/shroud/Services/Messaging/LocalHistoryCrypto.swift`; crypto spec §2). Local-only format,
 * ported byte for byte so the iOS-derived vectors apply and sealed stores stay separable:
 *
 * ```
 * subkey = HKDF-SHA256(ikm = historyKey, salt = "shroud-local-at-rest-v1", info = context, L = 32)
 * blob   = "SHRD1" ‖ nonce (12) ‖ AES-256-GCM(subkey, nonce, plaintext, aad) ‖ tag (16)
 * ```
 *
 * Android addition (web-parity §3.2, plan C10): the store passes the record's logical name as
 * [aad], so a sealed file cannot be moved to another name (two peers' threads swapped). The
 * default empty AAD is plain GCM, so the iOS vectors (no AAD) stay valid.
 *
 * Distinct from `KeystoreSealer`'s `SHRK1` magic: one file can carry both (outer `SHRK1`, inner
 * `SHRD1`). Never logs keys or plaintext.
 */
object LocalHistoryCrypto {
    /**
     * Domain separation, one HKDF info string per store, so one store cannot open another's blobs
     * by mistake (`LocalHistoryCrypto.swift:20-33`). [RecordNames] is Android-only: the key of
     * [de.corespace.shroud.core.keys.LocalNames] (crypto spec §2.1, plan §1.5).
     */
    enum class Context(val info: String) {
        MessagesSnapshot("shroud-local-messages-v1"),
        MediaFile("shroud-local-media-v1"),
        PlaintextPayload("shroud-local-plaintext-v1"),

        /** Identity, signed-prekey and one-time-prekey privates. */
        IdentityKeychain("shroud-keychain-identity-v1"),

        /** Double Ratchet session JSON, one record per peer. */
        RatchetKeychain("shroud-keychain-ratchet-v1"),

        /** Per-sender watermark of the first tagged identity box. */
        SenderTagKeychain("shroud-keychain-sender-tag-v1"),

        /** Per-conversation voice transcription language statistics. */
        LanguageStats("shroud-local-language-stats-v1"),

        /** Android-only: HMAC key for keyed local file names. */
        RecordNames("shroud-local-names-v1"),
    }

    const val MASTER_KEY_BYTES = 32

    /** `"SHRD1"` (`LocalHistoryCrypto.swift:36`). */
    private val MAGIC = byteArrayOf(0x53, 0x48, 0x52, 0x44, 0x31)
    private val SALT = utf8("shroud-local-at-rest-v1")

    /** Smallest blob that `open` even tries, exclusive: magic + nonce + tag (`:61`). */
    private const val MIN_BLOB_EXCLUSIVE = 5 + Primitives.GCM_NONCE_BYTES + Primitives.GCM_TAG_BYTES

    /**
     * The 32-byte store key for [context] (`LocalHistoryCrypto.swift:81-88`). The caller owns the
     * result and zeroes it when done. [masterKey] must be the 32-byte `historyKey`; anything else is
     * a programming error ([IllegalArgumentException]).
     *
     * [seal] and [open] derive the subkey per call, so the history key passes through HKDF on every
     * record. [Primitives.hkdf] zeroes everything it derives, but a store that seals many records
     * (W1-KEYS `SealedLocalState.withSubkey`) should derive its subkey once per unlock and zero it
     * on lock rather than hand the root key down per record.
     */
    fun subkey(masterKey: ByteArray, context: Context): ByteArray {
        require(masterKey.size == MASTER_KEY_BYTES) { "the history key is $MASTER_KEY_BYTES bytes" }
        return Primitives.hkdf(masterKey, SALT, utf8(context.info), 32)
    }

    /**
     * Seals [plaintext] under the [context] subkey of [masterKey] (`LocalHistoryCrypto.swift:39-53`),
     * binding [aad] (the logical record name; empty = iOS). The nonce is 12 bytes from [entropy].
     *
     * Like iOS, an empty [plaintext] seals to a 33-byte blob that [open] then refuses (the strict
     * `>` of `:61`). No store seals empty data; callers that could must not rely on reading it back.
     * A provider failure → [CryptoError.SealingFailed].
     */
    fun seal(
        plaintext: ByteArray,
        masterKey: ByteArray,
        context: Context,
        aad: ByteArray = ByteArray(0),
        entropy: Entropy = SystemEntropy,
    ): ByteArray {
        val key = subkey(masterKey, context)
        try {
            val combined = Primitives.aesGcmSeal(key, entropy.bytes(Primitives.GCM_NONCE_BYTES), plaintext, aad)
            return MAGIC + combined
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens a blob produced by [seal] with the same [context] and [aad]
     * (`LocalHistoryCrypto.swift:56-72`). Fails closed with [CryptoError.OpenFailed] unless the blob
     * is **strictly** longer than 33 bytes and starts with `SHRD1`, and on any GCM failure (wrong
     * key, context or AAD, tampering).
     */
    fun open(
        blob: ByteArray,
        masterKey: ByteArray,
        context: Context,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        if (blob.size <= MIN_BLOB_EXCLUSIVE || !hasMagic(blob)) throw CryptoError.OpenFailed
        val key = subkey(masterKey, context)
        try {
            return Primitives.aesGcmOpen(key, blob.copyOfRange(MAGIC.size, blob.size), aad)
        } finally {
            key.fill(0)
        }
    }

    /** True when [blob] carries the sealed-file magic (`LocalHistoryCrypto.swift:75-77`): longer than 5 bytes, starts with `SHRD1`. */
    fun isSealedBlob(blob: ByteArray): Boolean = blob.size > MAGIC.size && hasMagic(blob)

    private fun hasMagic(blob: ByteArray): Boolean {
        if (blob.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) return false
        return true
    }
}

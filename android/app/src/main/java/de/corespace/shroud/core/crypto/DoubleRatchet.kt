package de.corespace.shroud.core.crypto

import kotlinx.serialization.Serializable

/**
 * Signal-style Double Ratchet (X25519 + HKDF-SHA256 + AES-256-GCM) for 1:1 chats (iOS
 * `ios/shroud/Services/Crypto/DoubleRatchet.swift`, web `web/src/crypto/ratchet.ts`; crypto spec
 * §5). Every constant is wire-relevant.
 *
 * Bootstrap ("identity X3DH-lite", `DoubleRatchet.swift:6-13`): both peers derive the same root
 * seed from the static identity ECDH,
 * ```
 * rootSeed = HKDF(ECDH(ourPriv, theirPub), salt = sortedConcat(ourPub, theirPub), info "shroud-dr-root-v3", 32)
 * kdfRK    = HKDF(ikm = dhOut, salt = root, info "shroud-dr-rk-v3", 64) → (new root, chain)
 * kdfCK    = (HMAC(ck, 0x01) → next chain, HMAC(ck, 0x02) → message key)
 * body     = AES-256-GCM(message key, random 12-byte nonce), combined, no AAD
 * ```
 * The sender ([initiateAsSender]) ratchets a fresh DH key against the peer's identity; the
 * receiver ([prepareAsReceiver]) uses its identity private as the first DH private, so its first
 * DH ratchet computes the same `ECDH(identity, senderDH)`.
 *
 * Randomness comes from an [Entropy] so golden vectors can pin it: [initiateAsSender] draws the
 * 32-byte DH private, [encrypt] the 12-byte nonce (after a 32-byte DH private when it has to
 * promote a send chain), [decrypt] the new 32-byte DH private of a DH ratchet (crypto spec §1.3).
 *
 * Threading: a [Session] is plain mutable state with no lock; callers serialise load → mutate →
 * save per peer (`MessageCrypto` holds a per-peer monitor, plan §1.4). Like Swift's `inout`, a
 * failed [decrypt] may leave the session partly advanced — decrypt on a [Session.deepCopy] and
 * keep it only on success (`MessageCrypto.swift:386-395`, web `messageCrypto.ts:324-328`).
 *
 * Keys: a session owns every array it holds; keys it replaces (old roots, chains, DH privates,
 * used skipped keys) are zeroed, and so is every intermediate secret. Never logs keys or plaintext.
 */
object DoubleRatchet {
    /** `DoubleRatchet.envelopeVersion` (`:23`): the `v` of ratchet messages and v3 envelopes. */
    const val ENVELOPE_VERSION = 3

    /** Per-chain skip distance **and** total retained skipped keys (`:24-25`, web `MAX_SKIP`). */
    const val MAX_SKIP = 64

    private val INFO_ROOT = utf8("shroud-dr-root-v3")
    private val INFO_RK = utf8("shroud-dr-rk-v3")
    private val CK_NEXT = byteArrayOf(0x01)
    private val CK_MESSAGE = byteArrayOf(0x02)
    private const val KEY_BYTES = Primitives.X25519_KEY_BYTES

    /**
     * Ratchet header and body (`DoubleRatchet.swift:34-45`): `{"v":3,"dh":…,"n":…,"pn":…,"ct":…}`.
     * Inside a v3 envelope the same five fields sit at the top level next to `peer`/`self`.
     *
     * @property dh the sender's current ratchet public key, standard Base64.
     * @property n the message's index in the sending chain (`UInt32`).
     * @property pn the length of the sender's previous sending chain (`UInt32`).
     * @property ct AES-GCM `nonce ‖ ciphertext ‖ tag`, standard Base64.
     */
    @Serializable
    data class Message(val v: Int, val dh: String, val n: Long, val pn: Long, val ct: String) {
        init {
            require(n in 0..UINT32_MAX && pn in 0..UINT32_MAX) { "ratchet counter out of range" }
        }

        /** The message JSON (iOS `encrypt` returns these bytes, `:180`). */
        fun toJson(): ByteArray = utf8(CryptoJson.encodeToString(serializer(), this))

        companion object {
            /** Parses [toJson]'s output; null when it is not a ratchet message. */
            fun fromJson(json: ByteArray): Message? = try {
                CryptoJson.decodeFromString(serializer(), String(json, Charsets.UTF_8))
            } catch (_: IllegalArgumentException) { // SerializationException and the UInt32 check
                null
            }
        }
    }

    /**
     * Persistent session state for one peer (`DoubleRatchet.swift:47-63`). Every key is 32 bytes.
     * Equality compares contents, as Swift's synthesised `Equatable` does; [skipped] compares as a
     * map (its order only decides which keys are dropped first).
     *
     * Stored as JSON with the iOS field names ([toJson]); `MessageCrypto` hands that to
     * `RatchetSessionRecords`, which seals it under the history key (plan §1.5).
     */
    class Session(
        var rootKey: ByteArray,
        var sendChainKey: ByteArray?,
        var recvChainKey: ByteArray?,
        /** Index of the next message in the sending chain (`UInt32`). */
        var sendN: Long,
        /** Index of the next message expected in the receiving chain (`UInt32`). */
        var recvN: Long,
        /** Length of the previous sending chain, sent as `pn` (`UInt32`). */
        var prevChainLength: Long,
        var dhSendPrivate: ByteArray?,
        var dhSendPublic: ByteArray?,
        var dhRecvPublic: ByteArray?,
        /** The peer's identity public key: the root seed's input and the first remote DH key. */
        var peerIdentityPublic: ByteArray,
        /** True after at least one successful encrypt or decrypt with this peer (`:60-61`). */
        var touched: Boolean,
        /**
         * Skipped message keys, `"<dh b64>:<n>"` → key, oldest first. iOS drops arbitrary entries
         * past [MAX_SKIP] (Swift dictionary order, `:257-262`); this drops the oldest, as the web
         * does (`ratchet.ts:143-146`) — local state only.
         */
        val skipped: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
    ) {
        /** An independent copy: every array is copied, so a failed attempt on it leaves this one intact. */
        fun deepCopy(): Session = Session(
            rootKey = rootKey.copyOf(),
            sendChainKey = sendChainKey?.copyOf(),
            recvChainKey = recvChainKey?.copyOf(),
            sendN = sendN,
            recvN = recvN,
            prevChainLength = prevChainLength,
            dhSendPrivate = dhSendPrivate?.copyOf(),
            dhSendPublic = dhSendPublic?.copyOf(),
            dhRecvPublic = dhRecvPublic?.copyOf(),
            peerIdentityPublic = peerIdentityPublic.copyOf(),
            touched = touched,
            skipped = LinkedHashMap<String, ByteArray>().also { copy -> skipped.forEach { (k, v) -> copy[k] = v.copyOf() } },
        )

        /** Zeroes every secret this session holds (root, chains, DH private, skipped keys). Unusable afterwards. */
        fun wipe() {
            rootKey.fill(0)
            sendChainKey?.fill(0)
            recvChainKey?.fill(0)
            dhSendPrivate?.fill(0)
            skipped.values.forEach { it.fill(0) }
            skipped.clear()
        }

        /**
         * The stored JSON: the iOS field names (`:48-63`), byte fields as standard Base64, nil fields
         * left out, plus `"v": 1`, the version of this local format (crypto spec §7.1), so a later
         * change can migrate. The caller owns (and should zero) the result.
         */
        fun toJson(): ByteArray = utf8(
            CryptoJson.encodeToString(
                StoredSession.serializer(),
                StoredSession(
                    v = STORED_FORMAT,
                    rootKey = B64.encode(rootKey),
                    sendChainKey = sendChainKey?.let(B64::encode),
                    recvChainKey = recvChainKey?.let(B64::encode),
                    sendN = sendN,
                    recvN = recvN,
                    prevChainLength = prevChainLength,
                    dhSendPrivate = dhSendPrivate?.let(B64::encode),
                    dhSendPublic = dhSendPublic?.let(B64::encode),
                    dhRecvPublic = dhRecvPublic?.let(B64::encode),
                    peerIdentityPublic = B64.encode(peerIdentityPublic),
                    touched = touched,
                    skipped = skipped.mapValuesTo(LinkedHashMap()) { (_, key) -> B64.encode(key) },
                ),
            ),
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Session) return false
            return rootKey.contentEquals(other.rootKey) &&
                sendChainKey.contentEquals(other.sendChainKey) &&
                recvChainKey.contentEquals(other.recvChainKey) &&
                sendN == other.sendN &&
                recvN == other.recvN &&
                prevChainLength == other.prevChainLength &&
                dhSendPrivate.contentEquals(other.dhSendPrivate) &&
                dhSendPublic.contentEquals(other.dhSendPublic) &&
                dhRecvPublic.contentEquals(other.dhRecvPublic) &&
                peerIdentityPublic.contentEquals(other.peerIdentityPublic) &&
                touched == other.touched &&
                skipped.size == other.skipped.size &&
                skipped.all { (k, v) -> other.skipped[k]?.contentEquals(v) == true }
        }

        override fun hashCode(): Int {
            var h = rootKey.contentHashCode()
            h = 31 * h + sendN.hashCode()
            h = 31 * h + recvN.hashCode()
            h = 31 * h + peerIdentityPublic.contentHashCode()
            return h
        }

        /** Never prints key material. */
        override fun toString(): String = "DoubleRatchet.Session(sendN=$sendN, recvN=$recvN, pn=$prevChainLength, touched=$touched)"

        companion object {
            /** Version of the stored JSON ([toJson]). */
            const val STORED_FORMAT = 1

            /**
             * Reads [toJson]'s output. Null when the bytes are not a session this build understands:
             * not JSON, another `v`, a field that is not strict Base64 or not 32 bytes, a counter
             * outside `UInt32` — the store then reads it as "no session", as iOS does for a record
             * that does not decode (`RatchetSessionStore.swift:22-31`). Does not zero [json].
             */
            fun fromJson(json: ByteArray): Session? {
                val stored = try {
                    CryptoJson.decodeFromString(StoredSession.serializer(), String(json, Charsets.UTF_8))
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (stored.v != STORED_FORMAT) return null
                if (listOf(stored.sendN, stored.recvN, stored.prevChainLength).any { it !in 0..UINT32_MAX }) return null

                val decoded = ArrayList<ByteArray>()
                var valid = true
                fun key(b64: String?): ByteArray? {
                    if (b64 == null) return null
                    val bytes = B64.decodeStrict(b64)
                    if (bytes == null || bytes.size != KEY_BYTES) {
                        bytes?.fill(0)
                        valid = false
                        return null
                    }
                    return bytes.also { decoded += it }
                }
                val root = key(stored.rootKey)
                val peer = key(stored.peerIdentityPublic)
                val sendChain = key(stored.sendChainKey)
                val recvChain = key(stored.recvChainKey)
                val dhPrivate = key(stored.dhSendPrivate)
                val dhPublic = key(stored.dhSendPublic)
                val dhRecv = key(stored.dhRecvPublic)
                val skipped = LinkedHashMap<String, ByteArray>()
                for ((name, b64) in stored.skipped) key(b64)?.let { skipped[name] = it }
                if (!valid || root == null || peer == null) {
                    decoded.forEach { it.fill(0) }
                    return null
                }
                return Session(
                    rootKey = root,
                    sendChainKey = sendChain,
                    recvChainKey = recvChain,
                    sendN = stored.sendN,
                    recvN = stored.recvN,
                    prevChainLength = stored.prevChainLength,
                    dhSendPrivate = dhPrivate,
                    dhSendPublic = dhPublic,
                    dhRecvPublic = dhRecv,
                    peerIdentityPublic = peer,
                    touched = stored.touched,
                    skipped = skipped,
                )
            }
        }
    }

    /** JSON form of [Session] (field names of `DoubleRatchet.swift:48-63`). */
    @Serializable
    private class StoredSession(
        val v: Int,
        val rootKey: String,
        val sendChainKey: String? = null,
        val recvChainKey: String? = null,
        val sendN: Long,
        val recvN: Long,
        val prevChainLength: Long,
        val dhSendPrivate: String? = null,
        val dhSendPublic: String? = null,
        val dhRecvPublic: String? = null,
        val peerIdentityPublic: String,
        val touched: Boolean,
        val skipped: Map<String, String>,
    )

    // ---- bootstrap ----

    /**
     * The shared root seed, identical for both peers of an identity pair (`rootSeed`,
     * `DoubleRatchet.swift:68-84`). The salt binds both public keys in **unsigned** byte order
     * ([sortedConcat]). A key that is not 32 bytes or a low-order point → [RatchetError.InvalidKey].
     */
    fun rootSeed(ourPrivate: ByteArray, theirIdentityPublic: ByteArray): ByteArray {
        val shared = dh(ourPrivate, theirIdentityPublic)
        val salt = sortedConcat(publicKey(ourPrivate), theirIdentityPublic)
        try {
            return Primitives.hkdf(shared, salt, INFO_ROOT, KEY_BYTES)
        } finally {
            shared.fill(0)
        }
    }

    /**
     * We are about to send the first message, or re-initiate after a wipe (`initiateAsSender`,
     * `DoubleRatchet.swift:87-111`): a fresh DH key (drawn from [entropy]) against the peer's
     * identity. Until the peer ratchets, their identity stands in as the remote DH key. The session
     * keeps copies; [ourPrivate] is not retained.
     */
    fun initiateAsSender(ourPrivate: ByteArray, theirIdentityPublic: ByteArray, entropy: Entropy = SystemEntropy): Session {
        val rk0 = rootSeed(ourPrivate, theirIdentityPublic)
        val dhPrivate = entropy.bytes(KEY_BYTES)
        try {
            val (root, sendChain) = kdfRK(rk0, dh(dhPrivate, theirIdentityPublic))
            return Session(
                rootKey = root,
                sendChainKey = sendChain,
                recvChainKey = null,
                sendN = 0,
                recvN = 0,
                prevChainLength = 0,
                dhSendPrivate = dhPrivate.copyOf(),
                dhSendPublic = publicKey(dhPrivate),
                dhRecvPublic = theirIdentityPublic.copyOf(),
                peerIdentityPublic = theirIdentityPublic.copyOf(),
                touched = true,
            )
        } finally {
            rk0.fill(0)
            dhPrivate.fill(0)
        }
    }

    /**
     * We are about to decrypt a peer's first message with no session (`prepareAsReceiver`,
     * `DoubleRatchet.swift:115-135`): our identity private acts as the first DH private, so the
     * first header's DH ratchet computes `ECDH(ourIdentity, senderDH)`. The session holds a **copy**
     * of [ourPrivate], which that DH ratchet replaces (and zeroes) — store such a session only after
     * a successful decrypt, so the identity private never reaches storage (crypto spec §5.5).
     */
    fun prepareAsReceiver(ourPrivate: ByteArray, theirIdentityPublic: ByteArray): Session {
        val rk0 = rootSeed(ourPrivate, theirIdentityPublic)
        return Session(
            rootKey = rk0,
            sendChainKey = null,
            recvChainKey = null,
            sendN = 0,
            recvN = 0,
            prevChainLength = 0,
            dhSendPrivate = ourPrivate.copyOf(),
            dhSendPublic = publicKey(ourPrivate),
            dhRecvPublic = null,
            peerIdentityPublic = theirIdentityPublic.copyOf(),
            touched = false,
        )
    }

    // ---- encrypt / decrypt ----

    /**
     * Encrypts [plaintext] on [session]'s sending chain and advances it (`encrypt`,
     * `DoubleRatchet.swift:140-181`). A session that only ever received has a send chain from its
     * DH ratchet; one without (rare) is promoted first with a fresh DH key against the last remote
     * key, or the peer's identity (`:143-158`). Errors: [RatchetError.NoSession] without a chain,
     * [RatchetError.InvalidKey] for an unusable remote key, [CryptoError.SealingFailed] from GCM.
     */
    fun encrypt(plaintext: ByteArray, session: Session, entropy: Entropy = SystemEntropy): Message {
        if (session.sendChainKey == null) {
            val dhPrivate = entropy.bytes(KEY_BYTES)
            val remote = session.dhRecvPublic ?: session.peerIdentityPublic
            val (root, sendChain) = try {
                kdfRK(session.rootKey, dh(dhPrivate, remote))
            } catch (e: Exception) {
                dhPrivate.fill(0)
                throw e
            }
            session.rootKey.fill(0)
            session.rootKey = root
            session.sendChainKey = sendChain
            session.dhSendPrivate?.fill(0)
            session.dhSendPrivate = dhPrivate
            session.dhSendPublic = publicKey(dhPrivate)
            session.prevChainLength = session.sendN
            session.sendN = 0
        }
        val chain = session.sendChainKey ?: throw RatchetError.NoSession
        val dhPublic = session.dhSendPublic ?: throw RatchetError.NoSession
        val (next, messageKey) = kdfCK(chain)
        chain.fill(0)
        session.sendChainKey = next
        val ct = try {
            Primitives.aesGcmSeal(messageKey, entropy.bytes(Primitives.GCM_NONCE_BYTES), plaintext)
        } finally {
            messageKey.fill(0)
        }
        val message = Message(v = ENVELOPE_VERSION, dh = B64.encode(dhPublic), n = session.sendN, pn = session.prevChainLength, ct = B64.encode(ct))
        session.sendN += 1
        session.touched = true
        return message
    }

    /**
     * Decrypts [message] with [session] and advances it (`decrypt`, `DoubleRatchet.swift:183-210`):
     * a stored skipped key first; a new remote DH key runs a DH ratchet (skipping what is left of
     * the old chain up to `pn`); then the chain is skipped up to `n`. Errors:
     * [RatchetError.DecryptFailed] (wrong version, bad tag — the caller discards its copy),
     * [RatchetError.InvalidKey] (fields not strict Base64, unusable DH key),
     * [RatchetError.SkippedTooFar], [RatchetError.NoSession].
     *
     * An old message whose key was never stored (`n < recvN`) derives a wrong key and fails; the
     * caller falls back to the peer box (crypto spec §5.7).
     */
    fun decrypt(message: Message, session: Session, entropy: Entropy = SystemEntropy): ByteArray {
        if (message.v != ENVELOPE_VERSION) throw RatchetError.DecryptFailed
        val dhData = B64.decodeStrict(message.dh) ?: throw RatchetError.InvalidKey
        val ctData = B64.decodeStrict(message.ct) ?: throw RatchetError.InvalidKey

        // iOS keys the lookup by the wire string (`:190`); stored keys use canonical Base64.
        session.skipped.remove("${message.dh}:${message.n}")?.let { skippedKey ->
            try {
                return openBody(skippedKey, ctData)
            } finally {
                skippedKey.fill(0)
            }
        }

        // A new remote ratchet key: finish the old chain up to pn, then DH-ratchet (`:195-199`).
        if (session.dhRecvPublic?.contentEquals(dhData) != true) {
            skipMessageKeys(message.pn, session)
            dhRatchet(dhData, session, entropy)
        }
        skipMessageKeys(message.n, session)

        val chain = session.recvChainKey ?: throw RatchetError.NoSession
        val (next, messageKey) = kdfCK(chain)
        chain.fill(0)
        session.recvChainKey = next
        session.recvN += 1
        session.touched = true
        try {
            return openBody(messageKey, ctData)
        } finally {
            messageKey.fill(0)
        }
    }

    /**
     * [decrypt] of a message in its JSON form (iOS `decrypt(envelopeData:session:)`). Bytes that are
     * not a ratchet message → [RatchetError.DecryptFailed] (iOS lets the `DecodingError` escape).
     */
    fun decrypt(messageJson: ByteArray, session: Session, entropy: Entropy = SystemEntropy): ByteArray =
        decrypt(Message.fromJson(messageJson) ?: throw RatchetError.DecryptFailed, session, entropy)

    // ---- DH ratchet ----

    /** `dhRatchet` (`DoubleRatchet.swift:214-238`): a receiving chain from our current DH key, then a new DH key for replies. */
    private fun dhRatchet(remotePublic: ByteArray, session: Session, entropy: Entropy) {
        session.prevChainLength = session.sendN
        session.sendN = 0
        session.recvN = 0
        session.dhRecvPublic = remotePublic.copyOf()
        if (remotePublic.size != KEY_BYTES) throw RatchetError.InvalidKey
        val sendPrivate = session.dhSendPrivate ?: throw RatchetError.NoSession

        val (root1, recvChain) = kdfRK(session.rootKey, dh(sendPrivate, remotePublic))
        session.rootKey.fill(0)
        session.rootKey = root1
        session.recvChainKey?.fill(0)
        session.recvChainKey = recvChain

        val newPrivate = entropy.bytes(KEY_BYTES)
        sendPrivate.fill(0)
        session.dhSendPrivate = newPrivate
        session.dhSendPublic = publicKey(newPrivate)
        val (root2, sendChain) = kdfRK(session.rootKey, dh(newPrivate, remotePublic))
        session.rootKey.fill(0)
        session.rootKey = root2
        session.sendChainKey?.fill(0)
        session.sendChainKey = sendChain
    }

    /** `skipMessageKeys` (`DoubleRatchet.swift:240-263`): stores the keys of messages up to [target] that have not arrived. */
    private fun skipMessageKeys(target: Long, session: Session) {
        val start = session.recvChainKey ?: return
        if (target < session.recvN) return
        if (target - session.recvN > MAX_SKIP) throw RatchetError.SkippedTooFar

        var chain = start
        val dhB64 = session.dhRecvPublic?.let(B64::encode)
        while (session.recvN < target) {
            val (next, messageKey) = kdfCK(chain)
            chain.fill(0)
            chain = next
            if (dhB64 != null) {
                session.skipped.put("$dhB64:${session.recvN}", messageKey)?.fill(0)
            } else {
                messageKey.fill(0)
            }
            session.recvN += 1
        }
        session.recvChainKey = chain

        if (session.skipped.size > MAX_SKIP) {
            val iterator = session.skipped.values.iterator()
            repeat(session.skipped.size - MAX_SKIP) {
                iterator.next().fill(0)
                iterator.remove()
            }
        }
    }

    // ---- KDFs and primitives ----

    /** `kdfRK` (`DoubleRatchet.swift:267-276`). Zeroes [dhOut]. */
    private fun kdfRK(root: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = try {
            Primitives.hkdf(dhOut, root, INFO_RK, 2 * KEY_BYTES)
        } finally {
            dhOut.fill(0)
        }
        try {
            return okm.copyOfRange(0, KEY_BYTES) to okm.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
        } finally {
            okm.fill(0)
        }
    }

    /** `kdfCK` (`DoubleRatchet.swift:278-282`): (next chain key, message key). */
    private fun kdfCK(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        Primitives.hmacSha256(chainKey, CK_NEXT) to Primitives.hmacSha256(chainKey, CK_MESSAGE)

    /** `openAES` (`:284-287`); any failure → [RatchetError.DecryptFailed]. */
    private fun openBody(key: ByteArray, combined: ByteArray): ByteArray = try {
        Primitives.aesGcmOpen(key, combined)
    } catch (_: CryptoError) {
        throw RatchetError.DecryptFailed
    }

    /** X25519 inside the ratchet: a key that is not 32 bytes or a low-order point → [RatchetError.InvalidKey]. */
    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray = try {
        Primitives.x25519(privateKey, publicKey)
    } catch (_: CryptoError) {
        throw RatchetError.InvalidKey
    }

    private fun publicKey(privateKey: ByteArray): ByteArray = try {
        Primitives.x25519Public(privateKey)
    } catch (_: CryptoError) {
        throw RatchetError.InvalidKey
    }
}

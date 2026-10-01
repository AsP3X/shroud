package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoJson
import de.corespace.shroud.core.crypto.IdentityKeyMaterial
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether this account's identity is on the phone (iOS `IdentityKeyStore.Presence`,
 * `ios/shroud/Services/Crypto/IdentityKeyStore.swift:38-47`). A locked phone cannot read the
 * record; that is [Unavailable], never [Absent]. **Callers end the session only for [Absent]**
 * (`:38-42`, crypto spec §9.2).
 */
enum class IdentityPresence { Present, Absent, Unavailable }

/**
 * The identity's private keys at rest, never the phrase (iOS `IdentityKeyStore`,
 * `ios/shroud/Services/Crypto/IdentityKeyStore.swift:5-308`; crypto spec §9).
 *
 * One record, `noBackupFilesDir/keys/identity.v1` (plan §1.5), in two layers:
 * - outer: the WhenUnlocked Keystore sealer ([SealedFile] with `shroud.local.v1`) — unreadable while
 *   the phone is locked, never restorable (invariant 11);
 * - inner: every private value sealed with `LocalHistoryCrypto` context `IdentityKeychain` under the
 *   history key, so the keys are exactly as locked as the chats: an unlocked phone's storage alone
 *   does not give them up (`:7-11`). User id, registration id and signed-prekey id stay plain so
 *   the lock screen can tell an identity is here.
 *
 * ```json
 * {"v":1,"user_id":"…","registration_id":8525,"agreement_private":"<b64 SHRD1…>","signing_private":"…",
 *  "spk_id":123,"spk_private":"…","otpk_map":"<b64 SHRD1 of [2] ‖ (id u32 ‖ private 32 B)*>"}
 * ```
 *
 * Android additions: each inner seal binds its field name as AAD ([aad], web-parity §3.2), so two
 * sealed values cannot be swapped between fields; and there is no plaintext legacy — a value
 * without the `SHRD1` magic never opens ([openPrivate], crypto §9.2 note). Writes are dropped while
 * [StorageSeal.isSealed] (crypto §14). Never logs ids or key bytes.
 */
class IdentityKeyStore(private val record: SealedFile, private val seal: StorageSeal) {
    /** Keychain read statuses the pure [presence] decision takes (`errSec*` on iOS). */
    enum class ReadStatus { Success, NotFound, DeviceLocked, Failed }

    /** The sealed private fields, with their record keys (`IdentityKeyStore.swift:213-228`). */
    enum class Field(val key: String) {
        AgreementPrivate("agreement_private"),
        SigningPrivate("signing_private"),
        SignedPreKeyPrivate("spk_private"),
        OneTimePreKeys("otpk_map"),
    }

    /**
     * The opened shell without the history key (`StoredIdentity`, `:25-36`). Owns its arrays;
     * [wipe] zeroes them once [IdentityKeyMaterial.restore] has copied what it needs.
     */
    class StoredIdentity(
        val userId: String,
        val registrationId: Int,
        val agreementPrivate: ByteArray,
        val signingPrivate: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKeyPrivate: ByteArray,
        val oneTimePreKeys: Map<Int, ByteArray>,
    ) {
        fun wipe() {
            agreementPrivate.fill(0)
            signingPrivate.fill(0)
            signedPreKeyPrivate.fill(0)
            oneTimePreKeys.values.forEach { it.fill(0) }
        }
    }

    @Serializable
    private data class Record(
        // Required (CryptoJson leaves defaults out): a later format reads it to migrate.
        val v: Int,
        @SerialName("user_id") val userId: String,
        @SerialName("registration_id") val registrationId: Int,
        @SerialName("agreement_private") val agreementPrivate: String? = null,
        @SerialName("signing_private") val signingPrivate: String? = null,
        @SerialName("spk_id") val spkId: Int,
        @SerialName("spk_private") val spkPrivate: String? = null,
        @SerialName("otpk_map") val otpkMap: String? = null,
    )

    private sealed interface Parsed {
        class Ok(val record: Record) : Parsed

        class Unreadable(val status: ReadStatus) : Parsed
    }

    /**
     * The account the stored identity belongs to, as stored. Readable while chats are locked, once
     * the phone itself is unlocked; null without a readable record (`:49-53`).
     */
    fun storedUserId(): String? = (parse() as? Parsed.Ok)?.record?.userId

    /**
     * Whether an identity record exists on disk at all, readable or not. `CryptoController.lock`
     * uses it for `needsHistoryUnlock`: an Android lock can run while the phone is locked, when
     * [storedUserId] cannot read the record (iOS locks only while it is in front).
     */
    fun hasRecord(): Boolean = record.exists()

    /**
     * [presence] for this phone's record: a missing file, or one whose outer key is gone or does
     * not match, is `NotFound`; a locked phone `DeviceLocked`; any other failure `Failed`. A
     * readable record without `agreement_private` reports the key as `NotFound` (crypto spec §9.2).
     */
    fun presence(userId: String): IdentityPresence = when (val parsed = parse()) {
        is Parsed.Unreadable -> presence(parsed.status, null, userId, parsed.status)
        is Parsed.Ok -> presence(
            userIdStatus = ReadStatus.Success,
            storedUserId = parsed.record.userId,
            expected = userId,
            privateKeyStatus = if (parsed.record.agreementPrivate != null) ReadStatus.Success else ReadStatus.NotFound,
        )
    }

    /** True when this account's identity is here (`:189-194`). Does not open the sealed keys. */
    fun hasIdentity(userId: String): Boolean = presence(userId) == IdentityPresence.Present

    /**
     * Opens the private keys with [historyKey] (`:91-143`). Null while the phone is locked, with
     * the wrong key, or when a value is missing, tampered with or not exactly 32 bytes. One-time
     * prekeys that do not parse are skipped one by one (`:121-131`); a missing or unopenable OTPK map
     * is an empty pool.
     */
    fun load(historyKey: ByteArray): StoredIdentity? {
        val stored = (parse() as? Parsed.Ok)?.record ?: return null
        val agreement = openField(stored.agreementPrivate, Field.AgreementPrivate, historyKey)?.takeIf { it.size == KEY_BYTES }
        val signing = openField(stored.signingPrivate, Field.SigningPrivate, historyKey)?.takeIf { it.size == KEY_BYTES }
        val spk = openField(stored.spkPrivate, Field.SignedPreKeyPrivate, historyKey)?.takeIf { it.size == KEY_BYTES }
        if (agreement == null || signing == null || spk == null) {
            agreement?.fill(0)
            signing?.fill(0)
            spk?.fill(0)
            return null
        }
        val otpks = openField(stored.otpkMap, Field.OneTimePreKeys, historyKey)?.let { map ->
            try {
                parseOneTimePreKeys(map)
            } finally {
                map.fill(0)
            }
        }.orEmpty()
        return StoredIdentity(stored.userId, stored.registrationId, agreement, signing, stored.spkId, spk, otpks)
    }

    /**
     * Writes the identity with every private value sealed under `material.historyKey`
     * (`:157-180`), replacing the record atomically. Dropped while a wipe runs. Throws when the
     * record cannot be sealed or written (the phone locked meanwhile, I/O).
     */
    fun save(material: IdentityKeyMaterial) {
        if (seal.isSealed) return
        val key = material.historyKey
        val otpkMap = encodeOneTimePreKeys(material.oneTimePreKeys.map { it.keyId to it.privateKey })
        val stored = try {
            Record(
                v = VERSION,
                userId = material.userId,
                registrationId = material.registrationId,
                agreementPrivate = B64.encode(sealPrivate(material.agreementPrivateKey, key, Field.AgreementPrivate)),
                signingPrivate = B64.encode(sealPrivate(material.signingPrivateKey, key, Field.SigningPrivate)),
                spkId = material.signedPreKeyId,
                spkPrivate = B64.encode(sealPrivate(material.signedPreKeyPrivate, key, Field.SignedPreKeyPrivate)),
                otpkMap = B64.encode(sealPrivate(otpkMap, key, Field.OneTimePreKeys)),
            )
        } finally {
            otpkMap.fill(0)
        }
        if (seal.isSealed) return
        record.write(utf8(CryptoJson.encodeToString(Record.serializer(), stored)))
    }

    /** Deletes the record (`:182-187`). Needs no key and no unlock. */
    fun clear() {
        record.delete()
    }

    private fun parse(): Parsed = when (val read = record.readClassified()) {
        is RecordRead.Found -> try {
            Parsed.Ok(CryptoJson.decodeFromString(Record.serializer(), read.bytes.decodeToString()))
        } catch (_: Exception) {
            Parsed.Unreadable(ReadStatus.Failed)
        } finally {
            read.bytes.fill(0)
        }
        RecordRead.NotFound -> Parsed.Unreadable(ReadStatus.NotFound)
        RecordRead.DeviceLocked -> Parsed.Unreadable(ReadStatus.DeviceLocked)
        RecordRead.Failed -> Parsed.Unreadable(ReadStatus.Failed)
    }

    private fun openField(value: String?, field: Field, historyKey: ByteArray): ByteArray? {
        val sealed = value?.let(B64::decodeStrict) ?: return null
        return openPrivate(sealed, historyKey, field)
    }


    companion object {
        const val VERSION = 1
        private const val KEY_BYTES = 32

        /** Format byte of the sealed OTPK map ([encodeOneTimePreKeys]). Wave 1's JSON map is not read. */
        const val OTPK_FORMAT: Byte = 2
        private const val OTPK_ENTRY_BYTES = 4 + KEY_BYTES

        /**
         * The one-time prekeys as sealed: `OTPK_FORMAT ‖ (id u32 big-endian ‖ private 32 B)*`, in one
         * exactly sized array — local only, so not the iOS JSON (`{"<id>":"<b64>"}`), which would
         * put every private key into `String`s nothing can zero. The caller zeroes the result.
         */
        fun encodeOneTimePreKeys(keys: List<Pair<Int, ByteArray>>): ByteArray {
            val out = ByteArray(1 + keys.size * OTPK_ENTRY_BYTES)
            out[0] = OTPK_FORMAT
            var at = 1
            for ((id, private) in keys) {
                if (id < 0 || private.size != KEY_BYTES) {
                    out.fill(0)
                    throw IllegalArgumentException("invalid one-time prekey")
                }
                for (shift in intArrayOf(24, 16, 8, 0)) out[at++] = (id ushr shift).toByte()
                private.copyInto(out, at)
                at += KEY_BYTES
            }
            return out
        }

        /**
         * Inverse of [encodeOneTimePreKeys]; an unknown format or a length that is not whole entries is
         * an empty pool (`:121-131` skips what does not parse). Ids above `Int.MAX_VALUE` are skipped.
         * Copies every key out; does not zero [bytes].
         */
        fun parseOneTimePreKeys(bytes: ByteArray): Map<Int, ByteArray> {
            if (bytes.isEmpty() || bytes[0] != OTPK_FORMAT || (bytes.size - 1) % OTPK_ENTRY_BYTES != 0) return emptyMap()
            val out = HashMap<Int, ByteArray>()
            var at = 1
            while (at < bytes.size) {
                var id = 0L
                repeat(4) { id = (id shl 8) or (bytes[at++].toLong() and 0xFF) }
                if (id <= Int.MAX_VALUE) out.put(id.toInt(), bytes.copyOfRange(at, at + KEY_BYTES))?.fill(0)
                at += KEY_BYTES
            }
            return out
        }

        /** The inner seal's AAD for [field]: `shroud.identity.v1:<record key>` (web-parity §3.2). */
        fun aad(field: Field): ByteArray = utf8("shroud.identity.v1:" + field.key)

        /** Seals one private value (`sealPrivate`, `IdentityKeyStore.swift:198-201`) for [field]. */
        fun sealPrivate(value: ByteArray, historyKey: ByteArray, field: Field): ByteArray =
            LocalHistoryCrypto.seal(value, historyKey, LocalHistoryCrypto.Context.IdentityKeychain, aad(field))

        /**
         * Opens a value written by [sealPrivate] for the same [field]. Null when it is not a sealed
         * blob or does not open — never read as plaintext. iOS returns unsealed input as-is to
         * migrate older builds (`:203-209`); Android has none (crypto spec §9.2 note).
         */
        fun openPrivate(stored: ByteArray, historyKey: ByteArray, field: Field): ByteArray? {
            if (!LocalHistoryCrypto.isSealedBlob(stored)) return null
            return try {
                LocalHistoryCrypto.open(stored, historyKey, LocalHistoryCrypto.Context.IdentityKeychain, aad(field))
            } catch (_: Exception) {
                null
            }
        }

        /**
         * The presence decision, ported verbatim for tests (`IdentityKeyStore.swift:68-89`).
         * The stored id compares case-insensitively (iOS stores `uuidString` upper-case).
         */
        fun presence(userIdStatus: ReadStatus, storedUserId: String?, expected: String, privateKeyStatus: ReadStatus): IdentityPresence =
            when {
                userIdStatus == ReadStatus.DeviceLocked || privateKeyStatus == ReadStatus.DeviceLocked -> IdentityPresence.Unavailable
                userIdStatus != ReadStatus.Success ->
                    if (userIdStatus == ReadStatus.NotFound) IdentityPresence.Absent else IdentityPresence.Unavailable
                storedUserId == null || !storedUserId.equals(expected, ignoreCase = true) -> IdentityPresence.Absent
                privateKeyStatus == ReadStatus.Success -> IdentityPresence.Present
                privateKeyStatus == ReadStatus.NotFound -> IdentityPresence.Absent
                else -> IdentityPresence.Unavailable
            }
    }
}

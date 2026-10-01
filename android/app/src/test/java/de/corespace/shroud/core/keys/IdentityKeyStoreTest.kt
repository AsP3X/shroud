package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.IdentityKeyMaterial
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.TestWordlist
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The identity record `keys/identity.v1` (iOS `ios/shroud/Services/Crypto/IdentityKeyStore.swift`;
 * crypto spec §9): two layers (WhenUnlocked outer seal, history-key inner seal per private field),
 * presence from one classified read, loads that fail closed.
 */
class IdentityKeyStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val seal = StorageSeal()
    private val file get() = temp.noBackupFilesDir.resolve("keys/identity.v1")
    private val store by lazy { IdentityKeyStore(SealedFile(file, sealer), seal) }
    private val user = "8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E"
    private val material by lazy {
        IdentityKeyMaterial.establish(TestWordlist.bip39, List(11) { "abandon" } + "about", user, oneTimePreKeyCount = 3)
    }

    private fun record(): JsonObject = Json.parseToJsonElement(sealer.open(file.readBytes()).decodeToString()).jsonObject

    private fun rewrite(change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        val r = record().toMutableMap()
        change(r)
        SealedFile(file, sealer).write(JsonObject(r).toString().toByteArray())
    }

    @Test
    fun noRecordIsAbsent() {
        assertNull(store.storedUserId())
        assertFalse(store.hasRecord())
        assertEquals(IdentityPresence.Absent, store.presence(user))
        assertFalse(store.hasIdentity(user))
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun saveAndLoadRoundTripUnderTheHistoryKey() {
        store.save(material)
        assertTrue(store.hasRecord())
        assertEquals(user, store.storedUserId())
        assertEquals(IdentityPresence.Present, store.presence(user.lowercase()))
        assertTrue(store.hasIdentity(user))

        val stored = store.load(material.historyKey)!!
        assertEquals(user, stored.userId)
        assertEquals(material.registrationId, stored.registrationId)
        assertEquals(material.signedPreKeyId, stored.signedPreKeyId)
        assertArrayEquals(material.agreementPrivateKey, stored.agreementPrivate)
        assertArrayEquals(material.signingPrivateKey, stored.signingPrivate)
        assertArrayEquals(material.signedPreKeyPrivate, stored.signedPreKeyPrivate)
        assertEquals(listOf(1, 2, 3), stored.oneTimePreKeys.keys.sorted())
        for (otpk in material.oneTimePreKeys) assertArrayEquals(otpk.privateKey, stored.oneTimePreKeys[otpk.keyId])

        // Restored material is the same identity with this device's prekeys.
        val restored = IdentityKeyMaterial.restore(stored, material.historyKey)
        assertArrayEquals(material.agreementPublic, restored.agreementPublic)
        assertArrayEquals(material.signedPreKeyPublic, restored.signedPreKeyPublic)
    }

    @Test
    fun theRecordKeepsIdsPlainAndEveryPrivateSealed() {
        store.save(material)
        val r = record()
        assertEquals(setOf("v", "user_id", "registration_id", "agreement_private", "signing_private", "spk_id", "spk_private", "otpk_map"), r.keys)
        assertEquals(1, r["v"]!!.jsonPrimitive.int)
        assertEquals(material.registrationId, r["registration_id"]!!.jsonPrimitive.int)
        assertEquals(material.signedPreKeyId, r["spk_id"]!!.jsonPrimitive.int)
        for (field in IdentityKeyStore.Field.entries) {
            val sealed = B64.decodeStrict(r[field.key]!!.jsonPrimitive.content)!!
            assertTrue(field.key, LocalHistoryCrypto.isSealedBlob(sealed))
        }
        val plaintext = sealer.open(file.readBytes()).hex()
        for (secret in listOf(material.agreementPrivateKey, material.signingPrivateKey, material.signedPreKeyPrivate, material.historyKey)) {
            assertFalse(plaintext.contains(secret.hex()))
        }
    }

    @Test
    fun theWrongHistoryKeyOpensNothing() {
        store.save(material)
        assertNull(store.load(ByteArray(32) { 7 }))
        // The ids still read: the lock screen can tell the identity is here.
        assertEquals(IdentityPresence.Present, store.presence(user))
    }

    @Test
    fun aLockedPhoneIsUnavailableNeverAbsent() {
        store.save(material)
        sealer.readFailure = SealResult.DeviceLocked
        assertEquals(IdentityPresence.Unavailable, store.presence(user))
        assertNull(store.storedUserId())
        assertTrue("the file is still there", store.hasRecord())
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun aVanishedOuterKeyIsAbsentSoThePhraseRebuildsIt() {
        store.save(material)
        sealer.readFailure = SealResult.KeyGone
        assertEquals(IdentityPresence.Absent, store.presence(user))
        sealer.readFailure = SealResult.Corrupt
        assertEquals(IdentityPresence.Absent, store.presence(user))
        sealer.readFailure = SealResult.Failed
        assertEquals(IdentityPresence.Unavailable, store.presence(user))
    }

    @Test
    fun anotherAccountsIdentityIsAbsent() {
        store.save(material)
        assertEquals(IdentityPresence.Absent, store.presence("11111111-2222-4333-8444-555555555555"))
    }

    @Test
    fun aRecordWithoutTheAgreementKeyIsAbsent() {
        store.save(material)
        rewrite { it.remove("agreement_private") }
        assertEquals(IdentityPresence.Absent, store.presence(user))
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun anUnparsableRecordIsUnavailable() {
        SealedFile(file, sealer).write("not json".toByteArray())
        assertEquals(IdentityPresence.Unavailable, store.presence(user))
        assertNull(store.storedUserId())
    }

    @Test
    fun swappedFieldsDoNotOpen() {
        store.save(material)
        rewrite {
            val a = it["agreement_private"]!!
            it["agreement_private"] = it["signing_private"]!!
            it["signing_private"] = a
        }
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun aPlaintextKeyInTheRecordIsNeverRead() {
        store.save(material)
        rewrite { it["agreement_private"] = JsonPrimitive(B64.encode(material.agreementPrivateKey)) }
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun oneTimePreKeysThatDoNotParseAreAnEmptyPool() {
        store.save(material)
        val key = material.historyKey
        fun sealMap(bytes: ByteArray) = rewrite {
            it["otpk_map"] = JsonPrimitive(B64.encode(IdentityKeyStore.sealPrivate(bytes, key, IdentityKeyStore.Field.OneTimePreKeys)))
        }
        // Ids beyond Int are skipped one by one; the rest load.
        val ids = listOf(7 to ByteArray(32) { 3 }, 10 to ByteArray(32) { 4 })
        val encoded = IdentityKeyStore.encodeOneTimePreKeys(ids)
        val withHugeId = encoded + byteArrayOf(0x80.toByte(), 0, 0, 0) + ByteArray(32) { 5 }
        sealMap(withHugeId)
        val stored = store.load(key)!!
        assertEquals(listOf(7, 10), stored.oneTimePreKeys.keys.sorted())
        assertArrayEquals(ByteArray(32) { 4 }, stored.oneTimePreKeys[10])
        // A torn entry, another format, or wave 1's JSON map: an empty pool, not a failed load.
        for (broken in listOf(encoded.copyOf(encoded.size - 1), encoded.copyOf().also { it[0] = 1 }, """{"7":"AAAA"}""".toByteArray())) {
            sealMap(broken)
            assertEquals(emptyMap<Int, ByteArray>(), store.load(key)!!.oneTimePreKeys)
        }
        // A missing or unopenable pool is empty, not a failed load (`IdentityKeyStore.swift:121-131`).
        rewrite { it.remove("otpk_map") }
        assertEquals(emptyMap<Int, ByteArray>(), store.load(key)!!.oneTimePreKeys)
    }

    /**
     * The one-time prekeys are sealed as raw bytes, never through a `String` (which nothing can
     * zero): the opened map holds every private key as is and no Base64 or hex text of one.
     */
    @Test
    fun oneTimePreKeysAreSealedWithoutATextFormOfAnyKey() {
        store.save(material)
        val sealed = B64.decodeStrict(record()["otpk_map"]!!.jsonPrimitive.content)!!
        val opened = LocalHistoryCrypto.open(sealed, material.historyKey, LocalHistoryCrypto.Context.IdentityKeychain, IdentityKeyStore.aad(IdentityKeyStore.Field.OneTimePreKeys))
        assertEquals(IdentityKeyStore.OTPK_FORMAT, opened[0])
        assertEquals(1 + material.oneTimePreKeys.size * 36, opened.size)
        for (otpk in material.oneTimePreKeys) {
            assertTrue(opened.hex().contains(otpk.privateKey.hex()))
            assertFalse(opened.hex().contains(B64.encode(otpk.privateKey).toByteArray().hex()))
        }
    }

    @Test
    fun aWrongSizedPrivateFailsTheLoad() {
        store.save(material)
        rewrite {
            it["spk_private"] = JsonPrimitive(B64.encode(IdentityKeyStore.sealPrivate(ByteArray(31), material.historyKey, IdentityKeyStore.Field.SignedPreKeyPrivate)))
        }
        assertNull(store.load(material.historyKey))
    }

    @Test
    fun saveIsDroppedDuringAWipeAndClearNeedsNoKey() {
        seal.seal()
        store.save(material)
        assertFalse(file.exists())
        seal.unseal()
        store.save(material)
        sealer.readFailure = SealResult.DeviceLocked
        store.clear()
        assertFalse(file.exists())
        sealer.readFailure = null
        assertEquals(IdentityPresence.Absent, store.presence(user))
    }

    @Test
    fun aFailedWriteThrows() {
        sealer.sealFails = true
        assertTrue(runCatching { store.save(material) }.isFailure)
        assertFalse(file.exists())
    }

    @Test
    fun wipingAStoredShellZeroesItsKeys() {
        store.save(material)
        val stored = store.load(material.historyKey)!!
        stored.wipe()
        assertTrue(stored.agreementPrivate.all { it == 0.toByte() })
        assertTrue(stored.signingPrivate.all { it == 0.toByte() })
        assertTrue(stored.signedPreKeyPrivate.all { it == 0.toByte() })
        assertTrue(stored.oneTimePreKeys.values.all { v -> v.all { it == 0.toByte() } })
    }
}

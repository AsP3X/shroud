package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedDirectoryStore
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.SealedTestKey
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Identity privates and ratchet sessions are sealed under the history key before they reach the
 * Keystore-sealed files (iOS `ios/shroudTests/SealedKeychainValuesTests.swift`; crypto spec
 * §16.1). N/A on Android (no older build left plaintext): `legacyPlaintextIdentityValueIsReadForMigration`,
 * `legacyPlaintextSessionIsFlaggedForResealing`, `unlockReadsOnlyUnmarkedRatchetItems` — instead a
 * value without the sealed magic is never read ([plaintextIsNeverReadAsAKey]).
 */
class SealedKeyValuesTest {
    @get:Rule
    val temp = TempDirRule()

    private val historyKey = SystemEntropy.bytes(32)
    private val field = IdentityKeyStore.Field.AgreementPrivate
    private val sessionJson = """{"rootKey":"AAAA","sendChainKey":"BBBB","dhSelfPrivate":"CCCC","n":0}""".toByteArray()

    private fun ratchets(key: ByteArray?): Pair<RatchetSessionStore, SealedDirectoryStore> {
        val records = SealedDirectoryStore(temp.noBackupFilesDir.resolve("keys/ratchets"), XorSealer())
        val state = SealedLocalState().apply { setHistoryKeyForTesting(key) }
        return RatchetSessionStore(records, state, StorageSeal()) to records
    }

    /** `identityPrivateRoundTripsOnlyUnderTheHistoryKey` (`:14-23`). */
    @Test
    fun identityPrivateRoundTripsOnlyUnderTheHistoryKey() {
        val raw = SystemEntropy.bytes(32)
        val sealed = IdentityKeyStore.sealPrivate(raw, historyKey, field)
        assertTrue(LocalHistoryCrypto.isSealedBlob(sealed))
        assertFalse(sealed.hex().contains(raw.hex()))
        assertArrayEquals(raw, IdentityKeyStore.openPrivate(sealed, historyKey, field))
        assertNull(IdentityKeyStore.openPrivate(sealed, SystemEntropy.bytes(32), field))
    }

    /** `tamperedIdentityValueIsNeverReadAsPlaintext` (`:25-31`). */
    @Test
    fun tamperedIdentityValueIsNeverReadAsPlaintext() {
        val sealed = IdentityKeyStore.sealPrivate(SystemEntropy.bytes(32), historyKey, field)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertNull(IdentityKeyStore.openPrivate(sealed, historyKey, field))
    }

    /** Android: no plaintext legacy, so a raw value is never taken for a key (crypto spec §9.2 note). */
    @Test
    fun plaintextIsNeverReadAsAKey() {
        assertNull(IdentityKeyStore.openPrivate(SystemEntropy.bytes(32), historyKey, field))
    }

    /** Android: each sealed field binds its name (web-parity §3.2), so values cannot be swapped between fields. */
    @Test
    fun aValueSealedForOneFieldDoesNotOpenAsAnother() {
        val sealed = IdentityKeyStore.sealPrivate(SystemEntropy.bytes(32), historyKey, IdentityKeyStore.Field.SigningPrivate)
        assertNull(IdentityKeyStore.openPrivate(sealed, historyKey, IdentityKeyStore.Field.AgreementPrivate))
    }

    /** `ratchetSessionRoundTripsOnlyUnderTheHistoryKey` (`:49-60`), through the store. */
    @Test
    fun ratchetSessionRoundTripsOnlyUnderTheHistoryKey() {
        val peer = UUID.randomUUID()
        val (store, records) = ratchets(historyKey)
        store.save(peer, sessionJson)
        val name = records.names().single()
        val sealed = XorSealer().open((temp.noBackupFilesDir.resolve("keys/ratchets/$name")).readBytes())
        assertTrue(LocalHistoryCrypto.isSealedBlob(sealed))
        assertFalse(sealed.hex().contains(sessionJson.hex()))
        assertArrayEquals(sessionJson, store.load(peer))
        // Another history key finds no session (the keyed name differs), and the record itself
        // does not open under it either.
        val (other, _) = ratchets(SystemEntropy.bytes(32))
        assertNull(other.load(peer))
        assertTrue(
            runCatching {
                LocalHistoryCrypto.open(sealed, SystemEntropy.bytes(32), LocalHistoryCrypto.Context.RatchetKeychain, RatchetSessionStore.aad(name))
            }.isFailure,
        )
    }

    /** `storesUseSeparateSubkeys` (`:84-92`). */
    @Test
    fun storesUseSeparateSubkeys() {
        val peer = UUID.randomUUID()
        val (store, records) = ratchets(historyKey)
        val name = LocalNames.derive(historyKey).name(LocalNames.Kind.RATCHET, peer)
        // Identity-sealed bytes planted as a ratchet record do not open as a session…
        records.write(name, IdentityKeyStore.sealPrivate(sessionJson, historyKey, field))
        assertNull(store.load(peer))
        // …a ratchet record does not open as an identity value…
        store.save(peer, sessionJson)
        val asRatchet = (records.read(name) as RecordRead.Found).bytes
        assertNull(IdentityKeyStore.openPrivate(asRatchet, historyKey, field))
        // …nor as language statistics.
        assertTrue(runCatching { LocalHistoryCrypto.open(asRatchet, historyKey, LocalHistoryCrypto.Context.LanguageStats) }.isFailure)
    }

    @Test
    fun theSharedTestKeyIsTheIosOne() {
        assertArrayEquals(ByteArray(32) { 0x5A }, SealedTestKey.bytes())
    }
}

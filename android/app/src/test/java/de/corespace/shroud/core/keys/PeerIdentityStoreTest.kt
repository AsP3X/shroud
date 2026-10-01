package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Pinned peer identity keys (iOS `ios/shroud/Services/API/PeerIdentityStore.swift`, test
 * `ios/shroudTests/PeerIdentityStoreTests.swift`; api-realtime §10.1 with plan C16): one
 * WhenUnlocked record `keys/peer-identity.v1`. iOS `migratesLegacyUserDefaults` is N/A (nothing to
 * migrate on Android).
 */
class PeerIdentityStoreTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val seal = StorageSeal()
    private val file get() = temp.noBackupFilesDir.resolve("keys/peer-identity.v1")
    private fun store() = PeerIdentityStore(SealedFile(file, sealer), seal)

    /** `roundTripAndClear` (`PeerIdentityStoreTests.swift:6-15`): 32 × 0x07, Base64. */
    @Test
    fun roundTripAndClear() {
        val store = store()
        val userId = UUID.randomUUID()
        val key = ByteArray(32) { 7 }
        store.save(userId, key)
        assertEquals(B64.encode(key), store.publicKeyBase64(userId))
        assertArrayEquals(key, store.publicKey(userId))
        store.clear()
        assertNull(store.publicKeyBase64(userId))
        assertFalse(file.exists())
    }

    @Test
    fun pinsAndFlagsSurviveARestart() {
        val alice = UUID.fromString("8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E")
        val bob = UUID.randomUUID()
        store().apply {
            save(alice, ByteArray(32) { 1 })
            save(bob, ByteArray(32) { 2 })
            setVerified(alice, true)
        }
        val reopened = store()
        assertArrayEquals(ByteArray(32) { 1 }, reopened.publicKey(alice))
        assertArrayEquals(ByteArray(32) { 2 }, reopened.publicKey(bob))
        assertTrue(reopened.isVerified(alice))
        assertFalse(reopened.isVerified(bob))
        // The record: lower-case ids, Base64 keys, verified list (api-realtime §10.1).
        val r = Json.parseToJsonElement(sealer.open(file.readBytes()).decodeToString()).jsonObject
        assertEquals(1, r["v"]!!.jsonPrimitive.content.toInt())
        assertEquals(B64.encode(ByteArray(32) { 1 }), r["keys"]!!.jsonObject["8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"]!!.jsonPrimitive.content)
        assertEquals(listOf("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"), r["verified"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun verifiedFlagsToggle() {
        val store = store()
        val peer = UUID.randomUUID()
        assertFalse(store.isVerified(peer))
        store.setVerified(peer, true)
        store.setVerified(peer, true)
        assertTrue(store.isVerified(peer))
        store.setVerified(peer, false)
        assertFalse(store.isVerified(peer))
        assertFalse(store().isVerified(peer))
    }

    @Test
    fun aSavedKeyReplacesThePreviousPin() {
        val store = store()
        val peer = UUID.randomUUID()
        store.save(peer, ByteArray(32) { 1 })
        store.save(peer, ByteArray(32) { 2 })
        assertArrayEquals(ByteArray(32) { 2 }, store().publicKey(peer))
    }

    /** A merge over a record the locked phone refuses would drop every other pin: writes wait. */
    @Test
    fun aLockedPhoneReadsNothingAndDropsWritesUntilItReads() {
        val peer = UUID.randomUUID()
        store().save(peer, ByteArray(32) { 1 })
        sealer.readFailure = SealResult.DeviceLocked
        val store = store()
        assertNull(store.publicKey(peer))
        store.save(UUID.randomUUID(), ByteArray(32) { 9 })
        sealer.readFailure = null
        assertArrayEquals("not cached while locked", ByteArray(32) { 1 }, store.publicKey(peer))
        assertEquals(1, Json.parseToJsonElement(sealer.open(file.readBytes()).decodeToString()).jsonObject["keys"]!!.jsonObject.size)
    }

    @Test
    fun aWipeInProgressDropsWrites() {
        val store = store()
        seal.seal()
        store.save(UUID.randomUUID(), ByteArray(32) { 1 })
        assertFalse(file.exists())
    }

    @Test
    fun aFailedWriteLeavesMemoryAsItWas() {
        val store = store()
        val peer = UUID.randomUUID()
        sealer.sealFails = true
        store.save(peer, ByteArray(32) { 1 })
        assertNull(store.publicKey(peer))
    }

    @Test
    fun forgettingTheCacheRereadsTheFile() {
        val store = store()
        val peer = UUID.randomUUID()
        store.save(peer, ByteArray(32) { 1 })
        file.delete() // what KeyMaterialWipe does
        assertArrayEquals("still cached", ByteArray(32) { 1 }, store.publicKey(peer))
        store.forgetCache()
        assertNull(store.publicKey(peer))
    }

    @Test
    fun aKeyThatIsNotStrictBase64ReadsAsNone() {
        val peer = UUID.fromString("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e")
        SealedFile(file, sealer).write("""{"v":1,"keys":{"8f14e45f-ceea-467a-9575-3a6b7a1e6c0e":"AAAA AAAA"}}""".toByteArray())
        val store = store()
        assertEquals("AAAA AAAA", store.publicKeyBase64(peer))
        assertNull(store.publicKey(peer))
    }
}

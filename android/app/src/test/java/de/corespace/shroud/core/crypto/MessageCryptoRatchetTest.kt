package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import de.corespace.shroud.core.crypto.CryptoFixtures.version
import de.corespace.shroud.core.keys.RatchetSessionRecords
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v3 envelopes through [MessageCrypto]: the MessageCrypto-level cases of iOS
 * `ios/shroudTests/DoubleRatchetTests.swift:95-614` (crypto spec §5.8 interop scenarios), then the
 * Android additions — locked ratchets (D5), save-after-seal, and the per-peer monitor (plan §1.4).
 *
 * As on iOS, one ratchet store stands for both parties: Alice keeps her session under Bob's id,
 * Bob his under Alice's (`DoubleRatchetTests.swift:7-31`).
 */
class MessageCryptoRatchetTest {
    private val records = InMemoryRatchetSessionRecords()
    private val tags = InMemorySenderTagWatermarks()
    private val crypto = MessageCrypto(records, tags)
    private val aliceUser: UUID
    private val bobUser: UUID

    init {
        val (lower, higher) = CryptoFixtures.sortedUserIds()
        aliceUser = lower
        bobUser = higher
    }

    private val alice = TestIdentity.random()
    private val bob = TestIdentity.random()

    private fun seal(text: String, from: TestIdentity, fromUser: UUID, to: TestIdentity, toUser: UUID, useRatchet: Boolean = true): ByteArray =
        crypto.seal(utf8(text), toUser, to.public, from.private, from.public, fromUser, useRatchet)

    /** Opens [envelope] as [reader]; the session lives under [storeAsPeer]. */
    private fun open(
        envelope: ByteArray,
        reader: TestIdentity,
        sender: TestIdentity,
        storeAsPeer: UUID,
        role: OpenAs = OpenAs.Recipient,
        with: MessageCrypto = crypto,
    ): String = String(with.open(envelope, storeAsPeer, reader.private, reader.public, sender.public, role, Instant.now()), Charsets.UTF_8)

    private fun aliceSends(text: String) = seal(text, alice, aliceUser, bob, bobUser)
    private fun bobSends(text: String) = seal(text, bob, bobUser, alice, aliceUser)
    private fun bobReads(envelope: ByteArray) = open(envelope, bob, alice, aliceUser)
    private fun aliceReads(envelope: ByteArray) = open(envelope, alice, bob, bobUser)

    // ---- iOS DoubleRatchetTests (MessageCrypto level) ----

    /** `DoubleRatchetTests.swift:95-127`: the lower id is the deterministic initiator → v3 with both boxes. */
    @Test
    fun productionSealDefaultsToV3WhenInitiator() {
        val sealed = aliceSends("hello")
        val fields = CryptoJson.parseToJsonElement(String(sealed, Charsets.UTF_8)) as JsonObject
        assertEquals(3, version(sealed))
        assertNotNull(fields["peer"])
        assertNotNull(fields["self"])
        assertEquals("hello", bobReads(sealed))
    }

    /** `DoubleRatchetTests.swift:129-157`: the higher id must not create a poison initiator session. */
    @Test
    fun nonInitiatorFirstMessageUsesV2() {
        val sealed = bobSends("bob first")
        assertEquals(2, version(sealed))
        assertNull(records.load(aliceUser))
        assertEquals("bob first", aliceReads(sealed))
    }

    /** `DoubleRatchetTests.swift:159-229`: both send before either opens. */
    @Test
    fun dualInitiatorBothSendThenDecrypt() {
        val fromBob = bobSends("bob first")
        val fromAlice = aliceSends("alice first")
        assertEquals(3, version(fromAlice))
        assertEquals(2, version(fromBob))

        assertEquals("alice first", bobReads(fromAlice))
        assertEquals("bob first", aliceReads(fromBob))

        // Bob received, so he has a session and his next send is v3.
        val fromBob2 = bobSends("bob second")
        assertEquals(3, version(fromBob2))
        assertEquals("bob second", aliceReads(fromBob2))
    }

    /** `DoubleRatchetTests.swift:231-299`. */
    @Test
    fun fullChatSimulationAlternating() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        assertEquals("B1", aliceReads(bobSends("B1")))
        assertEquals("A2", bobReads(aliceSends("A2")))
    }

    /** `DoubleRatchetTests.swift:301-325`. */
    @Test
    fun selfBoxOpensOnV3() {
        val sealed = aliceSends("own history")
        assertEquals("own history", open(sealed, alice, alice, bobUser, OpenAs.Sender))
    }

    /** `DoubleRatchetTests.swift:327-383`: Alice's other device (same keys, no session) reads Bob's reply via the peer box. */
    @Test
    fun siblingDeviceOpensViaPeerBox() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        val fromBob = bobSends("B1")
        assertEquals("B1", aliceReads(fromBob))
        records.delete(bobUser)
        assertEquals("B1", aliceReads(fromBob))
    }

    /**
     * `DoubleRatchetTests.swift:385-442`: a sibling with its own (different) session opens Bob's reply
     * via the peer box and must not persist the failed DH-ratchet attempt over its session.
     */
    @Test
    fun staleSiblingSessionOpensViaPeerBoxWithoutSaving() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        records.delete(bobUser)
        aliceSends("A-web")
        val webAlice = records.session(bobUser)
        assertNotNull(webAlice)

        val fromBob = bobSends("B1")
        assertEquals("B1", aliceReads(fromBob))
        assertEquals(webAlice, records.session(bobUser))
    }

    /** `DoubleRatchetTests.swift:444-510`: Alice-web sending must not overwrite Bob's session; the phone sends on. */
    @Test
    fun siblingSendDoesNotPoisonPeerSession() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        val phoneAlice = records.session(bobUser)!!

        records.delete(bobUser)
        assertEquals("A2", bobReads(aliceSends("A2")))

        records.put(bobUser, phoneAlice)
        assertEquals("A3", bobReads(aliceSends("A3")))
    }

    /** `DoubleRatchetTests.swift:512-531`. */
    @Test
    fun legacyV2StillWorks() {
        val sealed = crypto.sealV2(utf8("classic"), bob.public, alice.private, alice.public)
        val opened = crypto.openLegacy(sealed, bob.private, bob.public, alice.public, OpenAs.Recipient, Instant.now())
        assertEquals("classic", String(opened, Charsets.UTF_8))
    }

    /** `DoubleRatchetTests.swift:533-547`. */
    @Test
    fun explicitV2OptOut() {
        val sealed = crypto.seal(utf8("no ratchet"), UUID.randomUUID(), bob.public, alice.private, alice.public, aliceUser, useRatchet = false)
        assertEquals(2, version(sealed))
    }

    /** `DoubleRatchetTests.swift:549-614`: re-opening a read envelope (peer box) must not break the next message. */
    @Test
    fun textThenImageDoesNotBreakRecipient() {
        val textEnv = aliceSends("hi")
        assertEquals("hi", bobReads(textEnv))
        assertEquals("hi", bobReads(textEnv))

        // MediaMessagePayload(t: image, mime, w: 10, h: 10, k: b64(0x01 × 32)) as the iOS test encodes it.
        val imagePayload = utf8("""{"t":"image","mime":"image/jpeg","w":10,"h":10,"k":"${B64.encode(bytes(0x01, 32))}"}""")
        val imageEnv = crypto.seal(imagePayload, bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertArrayEquals(imagePayload, crypto.open(imageEnv, aliceUser, bob.private, bob.public, alice.public, OpenAs.Recipient, Instant.now()))
    }

    // ---- Android: locked ratchets (crypto D5) ----

    @Test
    fun sealWhileTheRatchetStoreIsLockedThrowsInsteadOfForking() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        val before = records.session(bobUser)
        records.isUnlocked = false
        assertThrows(CryptoError.Locked::class.java) { aliceSends("A2") }
        // Also for the higher id, which would only have sealed v2.
        assertThrows(CryptoError.Locked::class.java) { bobSends("B1") }
        // Opting out of the ratchet needs no store.
        assertEquals(2, version(seal("v2", alice, aliceUser, bob, bobUser, useRatchet = false)))
        records.isUnlocked = true
        assertEquals(before, records.session(bobUser))
    }

    @Test
    fun aLockLandingDuringTheLoadIsReportedAsLocked() {
        val locking = object : RatchetSessionRecords by records {
            @Volatile
            var unlocked = true
            override val isUnlocked: Boolean get() = unlocked

            override fun load(peerUserId: UUID): ByteArray? {
                unlocked = false // the lock lands now: the store reads nothing
                return null
            }
        }
        val crypto = MessageCrypto(locking, tags)
        assertThrows(CryptoError.Locked::class.java) {
            crypto.seal(utf8("x"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        }
        assertEquals(0, records.saveCount)
    }

    @Test
    fun openingWhileLockedReadsByFreshReceiverButSavesNothing() {
        val sealed = aliceSends("first")
        records.deleteAll()
        val saves = records.saveCount
        records.isUnlocked = false
        assertEquals("first", bobReads(sealed)) // iOS behaviour: a locked store reads as "no session"
        assertEquals(saves, records.saveCount)
    }

    /**
     * Chats unlocked, phone locked (an auto-lock delay, the background connection): the session
     * record exists but the WhenUnlocked sealer cannot read it. Sealing must not start a fresh
     * initiator session over it — that forks the ratchet for good — but throw, so the outbox
     * retries after unlock; nothing is saved.
     */
    @Test
    fun aSessionThePhoneCannotReadNowIsNeverReplacedByAFreshOne() {
        assertEquals("A1", bobReads(aliceSends("A1")))
        assertEquals("B1", aliceReads(bobSends("B1")))
        val aliceBefore = records.session(bobUser)
        val saves = records.saveCount
        records.unreadable = true
        assertThrows(CryptoError.Locked::class.java) { aliceSends("A2") }
        assertThrows(CryptoError.Locked::class.java) { bobSends("B2") }
        assertEquals(saves, records.saveCount)
        records.unreadable = false
        assertEquals(aliceBefore, records.session(bobUser))
        // The chain carries on: no fork, both sides still read through the ratchet.
        val a2 = aliceSends("A2")
        assertEquals("A2", String(crypto.open(ratchetOnly(a2), aliceUser, bob.private, bob.public, alice.public, sentAt = Instant.now()), Charsets.UTF_8))
    }

    /** The same state on the receiving side: the peer box opens it, and no ratchet step is saved. */
    @Test
    fun openingWhileTheSessionIsUnreadableUsesThePeerBoxAndSavesNothing() {
        val a1 = aliceSends("A1")
        val a2 = aliceSends("A2")
        assertEquals("A1", bobReads(a1))
        val bobBefore = records.session(aliceUser)
        val saves = records.saveCount
        records.unreadable = true
        assertEquals("A2", bobReads(a2))
        assertEquals(saves, records.saveCount)
        records.unreadable = false
        assertEquals(bobBefore, records.session(aliceUser))
        assertEquals("A2", String(crypto.open(ratchetOnly(a2), aliceUser, bob.private, bob.public, alice.public, sentAt = Instant.now()), Charsets.UTF_8))
    }

    // ---- Android: no state change on a failed seal ----

    @Test
    fun aSealThatFailsSavesNoRatchetStep() {
        // Entropy for the initiator DH and the ratchet nonce only: sealing the self box then fails.
        val crypto = MessageCrypto(records, tags, ScriptedEntropy(bytes(0x33, 32), bytes(0x44, 12)))
        assertThrows(IllegalStateException::class.java) {
            crypto.seal(utf8("x"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        }
        assertNull(records.load(bobUser))
    }

    @Test
    fun keysThatAreNotX25519KeysAreRefusedBeforeAnyStateChanges() {
        assertThrows(CryptoError.InvalidPeerKey::class.java) {
            crypto.seal(utf8("x"), bobUser, bob.public.copyOf(31), alice.private, alice.public, aliceUser)
        }
        assertThrows(CryptoError.InvalidPeerKey::class.java) {
            crypto.seal(utf8("x"), bobUser, bob.public, alice.private.copyOf(33), alice.public, aliceUser)
        }
        assertThrows(CryptoError.InvalidPeerKey::class.java) { crypto.sealV2(utf8("x"), ByteArray(0), alice.private, alice.public) }
        assertEquals(0, records.saveCount)
    }

    @Test
    fun aChangedPeerKeyReplacesTheStoredOneOnTheNextSeal() {
        aliceSends("A1")
        val newBob = TestIdentity.random()
        crypto.seal(utf8("A2"), bobUser, newBob.public, alice.private, alice.public, aliceUser)
        assertArrayEquals(newBob.public, records.session(bobUser)!!.peerIdentityPublic)
    }

    // ---- Android: per-peer monitor (plan §1.4) ----

    /** Removes the `peer` box so a ratchet failure cannot hide behind the identity-box fallback. */
    private fun ratchetOnly(envelope: ByteArray): ByteArray {
        val fields = CryptoJson.parseToJsonElement(String(envelope, Charsets.UTF_8)) as JsonObject
        return utf8(JsonObject(fields - "peer").toString())
    }

    @Test
    fun concurrentOpensForOnePeerNeverLoseARatchetStep() {
        val count = 48
        // Bob reads first, so every later message rides one established receiving chain.
        assertEquals("m0", bobReads(aliceSends("m0")))
        val envelopes = (1..count).map { ratchetOnly(aliceSends("m$it")) }.shuffled()
        val opened = runConcurrently(envelopes.map { env -> Callable { bobReads(env) } })
        assertEquals((1..count).map { "m$it" }.toSet(), opened.toSet())
    }

    @Test
    fun concurrentSealsForOnePeerNeverReuseAChainStep() {
        val count = 48
        assertEquals("m0", bobReads(aliceSends("m0")))
        val envelopes = runConcurrently((1..count).map { i -> Callable { ratchetOnly(aliceSends("m$i")) } })
        val indexes = envelopes.map { (CryptoJson.parseToJsonElement(String(it, Charsets.UTF_8)) as JsonObject)["n"].toString().toInt() }
        assertEquals((1..count).toSet(), indexes.toSet())
        val opened = envelopes.shuffled().map { bobReads(it) }
        assertEquals((1..count).map { "m$it" }.toSet(), opened.toSet())
    }

    private fun <T> runConcurrently(tasks: List<Callable<T>>): List<T> {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = tasks.map { task -> pool.submit(Callable { start.await(); task.call() }) }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}

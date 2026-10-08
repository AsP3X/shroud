package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Sender tags on identity boxes (iOS `ios/shroudTests/SenderTagTests.swift`, web
 * `web/src/crypto/boxAuth.selftest.ts`; crypto spec §3, §16.1). A box built from the two public
 * keys alone — what the server can do — never opens: every identity box without a tag (v1, v2,
 * the v3 peer-box fallback, self boxes) is refused, whoever "sent" it and whenever.
 *
 * Every case has fresh keys and a fresh in-memory ratchet store (JUnit builds one instance per
 * test), as iOS gives every case fresh user ids. Test names match `SenderTagTests.swift`.
 */
class SenderTagTest {
    private val records = InMemoryRatchetSessionRecords()
    private val crypto = MessageCrypto(records)
    private val aliceUser: UUID
    private val bobUser: UUID

    init {
        val (lower, higher) = CryptoFixtures.sortedUserIds()
        aliceUser = lower
        bobUser = higher
    }

    private val alice = TestIdentity.random()
    private val bob = TestIdentity.random()
    private val mallory = TestIdentity.random()

    // ---- helpers ----

    /** An untagged box as builds before the tag sealed it — and as anyone with the public keys can. */
    private fun forgedBox(text: String, sender: ByteArray, recipient: ByteArray): SealedBox {
        val ephemeral = TestIdentity.random()
        val key = Primitives.hkdf(
            Primitives.x25519(ephemeral.private, recipient),
            utf8("shroud-v1"),
            utf8("shroud-msg-v1") + ephemeral.public + sender + recipient,
            32,
        )
        val combined = Primitives.aesGcmSeal(key, SystemEntropy.bytes(12), utf8(text))
        return SealedBox(ek = B64.encode(ephemeral.public), ct = B64.encode(combined))
    }

    private fun v2(peer: SealedBox? = null, selfBox: SealedBox? = null): ByteArray =
        utf8(CryptoJson.encodeToString(SealedEnvelope.serializer(), SealedEnvelope(v = 2, peer = peer, selfBox = selfBox)))

    /** v3 with a ratchet body no session can read, so the peer-box fallback runs. */
    private fun junkV3(peer: SealedBox? = null, selfBox: SealedBox? = null): ByteArray = utf8(
        CryptoJson.encodeToString(
            RatchetEnvelope.serializer(),
            RatchetEnvelope(
                v = 3,
                dh = B64.encode(TestIdentity.random().public),
                n = 0,
                pn = 0,
                ct = B64.encode(bytes(7, 40)),
                peer = peer,
                selfBox = selfBox,
            ),
        ),
    )

    /** v2 "from Alice", tagged with [signer]'s private key. */
    private fun aliceV2(text: String, signer: TestIdentity = alice): ByteArray =
        crypto.sealV2(utf8(text), bob.public, signer.private, alice.public)

    private fun decodeV2(envelope: ByteArray): SealedEnvelope =
        CryptoJson.decodeFromString(SealedEnvelope.serializer(), String(envelope, Charsets.UTF_8))

    private fun bobOpens(envelope: ByteArray): String =
        String(crypto.open(envelope, aliceUser, bob.private, bob.public, alice.public, OpenAs.Recipient), Charsets.UTF_8)

    private fun aliceOpensOwn(envelope: ByteArray): String =
        String(crypto.open(envelope, bobUser, alice.private, alice.public, alice.public, OpenAs.Sender), Charsets.UTF_8)

    // ---- box level ----

    @Test
    fun sealedBoxesCarryATagThatOpens() {
        val sealed = aliceV2("hi")
        val envelope = decodeV2(sealed)
        assertNotNull(envelope.peer?.t)
        assertNotNull(envelope.selfBox?.t)
        assertEquals("hi", bobOpens(sealed))
    }

    /** Mallory seals "from Alice" with her own key; the static ECDH does not match. */
    @Test
    fun tagFromAKeyThatIsNotTheSendersIsRefused() {
        val forged = aliceV2("forged", signer = mallory)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged) }
    }

    @Test
    fun tagMovedOntoOtherCiphertextIsRefused() {
        val real = decodeV2(aliceV2("real"))
        val other = decodeV2(aliceV2("other"))
        val moved = other.peer!!.copy(t = real.peer!!.t)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(v2(peer = moved)) }
    }

    /** Bob's own self box, handed back to Bob as if Alice had sent it. */
    @Test
    fun reflectedBoxIsRefused() {
        val bobsOwn = crypto.sealV2(utf8("mine"), alice.public, bob.private, bob.public)
        val selfBox = decodeV2(bobsOwn).selfBox!!
        assertThrows(Exception::class.java) { bobOpens(v2(peer = selfBox)) }
    }

    /** Sealed by `web/src/crypto/sealedBox.ts` with fixed keys; both clients derive one tag. */
    @Test
    fun webSealedTagVerifiesHere() {
        val alice = TestIdentity.filled(0x11)
        val bob = TestIdentity.filled(0x22)
        var box = SealedBox(
            ek = "S6UOubR4gmyGJzC+XuOl+S0Y3VykkvhJXJ32/m3JWCw=",
            ct = "s67IM+reT2lNl2zRNUPKbSdmtPpU5BX5o/cOH0WTaaHS23Hi",
            t = "iFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM=",
        )
        fun open(): ByteArray = crypto.openLegacy(v2(peer = box), bob.private, bob.public, alice.public, OpenAs.Recipient)
        assertEquals("from web", String(open(), Charsets.UTF_8))
        box = box.copy(t = "jFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM=")
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open() }
    }

    // ---- untagged boxes are refused; tagged boxes and ratchet reads open ----

    /** v1 never carried a tag: even a genuine one is refused, anyone with the public keys could build it. */
    @Test
    fun untaggedV1IsRefused() {
        val box = forgedBox("v1", alice.public, bob.public)
        val v1 = utf8(CryptoJson.encodeToString(SealedEnvelope.serializer(), SealedEnvelope(v = 1, ek = box.ek, ct = box.ct)))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(v1) }
    }

    /** No tagged message from the sender is needed first: there is no transition window any more. */
    @Test
    fun untaggedV2PeerBoxIsRefused() {
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged) }
    }

    /** A ratchet body nobody can read sends the open to the peer box, which must still carry a tag. */
    @Test
    fun untaggedV3PeerBoxFallbackIsRefused() {
        val forged = junkV3(peer = forgedBox("forged v3", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged) }
    }

    /** "Sent by me" cannot be forged onto our other devices either, in v2 or v3. */
    @Test
    fun untaggedSelfBoxIsRefused() {
        val ownBox = forgedBox("fake own", alice.public, alice.public)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { aliceOpensOwn(v2(selfBox = ownBox)) }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { aliceOpensOwn(junkV3(selfBox = ownBox)) }
    }

    /** Tagged boxes from the same sender change nothing for an untagged one (no watermark to learn). */
    @Test
    fun untaggedBoxStaysRefusedNextToTaggedOnes() {
        assertEquals("real", bobOpens(aliceV2("real")))
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged) }
        assertEquals("real again", bobOpens(aliceV2("real again")))
    }

    @Test
    fun ownTaggedSelfBoxOpens() {
        assertEquals("own", aliceOpensOwn(aliceV2("own")))
        val v3 = crypto.seal(utf8("own v3"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertEquals(3, CryptoFixtures.version(v3))
        assertEquals("own v3", aliceOpensOwn(v3))
    }

    /** The ratchet body authenticates its sender itself: a ratchet read opens as before. */
    @Test
    fun ratchetReadStillOpens() {
        val first = crypto.seal(utf8("by ratchet"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertEquals(3, CryptoFixtures.version(first))
        assertEquals("by ratchet", bobOpens(first))
        val second = crypto.seal(utf8("and again"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertEquals("and again", bobOpens(second))
    }

    @Test
    fun siblingWithoutRatchetStateReadsTheTaggedPeerBox() {
        val tagged = decodeV2(aliceV2("to sibling")).peer!!
        assertEquals("to sibling", bobOpens(junkV3(tagged)))

        val malloryBox = decodeV2(aliceV2("m", signer = mallory)).peer!!
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(junkV3(malloryBox)) }
    }

    // ---- box level and the v3 fallback ----

    /**
     * [IdentityBoxes] itself: the untagged copy of a tagged box is not reported as tagged and does
     * not open; a tag from another key, a moved tag, a reflected box and a tag that is not strict
     * Base64 are refused, by [IdentityBoxes.verifyTag] and by [IdentityBoxes.open].
     */
    @Test
    fun boxLevelTagBindsTheSenderAndTheBytes() {
        val box = IdentityBoxes.seal(utf8("hi"), alice.private, alice.public, bob.public, SystemEntropy)
        assertTrue(IdentityBoxes.verifyTag(box, bob.private, alice.public, bob.public))
        assertEquals("hi", String(IdentityBoxes.open(box, bob.private, alice.public, bob.public), Charsets.UTF_8))
        val untagged = box.copy(t = null)
        assertFalse(IdentityBoxes.verifyTag(untagged, bob.private, alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.open(untagged, bob.private, alice.public, bob.public) }

        val byMallory = IdentityBoxes.seal(utf8("hi"), mallory.private, alice.public, bob.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.verifyTag(byMallory, bob.private, alice.public, bob.public) }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.open(byMallory, bob.private, alice.public, bob.public) }
        val other = IdentityBoxes.seal(utf8("other"), alice.private, alice.public, bob.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            IdentityBoxes.verifyTag(other.copy(t = box.t), bob.private, alice.public, bob.public)
        }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.open(other.copy(t = box.t), bob.private, alice.public, bob.public) }
        val bobToAlice = IdentityBoxes.seal(utf8("mine"), bob.private, bob.public, alice.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.verifyTag(bobToAlice, bob.private, alice.public, bob.public) }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.open(bobToAlice, bob.private, alice.public, bob.public) }
        // A tag that is not strict Base64 is refused, never read as "untagged".
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            IdentityBoxes.verifyTag(box.copy(t = box.t!!.trimEnd('=')), bob.private, alice.public, bob.public)
        }
    }

    /** A real v3 whose ratchet body is broken is read by its tagged peer box, and only while it is tagged. */
    @Test
    fun aRealV3WithABrokenRatchetBodyOpensByItsPeerBox() {
        val real = crypto.seal(utf8("to sibling"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        val fields = CryptoJson.parseToJsonElement(String(real, Charsets.UTF_8)) as JsonObject
        val drBroken = utf8(JsonObject(fields + ("ct" to kotlinx.serialization.json.JsonPrimitive(B64.encode(SystemEntropy.bytes(40))))).toString())
        assertEquals("to sibling", bobOpens(drBroken))
        // The same envelope with its peer box stripped of the tag is refused.
        val peer = (fields["peer"] as JsonObject) - "t"
        val untagged = utf8(JsonObject(fields + ("ct" to kotlinx.serialization.json.JsonPrimitive(B64.encode(SystemEntropy.bytes(40)))) + ("peer" to JsonObject(peer))).toString())
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(untagged) }
    }

    /** Untagged boxes from a new identity key are refused as well: no sender gets a first-contact pass. */
    @Test
    fun untaggedBoxFromANewIdentityKeyIsRefused() {
        val newAlice = TestIdentity.random()
        val forged = v2(peer = forgedBox("new phrase", newAlice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            crypto.open(forged, aliceUser, bob.private, bob.public, newAlice.public, OpenAs.Recipient)
        }
    }
}

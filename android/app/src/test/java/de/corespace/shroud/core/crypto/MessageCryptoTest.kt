package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * v1/v2 envelopes (iOS `ios/shroudTests/MessageCryptoTests.swift`) and the deterministic v2/v3
 * vectors of crypto spec §16.3, printed by `gen_message_crypto_vectors.mjs` (Node/OpenSSL) next to
 * this file. `alicePriv = 0x11 × 32`, `bobPriv = 0x22 × 32`.
 */
class MessageCryptoTest {
    private val records = InMemoryRatchetSessionRecords()
    private val crypto = MessageCrypto(records)

    private fun json(bytes: ByteArray) = CryptoJson.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject
    private fun json(text: String) = CryptoJson.parseToJsonElement(text) as JsonObject

    // ---- iOS MessageCryptoTests ----

    /** `MessageCryptoTests.swift:13-35`. */
    @Test
    fun sealAndOpenAsRecipient() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val sealed = crypto.sealV2(utf8("hello shroud"), bob.public, alice.private, alice.public)
        val opened = crypto.openLegacy(sealed, bob.private, bob.public, alice.public, OpenAs.Recipient)
        assertEquals("hello shroud", String(opened, Charsets.UTF_8))
    }

    /** `MessageCryptoTests.swift:37-60`: Alice's other device decrypts the self box. */
    @Test
    fun sealAndOpenAsSenderWithoutLocalCache() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val sealed = crypto.sealV2(utf8("multi-device history"), bob.public, alice.private, alice.public)
        val opened = crypto.openLegacy(sealed, alice.private, alice.public, alice.public, OpenAs.Sender)
        assertEquals("multi-device history", String(opened, Charsets.UTF_8))
    }

    /** `MessageCryptoTests.swift:62-84`. */
    @Test
    fun wrongRecipientCannotOpen() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val eve = TestIdentity.random()
        val sealed = crypto.sealV2(utf8("secret"), bob.public, alice.private, alice.public)
        assertThrows(Exception::class.java) { crypto.openLegacy(sealed, eve.private, eve.public, alice.public, OpenAs.Recipient) }
    }

    /**
     * `MessageCryptoTests.swift:86-125`: a hand-built v1 envelope (peer only, untagged). v1 never
     * carried a sender tag, so even a genuine one is refused: anyone holding the two public keys
     * could have built it.
     */
    @Test
    fun legacyV1EnvelopeIsRefused() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val ephemeral = TestIdentity.random()
        val key = Primitives.hkdf(
            Primitives.x25519(ephemeral.private, bob.public),
            utf8("shroud-v1"),
            utf8("shroud-msg-v1") + ephemeral.public + alice.public + bob.public,
            32,
        )
        val combined = Primitives.aesGcmSeal(key, SystemEntropy.bytes(12), utf8("legacy"))
        val envelope = utf8(CryptoJson.encodeToString(SealedEnvelope.serializer(), SealedEnvelope(v = 1, ek = B64.encode(ephemeral.public), ct = B64.encode(combined))))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            crypto.openLegacy(envelope, bob.private, bob.public, alice.public, OpenAs.Recipient)
        }
    }

    // ---- golden vectors (crypto spec §16.3) ----

    private val alice = TestIdentity.filled(0x11)
    private val bob = TestIdentity.filled(0x22)

    @Test
    fun theFixedKeysMatchTheSharedVectors() {
        assertEquals("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13", alice.public.hex())
        assertEquals("e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=", B64.encode(alice.public))
        assertEquals("0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20", bob.public.hex())
        assertEquals("D6poTtKIZ7l/Smot7l34zpdOdrcBjj8iocTPJnhXDyA=", B64.encode(bob.public))
        assertEquals("9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056", Primitives.x25519(alice.private, bob.public).hex())
    }

    /** `MessageCrypto.swift:92-118`: peer box (eph 0x77, nonce 0x88), then self box (eph 0x99, nonce 0xaa). */
    @Test
    fun deterministicV2EnvelopeMatchesTheVector() {
        val entropy = ScriptedEntropy(bytes(0x77, 32), bytes(0x88, 12), bytes(0x99, 32), bytes(0xaa, 12))
        val sealed = MessageCrypto(records, entropy).sealV2(utf8("hello shroud"), bob.public, alice.private, alice.public)
        assertEquals(0, entropy.remaining)
        val fields = json(sealed)
        assertEquals(setOf("v", "peer", "self"), fields.keys) // nil ek/ct left out
        assertEquals(JsonPrimitive(2), fields["v"])
        assertEquals(
            json("""{"ek":"HPV5q6RaELodHvBtkfyiqp7QoRUFFWUxVUBdCxjLmmc=","ct":"iIiIiIiIiIiIiIiIcKwkFpNvVj6lW6v0N+EOrNsZc3qTtbwE/BG9Lw==","t":"Pv0PHDT0BL14qXks9TnzxFOVjaF2Q5fsCec3R3555PI="}"""),
            fields["peer"],
        )
        assertEquals(
            json("""{"ek":"uhk4Ns/x9OhmwTlxXTBkCNJqdvdtY4o5r8EAEITSVBE=","ct":"qqqqqqqqqqqqqqqqOBqBgwu8Ps/+0hOzN5McW+rSlWJ3DNPBnjOrbQ==","t":"av2pPfLYZOn2ZE8PvEd5ZBIxA1M2fLW34yXqAnZtepk="}"""),
            fields["self"],
        )
        assertEquals("hello shroud", String(crypto.openLegacy(sealed, bob.private, bob.public, alice.public, OpenAs.Recipient), Charsets.UTF_8))
        assertEquals("hello shroud", String(crypto.openLegacy(sealed, alice.private, alice.public, alice.public, OpenAs.Sender), Charsets.UTF_8))
    }

    /**
     * `MessageCrypto.swift:131-199`, Alice (the lower id) with no session: initiator DH 0x33, ratchet
     * nonce 0x44, then the self box (0x99/0xaa), then the peer box (0x77/0x88).
     */
    @Test
    fun deterministicV3EnvelopeMatchesTheVector() {
        val aliceUser = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val bobUser = UUID.fromString("00000000-0000-4000-8000-00000000000b")
        val entropy = ScriptedEntropy(bytes(0x33, 32), bytes(0x44, 12), bytes(0x99, 32), bytes(0xaa, 12), bytes(0x77, 32), bytes(0x88, 12))
        val sealed = MessageCrypto(records, entropy).seal(utf8("hello"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertEquals(0, entropy.remaining)
        assertEquals(
            json(
                """{"v":3,"dh":"ew1H2TQn+DERYHgcfHM/2J+IlwrvSQ2KoO4ZpMuKGxQ=","n":0,"pn":0,"ct":"RERERERERERERERELHwgS7O7x3z52s5kZ9Wq8A83ckIj",""" +
                    """"peer":{"ek":"HPV5q6RaELodHvBtkfyiqp7QoRUFFWUxVUBdCxjLmmc=","ct":"iIiIiIiIiIiIiIiIcKwkFpNhM82acXFYq4xAf4JQLHcP","t":"3D1HaXYysRf+JZbKiKtRhUW2pkCxhlum3+OJhFAorbg="},""" +
                    """"self":{"ek":"uhk4Ns/x9OhmwTlxXTBkCNJqdvdtY4o5r8EAEITSVBE=","ct":"qqqqqqqqqqqqqqqqOBqBgws9JAyEtlDhZYw6CGEeVI4+","t":"T283Npt4Gp9j7T4q9Aes+swh+7jehsGH5VZdbjbahw8="}}""",
            ),
            json(sealed),
        )
        // The saved session is the trace's Alice after one send.
        val session = records.session(bobUser)!!
        assertEquals("3e6fed5fdfeb9d7ac6661851b235c9e9f6af85a9fd45e99d408c94cf13ab939e", session.sendChainKey!!.hex())
        assertEquals(1L, session.sendN)
        // Bob reads it by ratchet; Alice reads her own copy from the self box.
        val opened = MessageCrypto(InMemoryRatchetSessionRecords()).open(sealed, aliceUser, bob.private, bob.public, alice.public, OpenAs.Recipient)
        assertEquals("hello", String(opened, Charsets.UTF_8))
        assertEquals("hello", String(crypto.open(sealed, bobUser, alice.private, alice.public, alice.public, OpenAs.Sender), Charsets.UTF_8))
    }

    // ---- wire form and malformed input ----

    @Test
    fun wireFormIsStrictStandardBase64() {
        val sealed = crypto.sealV2(utf8("x"), bob.public, alice.private, alice.public)
        val wire = MessageCrypto.toWire(sealed)
        assertTrue(MessageCrypto.fromWire(wire)!!.contentEquals(sealed))
        assertNull(MessageCrypto.fromWire(wire.trimEnd('=') + "A"))
        assertNull(MessageCrypto.fromWire("eyJ2IjoyfQ")) // unpadded
        assertNull(MessageCrypto.fromWire(" " + wire))
        assertNull(MessageCrypto.fromWire(wire.replace('+', '-').replace('/', '_').let { if (it == wire) "$it!" else it }))
    }

    @Test
    fun malformedEnvelopesFailClosed() {
        fun open(text: String, role: OpenAs = OpenAs.Recipient) =
            crypto.open(utf8(text), UUID.randomUUID(), bob.private, bob.public, alice.public, role)
        val v2 = String(crypto.sealV2(utf8("x"), bob.public, alice.private, alice.public), Charsets.UTF_8)

        assertThrows(CryptoError.OpenFailed::class.java) { open("not json") }
        assertThrows(CryptoError.OpenFailed::class.java) { open("""{"peer":{}}""") }
        assertThrows(CryptoError.OpenFailed::class.java) { open("""{"v":"two"}""") }
        assertThrows(CryptoError.UnsupportedVersion::class.java) { open("""{"v":9}""") }
        assertThrows(CryptoError.OpenFailed::class.java) { open("""{"v":2}""") }
        // v1 never carried a tag: refused whatever it holds, in either role.
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open("""{"v":1}""") }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open("""{"v":1,"ek":"a","ct":"b"}""", OpenAs.Sender) }
        // A v3 counter outside UInt32 does not decode.
        assertThrows(CryptoError.OpenFailed::class.java) { open("""{"v":3,"dh":"a","n":4294967296,"pn":0,"ct":"b"}""") }
        // v3 as recipient needs the ratchet overload.
        assertThrows(CryptoError.UnsupportedVersion::class.java) {
            crypto.openLegacy(utf8("""{"v":3,"dh":"a","n":0,"pn":0,"ct":"b"}"""), bob.private, bob.public, alice.public, OpenAs.Recipient)
        }
        // A v3 envelope for our own devices needs its self box.
        assertThrows(CryptoError.OpenFailed::class.java) { open("""{"v":3,"dh":"a","n":0,"pn":0,"ct":"b"}""", OpenAs.Sender) }
        // A v3 envelope with a broken ratchet body and no peer box has nothing to fall back to.
        assertThrows(Exception::class.java) { open("""{"v":3,"dh":"${B64.encode(bob.public)}","n":0,"pn":0,"ct":"${B64.encode(ByteArray(40))}"}""") }
        // A truncated box ciphertext or a short ek.
        val fields = json(v2)
        val peer = fields["peer"] as JsonObject
        assertThrows(CryptoError::class.java) {
            open(JsonObject(fields + ("peer" to JsonObject(peer + ("ct" to JsonPrimitive("AAAA"))))).toString())
        }
        assertThrows(CryptoError::class.java) {
            open(JsonObject(fields + ("peer" to JsonObject(peer - "t" + ("ek" to JsonPrimitive(B64.encode(ByteArray(31))))))).toString())
        }
    }

    @Test
    fun envelopesNeverCarryNulls() {
        val sealed = String(crypto.sealV2(utf8("x"), bob.public, alice.private, alice.public), Charsets.UTF_8)
        assertFalse(sealed.contains("null"))
        val v3 = String(crypto.seal(utf8("x"), UUID.fromString("ffffffff-0000-4000-8000-000000000000"), bob.public, alice.private, alice.public, UUID.fromString("00000000-0000-4000-8000-000000000000")), Charsets.UTF_8)
        assertEquals(3, CryptoFixtures.version(utf8(v3)))
        assertFalse(v3.contains("null"))
    }
}

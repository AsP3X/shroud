package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.calls.crypto.CallCryptoException
import de.corespace.shroud.core.calls.crypto.CallSignalKeys
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.crypto.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * The call-signal wire format against the vector in `docs/calls.md` — iOS `CallCryptoTests`
 * (`ios/shroudTests/CallCryptoTests.swift`, all 7) and the web's `crypto.selftest.ts` (the same
 * bytes). Vectors copied verbatim.
 */
class CallCryptoTest {
    private val alicePrivate = "ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d"
    private val alicePublic = "8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467"
    private val bobPrivate = "58b2859744734402b8fa838480195ffd0c8cfbda7bbe22a710a7d4d8b79b1afd"
    private val bobPublic = "389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338"
    private val callId = UUID.fromString("0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b")
    private val expectedSecret = "39ea5a3a4128617d799fab4480c1bff13f92bef72ccb0bbc246da1504ba3839f"
    private val expectedCallerKey = "3a3d8f2724fe45e6af1af14b490fd1a4e43aacb8d8e13a9009eb677c79bebb30"
    private val expectedCalleeKey = "46ac38a6ec923643613298cd51555c02f62c163bf191a62f200740d3a0cfb099"
    private val expectedPayload =
        "c1.AAECAwQFBgcICQoLQDFMfgG6gj47QCtynr5cS3IjS6czaNhnseRvgliRTrXZeUi+lMfrLwnqwjds+cTC3HtQ"

    /** `#"{"t":"offer","sdp":"v=0\r\n","n":1}"#` — `\r\n` is the two-character JSON escape. */
    private val vectorPlaintext = """{"t":"offer","sdp":"v=0\r\n","n":1}"""

    private fun secrets(): Pair<ByteArray, ByteArray> {
        val alice = CallCrypto.callSecret(hexToBytes(alicePrivate), hexToBytes(alicePublic), hexToBytes(bobPublic))
        val bob = CallCrypto.callSecret(hexToBytes(bobPrivate), hexToBytes(bobPublic), hexToBytes(alicePublic))
        return alice to bob
    }

    private fun assertOpenFails(expected: CallCryptoException, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: CallCryptoException) {
            assertSame(expected, e)
        }
    }

    @Test
    fun publicKeysMatchTheVector() {
        assertEquals(alicePublic, Primitives.x25519Public(hexToBytes(alicePrivate)).hex())
        assertEquals(bobPublic, Primitives.x25519Public(hexToBytes(bobPrivate)).hex())
        // web crypto.selftest.ts: "x25519 shared".
        assertEquals(
            "8e190e7874f1b7804fc6c5e9e650e0a2481638c33a1053d9a1d5a78b19d6a12a",
            Primitives.x25519(hexToBytes(alicePrivate), hexToBytes(bobPublic)).hex(),
        )
    }

    @Test
    fun bothSidesDeriveTheVectorsSecret() {
        val (alice, bob) = secrets()
        assertEquals(expectedSecret, alice.hex())
        assertEquals(expectedSecret, bob.hex())
        // From the shared secret alone (the production path through IdentityKeyMaterial.agreement).
        val shared = Primitives.x25519(hexToBytes(alicePrivate), hexToBytes(bobPublic))
        assertEquals(expectedSecret, CallCrypto.callSecretFromShared(shared, hexToBytes(alicePublic), hexToBytes(bobPublic)).hex())
    }

    @Test
    fun directionKeysMatchTheVector() {
        val secret = secrets().first
        assertEquals(expectedCallerKey, CallCrypto.signalKey(secret, callId, CallCrypto.Role.Caller).hex())
        assertEquals(expectedCalleeKey, CallCrypto.signalKey(secret, callId, CallCrypto.Role.Callee).hex())
        // The salt is the id's 16 bytes in RFC 4122 order (CallCrypto.swift:136-138), whatever its case.
        assertEquals("0190a3b41c2d7e8f9a0b1c2d3e4f5a6b", CallCrypto.uuidBytes(callId).hex())
        val upper = UUID.fromString("0190A3B4-1C2D-7E8F-9A0B-1C2D3E4F5A6B")
        assertEquals(expectedCallerKey, CallCrypto.signalKey(secret, upper, CallCrypto.Role.Caller).hex())
    }

    @Test
    fun sealingWithTheVectorsNonceGivesItsPayload() {
        val keys = CallSignalKeys.identity(secrets().first, callId, CallCrypto.Role.Caller)
        val nonce = ByteArray(12) { it.toByte() }
        val payload = CallCrypto.seal(vectorPlaintext.toByteArray(), keys.send, callId, "sdp_offer", nonce = nonce)
        assertEquals(expectedPayload, payload)
        assertEquals(
            "shroud-call-v1|0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b|sdp_offer",
            String(CallCrypto.aad(callId, "sdp_offer")),
        )
        // A real signal draws a fresh nonce: two seals differ.
        val one = CallCrypto.seal(vectorPlaintext.toByteArray(), keys.send, callId, "sdp_offer")
        val two = CallCrypto.seal(vectorPlaintext.toByteArray(), keys.send, callId, "sdp_offer")
        assertTrue(one.startsWith("c1."))
        assertNotEquals(one, two)
    }

    @Test
    fun forwardSecretMatchesTheVector() {
        val identity = secrets().first
        val aliceEph = ByteArray(32) { 0x20 }
        val bobEph = ByteArray(32) { 0x30 }
        val alicePub = Primitives.x25519Public(aliceEph)
        val bobPub = Primitives.x25519Public(bobEph)
        val fromAlice = CallCrypto.forwardSecret(identity, aliceEph, alicePub, bobPub, callId)
        val fromBob = CallCrypto.forwardSecret(identity, bobEph, bobPub, alicePub, callId)
        assertEquals("7d4c5c4a5c2a2d1bc6979d871db81e8642b840c0f0b816378ac62bcf8e4112d6", fromAlice.hex())
        assertEquals(fromAlice.hex(), fromBob.hex())
        assertEquals(
            "4f366306d6ee25ffa435b12c0cfb2bf937b625c7f056171c0a1386d2163b98f9",
            CallCrypto.forwardSignalKey(fromAlice, callId, CallCrypto.Role.Caller).hex(),
        )
        assertEquals(
            "e8b850838219ef3c085075166994a08e4d6a9a5c4edc16c867afe15f2c0f2873",
            CallCrypto.forwardSignalKey(fromAlice, callId, CallCrypto.Role.Callee).hex(),
        )
        val caller = CallSignalKeys.forward(fromAlice, callId, CallCrypto.Role.Caller)
        val callee = CallSignalKeys.forward(fromBob, callId, CallCrypto.Role.Callee)
        val sealed = CallCrypto.seal("ice".toByteArray(), caller.send, callId, "ice_candidate")
        assertArrayEquals("ice".toByteArray(), CallCrypto.open(sealed, callee.receive, callId, "ice_candidate"))
        // The identity key cannot open a per-call signal.
        val identityKeys = CallSignalKeys.identity(identity, callId, CallCrypto.Role.Callee)
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(sealed, identityKeys.receive, callId, "ice_candidate") }
    }

    @Test
    fun theCalleeOpensWhatTheCallerSealed() {
        val (alice, bob) = secrets()
        val caller = CallSignalKeys.identity(alice, callId, CallCrypto.Role.Caller)
        val callee = CallSignalKeys.identity(bob, callId, CallCrypto.Role.Callee)
        val opened = CallCrypto.open(expectedPayload, callee.receive, callId, "sdp_offer")
        assertEquals(vectorPlaintext, String(opened))

        val back = CallCrypto.seal("answer".toByteArray(), callee.send, callId, "sdp_answer")
        assertArrayEquals("answer".toByteArray(), CallCrypto.open(back, caller.receive, callId, "sdp_answer"))
    }

    @Test
    fun reflectedRelabelledOrTamperedSignalsDoNotOpen() {
        val (alice, _) = secrets()
        val caller = CallSignalKeys.identity(alice, callId, CallCrypto.Role.Caller)
        // Sent back to its sender as if from the other side.
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(expectedPayload, caller.receive, callId, "sdp_offer") }
        val callee = CallSignalKeys.identity(alice, callId, CallCrypto.Role.Callee)
        // Relabelled by the relay.
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(expectedPayload, callee.receive, callId, "sdp_answer") }
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(expectedPayload, callee.receive, callId, "media_state") }
        // Moved to another call.
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(expectedPayload, callee.receive, UUID.randomUUID(), "sdp_offer") }
        val bytes = B64.decodeStrict(expectedPayload.substring(3))!!
        // A flipped bit (iOS: byte 20; web: bytes 0, 12 and the last).
        for (index in listOf(20, 0, 12, bytes.size - 1)) {
            val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertOpenFails(CallCryptoException.OpenFailed) {
                CallCrypto.open("c1." + B64.encode(tampered), callee.receive, callId, "sdp_offer")
            }
        }
        assertOpenFails(CallCryptoException.BadPayload) { CallCrypto.open("v=0", callee.receive, callId, "sdp_offer") }
        // web: shorter than nonce and tag, another version, not base64, empty.
        assertOpenFails(CallCryptoException.BadPayload) {
            CallCrypto.open("c1." + B64.encode(bytes.copyOf(27)), callee.receive, callId, "sdp_offer")
        }
        assertOpenFails(CallCryptoException.BadPayload) { CallCrypto.open(expectedPayload.replace("c1.", "c2."), callee.receive, callId, "sdp_offer") }
        assertOpenFails(CallCryptoException.BadPayload) { CallCrypto.open("c1.%%%", callee.receive, callId, "sdp_offer") }
        assertOpenFails(CallCryptoException.BadPayload) { CallCrypto.open("", callee.receive, callId, "sdp_offer") }
        // Unpadded base64 is refused, as `Data(base64Encoded:)` refuses it.
        val padded = CallCrypto.seal("ice".toByteArray(), caller.send, callId, "ice_candidate")
        assertTrue(padded.endsWith("="))
        assertArrayEquals("ice".toByteArray(), CallCrypto.open(padded, callee.receive, callId, "ice_candidate"))
        assertOpenFails(CallCryptoException.BadPayload) { CallCrypto.open(padded.trimEnd('='), callee.receive, callId, "ice_candidate") }
        // Someone with only the public keys (the server) derives some other secret.
        val mallory = ByteArray(32) { 0x11 }
        val mallorySecret = CallCrypto.callSecret(mallory, Primitives.x25519Public(mallory), hexToBytes(bobPublic))
        val forged = CallCrypto.seal(
            vectorPlaintext.toByteArray(),
            CallSignalKeys.identity(mallorySecret, callId, CallCrypto.Role.Caller).send,
            callId,
            "sdp_offer",
            nonce = ByteArray(12) { it.toByte() },
        )
        val (_, bob) = secrets()
        val bobKeys = CallSignalKeys.identity(bob, callId, CallCrypto.Role.Callee)
        assertOpenFails(CallCryptoException.OpenFailed) { CallCrypto.open(forged, bobKeys.receive, callId, "sdp_offer") }
    }
}

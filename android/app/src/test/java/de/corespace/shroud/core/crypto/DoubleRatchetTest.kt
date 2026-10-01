package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Double Ratchet itself (iOS `ios/shroud/Services/Crypto/DoubleRatchet.swift`; crypto spec §5).
 * The first three tests are iOS `ios/shroudTests/DoubleRatchetTests.swift:33-93` (also the web
 * `ratchet.selftest.ts` round trip); the trace pins every key of crypto spec §16.3 with
 * `gen_message_crypto_vectors.mjs` (Node/OpenSSL) next to this file. The MessageCrypto-level cases
 * of `DoubleRatchetTests.swift` are in [MessageCryptoRatchetTest].
 */
class DoubleRatchetTest {
    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    // ---- iOS DoubleRatchetTests ----

    /** `DoubleRatchetTests.swift:33-46`. */
    @Test
    fun rootSeedIsSymmetric() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        assertArrayEquals(DoubleRatchet.rootSeed(alice.private, bob.public), DoubleRatchet.rootSeed(bob.private, alice.public))
    }

    /** `DoubleRatchetTests.swift:48-73` (web `ratchet.selftest.ts:37-53`). */
    @Test
    fun ratchetRoundTripAliceBob() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)

        val env1 = DoubleRatchet.encrypt(utf8("hello from alice"), aliceSession).toJson()
        assertEquals("hello from alice", text(DoubleRatchet.decrypt(env1, bobSession)))
        val env2 = DoubleRatchet.encrypt(utf8("reply from bob"), bobSession).toJson()
        assertEquals("reply from bob", text(DoubleRatchet.decrypt(env2, aliceSession)))
        val env3 = DoubleRatchet.encrypt(utf8("alice again"), aliceSession).toJson()
        assertEquals("alice again", text(DoubleRatchet.decrypt(env3, bobSession)))
    }

    /** `DoubleRatchetTests.swift:75-93`. */
    @Test
    fun multiMessageSameChain() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        for (i in 0 until 5) {
            val env = DoubleRatchet.encrypt(utf8("msg-$i"), aliceSession)
            assertEquals("msg-$i", text(DoubleRatchet.decrypt(env, bobSession)))
        }
    }

    // ---- golden trace (crypto spec §16.3) ----

    @Test
    fun traceMatchesTheGoldenVectors() {
        val alice = TestIdentity.filled(0x11)
        val bob = TestIdentity.filled(0x22)
        assertEquals("c6d8d23890df3d1d4e70d97a22e8033a373fafaf701fd7a3c391ca3ed1b1921d", DoubleRatchet.rootSeed(alice.private, bob.public).hex())

        // Alice initiates with DH private 0x33 and sends with nonce 0x44.
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public, ScriptedEntropy(bytes(0x33, 32)))
        assertEquals("a18e4f3c0542e26236ad0a16c5a74bc7d5242e5f084b539981f02367f57a1ec4", aliceSession.rootKey.hex())
        assertEquals("9fb20f39a1159865ec2e1b89d81ca6765badf946a45867003eed3cbf0d496326", aliceSession.sendChainKey!!.hex())
        assertArrayEquals(bob.public, aliceSession.dhRecvPublic)
        assertTrue(aliceSession.touched)
        val m1 = DoubleRatchet.encrypt(utf8("hello from alice"), aliceSession, ScriptedEntropy(bytes(0x44, 12)))
        assertEquals(
            DoubleRatchet.Message(3, "ew1H2TQn+DERYHgcfHM/2J+IlwrvSQ2KoO4ZpMuKGxQ=", 0, 0, "RERERERERERERERELHwgS7NXdVY3zOMU9dWJRqVMvU6h7REczRcz5bIbPD4="),
            m1,
        )
        assertEquals("3e6fed5fdfeb9d7ac6661851b235c9e9f6af85a9fd45e99d408c94cf13ab939e", aliceSession.sendChainKey!!.hex())
        assertEquals(1L, aliceSession.sendN)

        // Bob receives it as a fresh receiver; his DH ratchet draws 0x55. Reply with nonce 0x66.
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        assertEquals("hello from alice", text(DoubleRatchet.decrypt(m1, bobSession, ScriptedEntropy(bytes(0x55, 32)))))
        assertEquals("6018fa2a6630c3ef1f58447485aa9333dce7d0c672dcc1c79b4b5c21b14ff162", bobSession.rootKey.hex())
        assertEquals("e79ef4cee2c42bbb5391e3153504ab765ca27527d3f05b3cd062b96caedbf561", bobSession.sendChainKey!!.hex())
        // His receiving chain is Alice's sending chain, one step on.
        assertEquals("3e6fed5fdfeb9d7ac6661851b235c9e9f6af85a9fd45e99d408c94cf13ab939e", bobSession.recvChainKey!!.hex())
        assertEquals(1L, bobSession.recvN)
        assertTrue(bobSession.touched)
        val m2 = DoubleRatchet.encrypt(utf8("reply from bob"), bobSession, ScriptedEntropy(bytes(0x66, 12)))
        assertEquals(
            DoubleRatchet.Message(3, "OKtmS9hvd9fma92a4HkpE6lP2LM6EmACfktGwfSITGc=", 0, 0, "ZmZmZmZmZmZmZmZmeN4DPa5i6WpI+2xIXOTKIiiAZ5L7ff+MJGiK8BGU"),
            m2,
        )

        // Alice receives the reply; her DH ratchet draws 0x57.
        assertEquals("reply from bob", text(DoubleRatchet.decrypt(m2, aliceSession, ScriptedEntropy(bytes(0x57, 32)))))
        assertEquals(1L, aliceSession.prevChainLength)
        assertEquals(0L, aliceSession.sendN)
        assertEquals(1L, aliceSession.recvN)
        assertEquals("OKtmS9hvd9fma92a4HkpE6lP2LM6EmACfktGwfSITGc=", B64.encode(aliceSession.dhRecvPublic!!))
        assertEquals("44f9054dc5f6ca647a150256f2b9590396fbd4b89058571c6a3437a824106533", aliceSession.rootKey.hex())
        assertEquals("2eeb4f55b0b43b46e3c2b8904641e6a24ab42f39a6614300eae9b0838049e75d", aliceSession.sendChainKey!!.hex())
        assertEquals("fZBL47yrGpEMZaxLCizdFqhGrYoKxy2zHMPkdnHTDgI=", B64.encode(aliceSession.dhSendPublic!!))
    }

    @Test
    fun theReceiverNeverConsumesTheCallersIdentityArray() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val bobPrivate = bob.private.copyOf()
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        DoubleRatchet.decrypt(DoubleRatchet.encrypt(utf8("x"), DoubleRatchet.initiateAsSender(alice.private, bob.public)), bobSession)
        // The DH ratchet replaced (and zeroed) the session's copy, never the caller's key.
        assertArrayEquals(bobPrivate, bob.private)
        assertFalse(bobSession.dhSendPrivate!!.contentEquals(bobPrivate))
    }

    // ---- out-of-order delivery and skipped keys (DoubleRatchet.swift:240-263) ----

    @Test
    fun outOfOrderMessagesOpenWithSkippedKeys() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        val messages = (0 until 5).map { DoubleRatchet.encrypt(utf8("m$it"), aliceSession) }
        for (i in listOf(3, 0, 4, 1, 2)) assertEquals("m$i", text(DoubleRatchet.decrypt(messages[i], bobSession)))
        assertTrue(bobSession.skipped.isEmpty())
        assertEquals(5L, bobSession.recvN)
    }

    @Test
    fun aMessageMoreThanMaxSkipAheadIsRefused() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val messages = (0..DoubleRatchet.MAX_SKIP + 1).map { DoubleRatchet.encrypt(utf8("m$it"), aliceSession) }

        val far = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        assertThrows(RatchetError.SkippedTooFar::class.java) { DoubleRatchet.decrypt(messages[DoubleRatchet.MAX_SKIP + 1], far) }
        // Exactly MAX_SKIP ahead still opens and keeps every key before it.
        val near = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        assertEquals("m64", text(DoubleRatchet.decrypt(messages[DoubleRatchet.MAX_SKIP], near)))
        assertEquals(DoubleRatchet.MAX_SKIP, near.skipped.size)
        assertEquals("m0", text(DoubleRatchet.decrypt(messages[0], near)))
    }

    @Test
    fun skippedKeysAreCappedAtMaxSkipDroppingTheOldest() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)

        // Chain 1: Bob only gets message 40, so 0…39 are skipped.
        val chain1 = (0..40).map { DoubleRatchet.encrypt(utf8("one-$it"), aliceSession) }
        DoubleRatchet.decrypt(chain1[40], bobSession)
        assertEquals(40, bobSession.skipped.size)
        // Bob replies; Alice ratchets to a new sending chain.
        DoubleRatchet.decrypt(DoubleRatchet.encrypt(utf8("reply"), bobSession), aliceSession)
        // Chain 2: again only message 40 arrives: 80 skipped keys, the oldest 16 go.
        val chain2 = (0..40).map { DoubleRatchet.encrypt(utf8("two-$it"), aliceSession) }
        assertEquals(41L, chain2[0].pn)
        DoubleRatchet.decrypt(chain2[40], bobSession)
        assertEquals(DoubleRatchet.MAX_SKIP, bobSession.skipped.size)

        val chain1Dh = chain1[0].dh
        val kept = bobSession.skipped.keys.filter { it.startsWith("$chain1Dh:") }.map { it.substringAfter(':').toInt() }
        assertEquals((16..39).toList(), kept)
        assertEquals("one-20", text(DoubleRatchet.decrypt(chain1[20], bobSession.deepCopy())))
        assertEquals("two-5", text(DoubleRatchet.decrypt(chain2[5], bobSession.deepCopy())))
        assertThrows(RatchetError::class.java) { DoubleRatchet.decrypt(chain1[5], bobSession.deepCopy()) }
    }

    // ---- failures and copies ----

    @Test
    fun aFailedDecryptLeavesADeepCopiedOriginalUntouched() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        DoubleRatchet.decrypt(DoubleRatchet.encrypt(utf8("first"), aliceSession), bobSession)
        val before = bobSession.deepCopy()

        val tampered = DoubleRatchet.encrypt(utf8("second"), aliceSession).let { m ->
            val ct = B64.decodeStrict(m.ct)!!.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            m.copy(ct = B64.encode(ct))
        }
        val attempt = bobSession.deepCopy()
        assertThrows(RatchetError.DecryptFailed::class.java) { DoubleRatchet.decrypt(tampered, attempt) }
        assertEquals(before, bobSession)
        assertFalse(attempt == bobSession) // the copy advanced, the original did not
    }

    @Test
    fun malformedMessagesFailWithRatchetErrors() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val message = DoubleRatchet.encrypt(utf8("x"), DoubleRatchet.initiateAsSender(alice.private, bob.public))
        fun fresh() = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)

        assertThrows(RatchetError.DecryptFailed::class.java) { DoubleRatchet.decrypt(message.copy(v = 2), fresh()) }
        assertThrows(RatchetError.InvalidKey::class.java) { DoubleRatchet.decrypt(message.copy(dh = "not base64"), fresh()) }
        assertThrows(RatchetError.InvalidKey::class.java) { DoubleRatchet.decrypt(message.copy(ct = message.ct.trimEnd('=')), fresh()) }
        assertThrows(RatchetError.InvalidKey::class.java) { DoubleRatchet.decrypt(message.copy(dh = B64.encode(ByteArray(31))), fresh()) }
        // A low-order point as the remote DH key.
        assertThrows(RatchetError.InvalidKey::class.java) { DoubleRatchet.decrypt(message.copy(dh = B64.encode(ByteArray(32))), fresh()) }
        assertThrows(RatchetError.DecryptFailed::class.java) { DoubleRatchet.decrypt(utf8("{\"v\":3}"), fresh()) }
        assertThrows(RatchetError.DecryptFailed::class.java) { DoubleRatchet.decrypt(utf8("not json"), fresh()) }
    }

    @Test
    fun aSessionWithoutASendChainIsPromotedOnEncrypt() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        val newDh = bytes(0x42, 32)
        val message = DoubleRatchet.encrypt(utf8("x"), bobSession, ScriptedEntropy(newDh, bytes(0x43, 12)))
        assertEquals(B64.encode(Primitives.x25519Public(newDh)), message.dh)
        assertNotNull(bobSession.sendChainKey)
        assertEquals(0L, message.pn)
        assertEquals(1L, bobSession.sendN)
        assertTrue(bobSession.touched)
    }

    // ---- wire message and stored session ----

    @Test
    fun messageCountersAreUInt32() {
        assertNotNull(DoubleRatchet.Message.fromJson(utf8("""{"v":3,"dh":"a","n":4294967295,"pn":0,"ct":"b"}""")))
        assertNull(DoubleRatchet.Message.fromJson(utf8("""{"v":3,"dh":"a","n":4294967296,"pn":0,"ct":"b"}""")))
        assertNull(DoubleRatchet.Message.fromJson(utf8("""{"v":3,"dh":"a","n":0,"pn":-1,"ct":"b"}""")))
        assertNull(DoubleRatchet.Message.fromJson(utf8("""{"v":3,"dh":"a","n":0.5,"pn":0,"ct":"b"}""")))
    }

    @Test
    fun storedSessionsRoundTripInTheBinaryLayout() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val aliceSession = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val bobSession = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        val messages = (0 until 4).map { DoubleRatchet.encrypt(utf8("m$it"), aliceSession) }
        DoubleRatchet.decrypt(messages[3], bobSession) // leaves three skipped keys

        val encoded = bobSession.encode()
        // format, flags (all five optional keys and touched), root, peer, 5 keys, 3 counters, count, 3 entries.
        assertEquals(DoubleRatchet.Session.STORED_FORMAT.toByte(), encoded[0])
        assertEquals(0x3F, encoded[1].toInt())
        assertEquals(2 + 7 * 32 + 12 + 2 + 3 * 68, encoded.size)
        val decoded = DoubleRatchet.Session.decode(encoded)
        assertEquals(bobSession, decoded)
        assertEquals(bobSession.skipped.keys.toList(), decoded!!.skipped.keys.toList()) // order kept
        // The decoded session owns its arrays: zeroing the input does not touch it.
        encoded.fill(0)
        assertEquals(bobSession, decoded)
        // It keeps working: the skipped keys open their messages.
        assertEquals("m0", String(DoubleRatchet.decrypt(messages[0], decoded), Charsets.UTF_8))

        // Absent keys are left out: a fresh receiver has no chains and no remote DH key.
        val receiver = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        val receiverBytes = receiver.encode()
        assertEquals(1 shl 2 or (1 shl 3), receiverBytes[1].toInt())
        assertEquals(2 + 4 * 32 + 12 + 2, receiverBytes.size)
        assertEquals(receiver, DoubleRatchet.Session.decode(receiverBytes))
    }

    /**
     * The stored form never passes a key through a `String` (which nothing can zero): the bytes
     * hold the raw keys, never their Base64 or hex text, as a JSON encoding would.
     */
    @Test
    fun storedSessionsCarryNoTextFormOfAnyKey() {
        val alice = TestIdentity.random()
        val bob = TestIdentity.random()
        val sender = DoubleRatchet.initiateAsSender(alice.private, bob.public)
        val session = DoubleRatchet.prepareAsReceiver(bob.private, alice.public)
        DoubleRatchet.decrypt((0 until 3).map { DoubleRatchet.encrypt(utf8("m$it"), sender) }.last(), session)
        assertEquals(2, session.skipped.size)
        val encoded = session.encode()
        val secrets = listOfNotNull(session.rootKey, session.sendChainKey, session.recvChainKey, session.dhSendPrivate) + session.skipped.values
        for (secret in secrets) {
            assertTrue(encoded.hex().contains(secret.hex()))
            assertFalse(encoded.hex().contains(utf8(B64.encode(secret)).hex()))
            assertFalse(encoded.hex().contains(utf8(secret.hex()).hex()))
        }
    }

    @Test
    fun storedSessionsThisBuildCannotReadAreNoSession() {
        val good = DoubleRatchet.initiateAsSender(TestIdentity.random().private, TestIdentity.random().public).encode()
        assertNotNull(DoubleRatchet.Session.decode(good))
        assertNull(DoubleRatchet.Session.decode(good.copyOf().also { it[0] = 1 })) // another format
        assertNull(DoubleRatchet.Session.decode(good.copyOf().also { it[1] = (it[1].toInt() or 0x40).toByte() })) // unknown flag
        assertNull(DoubleRatchet.Session.decode(good.copyOf(good.size - 1))) // truncated
        assertNull(DoubleRatchet.Session.decode(good + byteArrayOf(0))) // trailing byte
        // A skipped count that the bytes do not hold.
        assertNull(DoubleRatchet.Session.decode(good.copyOf().also { it[it.size - 1] = 1 }))
        // Wave 1's JSON records (format 1) are not read.
        assertNull(DoubleRatchet.Session.decode(utf8("""{"v":1,"rootKey":"AAAA"}""")))
        assertNull(DoubleRatchet.Session.decode(ByteArray(0)))
    }

    @Test
    fun wipeZeroesTheSecretsAndToStringShowsNone() {
        val session = DoubleRatchet.initiateAsSender(TestIdentity.random().private, TestIdentity.random().public)
        val root = session.rootKey
        val chain = session.sendChainKey!!
        val dh = session.dhSendPrivate!!
        assertFalse(session.toString().contains(root.hex()))
        assertFalse(session.toString().contains(B64.encode(root)))
        session.wipe()
        assertTrue(root.all { it.toInt() == 0 } && chain.all { it.toInt() == 0 } && dh.all { it.toInt() == 0 })
    }
}

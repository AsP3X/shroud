package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.bytes
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Sender tags on identity boxes and the untagged-box watermark policy (iOS
 * `ios/shroudTests/SenderTagTests.swift`, web `web/src/crypto/boxAuth.selftest.ts`; crypto spec §3,
 * §16.1). A box built from the two public keys alone — what the server can do — must not open as
 * the peer once that peer is known to tag.
 *
 * Every case has fresh keys and fresh in-memory stores (JUnit builds one instance per test), the
 * role of `SenderTagStore.useInMemoryStorageForTesting()` and the per-case user ids of
 * `SenderTagTests.swift:16-28`.
 */
class SenderTagTest {
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
    private val mallory = TestIdentity.random()

    /** `SenderTagTests.swift:14`. */
    private val t0: Instant = Instant.ofEpochSecond(1_788_000_000)

    // ---- helpers (SenderTagTests.swift:33-107) ----

    /** An untagged box as builds before the tag sealed it — and as anyone with the public keys can (`:36-55`). */
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

    /** v3 with a ratchet body no session can read, so the peer-box fallback runs (`:61-72`). */
    private fun junkV3(peer: SealedBox): ByteArray = utf8(
        CryptoJson.encodeToString(
            RatchetEnvelope.serializer(),
            RatchetEnvelope(v = 3, dh = B64.encode(TestIdentity.random().public), n = 0, pn = 0, ct = B64.encode(bytes(7, 40)), peer = peer),
        ),
    )

    /** v2 "from Alice", tagged with [signer]'s private key (`:74-81`). */
    private fun aliceV2(text: String, signer: TestIdentity = alice): ByteArray =
        crypto.sealV2(utf8(text), bob.public, signer.private, alice.public)

    private fun decodeV2(envelope: ByteArray): SealedEnvelope =
        CryptoJson.decodeFromString(SealedEnvelope.serializer(), String(envelope, Charsets.UTF_8))

    private fun bobOpens(envelope: ByteArray, at: Instant): String =
        String(crypto.open(envelope, aliceUser, bob.private, bob.public, alice.public, OpenAs.Recipient, at), Charsets.UTF_8)

    private fun aliceOpensOwn(envelope: ByteArray, at: Instant): String =
        String(crypto.open(envelope, bobUser, alice.private, alice.public, alice.public, OpenAs.Sender, at), Charsets.UTF_8)

    // ---- box level (SenderTagTests.swift:109-180) ----

    /** `SenderTagTests.swift:111-118`. */
    @Test
    fun sealedBoxesCarryATagThatOpens() {
        val sealed = aliceV2("hi")
        val envelope = decodeV2(sealed)
        assertNotNull(envelope.peer?.t)
        assertNotNull(envelope.selfBox?.t)
        assertEquals("hi", bobOpens(sealed, t0))
    }

    /** `SenderTagTests.swift:120-127`: Mallory seals "from Alice" with her own key; the static ECDH does not match. */
    @Test
    fun tagFromAKeyThatIsNotTheSendersIsRefused() {
        val forged = aliceV2("forged", signer = mallory)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged, t0) }
    }

    /** `SenderTagTests.swift:129-138`. */
    @Test
    fun tagMovedOntoOtherCiphertextIsRefused() {
        val real = decodeV2(aliceV2("real"))
        val other = decodeV2(aliceV2("other"))
        val moved = other.peer!!.copy(t = real.peer!!.t)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(v2(peer = moved), t0) }
    }

    /** `SenderTagTests.swift:140-155`: Bob's own self box, handed back to Bob as if Alice had sent it. */
    @Test
    fun reflectedBoxIsRefused() {
        val bobsOwn = crypto.sealV2(utf8("mine"), alice.public, bob.private, bob.public)
        val selfBox = decodeV2(bobsOwn).selfBox!!
        assertThrows(Exception::class.java) { bobOpens(v2(peer = selfBox), t0) }
    }

    /** `SenderTagTests.swift:157-180`: sealed by `web/src/crypto/sealedBox.ts` with fixed keys; both clients derive one tag. */
    @Test
    fun webSealedTagVerifiesHere() {
        val alice = TestIdentity.filled(0x11)
        val bob = TestIdentity.filled(0x22)
        var box = SealedBox(
            ek = "S6UOubR4gmyGJzC+XuOl+S0Y3VykkvhJXJ32/m3JWCw=",
            ct = "s67IM+reT2lNl2zRNUPKbSdmtPpU5BX5o/cOH0WTaaHS23Hi",
            t = "iFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM=",
        )
        fun open(): ByteArray = crypto.openLegacy(v2(peer = box), bob.private, bob.public, alice.public, OpenAs.Recipient, t0)
        assertEquals("from web", String(open(), Charsets.UTF_8))
        box = box.copy(t = "jFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM=")
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open() }
    }

    // ---- policy (SenderTagTests.swift:182-274) ----

    /** `SenderTagTests.swift:184-190`: with no tagged message seen yet, a forged v2 still opens (the transition window). */
    @Test
    fun untaggedBoxOpensBeforeTheSenderIsSeenTagging() {
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertEquals("forged", bobOpens(forged, t0))
    }

    /** `SenderTagTests.swift:192-216`. */
    @Test
    fun untaggedBoxesAfterTheWatermarkAreRefused() {
        assertEquals("real", bobOpens(aliceV2("real"), t0))

        val forgedV2 = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forgedV2, t0.plusSeconds(1)) }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forgedV2, t0) }

        val box = forgedBox("forged v1", alice.public, bob.public)
        val forgedV1 = utf8(CryptoJson.encodeToString(SealedEnvelope.serializer(), SealedEnvelope(v = 1, ek = box.ek, ct = box.ct)))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forgedV1, t0.plusSeconds(1)) }

        val forgedV3 = junkV3(forgedBox("forged v3", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forgedV3, t0.plusSeconds(1)) }
    }

    /** `SenderTagTests.swift:218-230`: a fresh device reads the history from before the peer upgraded. */
    @Test
    fun untaggedHistoryBeforeTheWatermarkStillOpens() {
        assertEquals("real", bobOpens(aliceV2("real"), t0))
        val old = v2(peer = forgedBox("old", alice.public, bob.public))
        assertEquals("old", bobOpens(old, t0.minusSeconds(60)))

        // An older tagged message moves the watermark back past it.
        assertEquals("older", bobOpens(aliceV2("older"), t0.minusSeconds(120)))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(old, t0.minusSeconds(60)) }
    }

    /** `SenderTagTests.swift:232-250`: the device that reads by ratchet never opens the peer box; its tag still counts. */
    @Test
    fun ratchetReadMarksTheSenderAsTagging() {
        val real = crypto.seal(utf8("by ratchet"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        assertEquals(3, CryptoFixtures.version(real))
        assertEquals("by ratchet", bobOpens(real, t0))

        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged, t0.plusSeconds(1)) }
    }

    /** `SenderTagTests.swift:252-264`. */
    @Test
    fun siblingWithoutRatchetStateReadsTheTaggedPeerBox() {
        val tagged = decodeV2(aliceV2("to sibling")).peer!!
        assertEquals("to sibling", bobOpens(junkV3(tagged), t0))

        val malloryBox = decodeV2(aliceV2("m", signer = mallory)).peer!!
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(junkV3(malloryBox), t0) }
    }

    /** `SenderTagTests.swift:266-274`: "sent by me" cannot be forged onto our other devices either. */
    @Test
    fun forgedSelfBoxAfterOwnTaggedMessageIsRefused() {
        assertEquals("own", aliceOpensOwn(aliceV2("own"), t0))
        val forged = v2(selfBox = forgedBox("fake own", alice.public, alice.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { aliceOpensOwn(forged, t0.plusSeconds(1)) }
    }

    // ---- web boxAuth.selftest.ts extras ----

    /**
     * `boxAuth.selftest.ts:118-134` at box level: the untagged copy of a tagged box opens but is not
     * reported as tagged; a tag from another key, a moved tag and a reflected box are refused.
     */
    @Test
    fun boxLevelTagBindsTheSenderAndTheBytes() {
        val box = IdentityBoxes.seal(utf8("hi"), alice.private, alice.public, bob.public, SystemEntropy)
        assertTrue(IdentityBoxes.verifyTag(box, bob.private, alice.public, bob.public))
        assertEquals("hi", String(IdentityBoxes.open(box, bob.private, alice.public, bob.public), Charsets.UTF_8))
        val untagged = box.copy(t = null)
        assertFalse(IdentityBoxes.verifyTag(untagged, bob.private, alice.public, bob.public))
        assertEquals("hi", String(IdentityBoxes.open(untagged, bob.private, alice.public, bob.public), Charsets.UTF_8))

        val byMallory = IdentityBoxes.seal(utf8("hi"), mallory.private, alice.public, bob.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.verifyTag(byMallory, bob.private, alice.public, bob.public) }
        val other = IdentityBoxes.seal(utf8("other"), alice.private, alice.public, bob.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            IdentityBoxes.verifyTag(other.copy(t = box.t), bob.private, alice.public, bob.public)
        }
        val bobToAlice = IdentityBoxes.seal(utf8("mine"), bob.private, bob.public, alice.public, SystemEntropy)
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { IdentityBoxes.verifyTag(bobToAlice, bob.private, alice.public, bob.public) }
        // A tag that is not strict Base64 is refused, never read as "untagged".
        assertThrows(CryptoError.UnauthenticatedSender::class.java) {
            IdentityBoxes.verifyTag(box.copy(t = box.t!!.trimEnd('=')), bob.private, alice.public, bob.public)
        }
    }

    /** `boxAuth.selftest.ts:177-186`: a real v3 whose ratchet body is broken is read by its peer box, and that read sets the watermark. */
    @Test
    fun aPeerBoxReadSetsTheWatermark() {
        val real = crypto.seal(utf8("to sibling"), bobUser, bob.public, alice.private, alice.public, aliceUser)
        val fields = CryptoJson.parseToJsonElement(String(real, Charsets.UTF_8)) as JsonObject
        val drBroken = utf8(JsonObject(fields + ("ct" to kotlinx.serialization.json.JsonPrimitive(B64.encode(SystemEntropy.bytes(40))))).toString())
        assertEquals("to sibling", bobOpens(drBroken, t0))
        assertEquals(1, tags.count())
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged, t0.plusMillis(1)) }
    }

    /**
     * `boxAuth.selftest.ts:165` refuses an untagged box with no readable time. Android has no NaN
     * `Instant`; the fail-closed case here is a watermark store that cannot answer (no history key):
     * untagged boxes are refused, tagged ones still open (and their watermark write is dropped).
     */
    @Test
    fun untaggedBoxesAreRefusedWhileTheWatermarkStoreIsLocked() {
        tags.isLocked = true
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { bobOpens(forged, t0) }
        assertEquals("real", bobOpens(aliceV2("real"), t0))
        tags.isLocked = false
        assertEquals(0, tags.count())
    }

    /** `MessageCrypto.swift:34-36`, `:464-466`: untagged boxes from everyone are refused at or after the cutoff. */
    @Test
    fun theLegacyCutoffRefusesUntaggedBoxesAtAndAfterIt() {
        assertEquals(null, MessageCrypto.LEGACY_BOX_CUTOFF)
        val withCutoff = MessageCrypto(records, tags, SystemEntropy, legacyBoxCutoff = t0)
        fun open(envelope: ByteArray, at: Instant) =
            String(withCutoff.open(envelope, aliceUser, bob.private, bob.public, alice.public, OpenAs.Recipient, at), Charsets.UTF_8)
        val forged = v2(peer = forgedBox("forged", alice.public, bob.public))
        assertEquals("forged", open(forged, t0.minusMillis(1)))
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open(forged, t0) }
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { open(forged, t0.plusSeconds(1)) }
        // Tagged boxes are not affected.
        assertEquals("real", open(aliceV2("real"), t0.plusSeconds(1)))
    }

    /** `SenderTagStore.swift:10-11`: watermarks are keyed by the identity key, so a new phrase is a new sender. */
    @Test
    fun theWatermarkBelongsToTheIdentityKeyNotTheUser() {
        assertEquals("real", bobOpens(aliceV2("real"), t0))
        // Alice re-created her identity: the new key has no tagged history yet.
        val newAlice = TestIdentity.random()
        val forged = v2(peer = forgedBox("new phrase", newAlice.public, bob.public))
        val opened = crypto.open(forged, aliceUser, bob.private, bob.public, newAlice.public, OpenAs.Recipient, t0.plusSeconds(1))
        assertEquals("new phrase", String(opened, Charsets.UTF_8))
    }
}

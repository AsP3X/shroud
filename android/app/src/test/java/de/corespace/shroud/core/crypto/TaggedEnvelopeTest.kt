package de.corespace.shroud.core.crypto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * Tagged v2 envelopes — reactions (iOS `MessageCrypto.openTagged`, `ios/shroud/Services/Crypto/MessageCrypto.swift:317-351`;
 * web `openTaggedEnvelope`): the crypto parts of `ios/shroudTests/MessageReactionTests.swift` and
 * `web/src/crypto/reactionsCrypto.selftest.ts` (crypto spec §4.5, §16.1–16.2).
 *
 * Which strings count as one emoji is W1-WIRE's `MessageReactionPayload` and tested there; here the
 * opened plaintext is compared as JSON, with every string the sender put in (crypto spec §16.2).
 */
class TaggedEnvelopeTest {
    private val records = InMemoryRatchetSessionRecords()
    private val tags = InMemorySenderTagWatermarks()
    private val crypto = MessageCrypto(records, tags)

    private fun json(bytes: ByteArray) = CryptoJson.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject

    /** The reaction payload as both clients write it (`{"t":"reaction","r":<message id>,"e":[…]}`). */
    private fun reaction(messageId: UUID, emojis: List<String>): ByteArray = utf8(
        JsonObject(
            mapOf(
                "t" to JsonPrimitive("reaction"),
                "r" to JsonPrimitive(messageId.toString()),
                "e" to JsonArray(emojis.map(::JsonPrimitive)),
            ),
        ).toString(),
    )

    /**
     * Every string both cross-platform envelopes carry (crypto spec §16.2); the reactions parser
     * later drops "ok", "🔥🔥", the bare "❤" and the repeat (`MessageReactionTests.swift:125-127`).
     */
    private val sealedEmojis = listOf("👍🏽", "🏳️‍🌈", "🇩🇪", "❤️", "❤️‍🔥", "1️⃣", "👨‍👩‍👧", "ok", "🔥🔥", "❤", "👍🏽")
    private val sealedMessage = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    private fun assertSealedReaction(plaintext: ByteArray) {
        val fields = json(plaintext)
        assertEquals("reaction", fields["t"]!!.jsonPrimitive.content)
        assertEquals(sealedMessage, fields["r"]!!.jsonPrimitive.content)
        assertEquals(sealedEmojis, fields["e"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** `MessageReactionTests.swift:69-97`. */
    @Test
    fun sealedReactionOpensForThePeerAndOurOtherDevices() {
        val ours = TestIdentity.random()
        val theirs = TestIdentity.random()
        val plaintext = reaction(UUID.randomUUID(), listOf("😮"))
        val sealed = crypto.sealV2(plaintext, theirs.public, ours.private, ours.public)
        val forPeer = crypto.openTagged(sealed, theirs.private, theirs.public, ours.public, OpenAs.Recipient)
        val forUs = crypto.openTagged(sealed, ours.private, ours.public, ours.public, OpenAs.Sender)
        assertEquals(json(plaintext), json(forPeer))
        assertEquals(json(plaintext), json(forUs))
        // No ratchet and no watermark: a record per emoji must not cost a store write (`:321-322`).
        assertEquals(0, records.saveCount)
        assertEquals(0, tags.count())
    }

    /**
     * `MessageReactionTests.swift:99-128`: sealed by the web client from `0xa1 × 32` to `0xb2 × 32`
     * (`webSealedReaction`, `:14-28`, the 13 lines joined without their `\` continuations).
     */
    @Test
    fun reactionSealedOnTheWebOpensHere() {
        val alice = TestIdentity.filled(0xa1)
        val bob = TestIdentity.filled(0xb2)
        val sealed = B64.decodeStrict(WEB_SEALED_REACTION)!!
        assertSealedReaction(crypto.openTagged(sealed, bob.private, bob.public, alice.public, OpenAs.Recipient))
        assertSealedReaction(crypto.openTagged(sealed, alice.private, alice.public, alice.public, OpenAs.Sender))
    }

    /**
     * `web/src/crypto/reactionsCrypto.selftest.ts:87-119`: sealed by iOS (`MessageCrypto.seal` +
     * `MessageReactionPayload.make`) from `0xb2 × 32` to `0xa1 × 32`. Swift's `JSONEncoder` wrote the
     * keys in its own order and escaped `/` as `\/`.
     */
    @Test
    fun reactionSealedOnIosOpensHere() {
        val iosSender = TestIdentity.filled(0xb2)
        val iosPeer = TestIdentity.filled(0xa1)
        val sealed = B64.decodeStrict(IOS_SEALED_REACTION)!!
        assertEquals(true, String(sealed, Charsets.UTF_8).startsWith("{\"peer\":"))
        assertSealedReaction(crypto.openTagged(sealed, iosPeer.private, iosPeer.public, iosSender.public, OpenAs.Recipient))
        assertSealedReaction(crypto.openTagged(sealed, iosSender.private, iosSender.public, iosSender.public, OpenAs.Sender))
    }

    /** `MessageReactionTests.swift:223-260` and `reactionsCrypto.selftest.ts:79-85`. */
    @Test
    fun taggedOpenRefusesUntaggedAndForeignBoxes() {
        val ours = TestIdentity.random()
        val theirs = TestIdentity.random()
        val sealed = crypto.sealV2(utf8("x"), theirs.public, ours.private, ours.public)
        fun peerOpens(envelope: ByteArray, claimedSender: ByteArray = ours.public) =
            crypto.openTagged(envelope, theirs.private, theirs.public, claimedSender, OpenAs.Recipient)

        // Strip the tag: what the server could build from public keys alone.
        val fields = json(sealed)
        val peer = fields["peer"] as JsonObject
        val untagged = utf8(JsonObject(fields + ("peer" to JsonObject(peer - "t"))).toString())
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { peerOpens(untagged) }
        // The same untagged box as a v1 envelope.
        val v1 = utf8(JsonObject(mapOf("v" to JsonPrimitive(1), "ek" to peer["ek"]!!, "ct" to peer["ct"]!!)).toString())
        assertThrows(CryptoError.UnsupportedVersion::class.java) { peerOpens(v1) }
        // A genuine box claimed to be from someone else does not open either.
        val stranger = TestIdentity.random()
        assertThrows(CryptoError.UnauthenticatedSender::class.java) { peerOpens(sealed, stranger.public) }
        // The genuine one still does.
        assertEquals("x", String(peerOpens(sealed), Charsets.UTF_8))
    }

    /** `MessageCrypto.swift:330-336`: only v2; the box of the role must be there. */
    @Test
    fun taggedOpenTakesOnlyV2WithTheBoxOfItsRole() {
        val ours = TestIdentity.random()
        val theirs = TestIdentity.random()
        val (lower, higher) = CryptoFixtures.sortedUserIds()
        val v3 = crypto.seal(utf8("x"), higher, theirs.public, ours.private, ours.public, lower)
        assertEquals(3, CryptoFixtures.version(v3))
        assertThrows(CryptoError.UnsupportedVersion::class.java) {
            crypto.openTagged(v3, theirs.private, theirs.public, ours.public, OpenAs.Recipient)
        }
        val fields = json(crypto.sealV2(utf8("x"), theirs.public, ours.private, ours.public))
        val selfOnly = utf8(JsonObject(fields - "peer").toString())
        assertThrows(CryptoError.OpenFailed::class.java) {
            crypto.openTagged(selfOnly, theirs.private, theirs.public, ours.public, OpenAs.Recipient)
        }
        val peerOnly = utf8(JsonObject(fields - "self").toString())
        assertThrows(CryptoError.OpenFailed::class.java) {
            crypto.openTagged(peerOnly, ours.private, ours.public, ours.public, OpenAs.Sender)
        }
        assertThrows(CryptoError.OpenFailed::class.java) {
            crypto.openTagged(utf8("not json"), theirs.private, theirs.public, ours.public, OpenAs.Recipient)
        }
    }

    private companion object {
        /** `ios/shroudTests/MessageReactionTests.swift:14-28`, copied line by line. */
        const val WEB_SEALED_REACTION =
            "eyJ2IjoyLCJwZWVyIjp7ImVrIjoibEtaSnd0KzZiSy9nSEJiUlBZNkMydS8vUExJTkluSTJEU1Q3K0YxODNGaz0i" +
                "LCJjdCI6ImdHWXZpVWpxaTRSQkxHdTRhS2p4eHRnTnJjZnlReVNneUNaelQ1Ym1LUFp5L0VWYzlYRTdhcnlRSE1L" +
                "QWU0MnVERHNrK2s5dytRa0FZSk9Lb0tDSHhUNWM4Yk1MUXNmKy9Cclc4cDd6ais0aXhMdjJFVVpoZks0a3d0cHhK" +
                "M3VSM0h4OVpJZzFwVFEraFVzTS9rZXNLWGtadjJKMVRleU0vTTZqdDN0OHk2MERmZVBOdVlsTmFLWmF6eFBrQldN" +
                "UXNmcEtvSGRnN0lkU0QweDY5a3hxOUFZL040ckthV2hVeHR3SzZ4VHptZlRsZG1ZcTFRb1BLN0tEUEh3ZUJJU0pN" +
                "amd3R0dJeFc4bUNSQ2hGUjYvVUF2d0IzVFdpRmttMFhicmZhcm89IiwidCI6InUwa0NxSlBzdThIYkwrOElzM3V5" +
                "ZEZNZEZLNVRLMkFHN1Q4MUtsZDZGWXM9In0sInNlbGYiOnsiZWsiOiJaTi92QXgzZkJTTjBpWStOaUVYSlE0TCs4" +
                "RW5pbmdsRWl1SGJmUjBIRmtBPSIsImN0Ijoib3QvaHBodjY1SG4zbHhVYkYxbTE1MEtmcUdpVCtETFZ5QmJxUWJH" +
                "MUpsZlFkd0xYVEpKckpLai9sN3BtTnl4OTROLzBhZHRxY2JKSDkzVzZrTHdQbStvUE5Yb1ZCLzVPeE13TEtnVmR5" +
                "Vlk4eXBEcWdrWmpEVVh2YmRsbDBmbEZmZWxIUjE4OUZEN2d2NURvV1V3M2F1QnlyUW5MKzhyU1VkTEFTTkVtaTgw" +
                "RkgwZURoKzdhc0NDY2luRFdqMzJ4bXRxTHBXSXRjWURUR212TmJoTUlNeUYwQzlpem1RdmZHOUZFME5adlNKNHVQ" +
                "aThLOTdWNTFGWTNQU0haQ2tiYTRZQWNUdStxNlpnRlladVdweldkRnRHcjl0MzVwQkVuWVVEdVpzaz0iLCJ0Ijoi" +
                "cllhcm9RNWZZY2ZlZ2lEa2JvN3RKUThoNVVyaVE5ajI2NnN4S1FwcDBHST0ifX0="

        /** `web/src/crypto/reactionsCrypto.selftest.ts:90-102`, the 12 literals concatenated. */
        const val IOS_SEALED_REACTION =
            "eyJwZWVyIjp7ImVrIjoibjFEQTdvS0tUU05vRFFJQTZlMFlscjY2NWU4SlwvV3luSXRFMStMWXVvVmM9IiwiY3QiOiJLbWdB" +
                "TCtkdGUzQkd1XC82NGJKV1VYZ25EVHZHazlSQlIzWVg5THZsbnJwQnExOFJXWkVjQlJodDFhb3pmWE1MQmdcL1Y3K05MQkQ4" +
                "WnAyTENhY1pxMVNUYXRNaFVadjZPaG1uaEZZTXN0eEtTYnBlVzAyNzJEQTV0NWJ5RllUSnRPOTNZbkFNUHFLTjY4ZXprQXMx" +
                "Sys5Y2xIbjlYVTlPNWdXWW5QMUx3cU1RUlwvc1wvc01lSEdiNnk2alwvTHBZT0hYeUZreUg5ZUtNSGhSWHR3NmF3R0taZkF1" +
                "bTZcL052bzVWa3l5MU8wdEQ1RVRQT2RqSUhoSjhISnJmQ0s3K3Vxb3pYVTdiNGd2ZmxrYmpCaEk2bzlYejJ6eENjaUlFZHo0" +
                "WWxHOHJqXC9ZST0iLCJ0IjoiZEIxeFlFQjZRd0lsNU80OUVVUnBaR3d1Q3FWM3F5cUJtRk9RQzMxcmwyND0ifSwidiI6Miwi" +
                "c2VsZiI6eyJlayI6InlFTmR3VmVxbFwvVEttUXZRalpGTllGVnd6WWVpNTJIbElMc3NVUVEwZWhNPSIsImN0IjoiMlU2MHFZ" +
                "SmhxU1lhdko2UHNtRkNYV0N5Y2IxN2U4QklxUUlJOVdSV3JBK21sUlhneFBQdXIwc2IyeU9yYXVjU215cmdwRzFscWZWUnd2" +
                "KzFpZmZ2MFRuT0hiTElOREo2Q0ZHcWVEZjhmbHdOZ1pRSEk1N3QyanBqVnZVa2pOWVdmSUZoWnJlMWhYY2FlTlwvdHArNGZX" +
                "eVJ6TkFPNjZicTZYOWU0dkFBT0xhNHFkM01ONUJrazd2bk1ZQ3laMnZmWEg0OFVqVFdFNnVRcERyQU1FRjBXem5IOHFqU3Fj" +
                "cWcwald5YnFBSytFOVwvN3ZyZElpTkFKcDh3bW5OaWJLMHA3MHJmQ3N4TGpSR2d6V0ErbDFKczQrQTdNSTlrTFZyZnlIbFNV" +
                "Ync4PSIsInQiOiJPWTZSemJEVERaUUFvVFBwZ3pJbmJxNERQUXoyOGVTb2NmbDF1RHY1ZVZnPSJ9fQ=="
    }
}

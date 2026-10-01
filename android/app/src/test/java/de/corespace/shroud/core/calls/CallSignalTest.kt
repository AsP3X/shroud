package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.calls.crypto.CallCryptoException
import de.corespace.shroud.core.calls.crypto.CallSignalKeys
import de.corespace.shroud.core.calls.signal.CallEndReason
import de.corespace.shroud.core.calls.signal.CallMediaOrder
import de.corespace.shroud.core.calls.signal.CallSdp
import de.corespace.shroud.core.calls.signal.CallSignal
import de.corespace.shroud.core.calls.signal.CallSignalSequencer
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Sealed signal bodies and the end-of-call lines, against `docs/calls.md` — iOS `CallSignalTests`
 * (`ios/shroudTests/CallSignalTests.swift`, all 9) plus the web `logic.selftest.ts` signal rows
 * where iOS and the web agree (and where they differ, iOS — calls §2.4, §18).
 */
class CallSignalTest {
    private val callId = UUID.fromString("0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b")
    private val apiJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private fun secret(): ByteArray = CallCrypto.callSecret(
        hexToBytes("ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d"),
        hexToBytes("8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467"),
        hexToBytes("389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338"),
    )

    private fun parse(json: String, type: String) = CallSignal.parse(json.toByteArray(), type)

    private fun assertParseFails(expected: CallSignal.ParseException, json: String, type: String) {
        try {
            parse(json, type)
            fail("expected $expected for $json")
        } catch (e: CallSignal.ParseException) {
            assertSame(expected, e)
        }
    }

    @Test
    fun anOfferRoundTripsAndAReflectedCopyDoesNotOpen() {
        val secret = secret()
        val caller = CallSignalKeys.identity(secret, callId, CallCrypto.Role.Caller)
        val callee = CallSignalKeys.identity(secret, callId, CallCrypto.Role.Callee)
        val offer = CallSignal.Offer("v=0\r\n", restart = false, ephemeral = null)
        val payload = CallCrypto.seal(CallSignal.plaintext(offer, 1), caller.send, callId, offer.signalType)
        val opened = CallCrypto.open(payload, callee.receive, callId, "sdp_offer")
        val parsed = CallSignal.parse(opened, "sdp_offer")
        assertEquals(1, parsed.n)
        assertEquals(CallSignal.Offer("v=0\r\n", restart = false, ephemeral = null), parsed.signal)
        // Reflected back to the sender: their receive key is the other direction.
        try {
            CallCrypto.open(payload, caller.receive, callId, "sdp_offer")
            fail("a reflected offer opened")
        } catch (e: CallCryptoException) {
            assertSame(CallCryptoException.OpenFailed, e)
        }
    }

    @Test
    fun relabellingTheSignalTypeFails() {
        val keys = CallSignalKeys.identity(secret(), callId, CallCrypto.Role.Callee)
        val payload = CallCrypto.seal(CallSignal.plaintext(CallSignal.RestartRequest, 3), keys.send, callId, "renegotiate")
        try {
            CallCrypto.open(payload, keys.receive, callId, "sdp_offer")
            fail("a relabelled signal opened")
        } catch (e: CallCryptoException) {
            assertSame(CallCryptoException.OpenFailed, e)
        }
        // And a body that says another type than the one it arrived under.
        assertParseFails(CallSignal.ParseException.TypeMismatch, """{"t":"offer","sdp":"v=0","n":1}""", "sdp_answer")
        assertParseFails(CallSignal.ParseException.TypeMismatch, """{"t":"restart","n":3}""", "sdp_offer")
    }

    @Test
    fun candidatesAndMediaKeepTheirFields() {
        val ice = CallSignal.Candidates(listOf(IceCandidatePayload("candidate:1 1 udp 1 1.2.3.4 9 typ host", "0", 0)))
        val parsed = CallSignal.parse(CallSignal.plaintext(ice, 2), "ice_candidate")
        assertEquals(ice, parsed.signal)
        val media = CallSignal.parse(CallSignal.plaintext(CallSignal.Media(mic = false, camera = true), 4), "media_state")
        assertEquals(CallSignal.Media(mic = false, camera = true), media.signal)
        // web: entries without a candidate are dropped; an index alone will do; a missing index is 0.
        val list = parse(
            """{"t":"ice","cs":[{"candidate":"candidate:1 1 udp 1 10.0.0.1 5000 typ host","sdpMid":"0","sdpMLineIndex":0},""" +
                """{"candidate":"candidate:2","sdpMid":null,"sdpMLineIndex":1},{"candidate":"","sdpMid":"0","sdpMLineIndex":0},""" +
                """{"candidate":"candidate:3"}],"n":2}""",
            "ice_candidate",
        ).signal as CallSignal.Candidates
        assertEquals(
            listOf(
                IceCandidatePayload("candidate:1 1 udp 1 10.0.0.1 5000 typ host", "0", 0),
                IceCandidatePayload("candidate:2", null, 1),
                IceCandidatePayload("candidate:3", null, 0),
            ),
            list.candidates,
        )
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"ice","cs":"no","n":2}""", "ice_candidate")
        // iOS casts the whole list to dictionaries: one entry that is no object fails the signal
        // (the web drops just that entry).
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"ice","cs":[{"candidate":"c"},"junk"],"n":2}""", "ice_candidate")
        // Written: sdpMid only when set, sdpMLineIndex always.
        val written = Json.parseToJsonElement(
            String(CallSignal.plaintext(CallSignal.Candidates(listOf(IceCandidatePayload("c", null, null))), 5)),
        ).jsonObject
        val entry = (written["cs"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
        assertFalse(entry.containsKey("sdpMid"))
        assertEquals("0", entry["sdpMLineIndex"].toString())
    }

    @Test
    fun aSharedScreenIsSaidAndAnOlderAppSaysNothing() {
        val sharing = CallSignal.parse(CallSignal.plaintext(CallSignal.Media(mic = true, camera = false, screen = true), 5), "media_state")
        assertEquals(CallSignal.Media(mic = true, camera = false, screen = true), sharing.signal)
        val notSharing = CallSignal.parse(CallSignal.plaintext(CallSignal.Media(mic = true, camera = false, screen = false), 6), "media_state")
        assertEquals(CallSignal.Media(mic = true, camera = false, screen = false), notSharing.signal)
        // An app from before screen sharing sends no `screen`, and none is written for it.
        val older = parse("""{"t":"media","mic":true,"camera":true,"n":7}""", "media_state")
        assertEquals(CallSignal.Media(mic = true, camera = true, screen = null), older.signal)
        val written = Json.parseToJsonElement(String(CallSignal.plaintext(CallSignal.Media(mic = true, camera = true), 8))).jsonObject
        assertNull(written["screen"])
        // The web writes the same shape.
        val web = parse("""{"t":"media","mic":false,"camera":false,"screen":true,"n":9}""", "media_state")
        assertEquals(CallSignal.Media(mic = false, camera = false, screen = true), web.signal)
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"media","mic":true,"camera":true,"screen":"yes","n":10}""", "media_state")
        // NSNumber reads: 0/1 count as booleans (CallSignal.swift:165-171).
        assertEquals(CallSignal.Media(mic = false, camera = true, screen = true), parse("""{"t":"media","mic":0,"camera":1,"screen":1,"n":11}""", "media_state").signal)
        // A string is no bool: iOS falls back to the defaults (mic on, camera off).
        assertEquals(CallSignal.Media(mic = true, camera = false), parse("""{"t":"media","mic":"no","camera":"yes","n":12}""", "media_state").signal)
    }

    @Test
    fun anEphemeralKeyRoundTripsAndABadOneDoesNot() {
        val key = Bytes.of(ByteArray(32) { 7 })
        val offer = CallSignal.Offer("v=0\r\n", restart = false, ephemeral = key)
        val parsed = CallSignal.parse(CallSignal.plaintext(offer, 1), "sdp_offer")
        assertEquals(offer, parsed.signal)
        val described = "v=0\r\na=candidate:1 1 udp 1 10.0.0.1 9 typ host\r\na=fingerprint:sha-256 AA:BB:CC\r\n"
        assertEquals("v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n", CallSdp.withoutCandidates(described))
        assertEquals("aa:bb:cc", CallSdp.fingerprint(described))
        assertTrue(CallSdp.matches("AA:BB:CC", "aa bb cc"))
        assertFalse(CallSdp.matches("AA:BB:CC", "aa:bb:cd"))
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0","restart":false,"n":1,"ek":"%%%%"}""", "sdp_offer")
        // web: a short key, a bad answer key; null is no key either.
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0","ek":"${B64.encode("short".toByteArray())}","n":1}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"answer","sdp":"v=0","ek":"%%%","n":1}""", "sdp_answer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"answer","sdp":"v=0","ek":null,"n":1}""", "sdp_answer")
        val withKey = parse("""{"t":"answer","sdp":"v=0","ek":"${B64.encode(ByteArray(32))}","n":1}""", "sdp_answer")
        assertEquals(CallSignal.Answer("v=0", Bytes.of(ByteArray(32))), withKey.signal)
        assertNull(CallSdp.fingerprint("v=0\r\n"))
        assertFalse(CallSdp.matches("", "aa"))
    }

    @Test
    fun malformedBodiesAndNumbers() {
        // web readSignal rows: restart defaults to false; n starts at 1 and is required.
        assertEquals(CallSignal.Offer("v=0", restart = false), parse("""{"t":"offer","sdp":"v=0","n":1}""", "sdp_offer").signal)
        assertEquals(CallSignal.Offer("v=0", restart = true), parse("""{"t":"offer","sdp":"v=0","restart":true,"n":1}""", "sdp_offer").signal)
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0","n":0}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0"}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0","n":"1"}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","sdp":"v=0","n":true}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"toString","n":1}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"t":"offer","n":1}""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """[1,2]""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """not json""", "sdp_offer")
        assertParseFails(CallSignal.ParseException.Malformed, """{"n":1}""", "sdp_offer")
        // iOS keeps an empty SDP (the web refuses it) and cuts a fractional n like NSNumber.intValue.
        assertEquals(CallSignal.Offer("", restart = false), parse("""{"t":"offer","sdp":"","n":1}""", "sdp_offer").signal)
        assertEquals(1, parse("""{"t":"offer","sdp":"v=0","n":1.5}""", "sdp_offer").n)
        assertEquals(CallSignal.RestartRequest, parse("""{"t":"restart","n":3}""", "renegotiate").signal)
    }

    @Test
    fun endLinesMatchTheCallTable() {
        assertEquals("Declined", CallEndReason.from("rejected", "rejected", isOutgoing = true)?.announcement)
        assertNull(CallEndReason.from("rejected", "rejected", isOutgoing = false)?.announcement)
        assertEquals("No answer", CallEndReason.from("missed", "timeout", isOutgoing = true)?.announcement)
        assertEquals("Missed call", CallEndReason.from("missed", "timeout", isOutgoing = false)?.announcement)
        assertNull(CallEndReason.from("cancelled", "cancelled", isOutgoing = true)?.announcement)
        assertEquals("Connection lost", CallEndReason.from("cancelled", "connection_lost", isOutgoing = true)?.announcement)
        assertEquals("Missed call", CallEndReason.from("cancelled", "cancelled", isOutgoing = false)?.announcement)
        assertEquals("Call ended", CallEndReason.from("ended", "hangup", isOutgoing = true)?.announcement)
        assertEquals("Connection lost", CallEndReason.from("ended", "connection_lost", isOutgoing = false)?.announcement)
        assertNull(CallEndReason.from("ringing", null, isOutgoing = true))
    }

    @Test
    fun aRepeatedSignalNumberIsDropped() {
        val sequencer = CallSignalSequencer()
        val first = sequencer.accept(1)
        val again = sequencer.accept(1)
        val second = sequencer.accept(2)
        assertTrue(first)
        assertFalse(again)
        assertTrue(second)
    }

    /** A camera switch lost in a socket gap comes back from the server, possibly after a newer one. */
    @Test
    fun anOlderMediaStateDoesNotUndoANewerOne() {
        val order = CallMediaOrder()
        val first = order.isNewer(4, "DEV-A")
        val newer = order.isNewer(9, "dev-a")
        val older = order.isNewer(6, "dev-a")
        val same = order.isNewer(9, "dev-a")
        val otherDevice = order.isNewer(2, "dev-b")
        assertTrue(first)
        assertTrue(newer)
        assertFalse(older)
        assertFalse(same)
        assertTrue(otherDevice)
    }

    /** The server's copy of the other device's latest media state rides on `GET /calls/:id` and the heartbeat. */
    @Test
    fun aKeptMediaStateDecodesAndOpens() {
        val secret = secret()
        val caller = CallSignalKeys.identity(secret, callId, CallCrypto.Role.Caller)
        val callee = CallSignalKeys.identity(secret, callId, CallCrypto.Role.Callee)
        val payload = CallCrypto.seal(CallSignal.plaintext(CallSignal.Media(mic = true, camera = true), 7), caller.send, callId, "media_state")
        val device = UUID.randomUUID()
        fun json(kept: Boolean) = """
            {"id":"$callId","caller_user_id":"${UUID.randomUUID()}","caller_device_id":"${device.toString().uppercase()}",
             "caller_username":"alice","callee_user_id":"${UUID.randomUUID()}","callee_device_id":"${UUID.randomUUID()}",
             "callee_username":"bob","modality":"voice","status":"active","protocol":2,
             "created_at":"2026-09-26T10:00:00Z","answered_at":"2026-09-26T10:00:05Z"
             ${if (kept) ""","peer_media_state":{"from_device_id":"$device","payload":"$payload"}""" else ""}}
        """.trimIndent()
        val call = apiJson.decodeFromString(CallDto.serializer(), json(kept = true))
        assertEquals(CallModality.Voice, call.callModality)
        val kept = call.peerMediaState!!
        assertEquals(device, kept.fromDeviceId)
        val opened = CallCrypto.open(kept.payload, callee.receive, callId, "media_state")
        val parsed = CallSignal.parse(opened, "media_state")
        assertEquals(7, parsed.n)
        assertEquals(CallSignal.Media(mic = true, camera = true), parsed.signal)

        // Everywhere else the field is absent.
        assertNull(apiJson.decodeFromString(CallDto.serializer(), json(kept = false)).peerMediaState)
    }
}

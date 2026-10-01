package de.corespace.shroud.core.calls

import android.content.Intent
import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.calls.crypto.CallCryptoException
import de.corespace.shroud.core.calls.crypto.CallSignalKeys
import de.corespace.shroud.core.calls.signal.CallSignal
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.IceServerDto
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Scripted two-sided calls through the real [CallController]s of two (or three) devices, a fake
 * server with the rules of `routes/calls.rs` and fake engines (calls §12 `CallControllerTest`; the
 * web's `controller.selftest.ts` is the model). Every timing is the iOS one (CC, calls §4.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private fun world(eager: Boolean = false, test: suspend TestScope.(CallWorld) -> Unit) = runTest {
        val world = CallWorld(testScheduler, eager)
        try {
            test(world)
        } finally {
            world.close()
        }
    }

    /** Places a call from [from] to [to] and lets the ring arrive. */
    private fun TestScope.place(world: CallWorld, from: CallWorld.Device, to: CallWorld.Device, modality: CallModality = CallModality.Voice) {
        backgroundScope.launch { from.controller.startCall(to.userId, to.username, modality) }
        world.settle()
    }

    private fun TestScope.accept(world: CallWorld, device: CallWorld.Device) {
        backgroundScope.launch { device.controller.acceptIncoming() }
        world.settle()
    }

    private fun linked(world: CallWorld, a: CallWorld.Device, b: CallWorld.Device) {
        a.engine.peer = b.engine
        b.engine.peer = a.engine
        world.settle()
    }

    /** Ring → accept → offer/answer → ICE → connected, on two devices. */
    private fun TestScope.connect(world: CallWorld, modality: CallModality = CallModality.Voice): Pair<CallWorld.Device, CallWorld.Device> {
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        linked(world, alice, bob)
        place(world, alice, bob, modality)
        accept(world, bob)
        world.advance(CallController.ICE_BATCH_MS)
        return alice to bob
    }

    private fun opens(payload: String, key: ByteArray, callId: UUID, type: String): ByteArray? = try {
        CallCrypto.open(payload, key, callId, type)
    } catch (_: CallCryptoException) {
        null
    }

    // ---- 1. a voice call, answered, connected, hung up ----

    @Test
    fun aVoiceCallRingsIsAnsweredConnectsAndHangsUp() = world { world -> voiceCallScenario(world) }

    /** The same script with every launch running at once, as `Dispatchers.Main.immediate` does on the main thread. */
    @Test
    fun theSameCallWithEagerDispatch() = world(eager = true) { world -> voiceCallScenario(world) }

    private fun TestScope.voiceCallScenario(world: CallWorld) {
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        linked(world, alice, bob)
        var mediaStarting = 0
        backgroundScope.launch { alice.controller.callMediaStarting.collect { mediaStarting++ } }
        world.settle()

        place(world, alice, bob)
        assertEquals(CallPhase.OutgoingRinging, alice.phase)
        assertEquals(1, mediaStarting)
        val callId = world.calls.keys.single()
        assertEquals(callId, alice.call!!.id)
        assertTrue(alice.socket.held)
        assertEquals(listOf("outgoing", "media:false"), alice.system.log)
        assertEquals(true, alice.engine.startedOffering)
        assertEquals(1, alice.engine.offers)
        // The ring reached bob over the socket.
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        assertEquals("alice", bob.call!!.peerUsername)
        assertEquals(world.aliceId, bob.call!!.peerUserId)
        assertEquals(listOf("incoming"), bob.system.log)
        assertTrue(bob.socket.held)
        assertTrue(alice.controller.isInCall && bob.controller.isInCall)

        accept(world, bob)
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(listOf("accept:bob"), world.requests.filter { it.startsWith("accept") })
        assertEquals(CallPhase.Active, alice.phase)
        assertEquals(CallPhase.Active, bob.phase)
        assertNotNull(alice.call!!.startedAt)
        assertEquals("connected", bob.call!!.connectionState)
        assertTrue("connected" in alice.system.log && "connected" in bob.system.log)
        assertTrue(bob.system.log.contains("answer"))

        // The setup went under the identity keys with ephemeral keys; everything after under the forward keys.
        val identity = Vector.secret
        val fromAlice = CallSignalKeys.identity(identity, callId, CallCrypto.Role.Caller)
        val fromBob = CallSignalKeys.identity(identity, callId, CallCrypto.Role.Callee)
        val offer = world.signals.first { it.type == "sdp_offer" }
        val offerSignal = CallSignal.parse(opens(offer.payload, fromAlice.send, callId, "sdp_offer")!!, "sdp_offer").signal as CallSignal.Offer
        assertNotNull(offerSignal.ephemeral)
        assertFalse(offerSignal.restart)
        assertFalse("candidates leave the sealed SDP", offerSignal.sdp.contains("a=candidate"))
        val answer = world.signals.first { it.type == "sdp_answer" }
        val answerSignal = CallSignal.parse(opens(answer.payload, fromBob.send, callId, "sdp_answer")!!, "sdp_answer").signal as CallSignal.Answer
        assertNotNull(answerSignal.ephemeral)
        val afterSetup = world.signals.filter { it.type == "ice_candidate" || it.type == "media_state" }
        assertTrue(afterSetup.count { it.type == "ice_candidate" } >= 2)
        assertTrue(afterSetup.count { it.type == "media_state" } >= 2)
        for (signal in afterSetup) {
            val key = if (signal.from == alice.deviceId) fromAlice.send else fromBob.send
            assertNull("${signal.type} opened under the identity key", opens(signal.payload, key, callId, signal.type))
        }
        // Each side got the other's candidates, once, without the empty one; the offer reached bob without its candidates.
        assertEquals(listOf("candidate:alice-a 1 udp 1 10.0.0.1 9 typ host", "candidate:alice-b 1 udp 1 10.0.0.2 9 typ host"), bob.engine.remoteCandidates.map { it.candidate })
        assertEquals(2, alice.engine.remoteCandidates.size)
        assertFalse(bob.engine.receivedOffers.single().contains("a=candidate"))
        // Both media states said "screen" (this app can show one), so Share is possible.
        assertTrue(alice.call!!.canShareScreen)
        assertTrue(alice.call!!.canVideo)

        backgroundScope.launch { alice.controller.hangup() }
        world.settle()
        assertEquals(CallPhase.Ending, alice.phase)
        assertEquals("Call ended", alice.call!!.endedText)
        assertEquals(CallPhase.Ending, bob.phase)
        assertEquals("Call ended", bob.call!!.endedText)
        assertEquals("ended", world.calls.getValue(callId).status)
        assertEquals("hangup", world.calls.getValue(callId).reason)
        assertFalse(alice.socket.held || bob.socket.held)
        assertTrue(alice.engine.closes > 0 && bob.engine.closes > 0)
        // The history shows the call at once, connected.
        val row = alice.controller.history.value.recent.first()
        assertEquals(callId, row.id)
        assertTrue(row.connected)
        assertEquals(CallEndCause.Local, alice.system.ended.single().second)
        assertEquals(CallEndCause.Remote, bob.system.ended.single().second)

        world.advance(CallController.ENDING_VISIBLE_MS)
        assertNull(alice.call)
        assertNull(bob.call)
        assertEquals(1, alice.callScreensClosed)
        assertFalse(alice.controller.isInCall)
    }

    // ---- 2. every event twice (Redis fan-out): duplicate numbers are dropped ----

    @Test
    fun everyEventTwiceAndADuplicateSignalNumberIsDropped() = world { world ->
        world.duplicateEvents = true
        val (alice, bob) = connect(world)
        assertEquals(CallPhase.Active, alice.phase)
        assertEquals(CallPhase.Active, bob.phase)
        assertEquals(1, bob.engine.answers)
        assertEquals(1, alice.engine.receivedAnswers.size)
        assertEquals(2, alice.engine.remoteCandidates.size)
        assertEquals(2, bob.engine.remoteCandidates.size)
    }

    // ---- 3. early events and early signals ----

    /** The answer overtakes the ring's own response: `call.accepted` waits until `POST /calls` returns (CC:437-440, 992-998). */
    @Test
    fun anAnswerThatOvertakesTheRingsResponseIsReplayed() = world { world ->
        world.createDelayMs = 1_000
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        linked(world, alice, bob)
        place(world, alice, bob)
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        accept(world, bob)
        // Alice still dials: the event is kept.
        assertEquals(CallPhase.OutgoingRinging, alice.phase)
        world.advance(1_000)
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(CallPhase.Active, alice.phase)
        assertEquals(CallPhase.Active, bob.phase)
    }

    /** Signals that reach the callee before it has keys wait and are taken after the answer (CC:968-971, 1333-1344). */
    @Test
    fun earlySignalsAreReplayedAfterAccept() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        world.deliverRings = false
        val callId = UUID.randomUUID()
        // The server rang bob (a push), and alice's offer reached bob's socket before bob answered.
        world.calls[callId] = world.ServerCall(callId, alice, world.bobId, CallModality.Voice, world.clock.now())
        bob.controller.handleCallPush(CallPush(NotificationKind.Call, callId, world.aliceId, "alice", CallPush.Source.UnifiedPush))
        world.settle()
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        val keys = CallSignalKeys.identity(Vector.secret, callId, CallCrypto.Role.Caller)
        val ephemeral = Primitives.x25519Public(ByteArray(32) { 0x20 })
        val sdp = "v=0\r\na=fingerprint:sha-256 AA:BB:CC:ALICE\r\n"
        val payload = CallCrypto.seal(CallSignal.plaintext(CallSignal.Offer(sdp, false, Bytes.of(ephemeral)), 1), keys.send, callId, "sdp_offer")
        bob.socket.emit(RealtimeEvent.CallSignal(callId, world.aliceId, Ids.wire(alice.deviceId), "sdp_offer", payload))
        world.settle()
        assertEquals(0, bob.engine.answers)
        accept(world, bob)
        assertEquals(1, bob.engine.answers)
        assertEquals(sdp, bob.engine.receivedOffers.single())
        // Bob answered under the identity key with its own ephemeral key.
        val answer = world.signals.single { it.type == "sdp_answer" }
        val opened = CallCrypto.open(answer.payload, CallSignalKeys.identity(Vector.secret, callId, CallCrypto.Role.Callee).send, callId, "sdp_answer")
        assertNotNull((CallSignal.parse(opened, "sdp_answer").signal as CallSignal.Answer).ephemeral)
    }

    // ---- 4. no media within 30 s ----

    @Test
    fun noMediaWithinThirtySecondsCouldNotConnect() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        alice.engine.autoConnect = false
        bob.engine.autoConnect = false
        linked(world, alice, bob)
        place(world, alice, bob)
        accept(world, bob)
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(CallPhase.Connecting, alice.phase)
        assertEquals(CallPhase.Connecting, bob.phase)
        world.advance(CallController.CONNECT_MS - CallController.ICE_BATCH_MS - 1)
        assertEquals(CallPhase.Connecting, bob.phase)
        world.advance(1)
        assertEquals("Couldn't connect", bob.call!!.endedText)
        assertEquals("Couldn't connect", alice.call!!.endedText)
        val callId = world.calls.keys.single()
        assertEquals("ended", world.calls.getValue(callId).status)
        assertFalse(alice.controller.history.value.recent.single().connected)
        assertEquals(CallEndCause.Error, bob.system.ended.single().second)
    }

    // ---- 5. answered on another device ----

    @Test
    fun anotherOfTheCalleesDevicesAnswers() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val phone = world.device("bob-phone", world.bobId)
        val laptop = world.device("bob-laptop", world.bobId)
        linked(world, alice, laptop)
        place(world, alice, phone)
        assertEquals(CallPhase.IncomingRinging, phone.phase)
        assertEquals(CallPhase.IncomingRinging, laptop.phase)
        accept(world, laptop)
        assertEquals(CallPhase.Ending, phone.phase)
        assertEquals("Answered on another device", phone.call!!.endedText)
        assertEquals(CallEndCause.AnsweredElsewhere, phone.system.ended.single().second)
        assertEquals("answered_elsewhere", phone.controller.history.value.recent.single().status)
        assertFalse(world.requests.any { it.endsWith(":bob-phone") && !it.startsWith("get") && !it.startsWith("heartbeat") })
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(CallPhase.Active, alice.phase)
        assertEquals(CallPhase.Active, laptop.phase)
    }

    // ---- 6. the server ended the call: a signal's CALL_ENDED reconciles ----

    @Test
    fun aSignalToAnEndedCallReconciles() = world { world ->
        val (alice, _) = connect(world)
        val callId = world.calls.keys.single()
        world.endSilently(callId, "ended", "connection_lost")
        backgroundScope.launch { alice.controller.toggleMute() }
        world.settle()
        assertEquals(CallPhase.Ending, alice.phase)
        assertEquals("Connection lost", alice.call!!.endedText)
    }

    /** iOS: a reconcile that cannot reach the server ends nothing; the heartbeat and the server limit decide (CC:1487-1502). */
    @Test
    fun aReconcileWithoutAnAnswerEndsNothing() = world { world ->
        val (alice, _) = connect(world)
        val callId = world.calls.keys.single()
        world.endSilently(callId, "ended", "connection_lost")
        world.offline = true
        backgroundScope.launch { alice.controller.toggleMute() }
        world.settle()
        assertEquals(CallPhase.Active, alice.phase)
        // The next heartbeat hears the server.
        world.advance(CallController.HEARTBEAT_MS)
        assertEquals("Connection lost", alice.call!!.endedText)
    }

    /** A pushed name is trimmed and cut to 64 characters (calls §2.7). */
    @Test
    fun aPushedNameIsTrimmedAndCut() = world { world ->
        val bob = world.device("bob", world.bobId)
        val callId = UUID.randomUUID()
        bob.controller.handleCallPush(CallPush(NotificationKind.Call, callId, world.aliceId, "  " + "x".repeat(80) + " ", CallPush.Source.UnifiedPush))
        assertEquals("x".repeat(64), bob.call!!.peerUsername)
        // The confirming read fails (the server does not know it): the ring goes on (CC:802) until
        // the ring check hears the 404.
        world.settle()
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        world.advance(CallController.RING_CHECK_MS)
        assertEquals("Call ended", bob.call!!.endedText)
    }

    /** A media state lost in a socket gap comes back on the heartbeat (CC:1866-1878). */
    @Test
    fun aLostMediaStateIsCaughtUpByTheHeartbeat() = world { world ->
        val (alice, bob) = connect(world)
        world.dropSignalType = "media_state"
        backgroundScope.launch { bob.controller.toggleMute() }
        world.settle()
        assertFalse(alice.call!!.remoteMicMuted)
        world.advance(CallController.HEARTBEAT_MS)
        assertTrue(alice.call!!.remoteMicMuted)
        assertFalse(bob.engine.micEnabled)
    }

    // ---- 7. ICE restarts and the 10 s gate ----

    @Test
    fun aFailedLinkRestartsAtMostEveryTenSecondsOverTheRelay() = world { world ->
        val (alice, bob) = connect(world)
        alice.engine.report("failed")
        world.settle()
        assertTrue(alice.call!!.reconnecting)
        assertEquals(1, alice.engine.preferRelays)
        assertEquals(1, alice.engine.restartOffers)
        assertEquals(2, bob.engine.answers)
        val restart = world.signals.filter { it.type == "sdp_offer" }.last()
        assertNull("a restart offer goes under the forward key", opens(restart.payload, CallSignalKeys.identity(Vector.secret, restart.callId, CallCrypto.Role.Caller).send, restart.callId, "sdp_offer"))

        alice.engine.report("failed")
        world.settle()
        assertEquals(1, alice.engine.restartOffers)
        world.advance(CallController.RESTART_GAP_MS)
        assertEquals(2, alice.engine.restartOffers)

        alice.engine.report("connected")
        world.settle()
        assertFalse(alice.call!!.reconnecting)
        assertEquals(CallPhase.Active, alice.phase)
    }

    /** The callee asks the caller for the restart (`renegotiate`). */
    @Test
    fun theCalleeAsksTheCallerToRestart() = world { world ->
        val (alice, bob) = connect(world)
        bob.engine.report("disconnected")
        world.settle()
        assertTrue(bob.call!!.reconnecting)
        assertEquals(0, alice.engine.restartOffers)
        world.advance(CallController.GRACE_MS)
        assertTrue(world.signals.any { it.type == "renegotiate" })
        assertEquals(1, alice.engine.restartOffers)
    }

    @Test
    fun thirtySecondsOfABrokenLinkLoseTheCall() = world { world ->
        val (alice, bob) = connect(world)
        alice.engine.autoConnect = false
        bob.engine.autoConnect = false
        alice.engine.report("failed")
        world.settle()
        world.advance(CallController.RECONNECT_LIMIT_MS)
        assertEquals("Connection lost", alice.call!!.endedText)
        assertEquals("Call ended", bob.call!!.endedText)
    }

    // ---- 8. always relay ----

    @Test
    fun relayUnavailableOnAnswerEndsHereOnlyAndTellsNobody() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        bob.security.setAlwaysRelayCalls(true)
        place(world, alice, bob)
        accept(world, bob)
        assertEquals(CallTexts.RELAY_UNAVAILABLE, bob.call!!.endedText)
        assertEquals(CallTexts.RELAY_UNAVAILABLE, bob.controller.lastError.value)
        assertTrue(world.requests.none { it.endsWith(":bob") && (it.startsWith("accept") || it.startsWith("reject") || it.startsWith("hangup")) })
        assertEquals("ringing", world.calls.values.single().status)
        assertEquals(CallPhase.OutgoingRinging, alice.phase)
        world.advance(CallController.ERROR_VISIBLE_MS)
        assertNull(bob.call)
    }

    @Test
    fun relayUnavailableWhenPlacingNeverRings() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        alice.security.setAlwaysRelayCalls(true)
        place(world, alice, bob)
        assertEquals(CallTexts.RELAY_UNAVAILABLE, alice.controller.lastError.value)
        assertTrue(world.requests.none { it.startsWith("create") })
        assertNull(bob.call)
        // With a relay the call is relayed from the start.
        world.advance(CallController.ERROR_VISIBLE_MS)
        world.iceServers = listOf(IceServerDto(listOf("stun:s.example:3478")), IceServerDto(listOf("turn:t.example:3478"), "1:u", "c"))
        place(world, alice, bob)
        assertEquals(true, alice.engine.startedRelayOnly)
        assertEquals(CallPhase.IncomingRinging, bob.phase)
    }

    // ---- 9. declined, cancelled, no answer, busy ----

    @Test
    fun aDeclineClosesTheRingAtOnceAndTheCallerHearsDeclined() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        backgroundScope.launch { bob.controller.rejectIncoming() }
        world.settle()
        assertNull(bob.call)
        assertEquals(CallEndCause.Rejected, bob.system.ended.single().second)
        assertEquals("Declined", alice.call!!.endedText)
        assertEquals("rejected", world.calls.values.single().status)
        assertEquals("rejected", bob.controller.history.value.recent.single().status)
    }

    @Test
    fun aCallerWhoGivesUpLeavesAMissedCall() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        backgroundScope.launch { alice.controller.hangup() }
        world.settle()
        assertNull(alice.call)
        assertEquals("Missed call", bob.call!!.endedText)
        assertEquals("cancelled", world.calls.values.single().status)
        assertEquals(CallEndCause.Missed, bob.system.ended.single().second)
    }

    @Test
    fun noAnswerAfterSeventyFiveSecondsAndAMissedCallAfterSeventy() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        world.advance(CallController.RING_IN_MS)
        assertEquals("Missed call", bob.call!!.endedText)
        assertEquals("ringing", world.calls.values.single().status)
        assertEquals(CallPhase.OutgoingRinging, alice.phase)
        world.advance(CallController.RING_OUT_MS - CallController.RING_IN_MS)
        assertEquals("No answer", alice.call!!.endedText)
        assertEquals("cancelled", world.calls.values.single().status)
        // Heartbeats ran while it rang (every 10 s), and bob checked the ring.
        assertTrue(world.requests.count { it == "heartbeat:alice" } >= 7)
        assertTrue(world.requests.count { it == "get:bob" } >= 6)
    }

    @Test
    fun aBusyCalleeSaysSo() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        world.createError = ApiError.Server("CALL_BUSY", "busy", 409)
        place(world, alice, bob)
        assertEquals("bob is on another call.", alice.controller.lastError.value)
        assertEquals("bob is on another call.", alice.call!!.endedText)
        assertTrue(world.requests.none { it.startsWith("hangup") })
        world.advance(CallController.ERROR_VISIBLE_MS)
        assertNull(alice.call)
        assertFalse(alice.socket.held)
    }

    @Test
    fun aSecondCallIsRefusedWhileOneRuns() = world { world ->
        val (alice, bob) = connect(world)
        val carol = UUID.randomUUID()
        // A new call ends the open one first (CC:553-558), then places the new one.
        backgroundScope.launch { alice.controller.startCall(carol, "carol", CallModality.Voice) }
        world.settle()
        assertEquals("Call ended", bob.call!!.endedText)
        assertEquals(carol, alice.call!!.peerUserId)
        assertEquals(CallPhase.OutgoingRinging, alice.phase)
    }

    // ---- 10. pushes ----

    @Test
    fun aPushRingIsConfirmedNamedAndAnswered() = world { world ->
        world.deliverRings = false
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        linked(world, alice, bob)
        place(world, alice, bob)
        assertNull(bob.call)
        val callId = world.calls.keys.single()
        bob.controller.handleCallPush(CallPush(NotificationKind.VideoCall, callId, null, null, CallPush.Source.UnifiedPush))
        assertEquals(CallTexts.INCOMING_CALL_PLACEHOLDER, bob.call!!.peerUsername)
        world.settle()
        // GET /calls/{id}: still ringing, now named, the caller's user and the call's modality.
        assertEquals("alice", bob.call!!.peerUsername)
        assertEquals(world.aliceId, bob.call!!.peerUserId)
        assertEquals(CallModality.Voice, bob.call!!.modality)
        assertTrue(bob.system.log.contains("update:alice:false"))
        // The socket ring arriving late changes nothing.
        bob.socket.emit(RealtimeEvent.CallRing(world.calls.getValue(callId).dto(bob)))
        world.settle()
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        accept(world, bob)
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(CallPhase.Active, bob.phase)
    }

    @Test
    fun aCallEndedPushStopsTheRing() = world { world ->
        world.deliverRings = false
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        val callId = world.calls.keys.single()
        bob.controller.handleCallPush(CallPush(NotificationKind.Call, callId, world.aliceId, "alice", CallPush.Source.BackgroundSocket))
        world.settle()
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        // The socket misses the end: only the push says so.
        world.muted += bob
        backgroundScope.launch { alice.controller.hangup() }
        world.settle()
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        bob.controller.handleCallPush(CallPush(NotificationKind.CallEnded, callId, world.aliceId, null, CallPush.Source.UnifiedPush))
        world.settle()
        assertEquals("Missed call", bob.call!!.endedText)
        assertTrue(bob.system.ended.isNotEmpty())
        assertTrue(bob.controller.callWasFinished(callId))
        // A ring push for it after that shows nothing; a missed-call push neither: this phone showed it.
        world.advance(CallController.ENDING_VISIBLE_MS)
        bob.controller.handleCallPush(CallPush(NotificationKind.Call, callId, world.aliceId, "alice", CallPush.Source.UnifiedPush))
        bob.controller.handleCallPush(CallPush(NotificationKind.MissedCall, callId, world.aliceId, "alice", CallPush.Source.UnifiedPush))
        assertNull(bob.call)
        assertTrue(bob.system.missed.isEmpty())
        // A missed call this phone never saw is posted.
        val other = UUID.randomUUID()
        bob.controller.handleCallPush(CallPush(NotificationKind.MissedCall, other, world.aliceId, "alice", CallPush.Source.UnifiedPush))
        assertEquals(listOf(other), bob.system.missed)
    }

    // ---- 11. permissions and secrets ----

    @Test
    fun noMicrophoneNoCall() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        alice.permissions.microphone = false
        place(world, alice, bob)
        assertEquals(CallTexts.MIC_DENIED_CALL, alice.controller.lastError.value)
        assertNull(alice.call)
        assertTrue(world.requests.isEmpty())

        alice.permissions.microphone = true
        bob.permissions.microphone = false
        place(world, alice, bob)
        accept(world, bob)
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        assertEquals(CallTexts.MIC_DENIED_ANSWER, bob.call!!.notice)
        assertTrue(world.requests.none { it.startsWith("accept") })
    }

    @Test
    fun lockedChatsKeepTheRingAndAKeyChangeEndsItHereOnly() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        bob.peers.stored = false
        bob.peers.deriveError = CallSecretException.ChatsLocked
        accept(world, bob)
        assertEquals(CallPhase.IncomingRinging, bob.phase)
        assertEquals("Open Shroud and unlock your chats to connect this call.", bob.call!!.notice)
        assertTrue(world.requests.none { it.startsWith("accept") })

        bob.peers.deriveError = PeerIdentityChangedException()
        accept(world, bob)
        assertEquals(CallTexts.KEY_CHANGED, bob.call!!.endedText)
        assertTrue(world.requests.none { it.endsWith(":bob") && !it.startsWith("get") })
        assertEquals("ringing", world.calls.values.single().status)
    }

    // ---- 12. video, speaker, screens ----

    @Test
    fun aVoiceCallBecomesAVideoCallAndBackWithoutANewOffer() = world { world ->
        val (alice, bob) = connect(world)
        val offers = alice.engine.offers
        backgroundScope.launch { alice.controller.toggleVideo() }
        world.settle()
        assertTrue(alice.call!!.isVideoEnabled)
        assertTrue(alice.call!!.speakerOn)
        assertTrue(alice.system.speakerOn)
        assertFalse(bob.call!!.remoteCameraOff)
        assertEquals(1, bob.engine.awaitRemoteFrames)
        assertTrue(bob.system.log.contains("update:alice:true"))
        backgroundScope.launch { alice.controller.toggleVideo() }
        world.settle()
        assertFalse(alice.call!!.isVideoEnabled)
        assertFalse(alice.call!!.speakerOn)
        assertTrue(bob.call!!.remoteCameraOff)
        assertEquals(offers, alice.engine.offers)
        // Refused camera.
        alice.permissions.camera = false
        backgroundScope.launch { alice.controller.toggleVideo() }
        world.settle()
        assertEquals(CallTexts.CAMERA_DENIED, alice.call!!.notice)
        world.advance(CallController.NOTICE_MS)
        assertNull(alice.call!!.notice)
    }

    @Test
    fun aVideoCallWithoutACameraGoesOnWithSound() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        alice.engine.cameraAvailable = false
        place(world, alice, bob, CallModality.Video)
        assertFalse(alice.call!!.isVideoEnabled)
        assertEquals(CallTexts.CAMERA_OFF_ON_START, alice.call!!.notice)
        assertTrue(alice.call!!.speakerOn)
    }

    @Test
    fun theCameraPausesInTheBackground() = world { world ->
        val (alice, bob) = connect(world, CallModality.Video)
        assertTrue(alice.engine.isCameraOn)
        assertFalse(bob.call!!.remoteCameraOff)
        alice.controller.onAppVisible(false)
        world.settle()
        assertFalse(alice.engine.isCameraOn)
        assertTrue(alice.call!!.isVideoEnabled)
        assertTrue(bob.call!!.remoteCameraOff)
        alice.controller.onAppVisible(true)
        world.settle()
        assertTrue(alice.engine.isCameraOn)
        assertFalse(bob.call!!.remoteCameraOff)
    }

    @Test
    fun aScreenIsSharedNextToTheCameraAndTheSystemCanStopIt() = world { world ->
        val (alice, bob) = connect(world)
        assertEquals(ShareAction.RequestConsent, alice.controller.toggleScreenShare())
        // A refused consent says nothing.
        alice.controller.onScreenCaptureConsent(null)
        assertNull(alice.call!!.notice)
        alice.controller.onScreenCaptureConsent(ScreenCaptureGrant(-1, Intent()))
        assertTrue(alice.engine.screenOn)
        assertTrue(alice.call!!.screenShareStarting)
        assertEquals(listOf("screen-on"), alice.system.log.filter { it.startsWith("screen") })
        alice.engine.listener!!.onScreenFirstFrame()
        world.settle()
        assertTrue(alice.call!!.isSharingScreen)
        assertTrue(bob.call!!.remoteSharingScreen)
        assertEquals(1, bob.engine.awaitRemoteScreenFrames)
        assertTrue(bob.call!!.speakerOn)
        // The system's Stop chip.
        alice.engine.listener!!.onScreenCaptureEnded()
        world.settle()
        assertFalse(alice.call!!.isSharingScreen)
        assertFalse(alice.engine.screenOn)
        assertEquals(CallTexts.SCREEN_NO_LONGER_SHARED, alice.call!!.notice)
        assertFalse(bob.call!!.remoteSharingScreen)
        assertFalse("their screen gone, the sound comes back to the ear", bob.call!!.speakerOn)
        // Share again, and Stop from the app: no notice.
        alice.controller.onScreenCaptureConsent(ScreenCaptureGrant(-1, Intent()))
        alice.engine.listener!!.onScreenFirstFrame()
        world.settle()
        assertEquals(ShareAction.None, alice.controller.toggleScreenShare())
        world.settle()
        assertFalse(alice.call!!.isSharingScreen)
        assertEquals(listOf("screen-on", "screen-off", "screen-on", "screen-off"), alice.system.log.filter { it.startsWith("screen") })
        // Quality: kept, and handed to the engine.
        val quality = ScreenShareQuality(ScreenShareQuality.Resolution.Source, 60)
        alice.controller.setScreenShareQuality(quality)
        assertEquals(quality, alice.engine.screenQuality)
        assertEquals(quality, alice.preferences.screenShareQuality.value)
        assertEquals(quality, alice.controller.ui.value.screenShareQuality)
    }

    @Test
    fun shareSaysWhyItCannotWhileRinging() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        place(world, alice, bob)
        assertEquals(ShareAction.None, alice.controller.toggleScreenShare())
        assertEquals(CallTexts.SHARE_AFTER_CONNECT, alice.call!!.notice)
    }

    // ---- 13. verification, safety, clearing ----

    @Test
    fun aCertificateThatIsNotTheSealedOneEndsTheCall() = world { world ->
        val alice = world.device("alice", world.aliceId)
        val bob = world.device("bob", world.bobId)
        alice.engine.remoteFingerprint = "00:11:22"
        linked(world, alice, bob)
        place(world, alice, bob)
        accept(world, bob)
        world.advance(CallController.ICE_BATCH_MS)
        assertEquals(CallTexts.NOT_VERIFIED, alice.call!!.endedText)
        assertEquals("Call ended", bob.call!!.endedText)
    }

    @Test
    fun theSafetyNumberIsConfirmedOnTheCallScreen() = world { world ->
        val (alice, _) = connect(world)
        assertFalse(alice.call!!.safetyVerified)
        assertEquals("12345 67890", alice.controller.safetyNumberForActiveCall())
        alice.controller.confirmSafety()
        assertTrue(alice.call!!.safetyVerified)
        assertEquals(listOf(world.bobId), alice.peers.confirmed)
    }

    @Test
    fun signingOutHangsUpAJoinedCallAndForgetsTheHistory() = world { world ->
        val (alice, bob) = connect(world)
        alice.controller.clearLocalState()
        world.settle()
        assertNull(alice.call)
        assertTrue(alice.controller.history.value.recent.isEmpty())
        assertFalse(alice.socket.held)
        assertTrue(alice.system.log.contains("clear"))
        assertEquals("Call ended", bob.call!!.endedText)
    }

    @Test
    fun theHistoryShowsWhatTheServerKeeps() = world { world ->
        val (alice, _) = connect(world)
        world.advance(65_000)
        backgroundScope.launch { alice.controller.hangup() }
        world.settle()
        backgroundScope.launch { alice.controller.refreshHistory() }
        world.settle()
        val state = alice.controller.history.value
        assertTrue(state.hasLoaded)
        assertFalse(state.hasMore)
        val row = state.recent.single()
        assertTrue(row.isOutgoing)
        assertEquals("bob", row.peerUsername)
        assertEquals(65L, row.duration!!.seconds)
        assertEquals("1:05", CallHistory.durationLabel(row.duration))
    }

    @Test
    fun theOlderPageCursorIsOneMillisecondLaterInMilliseconds() {
        val cursor = java.time.Instant.parse("2026-09-30T09:41:00.123456Z")
        assertEquals("2026-09-30T09:41:00.124Z", CallController.formatBefore(cursor))
        assertEquals("2026-09-30T09:41:01.000Z", CallController.formatBefore(java.time.Instant.parse("2026-09-30T09:41:00.999999Z")))
    }

    @Test
    fun noEngineNoCall() = runTest {
        val world = CallWorld(testScheduler)
        try {
            val alice = world.device("alice", world.aliceId)
            val bob = world.device("bob", world.bobId)
            val bare = CallController(
                scope = world.scope,
                backend = NoBackend,
                socket = FakeSocket(),
                session = alice.session,
                peers = alice.peers,
                preferences = alice.preferences,
                permissions = alice.permissions,
                clock = world.clock,
            )
            backgroundScope.launch { bare.startCall(bob.userId, "bob", CallModality.Voice) }
            world.settle()
            assertEquals(CallTexts.COULD_NOT_START, bare.lastError.value)
            assertNull(bare.ui.value.active)
        } finally {
            world.close()
        }
    }

    private object NoBackend : CallsBackend {
        override suspend fun iceServers(token: String): List<IceServerDto> = error("unexpected")
        override suspend fun createCall(token: String, peerUserId: UUID, modality: CallModality): CallDto = error("unexpected")
        override suspend fun call(token: String, callId: UUID): CallDto = error("unexpected")
        override suspend fun history(token: String, limit: Int, before: String?): List<CallDto> = emptyList()
        override suspend fun accept(token: String, callId: UUID): CallDto = error("unexpected")
        override suspend fun reject(token: String, callId: UUID): CallDto = error("unexpected")
        override suspend fun hangup(token: String, callId: UUID): CallDto = error("unexpected")
        override suspend fun heartbeat(token: String, callId: UUID): CallDto = error("unexpected")
        override suspend fun signal(token: String, callId: UUID, signalType: String, payload: String): Unit = error("unexpected")
    }
}

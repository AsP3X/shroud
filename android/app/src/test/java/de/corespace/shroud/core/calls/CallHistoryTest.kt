package de.corespace.shroud.core.calls

import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The Calls tab's rows, from `GET /calls` as the server sends them: whose side, who the other
 * person is, how it ended and how long the two talked — iOS `CallHistoryTests`
 * (`ios/shroudTests/CallHistoryTests.swift`, all 12).
 */
class CallHistoryTest {
    private val me = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val them = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val myPhone = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val myLaptop = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val theirPhone = UUID.fromString("00000000-0000-0000-0000-0000000000d3")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private fun call(
        caller: UUID,
        callerDevice: UUID,
        callee: UUID,
        calleeDevice: UUID?,
        status: String,
        reason: String? = null,
        answered: String? = null,
        ended: String? = "2026-09-30T09:45:00.123456Z",
        calleeName: String? = "anna",
    ): CallDto {
        val fields = mutableListOf(
            """"id": "11111111-1111-1111-1111-111111111111"""",
            """"caller_user_id": "$caller"""",
            """"caller_device_id": "$callerDevice"""",
            """"caller_username": "${if (caller == me) "me" else "anna"}"""",
            """"callee_user_id": "$callee"""",
            """"modality": "video"""",
            """"status": "$status"""",
            """"protocol": 2""",
            """"created_at": "2026-09-30T09:41:00.5Z"""",
        )
        fields += if (calleeName != null) """"callee_username": "$calleeName"""" else """"callee_username": null"""
        if (calleeDevice != null) fields += """"callee_device_id": "$calleeDevice""""
        if (reason != null) fields += """"ended_reason": "$reason""""
        if (answered != null) fields += """"answered_at": "$answered""""
        if (ended != null) fields += """"ended_at": "$ended""""
        return json.decodeFromString(CallDto.serializer(), "{" + fields.joinToString(",") + "}")
    }

    @Test
    fun outgoingCallNamesTheCalleeAndCountsTheTalk() {
        val dto = call(me, myPhone, them, theirPhone, "ended", reason = "hangup", answered = "2026-09-30T09:41:48.123456Z")
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = null)!!
        assertTrue(row.isOutgoing)
        assertEquals(them, row.peerUserId)
        assertEquals("anna", row.peerUsername)
        assertFalse(row.peerDeleted)
        assertEquals(CallModality.Video, row.modality)
        assertEquals("ended", row.status)
        assertTrue(row.connected)
        assertEquals(192.0, row.duration!!.toNanos() / 1e9, 0.001)
        assertEquals(dto.createdAt, row.at)
    }

    @Test
    fun incomingCallNamesTheCaller() {
        val dto = call(them, theirPhone, me, null, "missed", reason = "timeout")
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = null)!!
        assertFalse(row.isOutgoing)
        assertEquals(them, row.peerUserId)
        assertEquals("anna", row.peerUsername)
        assertEquals("missed", row.status)
        assertFalse(row.connected)
        assertNull(row.duration)
    }

    @Test
    fun callsPlacedFromAnotherOfOurDevicesAreListed() {
        val dto = call(me, myLaptop, them, theirPhone, "ended", reason = "hangup", answered = "2026-09-30T09:42:00Z")
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = null)!!
        assertTrue(row.isOutgoing)
        assertTrue(row.connected)
        assertNotNull(row.duration)
    }

    @Test
    fun ringingAndRunningCallsWaitUntilTheyEnd() {
        val ringing = call(them, theirPhone, me, null, "ringing", ended = null)
        val active = call(me, myPhone, them, theirPhone, "active", answered = "2026-09-30T09:42:00Z", ended = null)
        assertNull(CallHistory.recentCall(ringing, me, myPhone, connectedHere = null))
        assertNull(CallHistory.recentCall(active, me, myPhone, connectedHere = null))
    }

    @Test
    fun aDeclineReadsDeclined() {
        val dto = call(me, myPhone, them, null, "missed", reason = "declined")
        assertEquals("rejected", CallHistory.recentCall(dto, me, myPhone, connectedHere = null)!!.status)
    }

    /** Answered, but the media never connected on this phone: not a completed call. */
    @Test
    fun thisPhoneKnowsBetterWhenItRanTheCall() {
        val dto = call(me, myPhone, them, theirPhone, "ended", reason = "hangup", answered = "2026-09-30T09:42:00Z")
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = false)!!
        assertFalse(row.connected)
        assertNull(row.duration)
    }

    /** This phone gave up on the ring, and the laptop then answered: the laptop's call talked. */
    @Test
    fun anotherDevicesCallIgnoresThisPhonesFailure() {
        val dto = call(them, theirPhone, me, myLaptop, "ended", reason = "hangup", answered = "2026-09-30T09:42:00Z")
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = false)!!
        assertTrue(row.connected)
        assertNotNull(row.duration)
    }

    @Test
    fun aDeletedAccountHasNoNameAndNoCallBack() {
        val dto = call(me, myPhone, them, null, "cancelled", reason = "cancelled", calleeName = null)
        val row = CallHistory.recentCall(dto, me, myPhone, connectedHere = null)!!
        assertTrue(row.peerDeleted)
        assertEquals("Deleted account", row.peerUsername)
    }

    private fun recent(peer: UUID, minutesAgo: Double) = RecentCall(
        id = UUID.randomUUID(),
        peerUserId = peer,
        peerUsername = if (peer == them) "anna" else "ben",
        modality = CallModality.Voice,
        isOutgoing = true,
        status = "ended",
        connected = true,
        at = Instant.ofEpochMilli((1_790_000_000_000.0 - minutesAgo * 60_000).toLong()),
    )

    /** Anna, Anna, Ben, Anna: the first two share a section, Ben's call ends it. */
    @Test
    fun backToBackCallsWithOnePersonShareASection() {
        val ben = UUID.randomUUID()
        val list = listOf(recent(them, 0.0), recent(them, 5.0), recent(ben, 10.0), recent(them, 15.0))
        val runs = CallHistory.runs(list)
        assertEquals(listOf(2, 1, 1), runs.map { it.calls.size })
        assertEquals(list[0], runs[0].latest)
        assertEquals(list[1].id, runs[0].id)
        assertEquals(listOf(list[3]), runs[2].calls)
    }

    /** A section spans an hour at most, counted from its newest call. */
    @Test
    fun aSectionSpansAnHourAtMost() {
        val list = listOf(recent(them, 0.0), recent(them, 40.0), recent(them, 60.0), recent(them, 61.0), recent(them, 100.0))
        val runs = CallHistory.runs(list)
        assertEquals(listOf(3, 2), runs.map { it.calls.size })
        assertEquals(list[3], runs[1].latest)
    }

    /** Red "Missed" is for their calls we never took; our own unanswered calls and declines are not. */
    @Test
    fun onlyTheirUntakenCallsCountAsMissed() {
        fun call(outgoing: Boolean, status: String) = RecentCall(
            id = UUID.randomUUID(), peerUserId = them, peerUsername = "anna", modality = CallModality.Voice,
            isOutgoing = outgoing, status = status, connected = false, at = Instant.now(),
        )
        assertTrue(CallHistory.isMissed(call(outgoing = false, status = "missed")))
        assertTrue(CallHistory.isMissed(call(outgoing = false, status = "cancelled")))
        assertFalse(CallHistory.isMissed(call(outgoing = false, status = "rejected")))
        assertFalse(CallHistory.isMissed(call(outgoing = true, status = "missed")))
        assertFalse(CallHistory.isMissed(call(outgoing = true, status = "cancelled")))
    }

    @Test
    fun durationsReadLikeTheCallTimer() {
        assertEquals("0:00", CallHistory.durationLabel(0.0))
        assertEquals("0:42", CallHistory.durationLabel(42.9))
        assertEquals("4:12", CallHistory.durationLabel(252.0))
        assertEquals("1:02:03", CallHistory.durationLabel(3723.0))
        assertEquals("4:12", CallHistory.durationLabel(Duration.ofMillis(252_999)))
    }

    /** The row's words (`CallsView.swift:403-472`); spoken forms for TalkBack. */
    @Test
    fun outcomesAndStatusLines() {
        val talked = RecentCall(
            id = UUID.randomUUID(), peerUserId = them, peerUsername = "anna", modality = CallModality.Voice,
            isOutgoing = true, status = "ended", connected = true, duration = Duration.ofSeconds(252), at = Instant.now(),
        )
        assertEquals("Outgoing voice · 4:12", CallHistory.statusLine(talked))
        assertEquals("4 minutes, 12 seconds", CallHistory.outcome(talked, spoken = true))
        assertEquals("Completed", CallHistory.outcome(talked.copy(duration = null)))
        assertEquals("Failed", CallHistory.outcome(talked.copy(connected = false, duration = null)))
        assertEquals("not connected", CallHistory.outcome(talked.copy(connected = false, duration = null), spoken = true))
        assertEquals("Incoming video · Missed", CallHistory.statusLine(talked.copy(isOutgoing = false, modality = CallModality.Video, status = "missed")))
        assertEquals("No answer", CallHistory.outcome(talked.copy(status = "missed")))
        assertEquals("Declined", CallHistory.outcome(talked.copy(status = "rejected")))
        assertEquals("Cancelled", CallHistory.outcome(talked.copy(status = "cancelled")))
        assertEquals("Busy", CallHistory.outcome(talked.copy(status = "busy")))
        assertEquals("Other device", CallHistory.outcome(talked.copy(status = "answered_elsewhere")))
        assertEquals("1 hour, 2 minutes, 3 seconds", CallHistory.spokenDuration(Duration.ofSeconds(3723)))
        assertEquals("0 seconds", CallHistory.spokenDuration(Duration.ZERO))
        // Never prints the name.
        assertFalse(talked.toString().contains("anna"))
    }
}

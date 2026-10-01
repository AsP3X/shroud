package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.signal.CallEndReason
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.net.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The end-of-call and error lines (calls §4.14, §4.15): iOS `CallEndReason`
 * (`CallSignal.swift:200-258`), `callErrorText` (`CallController.swift:2063-2090`) and the web's
 * `logic.selftest.ts` tables where they agree with iOS.
 */
class CallTextsTest {
    /** status, reason, what the caller sees, what the callee sees (`logic.selftest.ts` + calls §4.14). */
    @Test
    fun theStatusTable() {
        val table = listOf(
            listOf("rejected", "rejected", "Declined", null),
            listOf("missed", "declined", "Declined", null),
            listOf("missed", "timeout", "No answer", "Missed call"),
            listOf("cancelled", "cancelled", null, "Missed call"),
            listOf("cancelled", "connection_lost", "Connection lost", "Missed call"),
            listOf("ended", "hangup", "Call ended", "Call ended"),
            listOf("ended", "connection_lost", "Connection lost", "Connection lost"),
            listOf("busy", null, "Busy", "Busy"),
            listOf("ended", null, "Call ended", "Call ended"),
            listOf("something-new", null, "Call ended", "Call ended"),
        )
        for ((status, reason, caller, callee) in table) {
            assertEquals("caller on $status/$reason", caller, CallEndReason.from(status!!, reason, isOutgoing = true)?.announcement)
            assertEquals("callee on $status/$reason", callee, CallEndReason.from(status, reason, isOutgoing = false)?.announcement)
        }
        assertNull(CallEndReason.from("ringing", null, isOutgoing = false))
        assertNull(CallEndReason.from("active", "hangup", isOutgoing = true))
    }

    /** `CallEndReason.title` (`CallSignal.swift:213-226`). */
    @Test
    fun titles() {
        assertEquals("Call ended", CallEndReason.Ended.title)
        assertEquals("Call ended", CallEndReason.Cancelled.title)
        assertEquals("Declined", CallEndReason.Declined.title)
        assertEquals("No answer", CallEndReason.NoAnswer.title)
        assertEquals("Missed call", CallEndReason.Missed.title)
        assertEquals("Busy", CallEndReason.Busy.title)
        assertEquals("Answered on another device", CallEndReason.AnsweredElsewhere.title)
        assertEquals("Declined on another device", CallEndReason.DeclinedElsewhere.title)
        assertEquals("Connection lost", CallEndReason.ConnectionLost.title)
        assertEquals("Couldn't connect", CallEndReason.CouldNotConnect.title)
        assertEquals("Exactly this.", CallEndReason.Failed("Exactly this.").title)
        assertNull(CallEndReason.Cancelled.announcement)
        assertNull(CallEndReason.DeclinedElsewhere.announcement)
        assertEquals("Couldn't connect", CallEndReason.CouldNotConnect.announcement)
    }

    /** calls §4.15 — exact strings, curly vs straight apostrophes included. */
    @Test
    fun errorTexts() {
        fun server(code: String, status: Int) = ApiError.Server(code, "x", status)
        assertEquals("ana is on another call.", CallTexts.callErrorText(server("CALL_BUSY", 409), "ana"))
        assertEquals("You’re already in a call.", CallTexts.callErrorText(server("CALL_IN_PROGRESS", 409), "ana"))
        assertEquals("You can’t call ana.", CallTexts.callErrorText(server("FORBIDDEN", 403), "ana"))
        assertEquals("Too many calls. Try again in a moment.", CallTexts.callErrorText(server("RATE_LIMITED", 429), "ana"))
        assertEquals("Couldn’t reach Shroud. Check your connection.", CallTexts.callErrorText(ApiError.Transport("offline"), "ana"))
        assertEquals("Couldn’t start the call.", CallTexts.callErrorText(server("VALIDATION_ERROR", 400), "ana"))
        assertEquals("Couldn’t start the call.", CallTexts.callErrorText(ApiError.Decoding("?"), "ana"))
        assertEquals("Couldn’t start the call.", CallTexts.callErrorText(IllegalStateException("boom"), "ana"))
        assertEquals(
            "“Always relay calls” is on, but this server has no relay. Turn it off in Privacy and Security to call directly.",
            CallTexts.callErrorText(CallRelayUnavailableException(), "ana"),
        )
        assertEquals("Open Shroud and unlock your chats to connect this call.", CallTexts.callErrorText(CallSecretException.ChatsLocked, "ana"))
        assertEquals(
            "This contact's encryption key changed. Verify their safety number before calling.",
            CallTexts.callErrorText(PeerIdentityChangedException(), "ana"),
        )
        // The offer task's own line keeps iOS's straight apostrophe (CC:1009).
        assertEquals("Couldn't start the call.", CallTexts.OFFER_FAILED)
        assertEquals("This call couldn't be verified.", CallTexts.NOT_VERIFIED)
        assertEquals("Couldn't connect", CallTexts.COULD_NOT_CONNECT)
        assertEquals("Screen sharing isn’t available on this phone.", CallTexts.shareUnavailableOnDevice("phone"))
        assertEquals("Allow microphone access for Shroud in Settings to call.", CallTexts.MIC_DENIED_CALL)
        assertEquals("Allow microphone access for Shroud in Settings to answer calls.", CallTexts.MIC_DENIED_ANSWER)
    }

    /** The speaking meter (`SpeakingIndicatorView.swift:92-97`; calls §12 `SpeakingMeterTest`). */
    @Test
    fun speakingMeter() {
        assertEquals(0.25f, SpeakingMeter.display(0.01f), 0.0001f)
        assertEquals(1f, SpeakingMeter.display(0.31622776f), 0.0001f)
        assertEquals(1f, SpeakingMeter.display(1f), 0f)
        assertEquals(0f, SpeakingMeter.display(0f), 0f)
        assertEquals(0f, SpeakingMeter.display(-1f), 0f)
        assertEquals(0f, SpeakingMeter.display(Float.NaN), 0f)
        assertEquals(0f, SpeakingMeter.display(0.001f), 0.0001f)
        assertEquals(0.12f, SpeakingMeter.GATE, 0f)
    }
}

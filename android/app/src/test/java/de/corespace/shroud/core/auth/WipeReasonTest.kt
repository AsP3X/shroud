package de.corespace.shroud.core.auth

import de.corespace.shroud.core.devices.DeviceNoun
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Why the overlay says it is clearing this phone (`ios/shroudTests/DeviceWipeControllerTests.swift`
 * with the Android device nouns; web-parity §22.7).
 */
class WipeReasonTest {
    @Test
    fun eachReasonLeadsTheSubtitle() {
        assertEquals("", WipeReason.Logout.lead(DeviceNoun.PHONE))
        assertEquals("Your session ended. ", WipeReason.SessionEnded.lead(DeviceNoun.PHONE))
        assertEquals("This phone was removed from your account. ", WipeReason.Removed.lead(DeviceNoun.PHONE))
        assertEquals("This tablet was removed from your account. ", WipeReason.Removed.lead(DeviceNoun.TABLET))
        assertEquals("This phone was removed from your account. ", WipeReason.Removed.lead())
    }

    /** A removal reaches the wipe as an ended session; the server's `DEVICE_REMOVED` turns it into the removal. */
    @Test
    fun onlyAnEndedSessionBecomesARemoval() {
        assertEquals(WipeReason.Removed, WipeReason.after(WipeReason.SessionEnded, ServerSessionOutcome.Removed))
        assertEquals(WipeReason.SessionEnded, WipeReason.after(WipeReason.SessionEnded, ServerSessionOutcome.Ended))
        assertEquals(WipeReason.SessionEnded, WipeReason.after(WipeReason.SessionEnded, ServerSessionOutcome.Offline))
        assertEquals(WipeReason.Logout, WipeReason.after(WipeReason.Logout, ServerSessionOutcome.Removed))
        assertEquals(WipeReason.Removed, WipeReason.after(WipeReason.Removed, ServerSessionOutcome.Offline))
    }

    /** The whole subtitle while the wipe runs (web-parity §22.7, phone wording). */
    @Test
    fun theRemovalSubtitleReadsAsOneSentencePair() {
        assertEquals(
            "This phone was removed from your account. Removing everything Shroud stored for @x.",
            "${WipeReason.Removed.lead(DeviceNoun.PHONE)}Removing everything Shroud stored for @x.",
        )
    }

    @Test
    fun theStepsReadAsOnIos() {
        assertEquals(
            listOf("Signing out", "Messages", "Photos, videos & voice", "Encryption keys", "Settings & caches", "Checking nothing is left"),
            WipeStep.entries.map { it.title },
        )
    }
}

package de.corespace.shroud.ui.contacts

import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContactRequestStatus
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.UserCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The contacts screens' words and the rules that pick them (iOS `ContactsView`, `ContactProfileView`, `MyQRCodeSheet`, `QRCodeScannerView`). */
class ContactsCopyTest {
    private val requester = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    private val me = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")

    @Test
    fun theSortCapsuleUsesAnEnDashAndSpeaksTheOrder() {
        // `ContactsView.swift:69-73`.
        assertEquals("A–Z", ContactsCopy.sortLabel(ascending = true))
        assertEquals("Z–A", ContactsCopy.sortLabel(ascending = false))
        assertEquals("Sorted A to Z", ContactsCopy.sortSpokenLabel(ascending = true))
        assertEquals("Sorted Z to A", ContactsCopy.sortSpokenLabel(ascending = false))
        assertEquals("Reverses the order", ContactsCopy.SORT_HINT)
    }

    @Test
    fun aRequestIsNamedByItsHandleElseByTheIdAsIosPrintsIt() {
        // `request.user?.username ?? request.fromUserId.uuidString` (`ContactsView.swift:187`).
        assertEquals("jane", ContactsCopy.requestName(request(user = UserCardDto(requester, "jane"))))
        assertEquals("3F2C8A9E-5B1D-4C7A-9E2F-8D6B4A1C0E7F", ContactsCopy.requestName(request(user = null)))
        assertEquals("wants to connect", ContactsCopy.WANTS_TO_CONNECT)
    }

    @Test
    fun presenceFallsBackPerScreen() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val zone: ZoneId = ZoneOffset.UTC
        // Nothing heard yet: "contact" in the list, "Shroud contact" on the profile (`:272-275`, `:35-37`).
        assertEquals("contact", ContactStatus.row(null, now, zone, Locale.UK, true))
        assertEquals("Shroud contact", ContactStatus.profile(null, now, zone, Locale.UK, true))
        val online = PresenceDto(requester, online = true)
        assertEquals("online", ContactStatus.row(online, now, zone, Locale.UK, true))
        assertEquals("online", ContactStatus.profile(online, now, zone, Locale.UK, true))
        val seen = PresenceDto(requester, online = false, lastSeenAt = Instant.parse("2026-10-02T09:41:00Z"))
        assertEquals("last seen 09:41", ContactStatus.row(seen, now, zone, Locale.UK, true))
        assertEquals("offline", ContactStatus.profile(PresenceDto(requester, online = false), now, zone, Locale.UK, true))
    }

    @Test
    fun presenceLinesFollowTheSpecVectorsInTwelveHourEnglish() {
        // contacts §4.4 (`ChatListFormatting.swift:45-66`): UTC, en_US, 12-hour, now 2026-09-30 15:00.
        val now = Instant.parse("2026-09-30T15:00:00Z")
        val zone: ZoneId = ZoneOffset.UTC
        // JDK/ICU English puts a narrow no-break space before "AM"; the words are what is pinned.
        fun plain(text: String) = text.replace(' ', ' ').replace(' ', ' ')
        fun row(presence: PresenceDto?) = plain(ContactStatus.row(presence, now, zone, Locale.US, is24h = false))
        fun profile(presence: PresenceDto?) = plain(ContactStatus.profile(presence, now, zone, Locale.US, is24h = false))
        assertEquals("contact", row(null))
        assertEquals("Shroud contact", profile(null))
        assertEquals("online", row(PresenceDto(requester, online = true)))
        assertEquals("offline", profile(PresenceDto(requester, online = false)))
        assertEquals("last seen 9:41 AM", row(PresenceDto(requester, online = false, lastSeenAt = Instant.parse("2026-09-30T09:41:00Z"))))
        assertEquals("last seen yesterday", profile(PresenceDto(requester, online = false, lastSeenAt = Instant.parse("2026-09-29T23:59:00Z"))))
        assertEquals("last seen Sep 27, 2026", row(PresenceDto(requester, online = false, lastSeenAt = Instant.parse("2026-09-27T12:00:00Z"))))
    }

    @Test
    fun blockAndUnblockSayWhatHappens() {
        // `ContactProfileView.swift:417, 432-447, 533`.
        assertEquals("Block jane", ContactsCopy.blockRowTitle(isBlocked = false, name = "jane"))
        assertEquals("Unblock jane", ContactsCopy.blockRowTitle(isBlocked = true, name = "jane"))
        assertEquals("Block jane?", ContactsCopy.blockConfirmTitle(isBlocked = false, name = "jane"))
        assertEquals("Unblock jane?", ContactsCopy.blockConfirmTitle(isBlocked = true, name = "jane"))
        assertEquals(
            "They can't message you or send contact requests. You'll also stop being contacts.",
            ContactsCopy.blockConfirmMessage(isBlocked = false),
        )
        assertEquals(
            "They can send you a contact request again. Your existing messages are unaffected.",
            ContactsCopy.blockConfirmMessage(isBlocked = true),
        )
        assertEquals("jane blocked", ContactsCopy.blockDone(blocked = true, name = "jane"))
        assertEquals("jane unblocked", ContactsCopy.blockDone(blocked = false, name = "jane"))
    }

    @Test
    fun deletingTheChatSpellsOutBothScopes() {
        // `ContactProfileView.swift:481-500`.
        assertEquals("Delete chat with jane?", ContactsCopy.deleteConfirmTitle("jane"))
        assertEquals("Delete for me and jane", ContactsCopy.deleteForBoth("jane"))
        assertEquals("Delete for me", ContactsCopy.DELETE_FOR_ME)
        assertEquals(
            "Deleting for both unsends your messages in jane's chat. Their own messages stay unless they allow chats to be cleared for them. They stay in your contacts.",
            ContactsCopy.deleteConfirmMessage("jane"),
        )
        assertEquals("Offline.", ContactsCopy.deleteFailure(ChatDeleteOutcome.Failed("Offline.")))
        assertNull(ContactsCopy.deleteFailure(ChatDeleteOutcome.ClearedForBoth))
        assertNull(ContactsCopy.deleteFailure(ChatDeleteOutcome.UnsentForPeer))
        assertNull(ContactsCopy.deleteFailure(ChatDeleteOutcome.ClearedForMe))
    }

    @Test
    fun theProfileConfirmationsCarryIosTitlesAndMessages() {
        // The three `confirmationDialog`s of `ContactProfileView.swift:393-405, 431-448, 481-500` as action sheets (P13c).
        assertEquals("Trust the new key?", ProfileConfirm.TrustNewKey.title("jane"))
        assertEquals(
            "Only do this if you confirmed this contact's safety number through another channel.",
            ProfileConfirm.TrustNewKey.message("jane"),
        )
        assertEquals("Block jane?", ProfileConfirm.Block(blocked = false).title("jane"))
        assertEquals(ContactsCopy.BLOCK_MESSAGE, ProfileConfirm.Block(blocked = false).message("jane"))
        assertEquals("Unblock jane?", ProfileConfirm.Block(blocked = true).title("jane"))
        assertEquals(ContactsCopy.UNBLOCK_MESSAGE, ProfileConfirm.Block(blocked = true).message("jane"))
        assertEquals("Delete chat with jane?", ProfileConfirm.DeleteChat.title("jane"))
        assertEquals(ContactsCopy.deleteConfirmMessage("jane"), ProfileConfirm.DeleteChat.message("jane"))
    }

    @Test
    fun theMuteToastFollowsTheChatList() {
        // `ContactProfileView.swift:303`.
        assertEquals("Notifications on", ContactsCopy.muteDone(unmuted = true, label = null))
        assertEquals("Muted until 14:30", ContactsCopy.muteDone(unmuted = false, label = "Muted until 14:30"))
        assertEquals("Muted", ContactsCopy.muteDone(unmuted = false, label = null))
        assertEquals("Mute", ContactsCopy.muteTileTitle(isMuted = false))
        assertEquals("Unmute", ContactsCopy.muteTileTitle(isMuted = true))
        assertEquals("A chat can be muted once it has messages.", ContactsCopy.MUTE_NEEDS_CHAT)
    }

    @Test
    fun theIdentityCopyIsIosVerbatim() {
        // `ContactProfileView.swift:368-404`: straight apostrophes, as iOS.
        assertEquals("Encryption key changed", ContactsCopy.KEY_CHANGED_TITLE)
        assertEquals(
            "This contact's identity key no longer matches the one saved on this device. Compare safety numbers in person before trusting new messages.",
            ContactsCopy.KEY_CHANGED_BODY,
        )
        assertEquals("I verified this contact", ContactsCopy.I_VERIFIED)
        assertEquals("Trust the new key?", ContactsCopy.TRUST_TITLE)
        assertEquals("Only do this if you confirmed this contact's safety number through another channel.", ContactsCopy.TRUST_MESSAGE)
        assertEquals("Trust new key", ContactsCopy.TRUST_ACTION)
        assertEquals("New encryption key saved", ContactsCopy.NEW_KEY_SAVED)
        assertEquals("contact.safetyNumber", SAFETY_NUMBER_TAG)
    }

    @Test
    fun theScannerAndQrCopyIsVerbatim() {
        assertEquals("Point at their Shroud QR code", ContactsCopy.SCANNER_HINT)
        assertEquals("No camera available. Paste their link instead.", ContactsCopy.SCANNER_NO_CAMERA)
        assertEquals("Camera access is off", ContactsCopy.CAMERA_OFF_TITLE)
        assertEquals("Shroud needs the camera to scan a contact’s QR code. Nothing is recorded or sent.", ContactsCopy.CAMERA_OFF_BODY)
        assertEquals("Friends can scan this QR, open the link, or type your share code to add you.", ContactsCopy.MY_QR_HINT)
        assertEquals("Loading your code…", ContactsCopy.LOADING_YOUR_CODE)
        assertEquals("Loading share code…", ContactsCopy.LOADING_SHARE_CODE)
        assertEquals("Could not create QR", ContactsCopy.QR_FAILED)
    }

    private fun request(user: UserCardDto?) = ContactRequestDto(
        id = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"),
        fromUserId = requester,
        toUserId = me,
        status = ContactRequestStatus.PENDING,
        createdAt = Instant.parse("2026-10-01T08:00:00Z"),
        user = user,
    )
}

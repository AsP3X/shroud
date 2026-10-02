package de.corespace.shroud.ui.contacts

import android.view.ViewGroup
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The contact's profile on a scripted engine (W3-CONTACTS-UI acceptance; iOS
 * `ContactProfileView.swift`; contacts §5.8): the arrival order, presence and typing, the safety
 * number with Mark as Verified and Trust new key, block and unblock (core's sentences verbatim),
 * deleting the chat, the mute rules and a call that could not start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactProfileTest {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = FakeAppClock(wallMillis = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli())
    private val ports = FakeContactsPorts(actionScope, clock)
    private val jane = ContactsFixtures.JANE
    private val hosts = ArrayList<ComposeHarness>()
    private val left = ArrayList<String>()

    @After
    fun tearDown() {
        actionScope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    private fun profile(onChatDeleted: (() -> Unit)? = { left += "chatDeleted" }): ComposeHarness {
        ports.identities.numbers[jane] = ContactsFixtures.NUMBER
        val ui = ComposeHarness {
            OverlayHost { ContactProfile(ports, jane, "Jane Cooper", onBack = { left += "back" }, onChatDeleted = onChatDeleted) }
        }
        hosts += ui
        ui.settle()
        return ui
    }

    @Test
    fun arrivingRefreshesPresenceBlocksAndTheKeyInThatOrder() {
        profile()
        // `.task` (`ContactProfileView.swift:70-74`), one after the other.
        assertEquals(listOf("refreshPresence($jane)", "refreshBlocks", "refreshIdentity($jane)"), ports.log)
    }

    @Test
    fun theStatusLineReadsPresenceThenTyping() {
        val ui = profile()
        // Before the server answered (`:33-37`).
        assertTrue(ui.shows("Shroud contact"))
        ports.contacts.presence.value = mapOf(jane to PresenceDto(jane, online = false, lastSeenAt = Instant.parse("2026-09-27T12:00:00Z")))
        ui.settle()
        assertTrue(ui.describe(), ui.nodesWithText("last seen ").isNotEmpty())
        ports.contacts.presence.value = mapOf(jane to PresenceDto(jane, online = true))
        ui.settle()
        assertTrue(ui.shows("online"))
        ports.peerActivities.value = mapOf(jane to ChatPeerActivity.Typing)
        ui.settle()
        assertFalse("typing takes the status line's place", ui.shows("online"))
    }

    @Test
    fun markAsVerifiedConfirmsTheNumberOnce() {
        val ui = profile()
        assertTrue(ui.shows(ContactsFixtures.NUMBER))
        ui.tapText("Mark as Verified")
        assertEquals(listOf("confirmSafety($jane)"), ports.calls("confirmSafety"))
        assertTrue(ui.shows("Verified"))
        assertFalse(ui.shows("Mark as Verified"))
    }

    @Test
    fun trustingTheNewKeyAsksFirstThenSavesIt() {
        ports.identities.change(jane)
        val ui = profile()
        assertTrue(ui.shows("Encryption key changed"))
        assertFalse("no verifying while the change waits", ui.shows("Mark as Verified"))

        ui.tapText("I verified this contact")
        assertTrue(ui.shows("Trust the new key?"))
        assertTrue(ui.shows("Only do this if you confirmed this contact's safety number through another channel."))
        ui.tapText("Trust new key")

        assertEquals(listOf("acceptNewIdentity($jane)"), ports.calls("acceptNewIdentity"))
        assertTrue(ui.describe(), ui.shows("New encryption key saved"))
        assertFalse(ui.shows("Encryption key changed"))
        assertTrue(ui.shows("Mark as Verified"))
    }

    @Test
    fun cancellingTheTrustSheetChangesNothing() {
        ports.identities.change(jane)
        val ui = profile()
        ui.tapText("I verified this contact")
        ui.tapText("Cancel")
        assertEquals(emptyList<String>(), ports.calls("acceptNewIdentity"))
        assertTrue(ui.shows("Encryption key changed"))
    }

    @Test
    fun aRefusedBlockShowsCoresSentenceVerbatim() {
        ports.contacts.blockError = "You can't block yourself."
        val ui = profile()
        ui.tapText("Block Jane Cooper")
        assertTrue(ui.shows("Block Jane Cooper?"))
        assertTrue(ui.shows("They can't message you or send contact requests. You'll also stop being contacts."))
        ui.tapText("Block")

        assertEquals(listOf("block($jane)"), ports.calls("block("))
        assertTrue(ui.describe(), ui.shows("You can't block yourself."))
        assertTrue(ui.shows("Block Jane Cooper"))
    }

    @Test
    fun unblockingSaysSoAndTheRowTurnsBack() {
        ports.contacts.blocked.value = listOf(BlockItemDto(jane, "Jane Cooper", Instant.EPOCH))
        val ui = profile()
        ui.tapText("Unblock Jane Cooper")
        assertTrue(ui.shows("Unblock Jane Cooper?"))
        assertTrue(ui.shows("They can send you a contact request again. Your existing messages are unaffected."))
        ui.tapText("Unblock")

        assertEquals(listOf("unblock($jane)"), ports.calls("unblock("))
        assertTrue(ui.describe(), ui.shows("Jane Cooper unblocked"))
        assertTrue(ui.shows("Block Jane Cooper"))
    }

    @Test
    fun aFailedDeleteStaysWithCoresSentence() {
        ports.deleteOutcome = ChatDeleteOutcome.Failed("Could not connect to the server.")
        val ui = profile()
        ui.tapText("Delete Chat")
        assertTrue(ui.shows("Delete chat with Jane Cooper?"))
        ui.tapText("Delete for me and Jane Cooper")

        assertEquals(listOf("deleteConversation($jane, ${ConversationDeleteScope.Everyone})"), ports.calls("deleteConversation"))
        assertTrue(ui.describe(), ui.shows("Could not connect to the server."))
        assertEquals(emptyList<String>(), left)
    }

    @Test
    fun aDeletedChatLeavesTheThreadOrPops() {
        val ui = profile()
        ui.tapText("Delete Chat")
        ui.tapText("Delete for me")
        assertEquals(listOf("deleteConversation($jane, ${ConversationDeleteScope.Me})"), ports.calls("deleteConversation"))
        assertEquals(listOf("chatDeleted"), left)

        left.clear()
        val alone = profile(onChatDeleted = null)
        alone.tapText("Delete Chat")
        alone.tapText("Delete for me")
        assertEquals("without a host to leave, the profile pops (`:514-518`)", listOf("back"), left)
    }

    @Test
    fun aChatWithoutMessagesCannotBeMuted() {
        ports.canMuteChats = false
        val ui = profile()
        ui.tapLabel("Mute")
        assertTrue(ui.shows("A chat can be muted once it has messages."))
        assertEquals(emptyList<String>(), ports.calls("muteChat"))
    }

    @Test
    fun muteOffersTheDurationsAndSaysUntilWhen() {
        val ui = profile()
        ui.tapLabel("Mute")
        MuteDuration.entries.forEach { assertTrue(ui.describe(), ui.shows(it.title)) }
        ui.tapText("For 1 Hour")
        assertEquals(listOf("muteChat($jane, ${MuteDuration.Hour})"), ports.calls("muteChat"))
        assertTrue(ui.describe(), ui.nodesWithText("Muted until ").isNotEmpty())
        ui.node("Unmute")
    }

    @Test
    fun aMutedChatUnmutesAtOnce() {
        ports.mutes[jane] = ChatMuteDto(until = null)
        val ui = profile()
        assertTrue(ui.shows("Muted"))
        ui.tapLabel("Unmute")
        assertEquals(listOf("unmuteChat($jane)"), ports.calls("unmuteChat"))
        assertTrue(ui.describe(), ui.shows("Notifications on"))
        ui.node("Mute")
    }

    @Test
    fun aRefusedMuteShowsCoresSentence() {
        ports.muteError = "Could not connect to the server."
        val ui = profile()
        ui.tapLabel("Mute")
        ui.tapText("For 1 Day")
        assertTrue(ui.describe(), ui.shows("Could not connect to the server."))
        ui.node("Mute")
    }

    @Test
    fun aCallThatCannotStartSaysWhy() {
        ports.callFailure = "Allow microphone access for Shroud in Settings to call."
        val ui = profile()
        ui.tapLabel("Video")
        assertEquals(listOf("startCall($jane, Jane Cooper, ${CallModality.Video})"), ports.calls("startCall"))
        assertTrue(ui.describe(), ui.shows("Allow microphone access for Shroud in Settings to call."))
    }

    @Test
    fun editAndSearchAreComingSoon() {
        val ui = profile()
        ui.tapLabel("Search")
        assertTrue(ui.shows("Search coming soon"))
    }
}

package de.corespace.shroud.ui.contacts

import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.MainTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The Contacts tab on a scripted engine (W3-CONTACTS-UI acceptance; iOS `ContactsView.swift`,
 * `AddContactSheet.swift`; contacts §5.1–5.4, §5.10): what the tab asks the engine on arrival, that
 * an App Link only pre-fills Add Contact and never sends, that core's sentences reach the screen as
 * they are, and that the contact-request notifications close while the list is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsScreenTest {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = FakeAppClock(wallMillis = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli())
    private val ports = FakeContactsPorts(actionScope, clock)
    private val navigation = ContactsTestNavigation()
    private val hosts = ArrayList<ComposeHarness>()

    @After
    fun tearDown() {
        actionScope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    private fun tab(): ComposeHarness {
        val ui = ComposeHarness { OverlayHost { ContactsTab(ports, query = "", onQueryChange = {}, navigation = navigation) } }
        hosts += ui
        ui.settle()
        return ui
    }

    private val invite = "https://shroud.corespace.de/u/ABCD234567"

    @Test
    fun arrivingRefreshesTheRosterAndFetchesAMissingShareCode() {
        ports.session.value = ports.session.value!!.copy(shareCode = null)
        tab()
        // `.task` (`ContactsView.swift:178-183`): the roster first, then the session.
        assertEquals(listOf("refresh(force=false)", "validateSession"), ports.log.take(2))
    }

    @Test
    fun aSessionWithItsShareCodeIsNotValidatedAgain() {
        val ui = tab()
        assertEquals(listOf("refresh(force=false)"), ports.log)
        assertTrue(ui.shows("ABCD234567"))
        assertTrue(ui.shows(invite))
    }

    @Test
    fun anAppLinkOnlyPrefillsAddContactAndWaitsForTheTap() {
        ports.contacts.pendingInvite.value = invite
        val ui = tab()

        // The invite is taken (it opens the sheet once) and sits in the field.
        assertNull(ports.contacts.pendingInvite.value)
        assertTrue(ui.describe(), ui.exactly(invite).any { SemanticsActions.SetText in it.config })
        assertTrue(ui.shows("Add Contact"))
        // Time passes: nothing is sent by itself (contacts §5.10, P10c).
        repeat(4) { ui.settle() }
        assertEquals(emptyList<String>(), ports.calls("add("))
        assertFalse("Add is live for the user's tap", ui.isDisabled("Add"))

        ui.tapText("Add")
        assertEquals(listOf("add($invite)"), ports.calls("add("))
        // The sheet closed; the tab confirms, as iOS (`ContactsView.swift:166-172`).
        assertTrue(ui.describe(), ui.shows("Request sent to jane"))
        assertFalse(ui.shows("Add Contact"))
    }

    @Test
    fun aSecondInviteReplacesTheFieldWithoutSending() {
        val ui = tab()
        ports.contacts.pendingInvite.value = invite
        ui.settle()
        ports.contacts.pendingInvite.value = "https://shroud.corespace.de/u/WXYZ234567"
        ui.settle()
        assertTrue(ui.describe(), ui.shows("https://shroud.corespace.de/u/WXYZ234567"))
        assertEquals(emptyList<String>(), ports.calls("add("))
    }

    @Test
    fun aFailedAddKeepsTheSheetWithCoresSentence() {
        ports.contacts.addOutcome = { AddContactOutcome.Failed("You can't add yourself.") }
        val ui = tab()
        ui.tapLabel("Add contact")
        assertTrue("Add is off with an empty field", ui.isDisabled("Add"))
        ui.typeInField("noah")
        ui.tapText("Add")

        assertEquals(listOf("add(noah)"), ports.calls("add("))
        assertTrue(ui.describe(), ui.shows("You can't add yourself."))
        assertTrue("still in the sheet", ui.shows("Add Contact"))
        assertFalse(ui.shows("Request sent to noah"))
    }

    @Test
    fun addSaysAddingWhileTheRequestRunsAndAMutualRequestIsAdded() {
        val gate = CompletableDeferred<Unit>()
        ports.contacts.addGate = gate
        ports.contacts.addOutcome = { AddContactOutcome.Added("jane") }
        val ui = tab()
        ui.tapLabel("Add contact")
        ui.typeInField("jane")
        ui.tapText("Add")
        assertTrue(ui.describe(), ui.isDisabled("Adding…"))

        gate.complete(Unit)
        ui.settle()
        assertTrue(ui.describe(), ui.shows("jane added"))
    }

    @Test
    fun aRefusedAnswerShowsCoresSentenceAndTheButtonsWaitForIt() {
        val gate = CompletableDeferred<Unit>()
        ports.contacts.answerGate = gate
        ports.contacts.answerError = "Contact request not found."
        ports.contacts.incomingRequests.value = listOf(ContactsFixtures.request(ContactsFixtures.ZOE, "zoe"))
        ports.contacts.listState.value = ContactsListState(hasLoaded = true)
        val ui = tab()
        ui.node("zoe, wants to connect")

        ui.tapText("Accept")
        // Both buttons wait for the answer (`ContactsView.swift:214-229`); a second tap does nothing.
        assertTrue(ui.isDisabled("Accept"))
        assertTrue(ui.isDisabled("Reject"))
        assertEquals(1, ports.calls("accept(").size)

        gate.complete(Unit)
        ui.settle()
        assertTrue(ui.describe(), ui.shows("Contact request not found."))
        assertFalse(ui.isDisabled("Accept"))
    }

    @Test
    fun requestNotificationsCloseWhileTheListIsOnScreen() {
        ports.contacts.listState.value = ContactsListState(hasLoaded = true)
        val ui = tab()
        assertEquals(1, ports.notificationClears)

        // A new request while looking at the list: its notification goes too.
        ports.contacts.incomingRequests.value = listOf(ContactsFixtures.request(ContactsFixtures.ZOE, "zoe"))
        ui.settle()
        assertEquals(2, ports.notificationClears)

        // In the background, or on another tab, the shade keeps them.
        ports.appPhase.value = AppPhase.Background
        ports.contacts.incomingRequests.value = emptyList()
        ui.settle()
        assertEquals(2, ports.notificationClears)
        ports.appPhase.value = AppPhase.Active
        ui.settle()
        assertEquals(3, ports.notificationClears)
        navigation.select(MainTab.Chats)
        ports.contacts.incomingRequests.value = listOf(ContactsFixtures.request(ContactsFixtures.ZOE, "zoe"))
        ui.settle()
        assertEquals(3, ports.notificationClears)
    }

    @Test
    fun rowsReadPresenceAndOpenTheirChat() {
        val jane = ContactsFixtures.contact("jane", ContactsFixtures.JANE)
        val noah = ContactsFixtures.contact("noah")
        val mia = ContactsFixtures.contact("mia")
        ports.contacts.contacts.value = listOf(jane, mia, noah)
        ports.contacts.listState.value = ContactsListState(hasLoaded = true)
        ports.contacts.presence.value = mapOf(
            jane.userId to PresenceDto(jane.userId, online = true),
            noah.userId to PresenceDto(noah.userId, online = false, lastSeenAt = Instant.parse("2026-10-01T18:00:00Z")),
        )
        val ui = tab()
        // `ChatListFormatting.presenceLabel` with the list's fallback (`ContactsView.swift:272-275`).
        ui.node("jane, online")
        ui.node("noah, last seen yesterday")
        ui.node("mia, contact")

        ui.tapLabel("jane, online")
        assertEquals(listOf("push:${ChatRoute.Conversation(jane.userId, "jane")}"), navigation.log)
    }

    @Test
    fun aFailedFirstLoadOffersARetryThatForcesARefresh() {
        ports.contacts.listState.value = ContactsListState(hasLoaded = true, error = "Could not connect to the server.")
        val ui = tab()
        assertTrue(ui.shows("Can't load contacts"))
        assertTrue(ui.shows("Could not connect to the server."))
        ui.tapLabel("Try again")
        assertTrue(ports.log.toString(), "refresh(force=true)" in ports.log)
    }
}

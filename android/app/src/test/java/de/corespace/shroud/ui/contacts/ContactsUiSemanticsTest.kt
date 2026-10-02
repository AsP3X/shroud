package de.corespace.shroud.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.contacts.ContactsSorting
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ContactRequestStatus
import de.corespace.shroud.core.net.UserCardDto
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.rememberToastState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.UUID

/**
 * What TalkBack and a tap get from the contacts screens (contacts §9 "Compose UI tests": Add
 * Contact's Add / "Adding…", request buttons off while answering, the profile hiding "Mark as
 * Verified" while a key change waits; plus the list's sections, the sort capsule, the share
 * footer, the QR view and the designed camera-denied screen). JVM stand-in for device UI tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsUiSemanticsTest {
    private val me = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val jane = ContactItemDto(UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f"), "jane", Instant.EPOCH)
    private val noah = ContactItemDto(UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"), "noah", Instant.EPOCH)
    private val zoeRequest = ContactRequestDto(
        id = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b"),
        fromUserId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        toUserId = me,
        status = ContactRequestStatus.PENDING,
        createdAt = Instant.EPOCH,
        user = UserCardDto(UUID.fromString("00000000-0000-4000-8000-000000000001"), "zoe"),
    )

    @Test
    fun theListShowsPendingRequestsUnfilteredAndContactsBySearch() {
        var sortToggles = 0
        val content = ContactsSorting.listContent(
            contacts = listOf(jane, noah),
            requests = listOf(zoeRequest),
            state = ContactsListState(hasLoaded = true),
            searchText = "ja",
            ascending = true,
        )
        val ui = ComposeHarness {
            ContactsList(
                content = content,
                contactsEmpty = false,
                status = { "contact" },
                isOnline = { false },
                activity = { null },
                responding = emptySet(),
                query = "ja",
                onQueryChange = {},
                searchActive = false,
                ascending = true,
                onToggleSort = { sortToggles++ },
                shareCode = null,
                shareUrl = null,
                bottomExtra = 0.dp,
                onOpenContact = {},
                onRespond = { _, _ -> },
                onRetry = {},
                onShowMyQr = {},
                onShowAdd = {},
            )
        }
        assertTrue(SemanticsProperties.Heading in ui.node("Contacts").config)
        assertTrue(SemanticsProperties.Heading in textNode(ui, "Pending").config)
        assertTrue(SemanticsProperties.Heading in textNode(ui, "J").config)
        // Requests are never filtered by the search (`ContactsView.swift:96-104`); contacts are.
        ui.node("zoe, wants to connect")
        ui.node("jane, contact")
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("noah") } == true })
        // The bar: sort capsule with its hint, My QR code, Add contact.
        val sort = ui.node("Sorted A to Z")
        assertEquals("Reverses the order", sort.config[SemanticsActions.OnClick].label)
        sort.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, sortToggles)
        ui.node("My QR code")
        ui.node("Add contact")
        // The footer before the session knows its code.
        assertTrue(ui.nodesWithText("Your invite").isNotEmpty())
        assertTrue(ui.nodesWithText("Loading share code…").isNotEmpty())
        ui.node("Show QR code")
    }

    @Test
    fun anEmptyRosterSaysHowToAddSomeoneAndTheFooterShowsTheCode() {
        val content = ContactsSorting.listContent(emptyList(), emptyList(), ContactsListState(hasLoaded = true), "", ascending = false)
        val ui = ComposeHarness {
            ContactsList(
                content = content, contactsEmpty = true, status = { "" }, isOnline = { false }, activity = { null },
                responding = emptySet(), query = "", onQueryChange = {}, searchActive = false, ascending = false,
                onToggleSort = {}, shareCode = "ABCD234567", shareUrl = "https://shroud.corespace.de/u/ABCD234567",
                bottomExtra = 0.dp, onOpenContact = {}, onRespond = { _, _ -> }, onRetry = {}, onShowMyQr = {}, onShowAdd = {},
            )
        }
        ui.node("Sorted Z to A")
        assertTrue(ui.nodesWithText("No contacts yet").isNotEmpty())
        assertTrue(ui.nodesWithText("Scan a QR code or enter a share code to add someone.").isNotEmpty())
        assertTrue(ui.nodesWithText("ABCD234567").isNotEmpty())
        assertTrue(ui.nodesWithText("https://shroud.corespace.de/u/ABCD234567").isNotEmpty())
    }

    @Test
    fun requestButtonsAreOffWhileTheAnswerIsOnTheWire() {
        val answers = mutableListOf<Boolean>()
        var responding by mutableFlag(false)
        val ui = ComposeHarness {
            ContactRequestRow(zoeRequest, isResponding = responding, onRespond = { answers += it })
        }
        val accept = textNode(ui, "Accept")
        assertFalse(SemanticsProperties.Disabled in accept.config)
        accept.config[SemanticsActions.OnClick].action!!.invoke()
        textNode(ui, "Reject").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf(true, false), answers)

        responding = true
        ui.idle()
        assertTrue(SemanticsProperties.Disabled in textNode(ui, "Accept").config)
        assertTrue(SemanticsProperties.Disabled in textNode(ui, "Reject").config)
    }

    @Test
    fun addIsOffUntilThereIsTextAndSaysAddingWhileTheRequestRuns() {
        val form = AddContactForm(prefill = null)
        var submits = 0
        val ui = ComposeHarness {
            Column { AddContactContent(form, FocusRequester(), onCancel = {}, onSubmit = { submits++ }, onScan = {}) }
        }
        assertTrue(SemanticsProperties.Disabled in textNode(ui, "Add").config)
        textNode(ui, "Cancel")
        textNode(ui, "Scan QR code")

        form.text = "jane"
        ui.idle()
        val add = textNode(ui, "Add")
        assertFalse(SemanticsProperties.Disabled in add.config)
        add.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, submits)

        val gate = CompletableDeferred<AddContactOutcome>()
        CoroutineScope(Dispatchers.Unconfined).launch { form.submit("jane") { gate.await() } }
        ui.idle()
        assertTrue(SemanticsProperties.Disabled in textNode(ui, "Adding…").config)

        gate.complete(AddContactOutcome.Failed("User not found."))
        ui.idle()
        val error = textNode(ui, "User not found.")
        assertNotNull("read out like iOS's announcement", error.config.getOrNull(SemanticsProperties.LiveRegion))
        assertFalse(SemanticsProperties.Disabled in textNode(ui, "Add").config)
    }

    @Test
    fun aPendingKeyChangeHidesVerificationAndOffersToTrustTheNewKey() {
        var trusted = 0
        val ui = ComposeHarness { profile(hasIdentityChange = true, isVerified = false, onVerifiedInPerson = { trusted++ }) }
        assertTrue(ui.nodesWithText("Encryption key changed").isNotEmpty())
        assertTrue(ui.nodesWithText("Mark as Verified").isEmpty())
        assertTrue(ui.nodesWithText("Verified").isEmpty())
        // The new key's number is still shown (P10b), under the iOS identifier.
        assertTrue(ui.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == SAFETY_NUMBER_TAG })
        assertTrue(ui.nodesWithText(NUMBER).isNotEmpty())
        textNode(ui, "I verified this contact").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, trusted)
    }

    @Test
    fun withoutAChangeTheNumberCanBeMarkedVerifiedOnce() {
        var marked = 0
        val ui = ComposeHarness { profile(hasIdentityChange = false, isVerified = false, onMarkVerified = { marked++ }) }
        assertTrue(ui.nodesWithText("Encryption key changed").isEmpty())
        textNode(ui, "Mark as Verified").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, marked)

        val verified = ComposeHarness { profile(hasIdentityChange = false, isVerified = true) }
        assertTrue(verified.nodesWithText("Mark as Verified").isEmpty())
        assertTrue(verified.nodesWithText("Verified").isNotEmpty())
    }

    @Test
    fun theProfileNamesItsActionsAndTheBlockState() {
        var mutes = 0
        val ui = ComposeHarness { profile(isMuted = true, isBlocked = true, onMute = { mutes++ }) }
        ui.node("Call")
        ui.node("Video")
        ui.node("Search")
        val mute = ui.node("Unmute")
        mute.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, mutes)
        assertTrue(ui.nodesWithText("Unblock jane").isNotEmpty())
        assertTrue(ui.nodesWithText("Muted until 14:30").isNotEmpty())
        assertTrue(ui.nodesWithText("@jane").isNotEmpty())
        assertTrue(ui.nodesWithText("End-to-end encrypted").isNotEmpty())

        val unmuted = ComposeHarness { profile(isMuted = false, isBlocked = false) }
        unmuted.node("Mute")
        assertTrue(unmuted.nodesWithText("Block jane").isNotEmpty())
        assertTrue(unmuted.nodesWithText("Delete Chat").isNotEmpty())
    }

    @Test
    fun theQrViewIsDescribedAndFallsBackWhenItCannotEncode() {
        val ui = ComposeHarness { QrCodeView("https://shroud.corespace.de/u/ABCD234567") }
        ui.node("QR code")
        // The fallback square is one "QR code" stop too, as iOS labels the whole group (`QRCodeImage.swift:45`).
        val failed = ComposeHarness { QrCodeView("") }
        failed.node("QR code")
    }

    @Test
    fun theDeniedScreenOffersSettingsOrTheCodeField() {
        val calls = mutableListOf<String>()
        val ui = ComposeHarness {
            CameraDeniedScreen(onClose = { calls += "close" }, onOpenSettings = { calls += "settings" }, onEnterCode = { calls += "code" })
        }
        assertTrue(SemanticsProperties.Heading in textNode(ui, "Camera access is off").config)
        assertTrue(ui.nodesWithText("Shroud needs the camera to scan a contact’s QR code. Nothing is recorded or sent.").isNotEmpty())
        textNode(ui, "Open Settings").config[SemanticsActions.OnClick].action!!.invoke()
        textNode(ui, "Enter code instead").config[SemanticsActions.OnClick].action!!.invoke()
        ui.node("Cancel").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("settings", "code", "close"), calls)
    }

    @Test
    fun myQrCodeShowsTheLinkTheCodeAndTheirCopyButtons() {
        val copied = mutableListOf<String>()
        var shared: String? = null
        val ui = ComposeHarness {
            MyQrCodeContent(
                username = "jane",
                shareCode = "ABCD234567",
                shareUrl = "https://shroud.corespace.de/u/ABCD234567",
                toast = rememberToastState(),
                onDone = {},
                onCopy = { value, confirmation -> copied += "$value|$confirmation" },
                onShare = { shared = it },
            )
        }
        assertTrue(ui.nodesWithText("@jane").isNotEmpty())
        ui.node("QR code")
        ui.node("Copy link").config[SemanticsActions.OnClick].action!!.invoke()
        ui.node("Copy share code").config[SemanticsActions.OnClick].action!!.invoke()
        ui.node("Share invite").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(
            listOf("https://shroud.corespace.de/u/ABCD234567|Link copied", "ABCD234567|Code copied"),
            copied,
        )
        assertEquals("https://shroud.corespace.de/u/ABCD234567", shared)
        textNode(ui, "Done")
    }

    @Test
    fun myQrCodeWaitsForTheShareCode() {
        val ui = ComposeHarness {
            MyQrCodeContent(null, null, null, rememberToastState(), {}, { _, _ -> }, {})
        }
        assertTrue(ui.nodesWithText("Loading your code…").isNotEmpty())
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Share invite") == true })
    }

    @androidx.compose.runtime.Composable
    private fun profile(
        hasIdentityChange: Boolean = false,
        isVerified: Boolean = false,
        isMuted: Boolean = false,
        isBlocked: Boolean = false,
        onVerifiedInPerson: () -> Unit = {},
        onMarkVerified: () -> Unit = {},
        onMute: () -> Unit = {},
    ) {
        ContactProfileContent(
            username = "jane",
            seed = "jane",
            status = "online",
            isOnline = true,
            activity = null,
            isMuted = isMuted,
            notificationsValue = if (isMuted) "Muted until 14:30" else "On",
            hasIdentityChange = hasIdentityChange,
            safetyNumber = NUMBER,
            isVerified = isVerified,
            isBlocked = isBlocked,
            isBlocking = false,
            isDeletingChat = false,
            onCall = {},
            onVideo = {},
            onMute = { onMute() },
            onSearch = {},
            onVerifiedInPerson = onVerifiedInPerson,
            onMarkVerified = onMarkVerified,
            onBlock = {},
            onDeleteChat = {},
        )
    }

    /** The one merged node whose text is exactly [text]. */
    private fun textNode(ui: ComposeHarness, text: String): SemanticsNode {
        val matches = ui.nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        check(matches.size == 1) { "expected one node with text \"$text\", found ${matches.size}: ${ui.describe()}" }
        return matches.single()
    }

    private fun mutableFlag(initial: Boolean) = androidx.compose.runtime.mutableStateOf(initial)

    private companion object {
        const val NUMBER = "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890 12345 67890"
    }
}

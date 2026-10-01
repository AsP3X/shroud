package de.corespace.shroud.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.ui.theme.ShroudIcons
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What TalkBack gets from the list kit (the spec's a11y lines: shell-chats §10.1-10.8, §6.2-6.3;
 * settings-lock §2.1-2.3) and the search field's keyboard flag (plan P5: no personalised learning).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListKitSemanticsTest {
    @Test
    fun chatRowIsOneNodeWithTheIosLabelAndBothActions() {
        var clicks = 0
        var longPresses = 0
        val ui = ComposeHarness {
            ChatRow(
                "Family", "Photo", "11:02", { NameAvatar("Family") },
                unreadCount = 12, muted = true, hasUnseenReactions = true,
                onClick = { clicks++ }, onLongPress = { longPresses++ }, onLongPressLabel = "Chat options",
            )
        }
        val row = ui.node("Family, Photo, 11:02, 12 unread, new reactions, muted")
        assertTrue("children are folded into the row: ${ui.describe()}", ui.nodesWithText("Photo").isEmpty())
        assertEquals("Chat options", row.config[SemanticsActions.OnLongClick].label)
        row.config[SemanticsActions.OnClick].action!!.invoke()
        row.config[SemanticsActions.OnLongClick].action!!.invoke()
        assertEquals(1, clicks)
        assertEquals(1, longPresses)
        assertNull(row.config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun chatRowSpeaksTheActivityAndItsSelection() {
        val ui = ComposeHarness {
            ChatRow("Jane Cooper", "online", null, { NameAvatar("Jane Cooper") }, activity = ChatPeerActivity.Recording, selected = true, onClick = {})
        }
        val row = ui.node("Jane Cooper, recording a voice message")
        assertEquals(true, row.config[SemanticsProperties.Selected])
    }

    @Test
    fun avatarsAndPresenceAreDecorative() {
        val ui = ComposeHarness {
            Column {
                Avatar("JC")
                NameAvatar("Design Team")
                SymbolAvatar(ShroudIcons.BookmarkSimpleFill)
                PresenceDot()
            }
        }
        assertTrue(ui.describe(), ui.nodesWithText("JC").isEmpty())
        assertTrue(ui.describe(), ui.nodesWithText("DT").isEmpty())
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
    }

    @Test
    fun typingLabelSpeaksOnlyTheSpokenLabel() {
        val ui = ComposeHarness { TypingLabel(ChatPeerActivity.Recording) }
        ui.node("recording a voice message")
        assertTrue(ui.describe(), ui.nodesWithText("recording").isEmpty())
    }

    @Test
    fun skeletonIsOneLoadingNode() {
        val ui = ComposeHarness { SkeletonChatList() }
        ui.node("Loading")
        assertEquals(ui.describe(), 1, ui.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
    }

    @Test
    fun searchFieldOffersClearSearchOnlyWithText() {
        var query by mutableStateOf("jane")
        val ui = ComposeHarness { SearchField(query, { query = it }) }
        val clear = ui.node("Clear search")
        assertEquals(Role.Button, clear.config[SemanticsProperties.Role])
        clear.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals("", query)
        assertTrue(ui.describe(), ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Clear search") == true })
    }

    @Test
    fun searchFieldTurnsOffPersonalisedLearningForTheKeyboard() {
        val ui = ComposeHarness {
            var query by remember { mutableStateOf("") }
            SearchField(query, { query = it })
        }
        val field = ui.nodes().first { SemanticsActions.SetText in it.config }
        (field.config.getOrNull(SemanticsActions.RequestFocus) ?: field.config[SemanticsActions.OnClick]).action!!.invoke()
        ui.idle()
        val info = EditorInfo()
        val connection = ui.root.onCreateInputConnection(info)
        assertNotNull("the focused field opened an input connection", connection)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    @Test
    fun loadErrorHasAHeadingAndARetryButtonThatSaysRetrying() {
        val pending = CompletableDeferred<Unit>()
        var retries = 0
        val ui = ComposeHarness {
            ListLoadError("Can't load calls", "Could not connect to the server.", onRetry = {
                retries++
                pending.await()
            })
        }
        val title = ui.nodesWithText("Can't load calls").single()
        assertTrue(SemanticsProperties.Heading in title.config)
        val button = ui.node("Try again")
        assertEquals(Role.Button, button.config[SemanticsProperties.Role])
        button.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        val busy = ui.node("Retrying")
        assertTrue("a second tap is refused while retrying", SemanticsProperties.Disabled in busy.config)
        assertEquals(1, retries)
        pending.complete(Unit)
        ui.idle()
        assertFalse(ui.describe(), SemanticsProperties.Disabled in ui.node("Try again").config)
    }

    @Test
    fun glassBarButtonsAreNamedButtonsAndDisabledOnesSaySo() {
        var taps = 0
        val ui = ComposeHarness {
            GlassBarRow(
                leading = { GlassBarButton(ShroudIcons.CaretLeftBold, "Back", {}, enabled = false) },
                title = { GlassBarTitle("Contacts") },
                trailing = {
                    GlassBarGroup {
                        GlassBarButton(ShroudIcons.Search, "My QR code", { taps++ })
                        GlassBarButton(ShroudIcons.HeartFill, "Add contact", {})
                    }
                    GlassBarButton(null, "Sorted A to Z", {}, GlassBarButtonStyle.Capsule, label = "A–Z")
                },
            )
        }
        assertTrue(SemanticsProperties.Disabled in ui.node("Back").config)
        val qr = ui.node("My QR code")
        assertEquals(Role.Button, qr.config[SemanticsProperties.Role])
        qr.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, taps)
        ui.node("Add contact")
        ui.node("Sorted A to Z")
        assertTrue("the capsule's word is not read twice", ui.nodesWithText("A–Z").isEmpty())
        val title = ui.node("Contacts")
        assertTrue(SemanticsProperties.Heading in title.config)
    }

    @Test
    fun toggleRowIsOneSwitchNamedByItsTitle() {
        var checked by mutableStateOf(true)
        val ui = ComposeHarness {
            Column {
                ToggleRow("Show Sender", "Off, a notification only says that a message arrived.", checked = checked) { checked = it }
                ToggleRow("Include Muted Chats", checked = false, enabled = false) {}
            }
        }
        val row = ui.node("Show Sender")
        assertEquals(Role.Switch, row.config[SemanticsProperties.Role])
        assertEquals("On", row.config[SemanticsProperties.StateDescription])
        assertEquals(ToggleableState.On, row.config[SemanticsProperties.ToggleableState])
        row.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertFalse(checked)
        assertEquals("Off", ui.node("Show Sender").config[SemanticsProperties.StateDescription])
        assertTrue(SemanticsProperties.Disabled in ui.node("Include Muted Chats").config)
    }

    @Test
    fun settingsRowsReadAsOneItemEach() {
        var opened = 0
        val ui = ComposeHarness {
            SettingsCard {
                SettingsRow("Devices", ShroudIcons.PhoneFill, value = "3", onClick = { opened++ })
                SettingsRow("Chat Folders", ShroudIcons.Folder, soon = true, onClick = null)
                SettingsRow("Server", ShroudIcons.HardDrivesFill, subtitle = "shroud.corespace.de", onClick = {})
            }
        }
        val devices = merged(ui, "Devices")
        assertEquals(listOf("Devices", "3"), texts(devices))
        devices.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, opened)
        val folders = merged(ui, "Chat Folders")
        assertEquals(listOf("Chat Folders", "Soon"), texts(folders))
        assertTrue(SemanticsProperties.Disabled in folders.config)
        assertEquals(listOf("Server", "shroud.corespace.de"), texts(merged(ui, "Server")))
    }

    @Test
    fun emptyStateReadsTitleAndMessageButNotTheGlyph() {
        val ui = ComposeHarness {
            EmptyState(ShroudIcons.PhoneFill, "No calls yet", "Your recent calls show up here.") {
                EmptyStateButton("New Chat", {})
            }
        }
        assertEquals(1, ui.nodesWithText("No calls yet").size)
        assertEquals(1, ui.nodesWithText("Your recent calls show up here.").size)
        assertEquals(Role.Button, ui.nodesWithText("New Chat").single().config[SemanticsProperties.Role])
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
    }

    @Test
    fun pushedScreenHasABackButtonAndAHeading() {
        var backs = 0
        var enabled by mutableStateOf(true)
        val ui = ComposeHarness {
            PushedScreen("Devices", onBack = { backs++ }, backEnabled = enabled) {
                SectionHeader("This device")
            }
        }
        assertTrue(SemanticsProperties.Heading in ui.node("Devices").config)
        assertEquals(1, ui.nodesWithText("THIS DEVICE").size)
        assertTrue(SemanticsProperties.Heading in ui.nodesWithText("THIS DEVICE").single().config)
        ui.node("Back").config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, backs)
        enabled = false
        ui.idle()
        assertTrue(SemanticsProperties.Disabled in ui.node("Back").config)
    }

    @Test
    fun mainScrollScreenTitlesTheBarAndListsItsRows() {
        val ui = ComposeHarness {
            MainScrollScreen(title = "Chats", header = { SearchField("", {}) }) {
                items(3) { index -> ChatRow("Contact $index", "Hi", "9:41", { NameAvatar("Contact $index") }, onClick = {}) }
            }
        }
        assertTrue(SemanticsProperties.Heading in ui.node("Chats").config)
        ui.node("Contact 0, Hi, 9:41")
        ui.node("Contact 2, Hi, 9:41")
    }

    private fun merged(ui: ComposeHarness, title: String): SemanticsNode =
        ui.nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == title }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

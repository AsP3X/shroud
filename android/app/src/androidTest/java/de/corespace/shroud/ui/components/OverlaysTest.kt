package de.corespace.shroud.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W1-UI-OVERLAYS acceptance on a device: back and predictive back close every overlay, scrims
 * close them, actions run after the overlay began to close, TalkBack sees one modal pane with its
 * title and nothing behind it, and today's `ShroudSheet` call shape still works.
 */
@RunWith(AndroidJUnit4::class)
class OverlaysTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            ShroudTheme(dark = false) {
                OverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        ShroudText("Behind", de.corespace.shroud.ui.theme.inter(16f), ShroudTheme.colors.textPrimary)
                        content()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun predictiveBack() {
        rule.runOnUiThread {
            val dispatcher = rule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        rule.waitForIdle()
    }

    private fun cancelledPredictiveBack() {
        rule.runOnUiThread {
            val dispatcher = rule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 400f, 0.3f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        rule.waitForIdle()
    }

    private fun paneTitled(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    // ---- Action sheet ---------------------------------------------------------------------------

    @Test
    fun actionSheetShowsItsButtonsAndCancelClosesIt() {
        var visible by mutableStateOf(true)
        show {
            ActionSheet(
                visible = visible,
                title = "Delete chat with jane?",
                message = "They stay in your contacts.",
                items = listOf(ActionSheetItem("Delete for me and jane", destructive = true) {}, ActionSheetItem("Delete for me", destructive = true) {}),
                onDismiss = { visible = false },
            )
        }
        rule.onNodeWithText("Delete for me and jane").assertExists()
        rule.onNodeWithText("Delete for me").assertExists()
        rule.onNode(paneTitled("Delete chat with jane?")).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete for me").assertDoesNotExist()
    }

    @Test
    fun actionSheetItemClosesTheSheetThenActs() {
        val events = mutableListOf<String>()
        var visible by mutableStateOf(true)
        show {
            ActionSheet(
                visible = visible,
                title = "Delete all notes?",
                items = listOf(ActionSheetItem("Delete", destructive = true) { events += "delete" }),
                onDismiss = {
                    events += "dismiss"
                    visible = false
                },
            )
        }
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(listOf("dismiss", "delete"), events)
        rule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun actionSheetClosesOnBackPredictiveBackAndTheScrim() {
        var visible by mutableStateOf(true)
        show {
            ActionSheet(visible = visible, title = "Remove device?", items = listOf(ActionSheetItem("Remove", true) {}), onDismiss = { visible = false })
        }
        pressBack()
        assertTrue(!visible)
        rule.onNodeWithText("Remove").assertDoesNotExist()

        rule.runOnUiThread { visible = true }
        rule.waitForIdle()
        cancelledPredictiveBack()
        rule.onNodeWithText("Remove").assertExists()
        predictiveBack()
        rule.onNodeWithText("Remove").assertDoesNotExist()

        rule.runOnUiThread { visible = true }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        rule.waitForIdle()
        rule.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun aModalOverlayHidesTheScreenBehindItFromTalkBack() {
        var visible by mutableStateOf(false)
        show {
            ActionSheet(visible = visible, title = "Reset your QR code?", items = listOf(ActionSheetItem("Reset", true) {}), onDismiss = { visible = false })
        }
        rule.onNodeWithText("Behind").assertExists()
        rule.runOnUiThread { visible = true }
        rule.waitForIdle()
        rule.onNodeWithText("Behind").assertDoesNotExist()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true) and paneTitled("Reset your QR code?")).assertExists()
        pressBack()
        rule.onNodeWithText("Behind").assertExists()
    }

    // ---- Sheets ---------------------------------------------------------------------------------

    @Test
    fun todaysSheetCallStillOpensAndClosesOnBack() {
        var visible by mutableStateOf(true)
        show {
            // The onboarding call site's shape (ShroudApp.kt): no style, trailing content.
            ShroudSheet(visible = visible, onDismiss = { visible = false }) {
                ShroudText("Server", de.corespace.shroud.ui.theme.inter(28f), ShroudTheme.colors.textPrimary)
            }
        }
        rule.onNodeWithText("Server").assertExists()
        pressBack()
        rule.onNodeWithText("Server").assertDoesNotExist()
    }

    @Test
    fun insetAndCompactSheetsCloseOnPredictiveBackAndTheScrim() {
        var style by mutableStateOf(SheetStyle.Inset)
        var visible by mutableStateOf(false)
        show {
            ShroudSheet(visible = visible, onDismiss = { visible = false }, style = style, paneTitle = "Device details") {
                ShroudText("Device ID", de.corespace.shroud.ui.theme.inter(16f), ShroudTheme.colors.textPrimary)
            }
        }
        for (next in listOf(SheetStyle.Inset, SheetStyle.Compact)) {
            rule.runOnUiThread {
                style = next
                visible = true
            }
            rule.waitForIdle()
            rule.onNode(paneTitled("Device details")).assertExists()
            predictiveBack()
            rule.onNodeWithText("Device ID").assertDoesNotExist()
            rule.runOnUiThread { visible = true }
            rule.waitForIdle()
            rule.onNodeWithText("Device ID").assertExists()
            rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
            rule.waitForIdle()
            rule.onNodeWithText("Device ID").assertDoesNotExist()
        }
    }

    // ---- Context menu ---------------------------------------------------------------------------

    @Test
    fun contextMenuRunsTheActionAndClosesAfterItsAnimation() {
        var open by mutableStateOf(true)
        val events = mutableListOf<String>()
        show {
            if (open) {
                ContextMenu(
                    anchor = Rect(0f, 300f, 1000f, 480f),
                    actions = listOf(
                        MenuAction("Mark as Read") { events += "read" },
                        MenuAction("Delete Chat", destructive = true) { events += "delete" },
                    ),
                    onDismiss = {
                        events += "dismissed"
                        open = false
                    },
                    paneTitle = "Chat options",
                    header = { ShroudText("jane", de.corespace.shroud.ui.theme.inter(16f), ShroudTheme.colors.textPrimary) },
                )
            }
        }
        rule.onNode(paneTitled("Chat options")).assertExists()
        rule.onNodeWithText("Mark as Read").performClick()
        rule.waitForIdle()
        assertEquals(listOf("read", "dismissed"), events)
        rule.onNodeWithText("Mark as Read").assertDoesNotExist()
    }

    @Test
    fun submenuOpensInTheCardAndBackReturnsBeforeClosing() {
        var open by mutableStateOf(true)
        var muted: String? = null
        show {
            if (open) {
                ContextMenu(
                    anchor = Rect(0f, 300f, 1000f, 480f),
                    actions = listOf(
                        MenuAction(
                            "Mute",
                            submenu = listOf(
                                MenuAction("For 1 Hour") { muted = "1h" },
                                MenuAction("Until I Turn It Back On") { muted = "forever" },
                            ),
                        ),
                        MenuAction("Delete Chat", destructive = true),
                    ),
                    onDismiss = { open = false },
                )
            }
        }
        rule.onNodeWithText("Mute").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("For 1 Hour").assertExists()
        rule.onNodeWithText("Delete Chat").assertDoesNotExist()
        // Back leaves the submenu, not the menu.
        pressBack()
        rule.onNodeWithText("Delete Chat").assertExists()
        rule.onNodeWithText("Mute").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Until I Turn It Back On").performClick()
        rule.waitForIdle()
        assertEquals("forever", muted)
        assertTrue(!open)
    }

    @Test
    fun contextMenuClosesOnPredictiveBack() {
        var open by mutableStateOf(true)
        show {
            if (open) {
                ContextMenu(anchor = Rect(0f, 300f, 1000f, 480f), actions = listOf(MenuAction("Unmute")), onDismiss = { open = false })
            }
        }
        cancelledPredictiveBack()
        rule.onNodeWithText("Unmute").assertExists()
        predictiveBack()
        rule.onNodeWithText("Unmute").assertDoesNotExist()
        assertTrue(!open)
    }

    // ---- Menu picker ----------------------------------------------------------------------------

    @Test
    fun pickerShowsTheCurrentValueAndReportsOnlyChanges() {
        val chosen = mutableListOf<String>()
        show {
            var value by remember { mutableStateOf("Immediately") }
            MenuPicker(
                value = value,
                options = listOf("Immediately", "After 1 minute", "After 5 minutes"),
                label = { it },
                onSelect = {
                    chosen += it
                    value = it
                },
                contentDescription = "Auto-lock",
            )
        }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Immediately")).performClick()
        rule.waitForIdle()
        rule.onNode(paneTitled("Auto-lock")).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        rule.onNodeWithText("After 5 minutes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("After 5 minutes"), chosen)
        // Choosing the current (ticked) value again changes nothing.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "After 5 minutes")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("After 5 minutes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("After 5 minutes"), chosen)
    }

    // ---- Alert dialog ---------------------------------------------------------------------------

    @Test
    fun renameDialogEnablesSaveWithANameAndDoneSaves() {
        var visible by mutableStateOf(true)
        var saved: String? = null
        show {
            var draft by remember { mutableStateOf("") }
            ShroudAlertDialog(
                visible = visible,
                title = "Rename Device",
                message = "The name is encrypted — only your devices can read it.",
                field = AlertField(draft, { draft = it }, "Name"),
                primary = AlertButton("Save", enabled = draft.isNotBlank()) { saved = draft },
                onDismiss = { visible = false },
            )
        }
        rule.onNode(paneTitled("Rename Device")).assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Name"))).performTextInput("Work phone")
        rule.waitForIdle()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Name"))).performImeAction()
        rule.waitForIdle()
        assertEquals("Work phone", saved)
        assertTrue(!visible)
    }

    // ---- Pull to refresh ------------------------------------------------------------------------

    @Test
    fun pullingTheListDownRefreshesItAndTalkBackHasARefreshAction() {
        var refreshes = 0
        show {
            PullToRefresh(onRefresh = { refreshes++ }, modifier = Modifier.testTag("chats")) {
                LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                    items(30) { ShroudText("Row $it", de.corespace.shroud.ui.theme.inter(16f), ShroudTheme.colors.textPrimary) }
                }
            }
        }
        rule.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 20f, endY = top + 20f + 400.dp.toPx(), durationMillis = 600) }
        rule.waitForIdle()
        assertEquals(1, refreshes)

        val actions = rule.onNodeWithTag("chats").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnUiThread { actions.first { it.label == "Refresh" }.action() }
        rule.waitForIdle()
        assertEquals(2, refreshes)
    }

    // ---- Slider ---------------------------------------------------------------------------------

    @Test
    fun sliderExposesItsValueAndTakesTalkBackAdjustments() {
        var value by mutableStateOf(0.5f)
        show {
            ShroudSlider(value = value, onValueChange = { value = it }, label = "Filter intensity")
        }
        val node = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Filter intensity")))
        node.assertExists()
        val setProgress = node.fetchSemanticsNode().config[SemanticsActions.SetProgress]
        rule.runOnUiThread { setProgress.action?.invoke(0.8f) }
        rule.waitForIdle()
        assertEquals(0.8f, value, 0.0001f)
        rule.runOnUiThread { setProgress.action?.invoke(4f) }
        rule.waitForIdle()
        assertEquals(1f, value, 0.0001f)
    }
}

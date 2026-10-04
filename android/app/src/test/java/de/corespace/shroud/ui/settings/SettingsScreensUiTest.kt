package de.corespace.shroud.ui.settings

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.shell.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The W3-SETTINGS-A screens as TalkBack and a finger reach them (settings-lock §3, §8-§10): what
 * each row says, what it opens, and the confirmations. Robolectric hosts the real composables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreensUiTest {
    private val rootState = SettingsRootState(
        username = "niklas_v",
        userId = "3F2504E0-4F89-41D3-9A0C-0305E82C3301",
        deviceCount = 3,
        notificationsSummary = "On",
        themeTitle = "Dark",
        serverSubtitle = "Official · api.shroud.app",
        isLoggingOut = false,
        deviceNoun = DeviceNoun.PHONE,
        appVersion = "0.1.0",
        hasUpdate = false,
    )

    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun ComposeHarness.row(title: String): SemanticsNode =
        nodes().single { node -> node.texts().firstOrNull() == title && SemanticsActions.OnClick in node.config }

    private fun SemanticsNode.isSelected(): Boolean = config.getOrNull(SemanticsProperties.Selected) == true

    private fun SemanticsNode.isDisabled(): Boolean = SemanticsProperties.Disabled in config

    @Test
    fun rootReadsTheHeroFirstAndEveryRow() {
        val routes = ArrayList<SettingsRoute>()
        var calls = 0
        val ui = ComposeHarness {
            SettingsRootContent(rootState, onRoute = { routes += it }, onOpenCalls = { calls++ }, onLogOut = {})
        }
        val hero = ui.node("Niklas V, @niklas_v")
        assertTrue(SemanticsProperties.Heading in hero.config)
        assertEquals(-1f, hero.config[SemanticsProperties.TraversalIndex], 0f)
        assertTrue(ui.nodesWithText("Signed in as @niklas_v").isNotEmpty())
        assertTrue("the id is lower-case", ui.nodesWithText("3f2504e0-4f89-41d3-9a0c-0305e82c3301").isNotEmpty())
        assertTrue(ui.nodesWithText("Emoji status, colors, and photos come later.").isNotEmpty())

        assertEquals(listOf("Devices", "3"), ui.row("Devices").texts())
        assertEquals(listOf("Notifications and Sounds", "On"), ui.row("Notifications and Sounds").texts())
        assertEquals(listOf("Appearance", "Dark"), ui.row("Appearance").texts())
        assertEquals(listOf("Server", "Official · api.shroud.app"), ui.row("Server").texts())

        for ((title, route) in listOf(
            "Saved Messages" to SettingsRoute.SavedMessages,
            "Devices" to SettingsRoute.Devices,
            "Notifications and Sounds" to SettingsRoute.Notifications,
            "Privacy and Security" to SettingsRoute.PrivacySecurity,
            "Appearance" to SettingsRoute.Appearance,
            "Transcription" to SettingsRoute.Transcription,
            "Server" to SettingsRoute.Server,
        )) {
            ui.row(title).click()
            assertEquals(title, route, routes.last())
        }
        ui.row("Recent Calls").click()
        assertEquals(1, calls)

        // Rows without a screen yet say "Soon" and cannot be pressed (`SettingsRowView.swift:41-54`).
        for (title in listOf("Chat Folders", "Data and Storage", "Language")) {
            val soon = ui.nodes().single { it.texts().firstOrNull() == title }
            assertEquals(listOf(title, "Soon"), soon.texts())
            assertTrue(title, soon.isDisabled())
        }
    }

    @Test
    fun recentCallsIsSoonWithoutACallsTab() {
        val ui = ComposeHarness { SettingsRootContent(rootState.copy(deviceCount = null), onRoute = {}, onOpenCalls = null, onLogOut = {}) }
        val calls = ui.nodes().single { it.texts().firstOrNull() == "Recent Calls" }
        assertEquals(listOf("Recent Calls", "Soon"), calls.texts())
        assertEquals("no count before the first load", listOf("Devices"), ui.row("Devices").texts())
    }

    @Test
    fun logOutConfirmsInAnActionSheet() {
        var loggedOut = 0
        val ui = ComposeHarness { SettingsRootContent(rootState, onRoute = {}, onOpenCalls = {}, onLogOut = { loggedOut++ }) }
        assertTrue(ui.nodesWithText("Log out of Shroud?").isEmpty())
        ui.node("Log Out").click()
        ui.idle()
        assertTrue(ui.nodesWithText("Log out of Shroud?").isNotEmpty())
        assertTrue(ui.nodesWithText(SettingsCopy.logOutMessage(DeviceNoun.PHONE)).isNotEmpty())
        assertEquals("nothing happens before the confirmation", 0, loggedOut)
        ui.nodes().single { it.texts() == listOf("Log Out") && SemanticsActions.OnClick in it.config }.click()
        ui.idle()
        assertEquals(1, loggedOut)
        assertTrue(ui.nodesWithText("Log out of Shroud?").isEmpty())
    }

    @Test
    fun logOutIsDisabledWhileSigningOut() {
        val ui = ComposeHarness { SettingsRootContent(rootState.copy(isLoggingOut = true), onRoute = {}, onOpenCalls = {}, onLogOut = {}) }
        val button = ui.node("Signing out")
        assertTrue(button.isDisabled())
    }

    @Test
    fun appearanceSelectsThemeAndLogo() {
        var theme by mutableStateOf(ColorTheme.System)
        var logo by mutableStateOf(BrandLogoStyle.Detailed)
        val ui = ComposeHarness {
            AppearanceContent(theme, { theme = it }, logo, { logo = it }, failure = null, deviceNoun = DeviceNoun.PHONE, onBack = {})
        }
        assertTrue(ui.row("System").isSelected())
        assertFalse(ui.row("Dark").isSelected())
        ui.row("Dark").click()
        ui.idle()
        assertEquals(ColorTheme.Dark, theme)
        assertTrue(ui.row("Dark").isSelected())
        assertFalse(ui.row("System").isSelected())

        assertEquals(listOf("Simple", "One flat shape."), ui.row("Simple").texts())
        assertTrue(ui.row("Detailed").isSelected())
        ui.row("Simple").click()
        ui.idle()
        assertEquals(BrandLogoStyle.Simple, logo)
        assertTrue(ui.row("Simple").isSelected())
        assertTrue(ui.nodesWithText("System follows your phone’s light or dark setting. The choice applies to this phone only.").isNotEmpty())
        assertTrue(ui.nodesWithText(AppearanceCopy.LOGO_FAILURE).isEmpty())
    }

    @Test
    fun appearanceShowsTheIconFailure() {
        val ui = ComposeHarness {
            AppearanceContent(ColorTheme.Light, {}, BrandLogoStyle.Detailed, {}, failure = AppearanceCopy.LOGO_FAILURE, deviceNoun = DeviceNoun.TABLET, onBack = {})
        }
        assertTrue(ui.nodesWithText("The app icon couldn’t be changed. Try again.").isNotEmpty())
        assertTrue(ui.nodesWithText("System follows your tablet’s light or dark setting. The choice applies to this tablet only.").isNotEmpty())
    }

    @Test
    fun transcriptionPicksALanguageOrAutomatic() {
        var selection: Locale? by mutableStateOf(Locale.forLanguageTag("de-DE"))
        val ui = ComposeHarness {
            TranscriptionContent(
                TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, 0.42, isDeterminate = true, languageName = null, messageId = null),
                languages = listOf("en", "de").map(Locale::forLanguageTag),
                selection = selection,
                onChoose = { selection = it },
                onBack = {},
            )
        }
        assertTrue(ui.nodesWithText("Downloading Whisper… 42%").isNotEmpty())
        val german = Locale.forLanguageTag("de").getDisplayName(Locale.getDefault())
        assertTrue("a region-tagged override selects its language", ui.row(german).isSelected())
        val density = ui.activity.resources.displayMetrics.density
        assertTrue("a one-line language row is a full 48 dp touch target", ui.row(german).boundsInRoot.height >= 48 * density - 0.5f)
        assertFalse(ui.row("Automatic").isSelected())
        assertEquals(listOf("Automatic", TranscriptionPicker.AUTOMATIC_SUBTITLE), ui.row("Automatic").texts())
        ui.row("Automatic").click()
        ui.idle()
        assertEquals(null, selection)
        assertTrue(ui.row("Automatic").isSelected())
        assertFalse(ui.row(german).isSelected())
    }

    @Test
    fun transcriptionWithoutADownloadShowsNoCard() {
        val ui = ComposeHarness { TranscriptionContent(TranscriptionInstallState.Idle, emptyList(), selection = null, onChoose = {}, onBack = {}) }
        assertTrue(ui.nodesWithText("Downloading Whisper…").isEmpty())
        assertTrue(ui.row("Automatic").isSelected())
    }

    @Test
    fun serverRejectsABadAddressUnderTheFields() {
        var saved = 0
        val start = ServerConfiguration(ServerConnectionMode.SelfHosted, "", "8080", "/api/v1", false)
        val ui = ComposeHarness {
            ServerSettingsPage(start, ServerConfiguration.official, signedIn = true, save = { saved++ }, onSignOut = {}, onBack = {})
        }
        assertTrue(ui.nodesWithText("SELF-HOSTED DETAILS").isNotEmpty())
        ui.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true && SemanticsActions.OnClick in it.config }.click()
        ui.idle()
        assertTrue(ui.nodesWithText("Enter a host or IP address.").isNotEmpty())
        assertTrue(ui.nodesWithText("Change server?").isEmpty())
        assertEquals(0, saved)
    }

    @Test
    fun serverAsksBeforeSigningOutAndSavesNothing() {
        var saved = 0
        var switchedTo: ServerConfiguration? = null
        val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        val ui = ComposeHarness {
            ServerSettingsPage(local, local, signedIn = true, save = { saved++ }, onSignOut = { switchedTo = it }, onBack = {}, pause = {})
        }
        val official = ui.node("Official Shroud server. Managed by Shroud · always up to date")
        assertFalse(official.isSelected())
        assertTrue(ui.node("Self-hosted. Your Docker / private server").isSelected())
        official.click()
        ui.idle()
        assertTrue(ui.node("Official Shroud server. Managed by Shroud · always up to date").isSelected())
        assertTrue(ui.nodesWithText(ServerSettingsLogic.OFFICIAL_INFO).isNotEmpty())

        ui.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true && SemanticsActions.OnClick in it.config }.click()
        ui.idle()
        assertTrue(ui.nodesWithText("Change server?").isNotEmpty())
        ui.nodes().single { it.texts() == listOf("Save and sign out") }.click()
        // Saving…, Saved, then the sign-out (the beats are skipped here).
        ui.idle()
        assertNotNull(switchedTo)
        assertEquals(ServerConnectionMode.Official, switchedTo!!.mode)
        assertEquals("the Log Out stores the new server after the wipe", 0, saved)
    }

    @Test
    fun serverSavesTheSameEndpointAndPops() {
        var saved: ServerConfiguration? = null
        var popped = 0
        val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        val ui = ComposeHarness {
            ServerSettingsPage(local, local, signedIn = true, save = { saved = it }, onSignOut = {}, onBack = { popped++ }, pause = {})
        }
        ui.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true && SemanticsActions.OnClick in it.config }.click()
        ui.idle()
        assertEquals(local, saved)
        assertEquals(1, popped)
        assertTrue(ui.nodesWithText("Change server?").isEmpty())
    }

    @Test
    fun aDragOnTheServerFormPutsTheKeyboardAway() {
        val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        val ui = ComposeHarness {
            ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}, pause = {})
        }
        fun focused() = ui.nodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        val host = ui.nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "10.0.2.2" }
        host.config[SemanticsActions.RequestFocus].action!!.invoke()
        ui.idle()
        assertTrue("the host field has the focus", focused())
        // A finger drags the form up from the mode cards (`.scrollDismissesKeyboard(.interactively)`).
        drag(ui.root, x = ui.root.width / 2f, fromY = ui.root.height * 0.45f, toY = ui.root.height * 0.1f)
        ui.idle()
        assertFalse("the drag put the keyboard away", focused())
    }

    private fun drag(view: View, x: Float, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, y: Float) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, down, fromY)
        for (step in 1..12) send(MotionEvent.ACTION_MOVE, down + step * 16L, fromY + (toY - fromY) * step / 12f)
        send(MotionEvent.ACTION_UP, down + 13 * 16L, toY)
    }
}

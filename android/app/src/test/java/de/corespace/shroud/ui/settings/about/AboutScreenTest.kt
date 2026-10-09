package de.corespace.shroud.ui.settings.about

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.about.AppVersion
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.update.ClientUpdate
import de.corespace.shroud.core.update.ClientUpdateStatus
import de.corespace.shroud.core.update.UpdateCheckOutcome
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.settings.SettingsRootContent
import de.corespace.shroud.ui.settings.SettingsRootState
import de.corespace.shroud.ui.shell.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › About Shroud as TalkBack and a finger reach it: the Settings row and its update dot,
 * the header, every Updates state and its copy (the same as iOS and the web), "Check for Updates",
 * the server rows, and the links. Robolectric hosts the real composables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenTest {
    private val version = AppVersion("0.1.0", 1)
    private val link = "https://shroud.corespace.de/download"
    private val baseState = AboutState(version, AboutUpdateStatus.Current, AboutCopy.OFFICIAL_SERVER, "0.1.0")
    private val hosts = ArrayList<ComposeHarness>()

    /** The checking spinner runs forever: hosts go, or later compositions in this JVM starve. */
    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    private fun harness(content: @Composable () -> Unit): ComposeHarness = ComposeHarness(content = content).also { hosts += it }

    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun SemanticsNode.isDisabled(): Boolean = SemanticsProperties.Disabled in config

    private fun ComposeHarness.clickable(text: String): SemanticsNode =
        nodes().single { node -> node.texts().firstOrNull() == text && SemanticsActions.OnClick in node.config }

    private class Taps {
        var checks = 0
        val updates = ArrayList<String>()
        var sourceCode = 0
        var licenses = 0
    }

    private fun host(state: AboutState, taps: Taps = Taps()) = harness {
        AboutContent(
            state,
            onBack = {},
            onCheckForUpdates = { taps.checks++ },
            onUpdate = { taps.updates += it },
            onSourceCode = { taps.sourceCode++ },
            onOpenLicenses = { taps.licenses++ },
        )
    }

    // ---- The Settings row ----

    @Test
    fun theSettingsRowShowsTheVersionAndADotForAnUpdate() {
        val routes = ArrayList<SettingsRoute>()
        var root by mutableStateOf(
            SettingsRootState(
                username = "niklas_v",
                userId = null,
                deviceCount = 3,
                notificationsSummary = "On",
                themeTitle = "System",
                serverSubtitle = "Official · shroud-app.com",
                isLoggingOut = false,
                deviceNoun = DeviceNoun.PHONE,
                appVersion = "0.1.0",
                hasUpdate = false,
            ),
        )
        val ui = harness { SettingsRootContent(root, onRoute = { routes += it }, onOpenCalls = {}, onLogOut = {}) }
        val row = ui.clickable("About Shroud")
        assertEquals(listOf("About Shroud", "0.1.0"), row.texts())
        assertTrue("no dot while current", ui.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Update available") == true })
        row.click()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.About), routes)

        root = root.copy(hasUpdate = true)
        ui.idle()
        // The dot is read with the row: "About Shroud, 0.1.0, Update available".
        val badged = ui.clickable("About Shroud")
        assertEquals(listOf("Update available"), badged.config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("About Shroud", "0.1.0"), badged.texts())
    }

    // ---- The page ----

    @Test
    fun theHeaderNamesTheAppAndItsBaseVersionCode() {
        val ui = host(baseState.copy(version = AppVersion("0.3.1", 17)))
        val name = ui.nodesWithText("Shroud").single { it.texts() == listOf("Shroud") }
        assertTrue(SemanticsProperties.Heading in name.config)
        assertTrue(ui.nodesWithText("Version 0.3.1 (17)").isNotEmpty())
        assertEquals("Version 0.1.0 (1)", AboutCopy.versionLine(version))
    }

    @Test
    fun everyUpdateStateHasItsCopy() {
        var status: AboutUpdateStatus by mutableStateOf(AboutUpdateStatus.Unchecked)
        val ui = harness {
            AboutContent(baseState.copy(status = status), onBack = {}, onCheckForUpdates = {}, onUpdate = {}, onSourceCode = {}, onOpenLicenses = {})
        }
        for ((shown, copy) in listOf(
            AboutUpdateStatus.Unchecked to "Updates are checked automatically",
            AboutUpdateStatus.Checking to "Checking for updates…",
            AboutUpdateStatus.Current to "Shroud is up to date",
            AboutUpdateStatus.Available("0.2.0", link) to "Version 0.2.0 is available",
            AboutUpdateStatus.Available(null, null) to "A new version is available",
            AboutUpdateStatus.Required to "Update required",
            AboutUpdateStatus.Failed to "Couldn’t check for updates",
        )) {
            status = shown
            ui.idle()
            val row = ui.nodesWithText(copy)
            assertEquals("$shown: ${ui.describe()}", 1, row.size)
            assertEquals(shown.toString(), copy, AboutCopy.status(shown))
        }
    }

    @Test
    fun anOfferWithALinkHasAnUpdateButton() {
        val taps = Taps()
        val ui = host(baseState.copy(status = AboutUpdateStatus.Available("0.2.0", link)), taps)
        ui.clickable("Update").click()
        assertEquals(listOf(link), taps.updates)

        val noLink = host(baseState.copy(status = AboutUpdateStatus.Available("0.2.0", null)))
        assertTrue("no button without a link", noLink.nodes().none { it.texts() == listOf("Update") })
        for (status in listOf(AboutUpdateStatus.Current, AboutUpdateStatus.Required, AboutUpdateStatus.Failed)) {
            val other = host(baseState.copy(status = status))
            assertTrue("$status has no button", other.nodes().none { it.texts() == listOf("Update") })
        }
    }

    @Test
    fun checkForUpdatesAsksAndIsDisabledWhileChecking() {
        val taps = Taps()
        val ui = host(baseState, taps)
        val row = ui.clickable("Check for Updates")
        assertFalse(row.isDisabled())
        row.click()
        assertEquals(1, taps.checks)

        val checking = host(baseState.copy(status = AboutUpdateStatus.Checking))
        val running = checking.nodes().single { it.texts() == listOf("Checking…") }
        assertTrue(running.isDisabled())
        assertTrue(checking.nodes().none { it.texts() == listOf("Check for Updates") })
    }

    @Test
    fun serverRowsReadTitleAndValue() {
        val ui = host(baseState)
        assertTrue(ui.nodes().any { it.texts() == listOf("Address", "Official Shroud server") })
        assertTrue(ui.nodes().any { it.texts() == listOf("Server version", "0.1.0") })
        val unknown = host(baseState.copy(serverAddress = "shroud.example.org", serverVersion = null))
        assertTrue(unknown.nodes().any { it.texts() == listOf("Address", "shroud.example.org") })
        assertTrue(unknown.nodes().any { it.texts() == listOf("Server version", "—") })
    }

    @Test
    fun privacyNoteAndLinks() {
        val taps = Taps()
        val ui = host(baseState, taps)
        assertTrue(
            ui.nodesWithText(
                "Messages, media and calls are end-to-end encrypted. They’re sealed on your devices, so the server passes them on without being able to read them.",
            ).isNotEmpty(),
        )
        for (header in listOf("UPDATES", "SERVER", "PRIVACY", "MORE")) {
            val node = ui.nodes().single { it.texts() == listOf(header) }
            assertTrue(header, SemanticsProperties.Heading in node.config)
        }
        ui.clickable("Source Code").click()
        ui.clickable("Open-Source Licenses").click()
        assertEquals(1, taps.sourceCode)
        assertEquals(1, taps.licenses)
        assertEquals("https://github.com/AsP3X/shroud", AboutCopy.SOURCE_CODE_URL)
    }

    @Test
    fun rowsAreFullTouchTargets() {
        val ui = host(baseState.copy(status = AboutUpdateStatus.Available("0.2.0", link)))
        val density = ui.activity.resources.displayMetrics.density
        for (title in listOf("Update", "Check for Updates", "Source Code", "Open-Source Licenses")) {
            // The layout size: rows below the fold are clipped in boundsInRoot.
            assertTrue(title, ui.clickable(title).size.height >= 48 * density - 0.5f)
        }
    }

    // ---- Rules ----

    @Test
    fun aRunningCheckWinsThenAFailureThenTheAnswer() {
        val available = ClientUpdate(ClientUpdateStatus.UpdateAvailable, "0.2.0", link, "0.1.5")
        val answered = UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateAvailable)
        assertEquals(AboutUpdateStatus.Checking, AboutUpdateStatus.of(available, answered, checking = true))
        assertEquals(AboutUpdateStatus.Checking, AboutUpdateStatus.of(ClientUpdate.CURRENT, null, checking = true))
        // A failed check shows as failed even over an older offer.
        assertEquals(AboutUpdateStatus.Failed, AboutUpdateStatus.of(available, UpdateCheckOutcome.Failed, checking = false))
        assertEquals(AboutUpdateStatus.Unchecked, AboutUpdateStatus.of(ClientUpdate.CURRENT, null, checking = false))
        assertEquals(AboutUpdateStatus.Available("0.2.0", link), AboutUpdateStatus.of(available, answered, checking = false))
        assertEquals(
            AboutUpdateStatus.Current,
            AboutUpdateStatus.of(ClientUpdate.CURRENT, UpdateCheckOutcome.Answered(ClientUpdateStatus.Current), checking = false),
        )
        assertEquals(
            AboutUpdateStatus.Required,
            AboutUpdateStatus.of(
                ClientUpdate(ClientUpdateStatus.UpdateRequired, "0.3.0", null),
                UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateRequired),
                checking = false,
            ),
        )
    }

    @Test
    fun theAddressIsOfficialOrTheHost() {
        assertEquals("Official Shroud server", AboutCopy.address(ServerConfiguration.official))
        assertEquals("10.0.2.2", AboutCopy.address(ServerConfiguration.localDevelopment("10.0.2.2", 8080)))
        assertEquals("shroud.example.org", AboutCopy.address(ServerConfiguration.localDevelopment("  shroud.example.org ", 443)))
        val blank = ServerConfiguration.localDevelopment(" ", 8080)
        assertEquals(blank.selfHostedPreview, AboutCopy.address(blank))
    }
}

package de.corespace.shroud.ui.onboarding

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server sheet as TalkBack meets it (`ServerSettingsSheet.swift`; settings-lock addendum
 * *ServerSettingsSheet.swift* V.3-V.5; design `aNX3S`): the two connection cards as one selected
 * radio, the self-hosted details, Save's validation sentences (`ServerConfiguration.swift:89-107`
 * plus Android's plain-HTTP rule) and the account context that saves without asking while the
 * endpoint stays the same. The account context's "Change server?" path is [de.corespace.shroud.ui.lock.LockScreenTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServerSettingsSheetTest {
    private val hosts = HarnessHosts()
    private val saved = mutableListOf<ServerConfiguration>()
    private val signedOut = mutableListOf<ServerConfiguration>()
    private var cancelled = 0

    @After
    fun tearDown() = hosts.disposeAll()

    private fun sheet(initial: ServerConfiguration = ServerConfiguration.official, accountContext: Boolean = false) = hosts.host {
        OverlayHost {
            ServerSettingsContent(
                initial = initial,
                onSave = { saved += it },
                onCancel = { cancelled++ },
                accountContext = accountContext,
                onSignOut = if (accountContext) ({ signedOut += it }) else null,
            )
        }
    }

    private fun ComposeHarness.official() = button("$OFFICIAL_TITLE. Managed by Shroud · always up to date")
    private fun ComposeHarness.selfHosted() = button("$SELF_HOSTED_TITLE. Your Docker / private server")
    private val SemanticsNode.isSelected: Boolean get() = config.getOrNull(SemanticsProperties.Selected) == true

    /** Self-hosted, with the address fields set as typed. */
    private fun ComposeHarness.selfHostedAt(host: String, port: String = "8080", https: Boolean? = null) {
        click(selfHosted())
        type(field("Address / host"), host)
        type(field("Port"), port)
        if (https != null && (button("Use HTTPS").config.getOrNull(SemanticsProperties.StateDescription) == "On") != https) click(button("Use HTTPS"))
    }

    @Test
    fun theSavedServerIsTheSelectedCardAndSaveKeepsIt() {
        // V.2: the sheet opens on the saved configuration; V.3: one selected card at a time.
        val ui = sheet()
        assertTrue(ui.official().isSelected)
        assertFalse(ui.selfHosted().isSelected)
        assertTrue(ui.shows(OFFICIAL_INFO))
        assertFalse(ui.shows("Address / host"))
        assertFalse(ui.shows(SIGNED_IN_WARNING))
        ui.click(ui.button("Save"))
        assertEquals(listOf(ServerConfiguration.official), saved)
    }

    @Test
    fun selfHostedShowsItsDetailsAndTheEmulatorHint() {
        // V.3 self-hosted section and the Android info card copy ([A]: emulator, 10.0.2.2).
        val ui = sheet()
        ui.click(ui.selfHosted())
        assertTrue(ui.selfHosted().isSelected)
        assertFalse(ui.official().isSelected)
        assertTrue(ui.shows("SELF-HOSTED DETAILS"))
        assertTrue(ui.shows(EMULATOR_INFO))
        ui.type(ui.field("Address / host"), "192.168.1.20")
        ui.type(ui.field("Port"), "8080")
        assertTrue(ui.nodesWithText("https://192.168.1.20:8080/api/v1").isNotEmpty())
    }

    @Test
    fun theSameCardAgainChangesNothing() {
        // V3: selecting the selected mode does nothing (no reset of the error, no haptic).
        val ui = sheet()
        ui.click(ui.selfHosted())
        ui.type(ui.field("Address / host"), " ")
        ui.click(ui.button("Save"))
        assertTrue(ui.shows(BLANK_HOST))
        ui.click(ui.selfHosted())
        assertTrue(ui.shows(BLANK_HOST))
        // Another mode clears it.
        ui.click(ui.official())
        assertFalse(ui.shows(BLANK_HOST))
    }

    @Test
    fun saveSaysWhatIsWrongAndSavesNothing() {
        // V.4 step 1: the three iOS sentences; nothing reaches the store.
        val ui = sheet()
        ui.selfHostedAt(host = "  ")
        ui.click(ui.button("Save"))
        assertTrue(ui.shows(BLANK_HOST))

        // V7: the port keeps digits only and at most five of them; 80801 is still out of range.
        ui.type(ui.field("Address / host"), "192.168.1.20")
        ui.type(ui.field("Port"), "80a8012")
        assertEquals("80801", ui.field("Port").editableText)
        ui.click(ui.button("Save"))
        assertFalse(ui.shows(BLANK_HOST))
        assertTrue(ui.shows("Port must be a number between 1 and 65535."))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun plainHttpReachesOnlyLocalAddresses() {
        // Android's rule (`ServerConfiguration.kt:49`; iOS gets it from ATS at request time).
        val ui = sheet()
        ui.selfHostedAt(host = "chat.example.com", port = "443", https = false)
        ui.click(ui.button("Save"))
        assertTrue(ui.shows(ServerConfiguration.PLAIN_HTTP_REFUSED))
        assertTrue(saved.isEmpty())

        ui.type(ui.field("Address / host"), "10.0.2.2")
        ui.type(ui.field("Port"), "8080")
        ui.click(ui.button("Save"))
        val stored = saved.single()
        assertEquals(ServerConnectionMode.SelfHosted, stored.mode)
        assertEquals("http://10.0.2.2:8080/api/v1", stored.resolvedBaseUrl)
    }

    @Test
    fun cancelLeavesWithoutSaving() {
        val ui = sheet()
        ui.click(ui.selfHosted())
        ui.click(ui.button("Cancel"))
        assertEquals(1, cancelled)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun signedInTheSheetWarnsAndTheSameEndpointSavesWithoutAsking() {
        // V.1 / V.4 step 2: only an endpoint change asks "Change server?" and signs out.
        val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
        val ui = sheet(initial = local, accountContext = true)
        assertTrue(ui.shows(SIGNED_IN_WARNING))
        assertTrue(ui.selfHosted().isSelected)
        assertEquals("10.0.2.2", ui.field("Address / host").editableText)
        ui.click(ui.button("Save"))
        assertFalse(ui.shows("Change server?"))
        assertEquals(listOf(local), saved)
        assertTrue(signedOut.isEmpty())
    }

    private companion object {
        const val OFFICIAL_TITLE = "Official Shroud server"
        const val SELF_HOSTED_TITLE = "Self-hosted"
        const val BLANK_HOST = "Enter a host or IP address."
        const val OFFICIAL_INFO =
            "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."
        const val EMULATOR_INFO =
            "For Docker Compose on an emulator use 10.0.2.2 and port 8080. On a physical device, use your computer’s LAN IP."
    }
}

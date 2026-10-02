package de.corespace.shroud.ui.onboarding

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import de.corespace.shroud.core.net.ServerConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Welcome as TalkBack meets it (`WelcomeView.swift`; settings-lock addendum *WelcomeView.swift* W.2,
 * W6; design `oOR2X`): the copy, the two calls to action with iOS's identifiers, one stop per
 * feature tile, and the server it talks to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WelcomeScreenTest {
    private val hosts = HarnessHosts()
    private val taps = mutableListOf<String>()

    @After
    fun tearDown() = hosts.disposeAll()

    private fun screen(server: ServerConfiguration = ServerConfiguration.official) = hosts.host {
        WelcomeScreen(server, onStartMessaging = { taps += "signUp" }, onLogIn = { taps += "logIn" }, onOpenServerSettings = { taps += "server" })
    }

    @Test
    fun theCopyAndTheThreeTiles() {
        val ui = screen()
        assertTrue(ui.shows("Private messaging,\nfully encrypted"))
        assertTrue(ui.shows("No phone number. No email. Just your username and a 12-word encryption phrase."))
        // Each tile is one stop: its title and subtitle read together.
        for ((title, subtitle) in listOf(
            "End-to-end encrypted" to "Messages decrypt only on your devices",
            "Voice messages" to "Encrypted audio with on-device transcription",
            "Secure calls" to "Voice and video with WebRTC encryption",
        )) {
            assertEquals(1, ui.nodesWithText(title).size)
            assertTrue(ui.nodesWithText(title).single().config[SemanticsProperties.Text].any { it.text == subtitle })
        }
    }

    @Test
    fun theCallsToActionCarryIosIdentifiers() {
        // W6: `welcome.startMessaging`, `welcome.logIn`.
        val ui = screen()
        ui.click(ui.tagged("welcome.startMessaging"))
        ui.click(ui.tagged("welcome.logIn"))
        ui.click(ui.button("Server settings"))
        assertEquals(listOf("signUp", "logIn", "server"), taps)
    }

    @Test
    fun theConnectionHintNamesTheServer() {
        assertTrue(screen().shows("Current server Official Shroud server"))
    }

    @Test
    fun aSelfHostedServerShowsItsAddress() {
        val ui = screen(ServerConfiguration.localDevelopment("10.0.2.2", 8080))
        assertTrue(ui.shows("Current server ${ServerConfiguration.localDevelopment("10.0.2.2", 8080).selfHostedPreview}"))
    }
}

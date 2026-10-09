package de.corespace.shroud.ui.settings

import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Server's Save rules (`ServerSettingsView.swift:59-62, 418-425, 472-490`; settings-lock §10.1). */
class ServerSettingsLogicTest {
    private val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
    private val official = ServerConfiguration.official

    @Test
    fun theEndpointChangesWithTheUrlOrTheMode() {
        assertFalse(ServerSettingsLogic.endpointChanged(local, local))
        assertTrue(ServerSettingsLogic.endpointChanged(local.copy(port = "8081"), local))
        assertTrue(ServerSettingsLogic.endpointChanged(local.copy(useHTTPS = true), local))
        assertTrue(ServerSettingsLogic.endpointChanged(official, local))
        // Same mode, cosmetic difference that resolves to the same root.
        assertFalse(ServerSettingsLogic.endpointChanged(local.copy(apiPath = "/api/v1/"), local))
        // Official ignores the self-hosted fields it carries.
        assertFalse(ServerSettingsLogic.endpointChanged(official.copy(host = "elsewhere"), official))
        // Self-hosted at the official root is still another mode.
        val sameRoot = ServerConfiguration(ServerConnectionMode.SelfHosted, "shroud-app.com", "", "/api/v1", true)
        assertEquals(official.resolvedBaseUrl, sameRoot.resolvedBaseUrl)
        assertTrue(ServerSettingsLogic.endpointChanged(sameRoot, official))
    }

    @Test
    fun aRunningSaveIgnoresTaps() {
        assertEquals(ServerSaveDecision.Ignore, ServerSettingsLogic.decide(local, local, signedIn = true, busy = true))
    }

    @Test
    fun invalidFieldsShowTheirMessage() {
        val noHost = local.copy(host = " ")
        assertEquals(ServerSaveDecision.Invalid("Enter a host or IP address."), ServerSettingsLogic.decide(noHost, local, signedIn = true, busy = false))
        val badPort = local.copy(port = "70000")
        assertEquals(
            ServerSaveDecision.Invalid("Port must be a number between 1 and 65535."),
            ServerSettingsLogic.decide(badPort, local, signedIn = false, busy = false),
        )
        val plainHttp = local.copy(host = "chat.example.org")
        assertEquals(ServerSaveDecision.Invalid(ServerConfiguration.PLAIN_HTTP_REFUSED), ServerSettingsLogic.decide(plainHttp, local, true, false))
    }

    @Test
    fun aChangedEndpointWhileSignedInAsksFirst() {
        assertEquals(ServerSaveDecision.ConfirmSignOut, ServerSettingsLogic.decide(official, local, signedIn = true, busy = false))
        assertEquals(ServerSaveDecision.ConfirmSignOut, ServerSettingsLogic.decide(local.copy(port = "9090"), local, signedIn = true, busy = false))
    }

    @Test
    fun otherwiseItSaves() {
        assertEquals(ServerSaveDecision.Save, ServerSettingsLogic.decide(local, local, signedIn = true, busy = false))
        assertEquals(ServerSaveDecision.Save, ServerSettingsLogic.decide(official, local, signedIn = false, busy = false))
    }

    @Test
    fun copy() {
        assertEquals(
            "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure.",
            ServerSettingsLogic.infoCopy(ServerConnectionMode.Official),
        )
        assertEquals(
            "For Docker Compose on an emulator use 10.0.2.2 and port 8080. On a physical device, use your computer’s LAN IP.",
            ServerSettingsLogic.infoCopy(ServerConnectionMode.SelfHosted),
        )
        assertEquals("Change server?", ServerSettingsLogic.CONFIRM_TITLE)
        assertEquals(
            "Switching servers signs you out of this account on this device. You can sign in again on the new server.",
            ServerSettingsLogic.CONFIRM_MESSAGE,
        )
        assertEquals("Save and sign out", ServerSettingsLogic.CONFIRM_ACTION)
        assertEquals(480L, ServerSettingsLogic.SAVING_MS)
        assertEquals(620L, ServerSettingsLogic.SAVED_HOLD_MS)
    }
}

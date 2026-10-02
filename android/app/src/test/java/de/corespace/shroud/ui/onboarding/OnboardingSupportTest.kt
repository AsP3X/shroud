package de.corespace.shroud.ui.onboarding

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.badgePulses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding's small rules: Android 17's local-network permission, the `KEYS_REQUIRED` check
 * (P11b), the server sheet's endpoint change (`ServerSettingsSheet.swift:53-56`), the zoom's corner
 * radius (addendum Hero H.2), the phrase clipboard's trim (crypto §17.1) and the word badge's pulse
 * (addendum EncryptionPhraseCard E1).
 */
class OnboardingSupportTest {
    private fun selfHosted(host: String, port: String = "8080", https: Boolean = false) =
        ServerConfiguration(ServerConnectionMode.SelfHosted, host, port, "/api/v1", https)

    @Test
    fun theLocalNetworkPermissionIsNeededForLanServersOnAndroid17() {
        assertTrue(localNetworkPermissionNeeded(selfHosted("10.0.2.2"), sdk = 37) { false })
        assertTrue(localNetworkPermissionNeeded(selfHosted("192.168.1.20"), sdk = 37) { false })
        assertTrue(localNetworkPermissionNeeded(selfHosted("nas.local"), sdk = 37) { false })
        // Granted, older Android, the managed service, the phone's own loopback, a public host: not asked.
        assertFalse(localNetworkPermissionNeeded(selfHosted("10.0.2.2"), sdk = 37) { true })
        assertFalse(localNetworkPermissionNeeded(selfHosted("10.0.2.2"), sdk = 36) { false })
        assertFalse(localNetworkPermissionNeeded(ServerConfiguration.official, sdk = 37) { false })
        for (loopback in listOf("localhost", "127.0.0.1", "::1", "[::1]", " LOCALHOST ")) {
            assertFalse(loopback, localNetworkPermissionNeeded(selfHosted(loopback), sdk = 37) { false })
        }
        assertFalse(localNetworkPermissionNeeded(selfHosted("chat.example.com", "443", true), sdk = 37) { false })
    }

    @Test
    fun onlyKeysRequiredMeansTheAccountNeverHadAPhrase() {
        // `LogInFlowView.isKeysRequired` (`:777-781`); the server's 404 envelope (`error.rs:148-154`).
        assertTrue(isKeysRequired(ApiError.Server("KEYS_REQUIRED", "No pre-key bundle is available for this user.", 404)))
        assertFalse(isKeysRequired(ApiError.Server("NOT_FOUND", "Not found.", 404)))
        assertFalse(isKeysRequired(ApiError.Transport("offline")))
        assertFalse(isKeysRequired(IllegalStateException("KEYS_REQUIRED")))
    }

    @Test
    fun anEndpointChangeIsAnotherUrlOrAnotherMode() {
        val saved = selfHosted("192.168.1.20")
        assertFalse(endpointChanged(saved, saved))
        assertFalse(endpointChanged(saved.copy(host = " 192.168.1.20 "), saved))
        assertTrue(endpointChanged(saved.copy(port = "8081"), saved))
        assertTrue(endpointChanged(saved.copy(useHTTPS = true), saved))
        assertTrue(endpointChanged(ServerConfiguration.official, saved))
        assertTrue(endpointChanged(saved, ServerConfiguration.official))
        assertFalse(endpointChanged(ServerConfiguration.official, ServerConfiguration.official))
    }

    @Test
    fun theZoomClipOpensFromTheBrandTileToTheWindow() {
        // The 80 dp mark's 28 % corners (22.4 dp), shrinking as the frame grows.
        assertEquals(22.4f, heroCornerRadius(80f), 1e-4f)
        assertEquals(11.2f, heroCornerRadius(40f), 1e-4f)
        assertEquals(4.48f, heroCornerRadius(400f), 1e-4f)
        assertEquals(0f, heroCornerRadius(0f), 0f)
        assertEquals(0f, heroCornerRadius(-3f), 0f)
        assertEquals("onboardingBrandHero", OnboardingHeroId.BRAND)
    }

    @Test
    fun theClipboardGetsTheTrimmedPhraseAndNeverABlankOne() {
        // `EncryptionPhrasePasteboard.copy` trims and refuses blanks (`EncryptionPhrasePasteboardTests.copyRejectsEmptyPhrase`).
        // `EncryptionPhrasePasteboardTests.swift:10, 20, 28-29`, verbatim.
        val phrase = "ember copper lyric marble frost anchor velvet orbit prism delta canyon harbor"
        assertEquals(phrase, PhraseClipboard.prepared(phrase))
        assertEquals(
            "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima",
            PhraseClipboard.prepared("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"),
        )
        assertNull(PhraseClipboard.prepared(""))
        assertNull(PhraseClipboard.prepared("   "))
        assertEquals("abandon about", PhraseClipboard.prepared("  abandon about \n"))
        assertNull(PhraseClipboard.prepared("   \n\t"))
        assertEquals("Shroud encryption phrase", PhraseClipboard.LABEL)
    }

    @Test
    fun aWordBadgePulsesOnlyWhenItsWordArrives() {
        assertTrue(badgePulses(was = false, now = true))
        assertFalse(badgePulses(was = true, now = true))
        assertFalse(badgePulses(was = false, now = false))
        assertFalse(badgePulses(was = true, now = false))
    }

    @Test
    fun theSignedInWarning() {
        // `ServerSettingsSheet.swift:338-353`.
        assertEquals(
            "Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint.",
            SIGNED_IN_WARNING,
        )
    }
}

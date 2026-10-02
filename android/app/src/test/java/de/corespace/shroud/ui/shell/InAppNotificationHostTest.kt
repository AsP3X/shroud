package de.corespace.shroud.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.notifications.InAppNotification
import de.corespace.shroud.core.notifications.NotificationKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The in-app banner and its host per design `Chats — In-App Banner` (qvEKj; iOS
 * `InAppNotificationBanner.swift:9-105`; shell-chats §10.9–10.10): a card at most 500 dp wide,
 * 8 dp from the sides, 4 dp under the status bar; one TalkBack button ("Opens the chat", custom
 * action "Dismiss") announced politely as "{title}: {body}"; a drag up past 24 dp — or a flick that
 * would carry it past 60 dp — dismisses, anything else springs back, only upward movement shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class InAppNotificationHostTest {
    @After
    fun tearDown() = ShellUiHarness.disposeAll()

    private val jane = InAppNotification(
        kind = NotificationKind.Message,
        peerUserId = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f"),
        username = "jane_cooper",
        title = "jane_cooper",
        body = "Are we still on for 7?",
    )

    private class Host(initial: InAppNotification?) {
        var banner by mutableStateOf(initial)
        val opened = mutableListOf<InAppNotification>()
        val dismissed = mutableListOf<InAppNotification>()
        val ui = ShellUiHarness {
            InAppNotificationHost(banner = banner, onOpen = { opened += it }, onDismiss = { dismissed += it })
        }
    }

    @Test
    fun theBannerIsOnePoliteButtonThatOpensTheChatOrDismisses() {
        val host = Host(jane)
        val node = host.ui.node("jane_cooper: Are we still on for 7?")
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        val click = node.config[SemanticsActions.OnClick]
        assertEquals("Opens the chat", click.label)
        val dismiss = node.config[SemanticsActions.CustomActions].single()
        assertEquals("Dismiss", dismiss.label)

        click.action!!.invoke()
        assertEquals(listOf(jane), host.opened)
        dismiss.action()
        assertEquals(listOf(jane), host.dismissed)
    }

    @Test
    fun theCardSitsUnderTheStatusBarEightDpFromTheSides() {
        val host = Host(jane)
        val card = host.ui.boundsDp(host.ui.node("jane_cooper: Are we still on for 7?"))
        assertEquals(8f, card.left, 0.5f)
        assertEquals(host.ui.rootWidthDp() - 8f, card.right, 0.5f)
        assertTrue("top ${card.top}", card.top >= 4f && card.top <= 4f + 52f)
    }

    @Test
    @Config(qualifiers = "w840dp-h915dp")
    fun onAWideWindowTheCardStops500DpWideCentred() {
        val host = Host(jane)
        val card = host.ui.boundsDp(host.ui.node("jane_cooper: Are we still on for 7?"))
        assertEquals(500f, card.width, 0.5f)
        assertEquals(host.ui.rootWidthDp() / 2, card.center.x, 0.5f)
    }

    @Test
    fun aNewBannerReplacesTheOldOneAndNoneLeavesNothing() {
        val host = Host(jane)
        val next = jane.copy(id = "2", title = "Shroud", username = null, body = "New message")
        host.banner = next
        host.ui.idle()
        assertTrue(host.ui.has("Shroud: New message"))
        assertFalse(host.ui.has("jane_cooper: Are we still on for 7?"))
        host.banner = null
        host.ui.idle()
        assertFalse(host.ui.has("Shroud: New message"))
    }

    @Test
    fun aDragUpDismissesPast24DpOrWithAFlick() {
        assertFalse(InAppBannerGesture.dismisses(dragYDp = -24f, velocityYDpPerSecond = 0f))
        assertTrue(InAppBannerGesture.dismisses(dragYDp = -25f, velocityYDpPerSecond = 0f))
        // A short flick: −10 dp moving up at 600 dp/s would end at −70 dp (iOS predictedEndTranslation).
        assertTrue(InAppBannerGesture.dismisses(dragYDp = -10f, velocityYDpPerSecond = -600f))
        assertFalse(InAppBannerGesture.dismisses(dragYDp = -10f, velocityYDpPerSecond = -400f))
        assertFalse("a drag down never dismisses", InAppBannerGesture.dismisses(dragYDp = 80f, velocityYDpPerSecond = 2_000f))
        assertEquals(0f, InAppBannerGesture.visibleOffset(30f), 0f)
        assertEquals(-30f, InAppBannerGesture.visibleOffset(-30f), 0f)
    }
}

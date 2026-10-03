package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.model.Haptic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * `announce` — an arrival over the socket (iOS `NotificationsController.announce`,
 * `ios/shroud/Services/Notifications/NotificationsController.swift:88-130`; notifications-push
 * §5.12.2, `NotificationsControllerAnnounceTest` of §7.2): mutes, preferences, background posts,
 * the open chat, the 1.2 s quiet window, banners and their text.
 */
class NotificationsControllerAnnounceTest {
    private val peer = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val chat = UUID.fromString("6f9619ff-8b86-4d01-b42d-00c04fc964ff")

    private fun ControllerHarness.message(text: String? = "hi", muted: Boolean = false, kind: NotificationKind = NotificationKind.Message) =
        controller.announce(kind, peer, "alice", if (kind == NotificationKind.ContactRequest) null else chat, text, muted)

    @Test
    fun aMutedChatStaysQuietButARequestDoesNot() = runTest {
        val h = ControllerHarness(this)
        h.message(muted = true)
        h.message(kind = NotificationKind.Reaction, muted = true)
        assertNull(h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())
        h.message(kind = NotificationKind.ContactRequest, muted = true)
        assertEquals("Wants to add you as a contact", h.controller.banner.value!!.body)
        assertEquals(1, h.sounds.played.size)
    }

    @Test
    fun reactionsAndRequestsCanBeTurnedOff() = runTest {
        val h = ControllerHarness(this)
        h.preferences.reactions = false
        h.preferences.contactRequests = false
        h.message(kind = NotificationKind.Reaction)
        h.message(kind = NotificationKind.ContactRequest)
        assertNull(h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())
        assertTrue(h.haptics.isEmpty())
    }

    /** App not in front, socket still open (a call keeps it): a local post worded like a push (`:101-105`). */
    @Test
    fun inTheBackgroundALocalNotificationIsPosted() = runTest {
        val h = ControllerHarness(this)
        h.resumed = false
        h.controller.setBadge(3)
        h.message(text = "secret text")
        val posted = h.sink.posted.single()
        assertEquals("alice", posted.title)
        assertEquals("secret text", posted.body)
        assertEquals("New message", posted.publicBody)
        assertEquals(3, posted.number)
        assertEquals(chat.toString(), posted.tag)
        assertNull("no banner in the background", h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())

        h.preferences.showSender = false
        h.message()
        assertEquals("Shroud", h.sink.posted.last().title)
        assertNull(h.sink.posted.last().name)
        assertEquals("hi", h.sink.posted.last().body)
        assertEquals(2, h.sink.posted.last().count)
    }

    /** Message Preview off: the shade keeps the generic line, including when a push already covers the background. */
    @Test
    fun previewOffKeepsTheGenericLine() = runTest {
        val h = ControllerHarness(this)
        h.resumed = false
        h.preferences.showPreview = false
        h.controller.setPushCoversBackground(true)
        h.message(text = "secret text")
        assertTrue(h.sink.posted.isEmpty())
        h.controller.setPushCoversBackground(false)
        h.message(text = "secret text")
        assertEquals("New message", h.sink.posted.single().body)
        assertNull(h.sink.posted.single().publicBody)
    }

    /** A push already covers the background. With a preview, this phone replaces that generic line. */
    @Test
    fun inTheBackgroundAPushPathCoversIt() = runTest {
        val h = ControllerHarness(this)
        h.resumed = false
        h.controller.setPushCoversBackground(true)
        h.message(text = "hello")
        assertEquals("hello", h.sink.posted.single().body)
        h.preferences.enabled = false
        h.message(text = "again")
        assertEquals("Show Notifications off: nothing more", 1, h.sink.posted.size)
    }

    @Test
    fun theOpenChatGetsNothing() = runTest {
        val h = ControllerHarness(this)
        h.controller.activePeerId = peer
        h.message()
        h.message(kind = NotificationKind.Reaction)
        assertNull(h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())
        assertTrue(h.haptics.isEmpty())
        h.message(kind = NotificationKind.ContactRequest)
        assertEquals(NotificationKind.ContactRequest, h.controller.banner.value!!.kind)
    }

    /** Arrivals within 1.2 s replace the banner silently; the window is stamped even when nothing plays. */
    @Test
    fun theQuietWindow() = runTest {
        val h = ControllerHarness(this)
        h.message(text = "one")
        h.clock.advanceBy(1_000)
        h.message(text = "two")
        assertEquals(1, h.sounds.played.size)
        assertEquals(listOf(Haptic.Medium), h.haptics)
        assertEquals("two", h.controller.banner.value!!.body)

        h.clock.advanceBy(1_000)
        h.message(text = "three")
        assertEquals("still inside the window of the second arrival", 1, h.sounds.played.size)
        h.clock.advanceBy(1_300)
        h.message(text = "four")
        assertEquals(2, h.sounds.played.size)
        assertEquals(listOf(Haptic.Medium, Haptic.Medium), h.haptics)
    }

    @Test
    fun soundsVibrationAndBannersFollowTheirSwitches() = runTest {
        val h = ControllerHarness(this)
        h.preferences.sound = NotificationSound.Pop
        h.preferences.inAppBanners = false
        h.message()
        assertEquals(listOf(NotificationSound.Pop), h.sounds.played)
        assertEquals(listOf(Haptic.Medium), h.haptics)
        assertNull("banners off: sound only", h.controller.banner.value)

        h.clock.advanceBy(2_000)
        h.preferences.inAppBanners = true
        h.preferences.inAppSounds = false
        h.preferences.inAppVibrate = false
        h.message()
        assertEquals(1, h.sounds.played.size)
        assertEquals(1, h.haptics.size)
        assertEquals("hi", h.controller.banner.value!!.body)
    }

    /** The banner's text (`:115-122`): trimmed preview for messages, the kind's line otherwise. */
    @Test
    fun bannerText() = runTest {
        val h = ControllerHarness(this)
        h.message(text = "  hi  \n")
        assertEquals("hi", h.controller.banner.value!!.body)
        h.message(text = "   ")
        assertEquals("New message", h.controller.banner.value!!.body)
        h.message(text = null)
        assertEquals("New message", h.controller.banner.value!!.body)
        h.message(text = "a reaction never shows text", kind = NotificationKind.Reaction)
        assertEquals("Reacted to your message", h.controller.banner.value!!.body)
        h.preferences.showPreview = false
        h.message(text = "hidden")
        assertEquals("New message", h.controller.banner.value!!.body)
    }

    /** Show Sender off: "Shroud" as the title; the username stays for the open request (IAB:59-71). */
    @Test
    fun bannerTitle() = runTest {
        val h = ControllerHarness(this)
        h.message()
        val named = h.controller.banner.value!!
        assertEquals("alice", named.title)
        assertEquals("alice", named.username)
        assertEquals(peer, named.peerUserId)
        h.preferences.showSender = false
        h.message()
        val hidden = h.controller.banner.value!!
        assertEquals("Shroud", hidden.title)
        assertEquals("alice", hidden.username)
        assertTrue("a new banner gets a new id", named.id != hidden.id)
    }

    /** Arrivals keep the name cache fresh for the background connection — only while names are on. */
    @Test
    fun arrivalsRememberTheSendersName() = runTest {
        val dir = kotlin.io.path.createTempDirectory("names").toFile()
        try {
            val h = ControllerHarness(this, namesDir = dir)
            h.message()
            assertEquals("alice", h.nameCache!!.name(peer))
            h.preferences.showSender = false
            assertNull(h.nameCache.name(peer))
            assertEquals("turning names off deletes the cache and its key", 1, h.deletedKeys)
            h.message()
            h.preferences.showSender = true
            assertNull("nothing was kept while names were off", h.nameCache.name(peer))
        } finally {
            dir.deleteRecursively()
        }
    }
}

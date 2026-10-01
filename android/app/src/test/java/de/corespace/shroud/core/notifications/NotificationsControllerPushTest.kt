package de.corespace.shroud.core.notifications

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pushes that reach the running app, banners, taps, the badge and forgetting the account (iOS
 * `NotificationsController.swift:132-251`; notifications-push §5.12.3–5.12.8,
 * `NotificationsControllerPushTest` and `BannerLifetimeTest` of §7.2).
 */
class NotificationsControllerPushTest {
    private val peer = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val chat = UUID.fromString("6f9619ff-8b86-4d01-b42d-00c04fc964ff")

    private fun push(kind: NotificationKind?, rawKind: String = kind?.wire ?: "future_kind") = PushContents(
        kind = kind,
        rawKind = rawKind,
        thread = PushContents.threadFor(kind, chat),
        conversationId = chat,
        peerUserId = peer,
    )

    private fun ControllerHarness.signedInAndUnlocked() = apply {
        controller.isSignedIn = true
        controller.isUnlocked = true
    }

    // ---- onPushWhileRunning (`presentation(for:)`, `:182-205`) ----

    @Test
    fun signedOutPushesAreDropped() = runTest {
        val h = ControllerHarness(this)
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.Message), "alice"))
        assertNull(h.controller.banner.value)
    }

    @Test
    fun theSystemShowsWhatTheAppCannot() = runTest {
        val h = ControllerHarness(this).signedInAndUnlocked()
        h.resumed = false
        assertFalse("not in front: a system notification", h.controller.onPushWhileRunning(push(NotificationKind.Message), "alice"))
        h.resumed = true
        assertFalse("unknown kind", h.controller.onPushWhileRunning(push(null), "alice"))
        assertFalse("the test always shows in the shade", h.controller.onPushWhileRunning(push(NotificationKind.Test), null))
        h.controller.isUnlocked = false
        assertFalse("the lock screen is in front", h.controller.onPushWhileRunning(push(NotificationKind.Message), "alice"))
        assertNull(h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())
    }

    @Test
    fun theOpenChatsOwnPushIsSwallowed() = runTest {
        val h = ControllerHarness(this).signedInAndUnlocked()
        h.controller.activePeerId = peer
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.Message), "alice"))
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.Reaction), "alice"))
        assertNull(h.controller.banner.value)
        assertTrue(h.sounds.played.isEmpty())
    }

    /** A banner with the kind's line, one sound, no haptic and no quiet window (`:194-204`). */
    @Test
    fun inFrontTheAppShowsItsOwnBanner() = runTest {
        val h = ControllerHarness(this).signedInAndUnlocked()
        h.preferences.sound = NotificationSound.Glass
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.Message), "alice"))
        val banner = h.controller.banner.value!!
        assertEquals("alice", banner.title)
        assertEquals("alice", banner.username)
        assertEquals("New message", banner.body)
        assertEquals(listOf(NotificationSound.Glass), h.sounds.played)
        assertTrue(h.haptics.isEmpty())
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.ContactRequest), null))
        assertEquals("Shroud", h.controller.banner.value!!.title)
        assertNull(h.controller.banner.value!!.username)
        assertEquals(2, h.sounds.played.size)
        assertTrue(h.sink.posted.isEmpty())

        h.preferences.inAppBanners = false
        h.preferences.inAppSounds = false
        h.controller.dismissBanner()
        assertTrue(h.controller.onPushWhileRunning(push(NotificationKind.Reaction), "alice"))
        assertNull(h.controller.banner.value)
        assertEquals(2, h.sounds.played.size)
    }

    // ---- Banner lifetime (`:132-160`; §5.12.3) ----

    @Test
    fun aBannerLastsFourSeconds() = runTest {
        val h = ControllerHarness(this)
        h.controller.announce(NotificationKind.Message, peer, "alice", chat, "hi", false)
        advanceTimeBy(3_999)
        assertNotNull(h.controller.banner.value)
        advanceTimeBy(2)
        assertNull(h.controller.banner.value)
    }

    @Test
    fun withTalkBackItLastsTenSeconds() = runTest {
        val h = ControllerHarness(this)
        h.accessibility.touchExploration = true
        h.controller.show(InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = "alice", title = "alice", body = "hi"))
        advanceTimeBy(9_999)
        assertNotNull(h.controller.banner.value)
        advanceTimeBy(2)
        assertNull(h.controller.banner.value)
    }

    /** Android's "Time to take action" setting can only lengthen it. */
    @Test
    fun theUsersTimeoutSettingLengthensIt() = runTest {
        val h = ControllerHarness(this)
        h.accessibility.recommended = { 7_000 }
        h.controller.show(InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = null, title = "Shroud", body = "hi"))
        advanceTimeBy(6_999)
        assertNotNull(h.controller.banner.value)
        advanceTimeBy(2)
        assertNull(h.controller.banner.value)
        h.accessibility.recommended = { 1_000 }
        h.controller.show(InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = null, title = "Shroud", body = "hi"))
        advanceTimeBy(3_999)
        assertNotNull("never shorter than 4 s", h.controller.banner.value)
    }

    @Test
    fun aNewBannerRestartsTheTimer() = runTest {
        val h = ControllerHarness(this)
        h.controller.show(InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = null, title = "Shroud", body = "one"))
        advanceTimeBy(3_000)
        h.controller.show(InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = null, title = "Shroud", body = "two"))
        advanceTimeBy(3_000)
        assertEquals("two", h.controller.banner.value!!.body)
        advanceTimeBy(1_001)
        assertNull(h.controller.banner.value)
    }

    @Test
    fun dismissingAnotherBannerIsIgnored() = runTest {
        val h = ControllerHarness(this)
        val shown = InAppNotification(kind = NotificationKind.Message, peerUserId = peer, username = null, title = "Shroud", body = "hi")
        h.controller.show(shown)
        h.controller.dismissBanner("someone-else")
        assertEquals(shown, h.controller.banner.value)
        h.controller.dismissBanner(shown.id)
        assertNull(h.controller.banner.value)
        runCurrent()
    }

    /** A banner tap opens what it is about, with the banner's username (`:153-160`). */
    @Test
    fun openingABanner() = runTest {
        val h = ControllerHarness(this)
        h.controller.announce(NotificationKind.Message, peer, "alice", chat, "hi", false)
        h.controller.openBanner(h.controller.banner.value!!)
        assertNull(h.controller.banner.value)
        assertEquals(NotificationOpenRequest(NotificationKind.Message, peer, "alice"), h.controller.pendingOpen.value)
    }

    // ---- Taps (`:208-217`; §5.12.5) ----

    @Test
    fun aTapWaitsForTheChatsWithoutAName() = runTest {
        val h = ControllerHarness(this)
        h.controller.handleTap(NotificationKind.Message, peer)
        assertEquals(NotificationOpenRequest(NotificationKind.Message, peer, null), h.controller.pendingOpen.value)
        h.controller.handleTap(NotificationTap(NotificationKind.ContactRequest, peer))
        assertEquals(NotificationKind.ContactRequest, h.controller.pendingOpen.value!!.kind)
    }

    @Test
    fun theTestAndUnknownTapsOpenNothing() = runTest {
        val h = ControllerHarness(this)
        h.controller.handleTap(NotificationKind.Test, null)
        h.controller.handleTap(null, peer)
        assertNull(h.controller.pendingOpen.value)
    }

    @Test
    fun tapIntentsCarryAKindAndAPeerOnly() {
        val tap = NotificationTap.parse(NotificationTap.ACTION_OPEN_NOTIFICATION, "reaction", peer.toString().uppercase())!!
        assertEquals(NotificationTap(NotificationKind.Reaction, peer), tap)
        assertNull(NotificationTap.parse("android.intent.action.MAIN", "message", peer.toString()))
        assertEquals(NotificationTap(null, null), NotificationTap.parse(NotificationTap.ACTION_OPEN_NOTIFICATION, "future", "1-1-1-1-1"))
    }

    // ---- Badge, delivered, forgetting (`:219-251`) ----

    @Test
    fun theBadgeIsZeroWhenOff() = runTest {
        val h = ControllerHarness(this)
        h.resumed = false
        h.controller.setBadge(-2)
        h.controller.announce(NotificationKind.Message, peer, "alice", chat, null, false)
        assertEquals(0, h.sink.posted.last().number)
        h.preferences.badge = false
        h.controller.setBadge(5)
        h.controller.announce(NotificationKind.Message, peer, "alice", chat, null, false)
        assertEquals(0, h.sink.posted.last().number)
        h.preferences.badgeIncludesMuted = true
        assertTrue(h.controller.badgeIncludesMuted)
    }

    @Test
    fun clearingAChat() = runTest {
        val h = ControllerHarness(this)
        h.resumed = false
        h.controller.announce(NotificationKind.Message, peer, "alice", chat, null, false)
        h.controller.announce(NotificationKind.Reaction, peer, "alice", chat, null, false)
        h.controller.clearDelivered(chat)
        assertTrue(h.sink.shade.isEmpty())
    }

    /** The sound picker and badge toggles take effect on the channels at once (§5.6). */
    @Test
    fun aSoundChangeRecreatesTheChannels() = runTest {
        val h = ControllerHarness(this)
        assertTrue("messages.default.b" in h.store.channels)
        h.preferences.sound = NotificationSound.Chime
        assertTrue("messages.chime.b" in h.store.channels)
        assertFalse("messages.default.b" in h.store.channels)
        h.preferences.badge = false
        assertTrue("messages.chime.n" in h.store.channels)
    }

    /** `forgetAccount` (`:245-251`): memory, preferences, names, the shade; default channels. */
    @Test
    fun forgettingTheAccount() = runTest {
        val dir = kotlin.io.path.createTempDirectory("names").toFile()
        try {
            val h = ControllerHarness(this, namesDir = dir)
            h.preferences.sound = NotificationSound.Pulse
            h.controller.activePeerId = peer
            h.controller.handleTap(NotificationKind.Message, peer)
            h.controller.announce(NotificationKind.Message, UUID.randomUUID(), "bob", UUID.randomUUID(), "hi", false)
            h.resumed = false
            h.controller.announce(NotificationKind.Message, peer, "alice", chat, null, false)
            assertTrue(h.sink.shade.isNotEmpty())

            h.controller.forgetAccount()
            assertNull(h.controller.pendingOpen.value)
            assertNull(h.controller.activePeerId)
            assertNull(h.controller.banner.value)
            assertTrue("a reset stores nothing", h.prefsFile.keys.isEmpty())
            assertEquals(NotificationSound.Standard, h.preferences.sound)
            assertTrue("no notification survives", h.sink.shade.isEmpty())
            assertNull(h.nameCache!!.name(peer))
            assertFalse(java.io.File(dir, NotificationNameCache.FILE_NAME).exists())
            assertTrue(h.deletedKeys >= 1)
            assertEquals(setOf("messages.default.b", "contact_requests.default.b", "calls.missed.default.b", "push.background"), h.store.channels.keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- Permission (P6a) ----

    @Test
    fun askOnceAfterTheFirstUnlock() = runTest {
        val h = ControllerHarness(this)
        h.permission.authorization = NotificationAuthorization.NotDetermined
        h.controller.refreshAuthorization()
        assertEquals(NotificationAuthorization.NotDetermined, h.controller.authorization.value)
        assertTrue(h.controller.shouldRequestPermissionAfterUnlock())
        h.controller.markPermissionRequested()
        assertFalse(h.controller.shouldRequestPermissionAfterUnlock())

        val off = ControllerHarness(this)
        off.permission.authorization = NotificationAuthorization.NotDetermined
        off.preferences.enabled = false
        off.controller.refreshAuthorization()
        assertFalse("Show Notifications off: never asked", off.controller.shouldRequestPermissionAfterUnlock())
    }
}

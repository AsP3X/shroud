package de.corespace.shroud.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.MainActivity
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.ui.components.AvatarBitmap
import de.corespace.shroud.ui.components.AvatarPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The W2-NOTIF acceptance path on the platform classes (Robolectric's `NotificationManager`): with
 * the socket open and the app in the background a named local notification is posted, counted per
 * chat, worded like a push; its tap sets `pendingOpen`; the wipe leaves no notification
 * (00-plan §2.3 W2-NOTIF; notifications-push §5.6, §5.7, §5.12). The device run (W2-INT) repeats
 * it against a real server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsPlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val peer = UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b")
    private val chat = UUID.fromString("6f9619ff-8b86-4d01-b42d-00c04fc964ff")
    private val preferences = NotificationPreferences(FakeSharedPreferences(), StorageSeal())
    private val channels = NotificationChannels(AndroidChannelStore(context)) { preferences.state.value }
    private val sink = AndroidNotificationSink(context, MainActivity::class.java) { name, id ->
        AvatarBitmap.render(context, AvatarPalette.seed(name, id ?: UUID(0, 0)), AvatarPalette.initials(name))
    }
    private val notifier = SystemNotifier(sink, channels, StorageSeal(), DirectExecutor)

    @Before
    fun clean() {
        manager.cancelAll()
        for (channel in manager.notificationChannels) manager.deleteNotificationChannel(channel.id)
    }

    private fun shown(): List<Notification> = manager.activeNotifications.map { it.notification }

    @Test
    fun theChannels() {
        channels.ensure()
        val messages = manager.getNotificationChannel("messages.default.b")!!
        assertEquals("Messages", messages.name)
        assertEquals("New messages and reactions", messages.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, messages.importance)
        assertTrue(messages.canShowBadge())
        assertEquals(Notification.VISIBILITY_PRIVATE, messages.lockscreenVisibility)
        assertNotNull("the phone's notification sound", messages.sound)
        assertNotNull(manager.getNotificationChannel("contact_requests.default.b"))
        assertNotNull(manager.getNotificationChannel("calls.missed.default.b"))
        val background = manager.getNotificationChannel("push.background")!!
        assertEquals(NotificationManager.IMPORTANCE_MIN, background.importance)
        assertNull(background.sound)
        assertFalse(background.canShowBadge())

        preferences.sound = NotificationSound.Chime
        channels.ensure()
        assertNull(manager.getNotificationChannel("messages.default.b"))
        val chime = manager.getNotificationChannel("messages.chime.b")!!
        assertEquals("android.resource://de.corespace.shroud/raw/chime", chime.sound.toString())
        preferences.sound = NotificationSound.None
        preferences.badge = false
        channels.ensure()
        val silent = manager.getNotificationChannel("messages.none.n")!!
        assertNull(silent.sound)
        assertFalse(silent.canShowBadge())
    }

    /** §5.15, web-parity §7.3: the channel is read back to notice a sound changed in Android Settings. */
    @Test
    fun theChannelSoundIsReadBack() {
        val store = AndroidChannelStore(context)
        store.create(ChannelSpec("messages.glass.b", "Messages", null, NotificationManager.IMPORTANCE_HIGH, NotificationSound.Glass, true, true))
        assertFalse(store.soundDiffers("messages.glass.b", NotificationSound.Glass))
        assertTrue(store.soundDiffers("messages.glass.b", NotificationSound.Standard))
        assertTrue(store.soundDiffers("messages.glass.b", NotificationSound.None))
        assertFalse("no channel, nothing to say", store.soundDiffers("messages.pop.b", NotificationSound.Glass))
        preferences.sound = NotificationSound.Glass
        assertFalse(channels.messagesSoundDiffers())
    }

    @Test
    fun aNamedLocalNotificationItsTapAndTheWipe() = runTest {
        channels.ensure()
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val controller = NotificationsController(
            preferences = preferences,
            permission = FakePermission(),
            sounds = RecordingSoundPlayer(),
            systemNotifier = notifier,
            channels = channels,
            nameCache = null,
            isResumed = { false },
            accessibility = FakeAccessibility(),
            api = { error("no server") },
            systemAllows = { true },
            clock = FakeAppClock(),
            scope = scope,
            io = UnconfinedTestDispatcher(testScheduler),
        )
        controller.setBadge(4)
        controller.announce(NotificationKind.Message, peer, "alice", chat, "the secret text", muted = false)

        val active = manager.activeNotifications.single()
        assertEquals(chat.toString(), active.tag)
        assertEquals(SystemNotifier.ID_MESSAGE, active.id)
        val notification = active.notification
        assertEquals("messages.default.b", notification.channelId)
        assertEquals("alice", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("New message", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1, notification.extras.getInt(SystemNotifier.EXTRA_COUNT))
        assertEquals(4, notification.number)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Shroud", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull("the sender's avatar", notification.getLargeIcon())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse("never the message text", notification.extras.toString().contains("secret"))

        controller.announce(NotificationKind.Message, peer, "alice", chat, null, muted = false)
        assertEquals("2 new messages", shown().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        // The tap: the intent carries the kind and the peer, never the name.
        val tapIntent = shadowOf(shown().single().contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tapIntent.component!!.className)
        assertFalse(tapIntent.extras.toString().contains("alice"))
        val tap = NotificationTap.from(tapIntent)!!
        controller.handleTap(tap)
        assertEquals(NotificationOpenRequest(NotificationKind.Message, peer, null), controller.pendingOpen.value)

        controller.announce(NotificationKind.ContactRequest, peer, "alice", null, null, muted = false)
        assertEquals(2, shown().size)
        controller.forgetAccount()
        assertTrue("the wipe leaves no notification", manager.activeNotifications.isEmpty())
        assertNull(controller.pendingOpen.value)
    }

    @Test
    fun aHiddenSenderHasNoAvatar() {
        channels.ensure()
        notifier.postLocal(NotificationKind.Reaction, null, peer, chat, badge = 0)
        val notification = shown().single()
        assertEquals("Shroud", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Reacted to your message", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(notification.getLargeIcon())
        assertEquals("$chat:reaction", manager.activeNotifications.single().tag)
    }

    @Test
    fun aRingTimesOutAndClosesOnItsEnd() {
        channels.ensure()
        val call = UUID.fromString("00000000-0000-0000-0000-00000000001f")
        notifier.post(PushContents(NotificationKind.Call, "call", "calls", peerUserId = peer, callId = call), "dave")
        val ring = manager.activeNotifications.single()
        assertEquals("call:$call", ring.tag)
        assertEquals(60_000L, ring.notification.timeoutAfter)
        assertEquals("calls.missed.default.b", ring.notification.channelId)
        notifier.post(PushContents(NotificationKind.CallEnded, "call_ended", "calls", peerUserId = peer, callId = call), "dave")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun theSignedOutNoticeOpensTheAppOnly() {
        channels.ensure()
        notifier.post(PushContents(NotificationKind.Message, "message", chat.toString(), conversationId = chat, peerUserId = peer), "alice")
        notifier.postSignedOutNotice()
        val notice = shown().single()
        assertEquals("This phone was signed out.", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val tap = NotificationTap.from(shadowOf(notice.contentIntent).savedIntent)!!
        assertNull(tap.kind)
        assertNull(tap.peerUserId)
    }
}

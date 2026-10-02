package de.corespace.shroud.core.calls.system

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.core.calls.CallAudioRoute
import de.corespace.shroud.core.calls.CallAudioRouteType
import de.corespace.shroud.core.calls.CallEndCause
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.notifications.SystemNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Ring order and the notification that survives a refused phoneCall foreground start
 * (calls §6, 00-plan §1.7.11). Robolectric builds the real CallStyle notification; Telecom,
 * the ringer and the service start are fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallSystemTest {
    @Test
    fun speakerAndSelectRouteUpdateTheCurrentRoute() {
        val world = World()
        assertEquals(CallAudioRouteType.Earpiece, world.system.currentRoute.value?.type)
        assertEquals(2, world.system.audioRoutes.value.size)

        world.system.setSpeaker(true)
        assertEquals(CallAudioRouteType.Speaker, world.system.currentRoute.value?.type)

        val earpiece = world.system.audioRoutes.value.first { it.type == CallAudioRouteType.Earpiece }
        world.system.selectRoute(earpiece)
        assertEquals(CallAudioRouteType.Earpiece, world.system.currentRoute.value?.type)
        assertTrue(world.system.isOnEarpiece.value)
    }

    @Test
    fun reportIncomingPostsTheNotificationBeforeStartingTheForegroundService() {
        val world = World()
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)

        val posted = world.order.indexOf("notification")
        val started = world.order.indexOf("fgs")
        assertTrue(posted >= 0 && started > posted)
        assertEquals(0, world.telecom.adds)
        val notification = world.shade.active["${SystemNotifier.ID_CALL}"]
        assertNotNull(notification)
        assertEquals(CallChannels.INCOMING, notification!!.channelId)

        val show = intentWithAction(notification, "show")
        assertNotNull(show)
        assertEquals(CALL_ACTIVITY_CLASS, show!!.component?.className)
        assertEquals(Ids.wire(id), show.getStringExtra(CallIntents.EXTRA_CALL_ID))
        assertEquals("show", show.getStringExtra(CallIntents.EXTRA_ACTION))
        assertEquals(id to "show", CallIntents.parse(show))

        val answer = intentWithAction(notification, "answer")
        assertNotNull(answer)
        assertEquals(CALL_ACTIVITY_CLASS, answer!!.component?.className)
        assertEquals(Ids.wire(id), answer.getStringExtra(CallIntents.EXTRA_CALL_ID))
        assertEquals("answer", answer.getStringExtra(CallIntents.EXTRA_ACTION))
        assertEquals(id to "answer", CallIntents.parse(answer))
    }

    @Test
    fun refusedForegroundStartKeepsTheNotificationAndTheScreenRetries() {
        val world = World()
        world.starter.refuse = true
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)

        assertEquals(0, world.starter.starts)
        assertNotNull(world.shade.active["${SystemNotifier.ID_CALL}"])

        world.starter.refuse = false
        world.system.onCallScreenShown(id)

        assertEquals(1, world.starter.starts)
        assertNotNull(world.shade.active["${SystemNotifier.ID_CALL}"])
    }

    @Test
    fun reportEndedRemovesTheIncomingNotification() {
        val world = World()
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)
        world.system.reportEnded(id, CallEndCause.Remote)
        assertTrue(world.shade.active.isEmpty())
    }

    @Test
    fun hangupDuringForegroundStartStillPromotesBeforeStopping() {
        val world = World()
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)
        world.system.reportEnded(id, CallEndCause.Remote)
        val host = Robolectric.buildService(CallService::class.java).create().get()
        world.system.onServiceStart(host, CallService.promoteIntent(host, id), 7)
        assertEquals(SystemNotifier.ID_CALL, shadowOf(host).lastForegroundNotificationId)
    }

    @Test
    fun missedCausePostsCallBack() {
        val world = World()
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)
        world.system.reportEnded(id, CallEndCause.Missed)

        val notification = world.shade.active.values.single()
        val titles = notification.actions?.map { it.title?.toString() }.orEmpty()
        assertTrue(titles.contains("Call back"))
        assertEquals("Missed call", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun callIntentsParseShowAndAnswerAndRejectABadId() {
        val id = UUID.randomUUID()
        val show = Intent()
            .putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(id))
            .putExtra(CallIntents.EXTRA_ACTION, "show")
        val answer = Intent()
            .putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(id))
            .putExtra(CallIntents.EXTRA_ACTION, "answer")
        assertEquals(id to "show", CallIntents.parse(show))
        assertEquals(id to "answer", CallIntents.parse(answer))
        assertNull(CallIntents.parse(Intent().putExtra(CallIntents.EXTRA_CALL_ID, "not-a-uuid").putExtra(CallIntents.EXTRA_ACTION, "show")))
        assertNull(CallIntents.parse(Intent().putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(id)).putExtra(CallIntents.EXTRA_ACTION, "decline")))
    }

    @Test
    fun telecomUnavailableStillPostsTheNotification() {
        val world = World()
        world.telecom.available = false
        val id = UUID.randomUUID()
        world.system.reportIncoming(id, "", video = false)
        assertEquals(0, world.telecom.adds)
        assertNotNull(world.shade.active["${SystemNotifier.ID_CALL}"])
    }

    private fun intentWithAction(notification: Notification, action: String): Intent? {
        val pending = ArrayList<PendingIntent>()
        notification.fullScreenIntent?.let(pending::add)
        notification.contentIntent?.let(pending::add)
        notification.actions?.forEach { item -> item.actionIntent?.let(pending::add) }
        if (action == "answer") {
            notification.extras.getParcelable(NotificationCompat.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                ?.let(pending::add)
        }
        return pending.map { shadowOf(it).savedIntent }.firstOrNull { it.getStringExtra(CallIntents.EXTRA_ACTION) == action }
    }

    private class World {
        val order = mutableListOf<String>()
        val shade = RecordingShade(order)
        val starter = FakeStarter(order)
        val telecom = FakeTelecom()
        val system = AndroidCallSystem(
            context = ApplicationProvider.getApplicationContext(),
            notices = CallNotices(ApplicationProvider.getApplicationContext()),
            shade = shade,
            starter = starter,
            telecom = telecom,
            ringer = object : CallRinger {
                override fun start() {}
                override fun stop() {}
            },
            audio = object : CallAudio {
                override fun start(speaker: Boolean) = !speaker
                override fun setSpeaker(on: Boolean) = !on
                override fun stop() {}
            },
            proximity = object : ProximitySensor {
                override fun acquire() {}
                override fun release() {}
            },
            callbacks = object : CallSystemCallbacks {
                override fun onAnswer(callId: UUID) {}
                override fun onEnd(callId: UUID) {}
                override fun onMute(callId: UUID, muted: Boolean) {}
                override fun onToggleSpeaker() {}
            },
            missedChannelId = { "calls.missed.test" },
            appInForeground = { false },
        )
    }

    private class RecordingShade(private val order: MutableList<String>) : CallShade {
        val active = LinkedHashMap<String, Notification>()

        override fun ensureChannels() {}

        override fun post(tag: String?, id: Int, notification: Notification) {
            order += "notification"
            active[key(tag, id)] = notification
        }

        override fun cancel(tag: String?, id: Int) {
            order += "cancel"
            active.remove(key(tag, id))
        }

        private fun key(tag: String?, id: Int) = "${tag.orEmpty()}$id"
    }

    private class FakeStarter(private val order: MutableList<String>) : CallForegroundStarter {
        var refuse = false
        var starts = 0

        override fun start(intent: Intent) {
            if (refuse) throw ForegroundServiceStartNotAllowedException("refused")
            order += "fgs"
            starts += 1
        }
    }

    private class FakeTelecom : CallTelecom {
        override var listener: CallTelecomListener = object : CallTelecomListener {}
        var available = false
        var adds = 0
        override val tracksCall: Boolean = false

        override fun register() {}

        override fun add(callId: UUID, name: String, video: Boolean, outgoing: Boolean) {
            if (available) adds += 1
        }

        override fun answer(video: Boolean) {}
        override fun setActive() {}
        override fun setSpeaker(on: Boolean) {}
        override fun selectRoute(route: CallAudioRoute) {}
        override fun disconnect(cause: CallEndCause) {}
        override fun clear() {}
    }
}

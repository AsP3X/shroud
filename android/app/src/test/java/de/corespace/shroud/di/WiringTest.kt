package de.corespace.shroud.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.core.push.PushRegistration
import de.corespace.shroud.core.transcription.VoiceTranscription
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * W2-INT: every same-wave port points at its owner's object (00-plan §2.0 rule 3), and the wipe's
 * writer stop never builds a controller that does not exist yet (a wipe at launch or from a removal
 * wake must not start messaging or calls just to stop them).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WiringTest {
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        container = AppContainer(app, AppPhaseMonitor(tracks = { false }))
    }

    @Test
    fun sendEnginesReachTheirOwnersObjects() {
        val send = container.messagingSend
        assertSame(container.messagingStore.store, send.store())
        assertSame(container.media.localMedia, send.mediaStore())
        assertSame(container.media.transfers, send.transfers())
        assertSame(container.images.pipeline, send.images())
        assertSame(container.video.pipeline, send.video())
        assertSame(container.contacts.peerIdentities, send.peerIdentities())
        assertSame(container.notifications.controller, send.notifier())
    }

    @Test
    fun theMessageStoreRemovesMediaThroughTheSealedMediaCache() {
        assertSame(container.media.localMedia, container.messagingStore.localMedia())
    }

    @Test
    fun theNotificationTestAsksThePushModule() {
        assertSame(container.push.deliveryHooks, container.notifications.pushHooks())
    }

    @Test
    fun wave3SeamsStartInert() {
        val registration = container.push.registration
        assertFalse(registration is PushRegistration.Inactive)
        assertSame(registration, container.push.registration)
        assertSame(VoiceTranscription.Unavailable, container.transcription.voice)
    }

    @Test
    fun haltingWritersBuildsNoController() {
        container.wipeHooks.haltWriters()
        assertNull(container.messaging.controllerIfBuilt)
        assertNull(container.calls.controllerIfBuilt)
    }
}

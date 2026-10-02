package de.corespace.shroud.core.calls.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Constructing the engine must not call [WebRtcRuntime.acquire]. That loads `jingle_peerconnection_so`,
 * which Robolectric does not have, and which a push- or boot-started process should not load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallMediaEngineLoadTest {
    @Test
    fun constructionDoesNotLoadNativeWebRtc() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = CallMediaEngine(app)
        assertNotNull(engine)
    }
}

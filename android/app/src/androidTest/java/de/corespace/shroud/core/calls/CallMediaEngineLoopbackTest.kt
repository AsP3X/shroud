package de.corespace.shroud.core.calls

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.calls.media.CallMediaEngine
import de.corespace.shroud.core.calls.signal.CallSdp
import de.corespace.shroud.core.net.IceServerDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Two engines in one process: offer, answer, ICE, then a connected call. No server and no
 * emulator other than the one this test is installed on. A scripted call against the web client
 * is not run here.
 */
@RunWith(AndroidJUnit4::class)
class CallMediaEngineLoopbackTest {
    private val caller = engine()
    private val callee = engine()
    private val callerCandidates = ConcurrentLinkedQueue<IceCandidatePayload>()
    private val calleeCandidates = ConcurrentLinkedQueue<IceCandidatePayload>()
    private val callerStates = ConcurrentLinkedQueue<String>()
    private val calleeStates = ConcurrentLinkedQueue<String>()

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        runCatching { instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO) }
        runCatching { instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA) }
    }

    @After
    fun tearDown() {
        caller.close()
        callee.close()
    }

    @Test
    fun offerAnswerAndIceConnect() = runBlocking {
        caller.setCallbacks(recording(callerCandidates, callerStates))
        callee.setCallbacks(recording(calleeCandidates, calleeStates))
        assertNotNull(caller.eglContext)
        assertFalse(caller.canSendVideo)
        assertFalse(caller.canSendScreen)

        val ice = emptyList<IceServerDto>()
        caller.start(ice, video = false, offering = true, relayOnly = false)
        callee.start(ice, video = false, offering = false, relayOnly = false)
        assertTrue(caller.canSendVideo)
        assertTrue(caller.canSendScreen)
        assertFalse(callee.canSendVideo)
        assertFalse(caller.applyAnswer("v=0\r\n"))

        val offer = caller.makeOffer(iceRestart = false)
        assertTrue(offer.contains("m=audio"))
        assertTrue(offer.split("m=video").size >= 3)
        assertTrue(CallSdp.fingerprint(offer) != null)

        val answer = callee.answer(offer)
        assertTrue(callee.canSendVideo)
        assertTrue(callee.canSendScreen)
        assertTrue(callee.hasRemoteDescription)
        assertTrue(caller.applyAnswer(answer))
        assertFalse("a second answer is glare", caller.applyAnswer(answer))
        assertTrue(caller.hasRemoteDescription)

        val deadline = System.nanoTime() + 45_000_000_000L
        while (System.nanoTime() < deadline) {
            exchange()
            if (connected(callerStates) && connected(calleeStates)) break
            delay(100)
        }
        exchange()
        assertTrue("caller states=$callerStates", connected(callerStates))
        assertTrue("callee states=$calleeStates", connected(calleeStates))

        val callerPrint = fingerprint(caller)
        val calleePrint = fingerprint(callee)
        assertNotNull(callerPrint)
        assertNotNull(calleePrint)
        assertTrue(CallSdp.matches(CallSdp.fingerprint(answer) ?: "", callerPrint!!))
        assertTrue(CallSdp.matches(CallSdp.fingerprint(offer) ?: "", calleePrint!!))

        val level = caller.localAudioLevel()
        if (level != null) assertTrue(level in 0f..1f)

        caller.preferRelay()
        if (caller.startCamera()) {
            assertTrue(caller.isCameraOn)
            caller.stopCamera()
            assertFalse(caller.isCameraOn)
        } else {
            assertFalse(caller.isCameraOn)
        }
        assertFalse(caller.startScreen(ScreenCaptureGrant(0, Intent())))
    }

    private fun exchange() {
        if (caller.hasRemoteDescription) caller.addRemoteCandidates(drain(calleeCandidates))
        if (callee.hasRemoteDescription) callee.addRemoteCandidates(drain(callerCandidates))
    }

    private suspend fun fingerprint(engine: CallMediaEngine): String? {
        repeat(8) {
            engine.remoteCertificateFingerprint()?.let { return it }
            delay(200)
        }
        return engine.remoteCertificateFingerprint()
    }

    private fun recording(candidates: ConcurrentLinkedQueue<IceCandidatePayload>, states: ConcurrentLinkedQueue<String>) =
        object : CallMediaCallbacks {
            override fun onLocalCandidate(candidate: IceCandidatePayload) {
                candidates.add(candidate)
            }

            override fun onConnection(state: String) {
                states.add(state)
            }

            override fun onRemoteVideo(track: VideoTrack?) = Unit
            override fun onRemoteFrame() = Unit
            override fun onLocalFrame() = Unit
            override fun onCameraPaused(paused: Boolean) = Unit
            override fun onRemoteScreen(track: VideoTrack?) = Unit
            override fun onRemoteScreenFrame() = Unit
            override fun onScreenFirstFrame() = Unit
            override fun onScreenCaptureEnded() = Unit
        }

    companion object {
        private fun engine(): CallMediaEngine {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return CallMediaEngine(context)
        }

        private fun drain(queue: ConcurrentLinkedQueue<IceCandidatePayload>): List<IceCandidatePayload> {
            val out = ArrayList<IceCandidatePayload>()
            while (true) out += queue.poll() ?: return out
        }

        private fun connected(states: ConcurrentLinkedQueue<String>): Boolean =
            states.any { it == "connected" || it == "completed" }
    }
}

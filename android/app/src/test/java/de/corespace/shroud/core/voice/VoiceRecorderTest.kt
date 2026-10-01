package de.corespace.shroud.core.voice

import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.storage.SensitiveTempFiles
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * `VoiceRecorder` take logic on the JVM with a fake microphone and encoder (media-voice-links §8.1,
 * §12.7; `ios/shroud/Services/Voice/VoiceRecorder.swift:75-139`): the iOS copy of every error, the
 * 0.6 s minimum, `durationMs = max(1, round(s · 1000))`, the 44-bucket waveform read before teardown,
 * the 44-level live window, cancellation on background and focus loss, and that the
 * `cacheDir/shroud-voice-*.m4a` temp file is gone after every path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecorderTest {
    @get:Rule val temp = TempDirRule()

    private val rate = VoiceFormat.SAMPLE_RATE
    private val focusPort = FakeFocusPort()
    private val phase = MutableStateFlow(AppPhase.Active)
    private var permission = true

    /** Speech-like: a 220 Hz tone whose loudness swells and fades four times a second. */
    private val speech: (Int) -> Short = { i ->
        val envelope = 0.5 + 0.5 * sin(2 * PI * 4.0 * i / rate)
        (12_000 * envelope * sin(2 * PI * 220.0 * i / rate)).toInt().toShort()
    }

    private fun tempFiles() = SensitiveTempFiles(temp.cacheDir)

    private fun leftovers(): List<String> = temp.cacheDir.listFiles()?.map { it.name }.orEmpty()

    private fun TestScope.recorder(capture: VoiceCaptureFactory, scope: CoroutineScope = backgroundScope): VoiceRecorder =
        VoiceRecorder(
            scope = scope,
            capture = capture,
            audioFocus = AudioFocusCoordinator(focusPort),
            hasPermission = { permission },
            createTempFile = { tempFiles().create(VoiceFormat.TEMP_STEM, VoiceFormat.TEMP_EXTENSION) },
            appPhase = phase,
            ioDispatcher = Dispatchers.IO,
        )

    private fun assertFails(reason: VoiceRecorderException.Reason, block: suspend () -> Unit) = runTest(UnconfinedTestDispatcher()) {
        try {
            block()
            fail("expected $reason")
        } catch (e: VoiceRecorderException) {
            assertEquals(reason, e.reason)
            assertEquals(reason.message, e.message)
        }
    }

    // ---- Happy path ----

    @Test
    fun finishReturnsTheAudioItsDurationAndAVariedWaveform() = runTest(UnconfinedTestDispatcher()) {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val capture = FakeCapture({ source })
        val recorder = recorder(capture)

        assertTrue(recorder.start())
        assertTrue(recorder.isRecording)
        assertTrue(focusPort.held)
        assertEquals(1, leftovers().size)
        assertTrue(leftovers().single().startsWith("shroud-voice-") && leftovers().single().endsWith(".m4a"))

        assertTrue(source.delivered.await(5, TimeUnit.SECONDS))
        val take = recorder.finish()
        assertNotNull(take)
        take!!

        assertEquals(rate * 2, take.data.size)
        assertEquals(1000, take.durationMs)
        assertEquals(VoiceRecorder.WAVEFORM_BUCKETS, take.waveform.size)
        assertTrue("the waveform is read before teardown, never flat", VoiceWaveform.isUsable(take.waveform))
        assertFalse(recorder.isRecording)
        assertEquals(VoiceRecorder.RecState(), recorder.state.value)
        assertTrue(capture.encoders.single().finished)
        assertTrue(source.stopped)
        assertTrue(source.released.await(1, TimeUnit.SECONDS))
        assertFalse(focusPort.held)
        assertEquals("the temp file is deleted after reading it", emptyList<String>(), leftovers())
    }

    /** iOS `duration >= minimumDuration` (`VoiceRecorder.swift:118`): exactly 0.6 s is a message. */
    @Test
    fun exactlyTheMinimumIsAMessage() = runTest(UnconfinedTestDispatcher()) {
        val frames = (rate * 0.6).toInt()
        val source = FakePcmSource(totalFrames = frames, sample = speech)
        val recorder = recorder(FakeCapture({ source }))
        recorder.start()
        source.delivered.await(5, TimeUnit.SECONDS)
        val take = recorder.finish()
        assertNotNull(take)
        assertEquals(600, take!!.durationMs)
        assertEquals(12, frames / VoiceRecorder.WINDOW_FRAMES)
    }

    @Test
    fun shorterThanTheMinimumIsAMisTapAndLeavesNothing() = runTest(UnconfinedTestDispatcher()) {
        val source = FakePcmSource(totalFrames = (rate * 0.59).toInt(), sample = speech)
        val capture = FakeCapture({ source })
        val recorder = recorder(capture)
        recorder.start()
        source.delivered.await(5, TimeUnit.SECONDS)

        assertNull(recorder.finish())
        assertTrue(capture.encoders.single().aborted)
        assertFalse(capture.encoders.single().finished)
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(focusPort.held)
    }

    @Test
    fun durationRoundsToWholeMillisecondsAndIsNeverZero() {
        assertEquals(600, VoiceRecorder.durationMs(0.6))
        assertEquals(1001, VoiceRecorder.durationMs(1.0005))
        assertEquals(1000, VoiceRecorder.durationMs(1.0004))
        assertEquals(1, VoiceRecorder.durationMs(0.0))
        assertEquals(1, VoiceRecorder.durationMs(0.0001))
    }

    // ---- Live state ----

    @Test
    fun liveLevelsKeepTheNewest44AndTheTimerFollowsTheAudio() = runTest(UnconfinedTestDispatcher()) {
        val source = FakePcmSource(totalFrames = rate * 3, sample = speech)
        val recorder = recorder(FakeCapture({ source }))
        recorder.start()
        assertEquals(VoiceRecorder.RecState(recording = true), recorder.state.value)

        awaitCondition { recorder.state.value.levelCount == 60 }
        val state = recorder.state.value
        assertTrue(state.recording)
        assertEquals(VoiceRecorder.LIVE_WINDOW, state.liveLevels.size)
        assertEquals(3.0, state.elapsedSeconds, 1e-9)
        assertTrue(state.liveLevels.all { it in 0f..1f })
        recorder.cancel()
    }

    // ---- Cancel ----

    @Test
    fun cancelThrowsTheTakeAwayAndDeletesTheFile() = runTest(UnconfinedTestDispatcher()) {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val capture = FakeCapture({ source })
        val recorder = recorder(capture)
        recorder.start()
        source.delivered.await(5, TimeUnit.SECONDS)

        recorder.cancel()
        assertFalse(recorder.isRecording)
        assertFalse(focusPort.held)
        assertTrue(source.released.await(5, TimeUnit.SECONDS))
        awaitCondition { leftovers().isEmpty() }
        assertTrue(capture.encoders.single().aborted)
        assertFalse(capture.encoders.single().finished)

        try {
            recorder.finish()
            fail("expected NotRecording")
        } catch (e: VoiceRecorderException) {
            assertEquals(VoiceRecorderException.Reason.NotRecording, e.reason)
        }
    }

    @Test
    fun cancelWithoutATakeOnlyResetsTheState() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder(FakeCapture({ FakePcmSource(0) }))
        recorder.cancel()
        assertEquals(VoiceRecorder.RecState(), recorder.state.value)
    }

    /** Android 14+ silences background capture; iOS tears the thread down (media-voice-links §8.1). */
    @Test
    fun goingToTheBackgroundCancelsTheTake() = runTest {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val capture = FakeCapture({ source })
        val recorder = recorder(capture)
        recorder.start()

        phase.value = AppPhase.Inactive
        runCurrent()
        assertTrue("a dialog on top does not end the take", recorder.isRecording)

        phase.value = AppPhase.Background
        runCurrent()
        assertFalse(recorder.isRecording)
        assertTrue(source.released.await(5, TimeUnit.SECONDS))
        awaitCondition { leftovers().isEmpty() }
        assertTrue(capture.encoders.single().aborted)
    }

    /** A ringing call takes the focus: the take ends (iOS: a call's media cancels it). */
    @Test
    fun losingAudioFocusCancelsTheTake() = runTest(UnconfinedTestDispatcher()) {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val recorder = recorder(FakeCapture({ source }))
        recorder.start()

        focusPort.loseFocus()
        assertFalse(recorder.isRecording)
        assertTrue(source.released.await(5, TimeUnit.SECONDS))
        awaitCondition { leftovers().isEmpty() }
    }

    /** Released during the microphone round trip: a tap, not a message (`ChatComposerView.swift:340-346`). */
    @Test
    fun cancelDuringStartAbandonsTheTake() = runTest(UnconfinedTestDispatcher()) {
        val gate = CountDownLatch(1)
        val source = FakePcmSource(totalFrames = rate, startGate = gate)
        val capture = FakeCapture({ source })
        val recorder = recorder(capture)

        val started = async { recorder.start() }
        recorder.cancel()
        gate.countDown()
        assertFalse(started.await())
        assertFalse(recorder.isRecording)
        assertTrue(source.released.await(5, TimeUnit.SECONDS))
        assertTrue(capture.encoders.single().aborted)
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(focusPort.held)
    }

    @Test
    fun aNewTakeAfterACancelGetsAFreshFile() = runTest(UnconfinedTestDispatcher()) {
        val sources = ArrayDeque(listOf(FakePcmSource(rate, speech), FakePcmSource(rate, speech)))
        val recorder = recorder(FakeCapture({ sources.removeFirst() }))
        recorder.start()
        recorder.cancel()
        assertTrue(recorder.start())
        assertTrue(recorder.isRecording)
        awaitCondition { leftovers().size == 1 }
        recorder.cancel()
        awaitCondition { leftovers().isEmpty() }
    }

    // ---- Errors (iOS copy, `VoiceRecorder.swift:19-33`) ----

    @Test
    fun withoutMicrophonePermissionNothingStarts() {
        permission = false
        var built = false
        assertFails(VoiceRecorderException.Reason.PermissionDenied) {
            recorderForErrors(FakeCapture({ built = true; FakePcmSource(rate) })).start()
        }
        assertFalse(built)
        assertEquals(0, focusPort.requests)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals("Microphone access is required for voice messages.", VoiceRecorderException.Reason.PermissionDenied.message)
    }

    @Test
    fun startingTwiceIsAlreadyRecording() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder(FakeCapture({ FakePcmSource(rate, speech) }))
        recorder.start()
        try {
            recorder.start()
            fail("expected AlreadyRecording")
        } catch (e: VoiceRecorderException) {
            assertEquals("Already recording.", e.message)
        }
        assertTrue(recorder.isRecording)
        recorder.cancel()
    }

    @Test
    fun finishWithoutATakeIsNotRecording() {
        assertFails(VoiceRecorderException.Reason.NotRecording) {
            recorderForErrors(FakeCapture({ FakePcmSource(rate) })).finish()
        }
        assertEquals("Not recording.", VoiceRecorderException.Reason.NotRecording.message)
    }

    @Test
    fun aMicrophoneThatCannotStartFailsAndCleansUp() {
        val capture = FakeCapture({ FakePcmSource(rate, failStart = true) })
        assertFails(VoiceRecorderException.Reason.EncodeFailed) { recorderForErrors(capture).start() }
        assertTrue(capture.encoders.single().aborted)
        assertFalse(focusPort.held)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals("Could not finish the recording.", VoiceRecorderException.Reason.EncodeFailed.message)
    }

    /** Focus refused (a phone call in progress): iOS's session activation fails, the take never starts. */
    @Test
    fun refusedAudioFocusFailsBeforeOpeningTheMicrophone() {
        focusPort.grant = false
        var opened = false
        assertFails(VoiceRecorderException.Reason.EncodeFailed) {
            recorderForErrors(FakeCapture({ opened = true; FakePcmSource(rate) })).start()
        }
        assertFalse(opened)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun anEmptyFileIsEncodeFailed() {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val capture = FakeCapture({ source }, encoder = { FakeVoiceEncoder(it, writesNothing = true) })
        assertFails(VoiceRecorderException.Reason.EncodeFailed) {
            val recorder = recorderForErrors(capture)
            recorder.start()
            source.delivered.await(5, TimeUnit.SECONDS)
            recorder.finish()
        }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun anEncoderThatCannotFinishIsEncodeFailed() {
        val source = FakePcmSource(totalFrames = rate, sample = speech)
        val capture = FakeCapture({ source }, encoder = { FakeVoiceEncoder(it, failFinish = true) })
        assertFails(VoiceRecorderException.Reason.EncodeFailed) {
            val recorder = recorderForErrors(capture)
            recorder.start()
            source.delivered.await(5, TimeUnit.SECONDS)
            recorder.finish()
        }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aMicrophoneThatFailsMidTakeIsEncodeFailedOnFinish() {
        val source = FakePcmSource(totalFrames = rate * 2, sample = speech, failReadAfter = rate)
        val capture = FakeCapture({ source })
        assertFails(VoiceRecorderException.Reason.EncodeFailed) {
            val recorder = recorderForErrors(capture)
            recorder.start()
            assertTrue(source.released.await(5, TimeUnit.SECONDS))
            recorder.finish()
        }
        assertTrue(capture.encoders.single().aborted)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun recordingToStringNeverPrintsTheAudio() {
        val take = VoiceRecorder.Recording(ByteArray(10), 1234, ByteArray(44))
        assertEquals("Recording(bytes=10, durationMs=1234, buckets=44)", take.toString())
        assertArrayEquals(ByteArray(44), take.waveform)
    }

    /** For the error tests, which run inside [assertFails]'s own `runTest`: a plain scope on the IO pool. */
    private fun recorderForErrors(capture: VoiceCaptureFactory): VoiceRecorder =
        VoiceRecorder(
            scope = CoroutineScope(Dispatchers.Unconfined),
            capture = capture,
            audioFocus = AudioFocusCoordinator(focusPort),
            hasPermission = { permission },
            createTempFile = { tempFiles().create(VoiceFormat.TEMP_STEM, VoiceFormat.TEMP_EXTENSION) },
            appPhase = null,
            ioDispatcher = Dispatchers.IO,
        )
}

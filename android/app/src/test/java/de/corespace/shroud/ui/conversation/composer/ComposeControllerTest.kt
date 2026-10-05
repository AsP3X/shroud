package de.corespace.shroud.ui.conversation.composer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import de.corespace.shroud.core.links.LinkPreviewDraft
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.replyReference
import de.corespace.shroud.core.net.wire.Icu4jTextUnitsRule
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.core.voice.VoiceRecorderException
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.attach.ChatAttachOption
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.ME
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.PEER
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.message
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.probe
import de.corespace.shroud.ui.conversation.pickers.PickerFilter
import de.corespace.shroud.ui.conversation.pickers.PickerRequest
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.media.PickedVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The compose half of iOS `ConversationView` (`ConversationView.swift:1235-1507, 1694-1934,
 * 2305-2438`; conversation-compose-media §3.7, §3.8, §4.6, §7.3, §8, §9.6, §15.1, §19, §20, §22).
 * Robolectric only for `Uri` and `Bitmap`; everything else runs against [FakeComposeServices].
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: these tests run on fakes; ShroudApplication would start the whole
// container for every test, which piles up in the one test JVM.
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeControllerTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val textUnits = Icu4jTextUnitsRule()

    @get:Rule
    val temp = TemporaryFolder()

    private class Env(
        val services: FakeComposeServices,
        val host: FakeComposeHost,
        val controller: ComposeController,
        val effects: MutableList<ComposeEffect>,
    )

    private fun TestScope.env(isNotes: Boolean = false, peerName: String = "jane", configure: FakeComposeServices.() -> Unit = {}): Env {
        val services = FakeComposeServices(backgroundScope).apply(configure)
        val host = FakeComposeHost()
        val controller = ComposeController(if (isNotes) NOTES_PEER_ID else PEER, isNotes, services, backgroundScope, host, peerName)
        val effects = ArrayList<ComposeEffect>()
        backgroundScope.launch { controller.effects.collect { effects += it } }
        return Env(services, host, controller, effects)
    }

    /** The peer saw "recording" and then saw it stop (messaging drops repeats of the same signal). */
    private fun assertRecordingStartedAndStopped(signals: List<Boolean>) {
        assertEquals(true, signals.first())
        assertEquals(false, signals.last())
        assertEquals(1, signals.count { it })
    }

    private fun uri(n: Int): Uri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/$n")

    private fun photo(): PickedPhoto = PickedPhoto(preview = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888), source = MediaImageSource.ContentUri(uri(99)))

    private fun ownedMovie(): Pair<PickedMovie, File> {
        val file = temp.newFile("shroud-cam-${UUID.randomUUID()}.mp4")
        return PickedMovie(Uri.fromFile(file), file) to file
    }

    // ---- Text, reply, link preview (CV:1268-1345) -------------------------------------------------

    @Test
    fun `send takes the text, the reply snapshot and a loaded preview, then clears with a light tick (CV 1335-1345)`() = runTest(main.dispatcher) {
        val original = message(text = "Where?")
        val env = env {
            threads.value = mapOf(PEER to listOf(original))
            previews["https://example.com/a"] = LinkPreviewDraft(LinkPreview(url = "https://example.com/a", title = "A"), null, null, null, false)
        }
        env.controller.startReply(original)
        assertEquals(1, env.controller.focusToken)
        env.controller.draft.setTextAndPlaceCursorAtEnd("see https://example.com/a")
        env.controller.onDraftChanged("see https://example.com/a")
        advanceUntilIdle()
        env.controller.sendDraft()
        advanceUntilIdle()

        val sent = env.services.texts.single()
        assertEquals("see https://example.com/a", sent.text)
        assertEquals(PEER, sent.peer)
        assertEquals(original.replyReference, sent.replyTo)
        assertEquals("https://example.com/a", sent.linkPreview?.preview?.url)
        assertEquals("", env.controller.draft.text.toString())
        assertNull(env.controller.replyTarget)
        assertEquals(false, env.services.typing.last())
        assertEquals(listOf(Haptic.Light), env.effects.haptics())
    }

    @Test
    fun `a blank draft sends nothing`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.draft.setTextAndPlaceCursorAtEnd(" \n ")
        env.controller.sendDraft()
        advanceUntilIdle()
        assertTrue(env.services.texts.isEmpty())
        assertTrue(env.effects.isEmpty())
    }

    @Test
    fun `typing follows the trimmed draft, never in Notes (CV 309-318)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.onDraftChanged("hi")
        env.controller.onDraftChanged(" \n")
        assertEquals(listOf(true, false), env.services.typing)
        val notes = env(isNotes = true)
        notes.controller.onDraftChanged("note")
        assertTrue(notes.services.typing.isEmpty())
    }

    @Test
    fun `only a quotable message starts a reply (CV 1289-1294)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.startReply(message(receipt = ReceiptStatus.Failed))
        env.controller.startReply(message(receipt = ReceiptStatus.Sending, isMine = true))
        env.controller.startReply(message(deleted = true))
        assertNull(env.controller.replyTarget)
        assertEquals(0, env.controller.focusToken)
    }

    @Test
    fun `the strip shows the live original, the send keeps the snapshot (CV 1268-1287)`() = runTest(main.dispatcher) {
        val original = message(text = "Where?")
        val env = env { threads.value = mapOf(PEER to listOf(original)) }
        env.controller.startReply(original)
        val reference = env.controller.outgoingReplyReference
        env.services.threads.value = mapOf(PEER to listOf(original.copy(deleted = true, text = "Message deleted")))
        assertTrue(env.controller.liveReplyTarget!!.deleted)
        assertEquals(reference, env.controller.outgoingReplyReference)
        env.controller.jumpToReplyTarget()
        assertEquals(listOf(original.id), env.host.jumps)
        env.controller.clearReply()
        assertNull(env.controller.liveReplyTarget)
    }

    @Test
    fun `removing the link preview ticks and dismisses it (CV 331-334)`() = runTest(main.dispatcher) {
        val env = env { previews["https://example.com/a"] = LinkPreviewDraft(LinkPreview(url = "https://example.com/a", title = "A"), null, null, null, false) }
        env.controller.onDraftChanged("https://example.com/a")
        advanceUntilIdle()
        assertNotNull(ChatLinkBarState.from(env.controller.linkComposer.phase))
        env.controller.removeLinkPreview()
        assertNull(ChatLinkBarState.from(env.controller.linkComposer.phase))
        assertEquals(listOf(Haptic.Light), env.effects.haptics())
    }

    // ---- Notes todo (CV:1694-1721) ----------------------------------------------------------------

    @Test
    fun `Todo with an empty draft explains itself (CV 1697-1701)`() = runTest(main.dispatcher) {
        val env = env(isNotes = true)
        env.controller.sendTodo()
        assertEquals(Toast.info("Type a todo, then tap Todo."), env.host.toasts.single())
        assertTrue(env.services.todos.isEmpty())
    }

    @Test
    fun `Todo sends the trimmed draft, clears it and pins (CV 1702-1705)`() = runTest(main.dispatcher) {
        val env = env(isNotes = true)
        env.controller.draft.setTextAndPlaceCursorAtEnd("  buy milk \n")
        env.controller.sendTodo()
        assertEquals(listOf("buy milk"), env.services.todos)
        assertEquals("", env.controller.draft.text.toString())
        assertEquals(1, env.host.pins)
        assertEquals(listOf(Haptic.Success), env.effects.haptics())
    }

    // ---- Voice (CV:1414-1507; §4.7) ----------------------------------------------------------------

    @Test
    fun `holding without the microphone permission asks and records nothing (section 4_7)`() = runTest(main.dispatcher) {
        val env = env { micGranted = false }
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()
        assertTrue(ComposeEffect.RequestMicrophone in env.effects)
        assertEquals(0, env.services.starts)
        assertEquals(ComposerPhase.Idle, env.controller.gesture.phase.value)
        assertFalse(env.controller.isRecording.value)
    }

    @Test
    fun `a take starts - playback yields, medium tick, recording signal, model download (CV 1418-1431)`() = runTest(main.dispatcher) {
        val env = env { automaticTranscription = true }
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()
        assertEquals(1, env.services.playbackStops)
        assertEquals(listOf(true), env.services.recordingSignals)
        assertEquals(1, env.services.modelPrepares)
        assertEquals(listOf(Haptic.Medium), env.effects.haptics())
        assertTrue(env.controller.isRecording.value)
        assertEquals(ComposerPhase.Recording(0f, 0f), env.controller.gesture.phase.value)
    }

    @Test
    fun `with automatic transcription off a take downloads no model and sends no transcript`() = runTest(main.dispatcher) {
        val env = env { modelInstalled = true }
        env.controller.gesture.pointer(0f, 0f)
        env.controller.gesture.pointerUp()
        advanceUntilIdle()
        assertEquals(0, env.services.modelPrepares)
        val provider = env.services.voices.single().transcriptProvider!!
        assertNull(provider(UUID.randomUUID()))
        assertTrue(env.services.transcribed.isEmpty())
    }

    @Test
    fun `Notes never signals recording`() = runTest(main.dispatcher) {
        val env = env(isNotes = true)
        env.controller.gesture.pointer(0f, 0f)
        env.controller.gesture.pointerUp()
        advanceUntilIdle()
        assertTrue(env.services.recordingSignals.isEmpty())
        assertTrue(env.services.typing.isEmpty())
        assertEquals(1, env.services.voices.size)
    }

    @Test
    fun `a recorder failure shows the recorder's own words (section 4_7 item 5)`() = runTest(main.dispatcher) {
        val env = env { startOutcome = { throw VoiceRecorderException(VoiceRecorderException.Reason.EncodeFailed) } }
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()
        assertEquals(Toast.failure("Could not finish the recording."), env.host.toasts.single())
        assertEquals(listOf(Haptic.Error), env.effects.haptics())
        assertEquals(ComposerPhase.Idle, env.controller.gesture.phase.value)
    }

    @Test
    fun `releasing sends the note with the reply, a transcript only once the model is here (CV 1457-1502)`() = runTest(main.dispatcher) {
        val original = message(text = "Call me")
        val env = env {
            threads.value = mapOf(PEER to listOf(original))
            contacts = listOf("zed", "amy", "jane")
            automaticTranscription = true
        }
        env.controller.startReply(original)
        env.controller.gesture.pointer(0f, 0f)
        env.controller.gesture.pointerUp()
        advanceUntilIdle()

        val voice = env.services.voices.single()
        assertEquals(1_500, voice.durationMs)
        assertEquals(44, voice.waveform?.size)
        assertEquals(original.replyReference, voice.replyTo)
        assertNull(env.controller.replyTarget)
        assertRecordingStartedAndStopped(env.services.recordingSignals)
        assertEquals(false, env.services.typing.last())
        assertEquals(listOf(Haptic.Medium, Haptic.Light, Haptic.Success), env.effects.haptics())
        assertFalse(env.controller.isRecording.value)

        val provider = voice.transcriptProvider!!
        val id = UUID.randomUUID()
        assertNull(provider(id))
        env.services.modelInstalled = true
        assertEquals("hello", provider(id))
        assertEquals(listOf("amy", "jane", "zed") to id, env.services.transcribed.single())
    }

    @Test
    fun `a take under 0_6 s is a mis-tap - hint, warning, the reply stays (CV 1459-1466)`() = runTest(main.dispatcher) {
        val original = message()
        val env = env {
            threads.value = mapOf(PEER to listOf(original))
            finishOutcome = { null }
        }
        env.controller.startReply(original)
        env.controller.gesture.pointer(0f, 0f)
        env.controller.gesture.pointerUp()
        advanceUntilIdle()
        assertTrue(env.services.voices.isEmpty())
        assertEquals(Toast.info("Hold to record, release to send"), env.host.toasts.single())
        assertEquals(listOf(Haptic.Medium, Haptic.Warning), env.effects.haptics())
        assertRecordingStartedAndStopped(env.services.recordingSignals)
        assertNotNull(env.controller.replyTarget)
    }

    @Test
    fun `a failed voice send shows its reason (CV 1496-1501)`() = runTest(main.dispatcher) {
        val env = env { voiceError = "Could not send." }
        env.controller.gesture.pointer(0f, 0f)
        env.controller.gesture.pointerUp()
        advanceUntilIdle()
        assertEquals(Toast.failure("Could not send."), env.host.toasts.single())
        assertEquals(Haptic.Error, env.effects.haptics().last())
    }

    @Test
    fun `Back during a take throws it away and stays in the chat (thread D9 = compose Q10)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()
        env.controller.cancelVoiceTake()
        assertEquals(1, env.services.cancels)
        assertEquals(ComposerPhase.Idle, env.controller.gesture.phase.value)
        assertFalse(env.controller.isRecording.value)
        assertRecordingStartedAndStopped(env.services.recordingSignals)
        assertEquals(Haptic.Rigid, env.effects.haptics().last())
    }

    @Test
    fun `the recorder stopping on its own (a call, the background) folds the bar (CV 384-388, CCV 145-148)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()
        env.services.recorderState.value = VoiceRecorder.RecState()
        assertEquals(ComposerPhase.Idle, env.controller.gesture.phase.value)
        assertFalse(env.controller.isRecording.value)
        assertRecordingStartedAndStopped(env.services.recordingSignals)
        env.controller.gesture.pointerUp()
        assertTrue(env.services.voices.isEmpty())
    }

    @Test
    fun `transcription hints - the peer and contacts, unique, sorted, at most 50 (CV 1448-1453)`() = runTest(main.dispatcher) {
        val env = env(peerName = "mia") { contacts = (1..80).map { "user%02d".format(it) } + "mia" }
        val hints = env.controller.transcriptionHints()
        assertEquals(50, hints.size)
        assertEquals(hints.sorted(), hints)
        assertEquals(hints.toSet().size, hints.size)
        assertEquals("mia", hints.first())
        assertEquals("user49", hints.last())
    }

    @Test
    fun `recorder errors keep their own copy, others the generic line`() {
        assertEquals("Already recording.", ComposeController.recorderMessage(VoiceRecorderException(VoiceRecorderException.Reason.AlreadyRecording)))
        assertEquals("Not recording.", ComposeController.recorderMessage(VoiceRecorderException(VoiceRecorderException.Reason.NotRecording)))
    }

    // ---- Attach (CV:1723-1738, 1931-1934) -------------------------------------------------------

    @Test
    fun `Photos opens a fresh pick of up to ten (CV 1725-1728)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.openAttachSheet()
        env.controller.handleAttach(ChatAttachOption.Photos)
        assertFalse(env.controller.showsAttachSheet)
        assertEquals(ComposeEffect.OpenPicker(PickerRequest(10, PickerFilter.ImageAndVideo)), env.effects.single())
    }

    @Test
    fun `Camera asks for the camera, or says there is none (CV 1729-1734)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.handleAttach(ChatAttachOption.Camera)
        assertEquals(ComposeEffect.RequestCamera, env.effects.single())
        env.controller.presentCamera()
        assertTrue(env.controller.showsCamera)
        assertTrue(env.controller.coversComposer)

        val noCamera = env { camera = false }
        noCamera.controller.handleAttach(ChatAttachOption.Camera)
        assertEquals(Toast.failure("Camera is not available on this device."), noCamera.host.toasts.single())
    }

    @Test
    fun `the other options are coming soon (CV 1735-1736, 1931-1934)`() = runTest(main.dispatcher) {
        val env = env()
        for (option in listOf(ChatAttachOption.Location, ChatAttachOption.Contact, ChatAttachOption.Music, ChatAttachOption.Gift, ChatAttachOption.Stickers)) {
            env.controller.handleAttach(option)
        }
        assertEquals(
            listOf("Location", "Contact", "Music", "Gift", "Stickers").map { Toast.info("$it coming soon") },
            env.host.toasts,
        )
        assertEquals(List(5) { Haptic.Light }, env.effects.haptics())
    }

    @Test
    fun `a Recents tile opens compose with that photo (CV 396-399)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.openAttachSheet()
        val picked = photo()
        env.controller.pickRecentPhoto(picked)
        assertFalse(env.controller.showsAttachSheet)
        assertSame(picked, env.controller.composeDraft!!.photos.single())
        assertEquals("jane", env.controller.composeDraft!!.peerName)
    }

    // ---- Picked media (CV:1740-1801, 1839-1892) ---------------------------------------------------

    @Test
    fun `picked photos open the photo compose, sources stay content URIs (C27)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.loadPickedMedia(listOf(uri(1), uri(2)))
        val photos = env.controller.composeDraft!!.photos
        assertEquals(listOf(uri(1), uri(2)), photos.map { (it.source as MediaImageSource.ContentUri).uri })
        assertNull(env.controller.videoDraft)
    }

    @Test
    fun `nothing readable - one toast, worded for one or many (CV 1771-1774)`() = runTest(main.dispatcher) {
        val env = env { undecodable += listOf(uri(1), uri(2)) }
        env.controller.loadPickedMedia(listOf(uri(1)))
        env.controller.loadPickedMedia(listOf(uri(1), uri(2)))
        assertEquals(listOf(Toast.failure("Could not load that item."), Toast.failure("Could not load those items.")), env.host.toasts)
        assertNull(env.controller.composeDraft)
    }

    @Test
    fun `a mixed pick is two steps - the clips first, the photos 260 ms after (CV 1793-1800, 1862-1873)`() = runTest(main.dispatcher) {
        val env = env {
            mimeTypes[uri(2)] = "video/mp4"
            mimeTypes[uri(3)] = "video/quicktime"
            probes[uri(2)] = probe()
            // uri(3) cannot be probed: skipped.
        }
        env.controller.loadPickedMedia(listOf(uri(1), uri(2), uri(3)))
        assertEquals(listOf(uri(2)), env.controller.videoDraft!!.videos.map { it.uri })
        assertNull(env.controller.composeDraft)

        env.controller.cancelVideoCompose()
        assertNull(env.controller.videoDraft)
        advanceTimeBy(ComposeController.VIDEO_TO_PHOTO_COMPOSE_MS - 1)
        assertNull(env.controller.composeDraft)
        advanceTimeBy(2)
        assertEquals(1, env.controller.composeDraft!!.photos.size)
    }

    @Test
    fun `Add asks for the room left and the open screen's kind, never past ten (CV 670-689)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.presentMediaCompose(List(9) { photo() })
        env.controller.openPickerToAppend()
        assertEquals(ComposeEffect.OpenPicker(PickerRequest(1, PickerFilter.ImageOnly)), env.effects.single())
        env.controller.loadPickedMedia(listOf(uri(1), uri(2)))
        assertEquals(10, env.controller.composeDraft!!.photos.size)

        env.effects.clear()
        env.controller.openPickerToAppend()
        assertTrue(env.effects.isEmpty())
    }

    @Test
    fun `Add to a video compose merges clips and drops the files past ten (CV 1779-1785)`() = runTest(main.dispatcher) {
        val env = env { mimeTypes[uri(1)] = "video/mp4"; probes[uri(1)] = probe() }
        val staged = List(10) { ownedMovie() }
        env.controller.presentVideoCompose(staged.map { PickedVideo(movie = it.first, probe = probe(), poster = null) })
        // A pick that the room would not allow anyway (the picker offered one) is merged and capped.
        env.controller.openPickerToAppend()
        assertTrue(env.effects.none { it is ComposeEffect.OpenPicker })
        env.controller.presentVideoCompose(staged.take(9).map { PickedVideo(movie = it.first, probe = probe(), poster = null) })
        env.controller.openPickerToAppend()
        assertEquals(ComposeEffect.OpenPicker(PickerRequest(1, PickerFilter.VideoOnly)), env.effects.last())
        env.controller.loadPickedMedia(listOf(uri(1)))
        assertEquals(10, env.controller.videoDraft!!.videos.size)
    }

    @Test
    fun `a camera clip that cannot be read is deleted and reported (CV 1852-1857)`() = runTest(main.dispatcher) {
        val env = env()
        val (movie, file) = ownedMovie()
        env.controller.presentCamera()
        env.controller.onCameraVideo(movie)
        advanceUntilIdle()
        assertFalse(env.controller.showsCamera)
        assertFalse(file.exists())
        assertEquals(Toast.failure("Could not load that video."), env.host.toasts.single())
        assertNull(env.controller.videoDraft)
    }

    @Test
    fun `a camera photo opens compose (CV 432-433)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.presentCamera()
        val shot = PickedPhoto.fromImage(Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888))
        env.controller.onCameraPhoto(shot)
        assertFalse(env.controller.showsCamera)
        assertSame(shot, env.controller.composeDraft!!.photos.single())
    }

    // ---- Sends from the compose screens (CV:506-579, 1809-1929) -----------------------------------

    @Test
    fun `photos go out in order, caption and reply on the first, with the Sending card up meanwhile (CV 517-533, 1896-1929)`() = runTest(main.dispatcher) {
        val original = message()
        val env = env { threads.value = mapOf(PEER to listOf(original)) }
        env.controller.startReply(original)
        env.controller.presentMediaCompose(List(3) { photo() })
        val gate = CompletableDeferred<Unit>()
        env.services.imageGate = gate
        val crop = MediaEdits(rotationQuarters = 1)
        env.controller.sendComposedPhotos("Look", MediaComposeQuality.HD, listOf(crop))
        assertNull(env.controller.composeDraft)
        assertTrue(env.controller.photoComposeSent)
        assertNull(env.controller.replyTarget)
        assertTrue(env.controller.isSendingMedia)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(env.controller.isSendingMedia)

        val sent = env.services.images
        assertEquals(listOf("Look", "", ""), sent.map { it.caption })
        assertEquals(listOf(original.replyReference, null, null), sent.map { it.replyTo })
        assertEquals(listOf(crop, MediaEdits.Identity, MediaEdits.Identity), sent.map { it.edits })
        assertTrue(sent.all { it.quality == MediaComposeQuality.HD })
        assertEquals(Haptic.Success, env.effects.haptics().last())
    }

    @Test
    fun `the first photo error is shown for 4 s, the rest keep going (CV 1918-1925)`() = runTest(main.dispatcher) {
        val env = env { imageErrors += listOf(null, "Upload failed.", "Other.") }
        env.controller.presentMediaCompose(List(3) { photo() })
        env.controller.sendComposedPhotos("", MediaComposeQuality.Original, emptyList())
        advanceUntilIdle()
        assertEquals(3, env.services.images.size)
        assertEquals(Toast.failure("Upload failed.", 4_000), env.host.toasts.single())
        assertEquals(Haptic.Error, env.effects.haptics().last())
    }

    @Test
    fun `clips go out with the quote on the first, pin at once, and the files go afterwards (CV 559-567, 1811-1837)`() = runTest(main.dispatcher) {
        val original = message()
        val env = env { threads.value = mapOf(PEER to listOf(original)) }
        env.controller.startReply(original)
        val movies = List(2) { ownedMovie() }
        env.controller.presentVideoCompose(movies.map { PickedVideo(movie = it.first, probe = probe(), poster = null) })
        val plans = movies.map { VideoSendPlan(sourceUri = it.first.uri) }
        env.controller.sendComposedVideos(plans)
        advanceUntilIdle()
        assertEquals(1, env.host.pins)
        assertNull(env.controller.videoDraft)
        assertEquals(listOf(original.replyReference, null), env.services.videos.map { it.replyTo })
        assertTrue(movies.none { it.second.exists() })
        assertEquals(Haptic.Success, env.effects.haptics().last())
    }

    @Test
    fun `cancelling or removing clips deletes their capture files (CV 555-558, 1883-1892)`() = runTest(main.dispatcher) {
        val env = env()
        val movies = List(3) { ownedMovie() }
        env.controller.presentVideoCompose(movies.map { PickedVideo(movie = it.first, probe = probe(), poster = null) })
        env.controller.removeComposeVideo(1)
        assertFalse(movies[1].second.exists())
        assertEquals(2, env.controller.videoDraft!!.videos.size)
        env.controller.cancelVideoCompose()
        assertTrue(movies.none { it.second.exists() })
        assertNull(env.controller.videoDraft)
    }

    @Test
    fun `removing the last photo closes compose (CV 1875-1881)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.presentMediaCompose(List(2) { photo() })
        env.controller.removeComposePhoto(5)
        assertEquals(2, env.controller.composeDraft!!.photos.size)
        env.controller.removeComposePhoto(0)
        env.controller.removeComposePhoto(0)
        assertNull(env.controller.composeDraft)
    }

    // ---- Media tap, download, viewers (CV:2305-2438) ----------------------------------------------

    @Test
    fun `a tombstone or a failed send does nothing on tap (CV 2333-2336)`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.handleMediaTap(message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), deleted = true))
        env.controller.handleMediaTap(message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), receipt = ReceiptStatus.Failed, isMine = true))
        assertTrue(env.services.downloads.isEmpty())
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `a tap downloads what is not here, success ticks, failure says so (CV 2351-2384)`() = runTest(main.dispatcher) {
        val good = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID())
        val bad = message(kind = ChatMessageKind.Video, mediaObjectId = UUID.randomUUID())
        val env = env {
            threads.value = mapOf(PEER to listOf(good, bad))
            downloadable += good.id
        }
        env.controller.handleMediaTap(good)
        env.controller.handleMediaTap(bad)
        advanceUntilIdle()
        assertEquals(listOf(good.id, bad.id), env.services.downloads)
        assertEquals(listOf(Haptic.Light, Haptic.Error), env.effects.haptics())
        assertEquals(Toast.failure("Could not download that video."), env.host.toasts.single())
        // The first tap only downloaded; the photo opens on the next one.
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `a download stopped from the ring is no failure, and one download per message at a time (CV 2353-2362, 2386-2394)`() = runTest(main.dispatcher) {
        val photo = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID())
        val env = env { threads.value = mapOf(PEER to listOf(photo)) }
        env.services.downloadGate = CompletableDeferred()
        env.controller.handleMediaTap(photo)
        env.controller.handleMediaTap(photo)
        env.controller.cancelDownload(photo)
        advanceUntilIdle()
        assertEquals(1, env.services.downloads.size)
        assertEquals(listOf(photo.id), env.services.cancelledDownloads)
        assertTrue(env.host.toasts.isEmpty())
        assertTrue(env.effects.haptics().isEmpty())
    }

    @Test
    fun `a downloaded photo opens the viewer, a clip the player with its title and date (CV 2396-2426)`() = runTest(main.dispatcher) {
        val image = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val clip = message(kind = ChatMessageKind.Video, mediaObjectId = UUID.randomUUID(), hasFullMedia = true, isMine = true)
        val env = env { threads.value = mapOf(PEER to listOf(image, clip)) }
        env.controller.handleMediaTap(image)
        assertEquals(image.id, env.controller.viewingMedia)
        env.controller.closeMediaViewer()
        env.controller.handleMediaTap(clip)
        val video = env.controller.viewingVideo!!
        assertEquals(clip.id, video.id)
        assertEquals("You", video.title)
        assertEquals(ComposeController.viewerDateLine(clip.createdAt), video.dateLine)
        assertTrue(env.controller.coversComposer)
    }

    @Test
    fun `the release of the hold that opened a message menu opens nothing (CV 2398-2399, 2410)`() = runTest(main.dispatcher) {
        val image = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val env = env { threads.value = mapOf(PEER to listOf(image)) }
        env.host.isShowingMessageMenu = true
        env.controller.handleMediaTap(image)
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `the viewer leaves when its photo is deleted for everyone (CV 182-187, 265-271)`() = runTest(main.dispatcher) {
        val image = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val env = env { threads.value = mapOf(PEER to listOf(image)) }
        env.controller.openMediaViewer(image)
        env.services.threads.value = mapOf(PEER to listOf(image.copy(deleted = true)))
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `Delete in the viewer asks the thread, and the viewer leaves once that photo is gone (CV 490-496, 2270-2275)`() = runTest(main.dispatcher) {
        val first = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val second = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val env = env { threads.value = mapOf(PEER to listOf(first, second)) }
        env.controller.openMediaViewer(first)
        // Paged to the second photo, then Delete.
        env.controller.requestDeleteFromViewer(second.id)
        assertEquals(listOf(second), env.host.deleteRequests)
        assertEquals(first.id, env.controller.viewingMedia)
        // Deleted just for us: the row goes.
        env.services.threads.value = mapOf(PEER to listOf(first))
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `viewer items - downloaded photos only, the Photo stand-in is no caption (CV 2305-2330)`() {
        val ok = message(kind = ChatMessageKind.Image, text = "Photo", hasFullMedia = true, width = 4000, height = 3000, isMine = true)
        val captioned = message(kind = ChatMessageKind.Image, text = "  Sunset \n", hasFullMedia = true)
        val notHere = message(kind = ChatMessageKind.Image, hasFullMedia = false)
        val gone = message(kind = ChatMessageKind.Image, hasFullMedia = true, deleted = true)
        val failed = message(kind = ChatMessageKind.Image, hasFullMedia = true, receipt = ReceiptStatus.Failed, isMine = true)
        val clip = message(kind = ChatMessageKind.Video, hasFullMedia = true)
        val items = ComposeController.viewerItems(listOf(ok, captioned, notHere, gone, failed, clip), "jane", ZoneOffset.UTC)
        assertEquals(listOf(ok.id, captioned.id), items.map { it.id })
        assertEquals(listOf("You", "jane"), items.map { it.title })
        assertEquals(listOf(null, "Sunset"), items.map { it.caption })
        assertEquals(4000f / 3000f, items[0].aspect)
        assertEquals(1f, items[1].aspect)
        assertEquals("30.09.26", items[0].dateLine)
        assertTrue(items.all { it.isLoaded })
    }

    @Test
    fun `the viewer date line is dd_MM_yy (CV 2429-2438)`() {
        assertEquals("01.02.27", ComposeController.viewerDateLine(Instant.parse("2027-02-01T23:30:00Z"), ZoneOffset.UTC))
        assertEquals("02.02.27", ComposeController.viewerDateLine(Instant.parse("2027-02-01T23:30:00Z"), ZoneOffset.ofHours(2)))
    }

    @Test
    fun `toasts land on the covering layer while one is up (CV 208-212)`() = runTest(main.dispatcher) {
        val env = env { undecodable += uri(1) }
        env.controller.presentMediaCompose(listOf(photo()))
        env.controller.loadPickedMedia(listOf(uri(1)))
        assertTrue(env.host.toasts.isEmpty())
        assertEquals(Toast.failure("Could not load that item."), env.controller.coveredToasts.current)
    }

    // ---- Lifecycle (CV:363-388; §20, §22) ---------------------------------------------------------

    @Test
    fun `locking drops the draft, the reply, the take, staged media and the viewers (section 22)`() = runTest(main.dispatcher) {
        val original = message()
        val env = env { threads.value = mapOf(PEER to listOf(original)) }
        env.controller.draft.setTextAndPlaceCursorAtEnd("secret")
        env.controller.startReply(original)
        val (movie, file) = ownedMovie()
        env.controller.presentVideoCompose(listOf(PickedVideo(movie = movie, probe = probe(), poster = null)))
        env.controller.openAttachSheet()
        env.controller.gesture.pointer(0f, 0f)
        advanceUntilIdle()

        // The chats lock: messaging tells every artifact sink.
        env.services.sinks.toList().forEach { it.onSensitiveMemoryLocked() }

        assertEquals("", env.controller.draft.text.toString())
        assertNull(env.controller.replyTarget)
        assertNull(env.controller.videoDraft)
        assertFalse(file.exists())
        assertFalse(env.controller.showsAttachSheet)
        assertEquals(1, env.services.cancels)
        assertEquals(ComposerPhase.Idle, env.controller.gesture.phase.value)
        assertFalse(env.controller.isRecording.value)
        assertEquals(false, env.services.recordingSignals.last())
    }

    @Test
    fun `leaving for the profile keeps the draft and the reply, leaving for good drops the rest (CV 363-379)`() = runTest(main.dispatcher) {
        val original = message()
        val env = env { threads.value = mapOf(PEER to listOf(original)) }
        env.controller.draft.setTextAndPlaceCursorAtEnd("draft")
        env.controller.startReply(original)
        env.controller.presentMediaCompose(listOf(photo()))
        env.controller.onLeave(profilePushed = true)
        assertEquals("draft", env.controller.draft.text.toString())
        assertNotNull(env.controller.replyTarget)
        assertNotNull(env.controller.composeDraft)
        assertEquals(1, env.services.sinks.size)
        assertEquals(1, env.services.playbackStops)

        env.controller.onLeave(profilePushed = false)
        assertNull(env.controller.composeDraft)
        assertTrue(env.services.sinks.isEmpty())
        // Work finishing after the chat closed stays silent.
        env.controller.handleAttach(ChatAttachOption.Location)
        assertTrue(env.effects.haptics().isEmpty())

        // Drawn again: it hears locks and speaks up again.
        env.controller.onShown()
        assertEquals(1, env.services.sinks.size)
        env.controller.handleAttach(ChatAttachOption.Location)
        assertEquals(listOf(Haptic.Light), env.effects.haptics())
    }

    @Test
    fun `purges and re-keys move the open viewer (MessageArtifactSinks)`() = runTest(main.dispatcher) {
        val image = message(kind = ChatMessageKind.Image, mediaObjectId = UUID.randomUUID(), hasFullMedia = true)
        val env = env { threads.value = mapOf(PEER to listOf(image)) }
        env.controller.openMediaViewer(image)
        val serverId = UUID.randomUUID()
        env.services.sinks.single().onMessageRekeyed(image.id, serverId)
        assertEquals(serverId, env.controller.viewingMedia)
        env.services.sinks.single().onPurged(listOf(serverId))
        assertNull(env.controller.viewingMedia)
    }

    @Test
    fun `a capture's preview is at most 2048 px on the long edge, rounded (MCO 14-35)`() {
        assertEquals(2048 to 1536, PickedPhoto.previewSize(4000, 3000))
        assertEquals(1536 to 2048, PickedPhoto.previewSize(3000, 4000))
        assertEquals(2048 to 1536, PickedPhoto.previewSize(4033, 3025))
        assertEquals(2048 to 1, PickedPhoto.previewSize(5000, 1))
        assertEquals(2048 to 100, PickedPhoto.previewSize(2048, 100))
        val small = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val keptAsIs = PickedPhoto.fromImage(small)
        assertSame(small, keptAsIs.preview)
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val shot = PickedPhoto.fromImage(big)
        assertEquals(2048, shot.preview.width)
        assertEquals(1536, shot.preview.height)
        assertSame(big, (shot.source as MediaImageSource.Decoded).bitmap)
        assertFalse(shot.toString().contains("content://"))
    }

    @Test
    fun `the peer name comes from messaging when the screen has none`() = runTest(main.dispatcher) {
        val env = env(peerName = "") { usernames[PEER] = "otto" }
        assertEquals("otto", env.controller.peerName)
        assertEquals(ME, env.services.myUserId)
    }
}

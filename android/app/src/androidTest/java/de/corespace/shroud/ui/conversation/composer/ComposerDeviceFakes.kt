package de.corespace.shroud.ui.conversation.composer

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.links.LinkPreviewException
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.library.LibraryAccess
import de.corespace.shroud.core.media.library.LibraryItem
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.UUID

/**
 * The composer's engines as stand-ins for its device journeys (the JVM tests keep their own copy,
 * `FakeComposeServices`; the two source sets do not share code). Every call is recorded; the
 * "recorder" runs a take the moment it is started, with a speaking voice's levels, so the bars draw
 * as they do in a real take. Main-confined like the production services.
 */
internal class DeviceComposeServices(override val sendScope: CoroutineScope) : ComposeServices {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    override val myUserId: UUID = ME
    override fun username(peer: UUID): String? = null
    override val threads = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())

    val texts = ArrayList<String>()
    var voices = 0
        private set
    var cancels = 0
        private set
    var starts = 0
        private set
    val recordingSignals = ArrayList<Boolean>()

    override suspend fun sendText(text: String, peer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) {
        texts += text
    }

    override fun sendTodo(text: String) {
        log += "todo"
    }

    override suspend fun sendImage(
        source: MediaImageSource,
        peer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? {
        log += "image"
        return null
    }

    override suspend fun sendVideo(plan: VideoSendPlan, peer: UUID, replyTo: MessageReplyReference?): String? {
        log += "video"
        return null
    }

    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        peer: UUID,
        waveform: ByteArray?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? {
        voices++
        return null
    }

    override fun setTyping(peer: UUID, isTyping: Boolean) = Unit

    override fun setRecording(peer: UUID, isRecording: Boolean) {
        recordingSignals += isRecording
    }

    override suspend fun ensureImageLoaded(message: ChatMessage) = Unit
    override suspend fun ensureVideoLoaded(message: ChatMessage) = Unit
    override fun cancelMediaDownload(messageId: UUID) = Unit
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = AutoCloseable {}
    override fun contactUsernames(): List<String> = emptyList()

    // ---- Voice ----
    override val recorderState = MutableStateFlow(VoiceRecorder.RecState())
    var micGranted = true
    override fun hasMicPermission(): Boolean = micGranted

    override suspend fun startRecording(): Boolean {
        starts++
        recorderState.value = VoiceRecorder.RecState(recording = true, elapsedSeconds = 1.25, liveLevels = SPEAKING, levelCount = SPEAKING.size)
        return true
    }

    override suspend fun finishRecording(): VoiceRecorder.Recording? {
        recorderState.value = VoiceRecorder.RecState()
        return VoiceRecorder.Recording(ByteArray(16) { 1 }, 1_250, ByteArray(44) { 7 })
    }

    override fun cancelRecording() {
        cancels++
        recorderState.value = VoiceRecorder.RecState()
    }

    override fun stopPlayback() = Unit

    // ---- Transcription: never installed here ----
    override fun prepareTranscriptionModel() = Unit
    override suspend fun transcriptionModelInstalled(): Boolean = false
    override suspend fun transcribe(audio: ByteArray, hints: List<String>, conversationId: UUID, tracking: UUID): String = ""

    // ---- Links: no page ever loads ----
    override fun newLinkComposer(scope: CoroutineScope): LinkPreviewComposer =
        LinkPreviewComposer(fetcher = { throw LinkPreviewException(LinkPreviewException.Reason.Empty) }, scope = scope, debounceMs = 0)

    // ---- Media ----
    override fun hasCamera(): Boolean = true
    override fun mimeType(uri: Uri): String? = "image/jpeg"

    override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? {
        log += "decode"
        return tile(TILE_COLOURS[0], 640)
    }

    override suspend fun probeVideo(uri: Uri): VideoProbe? = null
    override suspend fun videoPoster(uri: Uri, maxEdge: Int): Bitmap? = null

    /** The Recents strip: full access to [library]'s three images. */
    var libraryAccess: LibraryAccess = LibraryAccess.Full
    val library: List<LibraryItem> = List(3) { i ->
        LibraryItem(Uri.parse("content://media/external/images/media/${100 + i}"), isVideo = false, dateTaken = null, durationMs = null)
    }

    override fun photoLibraryAccess(): LibraryAccess = libraryAccess
    override suspend fun recentPhotos(limit: Int): List<LibraryItem> = library.take(limit)
    override suspend fun photoThumbnail(uri: Uri, maxEdge: Int): Bitmap? =
        tile(TILE_COLOURS[(uri.lastPathSegment?.toIntOrNull() ?: 0) % TILE_COLOURS.size], maxEdge)

    override fun photoAccessRequested(): Boolean = true
    override fun markPhotoAccessRequested() = Unit

    companion object {
        val ME: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val PEER: UUID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")

        /** A voice's 44 levels, so the halo and the locked waveform have something to show. */
        val SPEAKING: List<Float> = List(44) { i -> 0.3f + 0.5f * ((i * 7) % 11) / 10f }

        private val TILE_COLOURS = intArrayOf(0xFF8FB8DE.toInt(), 0xFFE9B872.toInt(), 0xFF9BC59D.toInt())

        private fun tile(colour: Int, edge: Int): Bitmap =
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
    }
}

/** What the composer asks of the conversation screen, recorded; its toasts are kept as text. */
internal class DeviceComposeHost : ComposeHost {
    val toasts: MutableList<String> = Collections.synchronizedList(ArrayList())
    override val isShowingMessageMenu: Boolean = false
    override fun pinToBottom() = Unit
    override fun jumpToQuoted(messageId: UUID) = Unit
    override fun showToast(toast: Toast) {
        toasts += toast.message
    }
}

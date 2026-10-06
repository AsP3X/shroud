package de.corespace.shroud.ui.conversation.composer

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.links.LinkPreviewDraft
import de.corespace.shroud.core.links.LinkPreviewException
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.files.FileIntake
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.library.LibraryAccess
import de.corespace.shroud.core.media.library.LibraryItem
import de.corespace.shroud.core.media.share.FileOpenOutcome
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.media.share.ShareTarget
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/** [ComposeServices] recording every call, for [ComposeControllerTest]. Main-confined like the real one. */
internal class FakeComposeServices(override val sendScope: CoroutineScope) : ComposeServices {
    override var myUserId: UUID? = ME
    val usernames = HashMap<UUID, String>()
    override fun username(peer: UUID): String? = usernames[peer]
    override val threads = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())

    data class SentText(val text: String, val peer: UUID, val replyTo: MessageReplyReference?, val linkPreview: LinkPreviewAttachment?)
    data class SentImage(val source: MediaImageSource, val caption: String, val quality: MediaComposeQuality, val edits: MediaEdits, val replyTo: MessageReplyReference?)
    data class SentVideo(val plan: VideoSendPlan, val replyTo: MessageReplyReference?)
    data class SentVoice(val durationMs: Int, val waveform: ByteArray?, val replyTo: MessageReplyReference?, val transcriptProvider: (suspend (UUID) -> String?)?)

    val texts = ArrayList<SentText>()
    val todos = ArrayList<String>()
    val images = ArrayList<SentImage>()
    val videos = ArrayList<SentVideo>()
    val voices = ArrayList<SentVoice>()
    val typing = ArrayList<Boolean>()
    val recordingSignals = ArrayList<Boolean>()

    /** Errors the next image / video / voice sends return, in order (null = sent). */
    val imageErrors = ArrayDeque<String?>()
    val videoErrors = ArrayDeque<String?>()
    var voiceError: String? = null

    /** While set, image sends wait on it (the "Sending media…" card is up meanwhile). */
    var imageGate: CompletableDeferred<Unit>? = null

    override suspend fun sendText(text: String, peer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) {
        texts += SentText(text, peer, replyTo, linkPreview)
    }

    override fun sendTodo(text: String) {
        todos += text
    }

    override suspend fun sendImage(
        source: MediaImageSource,
        peer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? {
        imageGate?.await()
        images += SentImage(source, caption, quality, edits, replyTo)
        return imageErrors.removeFirstOrNull()
    }

    override suspend fun sendVideo(plan: VideoSendPlan, peer: UUID, replyTo: MessageReplyReference?): String? {
        videos += SentVideo(plan, replyTo)
        return videoErrors.removeFirstOrNull()
    }

    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        peer: UUID,
        waveform: ByteArray?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? {
        voices += SentVoice(durationMs, waveform, replyTo, transcriptProvider)
        return voiceError
    }

    override fun setTyping(peer: UUID, isTyping: Boolean) {
        typing += isTyping
    }

    override fun setRecording(peer: UUID, isRecording: Boolean) {
        recordingSignals += isRecording
    }

    /** Full media lands for these ids when downloaded; others end empty-handed. */
    val downloadable = HashSet<UUID>()
    val downloads = ArrayList<UUID>()
    val cancelledDownloads = ArrayList<UUID>()
    var downloadGate: CompletableDeferred<Unit>? = null

    private suspend fun download(message: ChatMessage) {
        downloads += message.id
        downloadGate?.await()
        if (message.id in downloadable) {
            val peer = message.peerUserId
            threads.value = threads.value + (peer to threads.value[peer].orEmpty().map { if (it.id == message.id) it.copy(hasFullMedia = true) else it })
        }
    }

    override suspend fun ensureImageLoaded(message: ChatMessage) = download(message)
    override suspend fun ensureVideoLoaded(message: ChatMessage) = download(message)
    override fun cancelMediaDownload(messageId: UUID) {
        cancelledDownloads += messageId
        downloadGate?.complete(Unit)
    }

    // ---- Files ----

    /** What the next `inspectFiles` answers. */
    var fileIntake = FileIntake.Result(emptyList(), emptyList())
    val inspectedUris = ArrayList<List<Uri>>()

    data class SentFile(val file: PickedFile, val caption: String, val replyTo: MessageReplyReference?)

    val files = ArrayList<SentFile>()
    val fileErrors = ArrayDeque<String?>()
    val fileActions = ArrayList<String>()
    var openOutcome: FileOpenOutcome = FileOpenOutcome.Refused("Could not open that file.")
    var shareTarget: ShareTarget? = null
    var saveOutcome: SaveOutcome = SaveOutcome.Saved

    override suspend fun inspectFiles(uris: List<Uri>): FileIntake.Result {
        inspectedUris += uris
        return fileIntake
    }

    override suspend fun sendFile(file: PickedFile, peer: UUID, caption: String, replyTo: MessageReplyReference?): String? {
        files += SentFile(file, caption, replyTo)
        return fileErrors.removeFirstOrNull()
    }

    override suspend fun ensureFileLoaded(message: ChatMessage) = download(message)

    override suspend fun fileOpenTarget(messageId: UUID, fileName: String): FileOpenOutcome {
        fileActions += "open:$fileName"
        return openOutcome
    }

    /** What §4's check says before the PDF viewer opens; null = it may open. */
    var openRefusal: String? = null

    override suspend fun fileOpenRefusal(messageId: UUID, fileName: String): String? {
        fileActions += "check:$fileName"
        return openRefusal
    }

    override suspend fun fileShareTarget(messageId: UUID, fileName: String): ShareTarget? {
        fileActions += "share:$fileName"
        return shareTarget
    }

    override suspend fun saveFileToDownloads(messageId: UUID, fileName: String): SaveOutcome {
        fileActions += "save:$fileName"
        return saveOutcome
    }

    val sinks = ArrayList<MessageArtifactSinks>()
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable {
        sinks += sink
        return AutoCloseable { sinks -= sink }
    }

    var contacts: List<String> = emptyList()
    override fun contactUsernames(): List<String> = contacts

    // ---- Voice ----
    override val recorderState = MutableStateFlow(VoiceRecorder.RecState())
    var micGranted = true
    override fun hasMicPermission(): Boolean = micGranted

    /** What the next start does: true / false, or throws. */
    var startOutcome: () -> Boolean = { true }
    var starts = 0
    var cancels = 0
    var finishOutcome: () -> VoiceRecorder.Recording? = { VoiceRecorder.Recording(byteArrayOf(1, 2, 3), 1_500, ByteArray(44) { 7 }) }
    var playbackStops = 0

    override suspend fun startRecording(): Boolean {
        starts++
        val started = startOutcome()
        if (started) recorderState.value = VoiceRecorder.RecState(recording = true)
        return started
    }

    override suspend fun finishRecording(): VoiceRecorder.Recording? {
        recorderState.value = VoiceRecorder.RecState()
        return finishOutcome()
    }

    override fun cancelRecording() {
        cancels++
        recorderState.value = VoiceRecorder.RecState()
    }

    override fun stopPlayback() {
        playbackStops++
    }

    /** The audio-file player the composer drives (docs/file-sharing.md §11.5); none unless a test sets one. */
    override var audioFiles: de.corespace.shroud.core.voice.AudioFilePlaybackCoordinator? = null

    // ---- Transcription ----
    var modelPrepares = 0
    var modelInstalled = false
    /** Settings › Transcription › Transcribe automatically; off by default, as in the app. */
    var automaticTranscription = false
    val transcribed = ArrayList<Pair<List<String>, UUID>>()
    override fun prepareTranscriptionModel() {
        modelPrepares++
    }

    override suspend fun transcriptionModelInstalled(): Boolean = modelInstalled
    override fun transcribesAutomatically(): Boolean = automaticTranscription
    override suspend fun transcribe(audio: ByteArray, hints: List<String>, conversationId: UUID, tracking: UUID): String {
        transcribed += hints to tracking
        return "hello"
    }

    // ---- Links ----
    /** The previews the fake fetcher knows, by URL. */
    val previews = HashMap<String, LinkPreviewDraft>()
    /** While set, every fetch waits on it (the strip stays "Loading preview…"). */
    var previewGate: CompletableDeferred<Unit>? = null
    override fun newLinkComposer(scope: CoroutineScope): LinkPreviewComposer = LinkPreviewComposer(
        fetcher = { url ->
            previewGate?.await()
            previews[url] ?: throw LinkPreviewException(LinkPreviewException.Reason.Empty)
        },
        scope = scope,
        debounceMs = 0,
    )

    // ---- Media ----
    var camera = true
    override fun hasCamera(): Boolean = camera
    val mimeTypes = HashMap<Uri, String>()
    override fun mimeType(uri: Uri): String? = mimeTypes[uri]
    val undecodable = HashSet<Uri>()

    /** While set, decoding waits on it (a Recents tile shows its spinner meanwhile). */
    var decodeGate: CompletableDeferred<Unit>? = null
    override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? {
        decodeGate?.await()
        val uri = (source as? MediaImageSource.ContentUri)?.uri
        if (uri != null && uri in undecodable) return null
        return Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    }

    val probes = HashMap<Uri, VideoProbe>()
    override suspend fun probeVideo(uri: Uri): VideoProbe? = probes[uri]
    override suspend fun videoPoster(uri: Uri, maxEdge: Int): Bitmap? = null

    var libraryAccess: LibraryAccess = LibraryAccess.None
    val library = ArrayList<LibraryItem>()
    val unthumbnailable = HashSet<Uri>()
    override fun photoLibraryAccess(): LibraryAccess = libraryAccess
    /** While set, the Recents strip waits on it (its shimmering tiles stay up). */
    var recentsGate: CompletableDeferred<Unit>? = null

    /** The colour a tile's thumbnail is filled with (renders); transparent when null. */
    var thumbnailColor: ((Uri) -> Int)? = null
    override suspend fun recentPhotos(limit: Int): List<LibraryItem> {
        recentsGate?.await()
        return library.take(limit)
    }

    override suspend fun photoThumbnail(uri: Uri, maxEdge: Int): Bitmap? =
        if (uri in unthumbnailable) {
            null
        } else {
            Bitmap.createBitmap(maxEdge, maxEdge, Bitmap.Config.ARGB_8888).also { bitmap -> thumbnailColor?.let { bitmap.eraseColor(it(uri)) } }
        }

    var photoAccessAsked = false
    override fun photoAccessRequested(): Boolean = photoAccessAsked
    override fun markPhotoAccessRequested() {
        photoAccessAsked = true
    }

    companion object {
        val ME: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val PEER: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")

        fun probe(seconds: Double = 8.0) = VideoProbe(seconds, 1920, 1080, 2_000_000, true, "mp4", "video/avc", "audio/mp4a-latm")

        fun message(
            id: UUID = UUID.randomUUID(),
            text: String = "Hi",
            kind: ChatMessageKind = ChatMessageKind.Text,
            isMine: Boolean = false,
            hasFullMedia: Boolean = false,
            mediaObjectId: UUID? = null,
            deleted: Boolean = false,
            receipt: de.corespace.shroud.core.model.ReceiptStatus = de.corespace.shroud.core.model.ReceiptStatus.Sent,
            width: Int? = null,
            height: Int? = null,
            createdAt: Instant = Instant.parse("2026-09-30T10:15:00Z"),
        ) = ChatMessage(
            id = id,
            peerUserId = PEER,
            senderUserId = if (isMine) ME else PEER,
            text = text,
            createdAt = createdAt,
            isMine = isMine,
            deleted = deleted,
            receipt = receipt,
            kind = kind,
            mediaObjectId = mediaObjectId,
            imageWidth = width,
            imageHeight = height,
            hasFullMedia = hasFullMedia,
        )
    }
}

/** [ComposeHost] recording what the controller asks of the conversation screen. */
internal class FakeComposeHost : ComposeHost {
    val toasts = ArrayList<Toast>()
    var pins = 0
    val jumps = ArrayList<UUID>()
    val deleteRequests = ArrayList<ChatMessage>()
    override var isShowingMessageMenu: Boolean = false

    override fun pinToBottom() {
        pins++
    }

    override fun jumpToQuoted(messageId: UUID) {
        jumps += messageId
    }

    override fun showToast(toast: Toast) {
        toasts += toast
    }

    override fun requestDelete(message: ChatMessage) {
        deleteRequests += message
    }

    /** Messages whose menu a tap asked for (a tapped APK). */
    val menus = ArrayList<UUID>()

    override fun showMessageMenu(message: ChatMessage) {
        menus += message.id
    }
}

/** The haptics a controller asked the view for. */
internal fun List<ComposeEffect>.haptics(): List<Haptic> = filterIsInstance<ComposeEffect.PlayHaptic>().map { it.haptic }

package de.corespace.shroud.ui.conversation.composer

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.edit
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.voice.VoiceFormat
import de.corespace.shroud.core.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Everything the composer reaches outside the UI (conversation-compose-media §24.1), behind one
 * port so [ComposeController] is tested with a fake. The production implementation is
 * [ContainerComposeServices]: messaging (W2-MSG-CORE), the recorder and the voice player (W2-VOICE),
 * transcription (W3-TRANSCRIPTION), link previews (W2-LINKS), photo decoding (W2-MEDIA-IMAGE),
 * video probing (W2-VIDEO) and contacts (W2-CONTACTS). Main-confined unless a member says otherwise.
 */
internal interface ComposeServices {
    /**
     * Where sends and downloads run: the app scope, so leaving the chat never cancels an upload in
     * flight (iOS runs them in unstructured `Task`s the view does not cancel, CV:1344, 1474, 524).
     */
    val sendScope: CoroutineScope

    val myUserId: UUID?

    /** The peer's name as messaging knows it (conversation list / contacts), for screens opened without one. */
    fun username(peer: UUID): String?

    /** Every open thread, oldest first, keyed by store peer ([de.corespace.shroud.core.model.NOTES_PEER_ID] for Notes). */
    val threads: StateFlow<Map<UUID, List<ChatMessage>>>

    suspend fun sendText(text: String, peer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?)
    fun sendTodo(text: String)
    suspend fun sendImage(source: MediaImageSource, peer: UUID, caption: String, quality: MediaComposeQuality, edits: MediaEdits, replyTo: MessageReplyReference?): String?
    suspend fun sendVideo(plan: VideoSendPlan, peer: UUID, replyTo: MessageReplyReference?): String?
    suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        peer: UUID,
        waveform: ByteArray?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String?

    fun setTyping(peer: UUID, isTyping: Boolean)
    fun setRecording(peer: UUID, isRecording: Boolean)

    suspend fun ensureImageLoaded(message: ChatMessage)
    suspend fun ensureVideoLoaded(message: ChatMessage)
    fun cancelMediaDownload(messageId: UUID)

    /** Purges, locks and re-keys (`MessagingController.registerArtifactSink`). */
    fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable

    /** Contact usernames, for the transcription hints (`ConversationView.swift:1448-1453`). */
    fun contactUsernames(): List<String>

    // ---- Voice (W2-VOICE) ----

    val recorderState: StateFlow<VoiceRecorder.RecState>
    fun hasMicPermission(): Boolean

    /** `VoiceRecorder.start()`: true when recording, false when abandoned meanwhile; throws `VoiceRecorderException`. */
    suspend fun startRecording(): Boolean

    /** `VoiceRecorder.finish()`: null for a take under 0.6 s; throws `VoiceRecorderException`. */
    suspend fun finishRecording(): VoiceRecorder.Recording?
    fun cancelRecording()

    /** Playback and recording cannot share the route (`ConversationView.swift:1419-1420`). */
    fun stopPlayback()

    // ---- Transcription (W3-TRANSCRIPTION) ----

    /** Starts the model download in the background, never awaited (`ConversationView.swift:1427-1430`). */
    fun prepareTranscriptionModel()
    suspend fun transcriptionModelInstalled(): Boolean

    /** Throws when it cannot transcribe. */
    suspend fun transcribe(audio: ByteArray, hints: List<String>, conversationId: UUID, tracking: UUID): String

    // ---- Links (W2-LINKS) ----

    /** One link-preview composer per open chat, on the screen's [scope]. */
    fun newLinkComposer(scope: CoroutineScope): LinkPreviewComposer

    // ---- Media (W2-MEDIA-IMAGE, W2-VIDEO) ----

    /** `PackageManager.FEATURE_CAMERA_ANY` (`UIImagePickerController.isSourceTypeAvailable(.camera)`, CV:1730). */
    fun hasCamera(): Boolean

    /** The picked item's MIME type (`ContentResolver.getType`); null when unknown. */
    fun mimeType(uri: Uri): String?

    /** A screen-sized decode, orientation applied; null when it cannot be decoded (off main). */
    suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap?

    /** Metadata only; null when the clip cannot be read (off main). */
    suspend fun probeVideo(uri: Uri): VideoProbe?

    /** The first frame, ≤ [maxEdge] px (off main). */
    suspend fun videoPoster(uri: Uri, maxEdge: Int): Bitmap?

    // ---- The Recents strip's one device fact (conversation-compose-media §7.4, §22) ----

    /** The photo permission was asked for once (plain prefs `shroud.device`, kept across Log Out like the grant itself). */
    fun photoAccessRequested(): Boolean
    fun markPhotoAccessRequested()
}

/**
 * [ComposeServices] over the process's modules. Built per chat by [ComposeController]'s public
 * constructor; nothing here is constructed outside its own module (plan §2.0 rule 3) — it only
 * reaches through [AppContainer].
 */
internal class ContainerComposeServices(private val container: AppContainer) : ComposeServices {
    private val messaging get() = container.messaging.controller
    private val recorder get() = container.voice.recorder

    override val sendScope: CoroutineScope get() = container.appScope
    override val myUserId: UUID? get() = messaging.myUserId
    override fun username(peer: UUID): String? = messaging.username(peer)
    override val threads: StateFlow<Map<UUID, List<ChatMessage>>> get() = messaging.threads

    override suspend fun sendText(text: String, peer: UUID, replyTo: MessageReplyReference?, linkPreview: LinkPreviewAttachment?) {
        messaging.sendText(text, peer, replyTo, linkPreview)
    }

    override fun sendTodo(text: String) = messaging.sendTodo(text)

    override suspend fun sendImage(
        source: MediaImageSource,
        peer: UUID,
        caption: String,
        quality: MediaComposeQuality,
        edits: MediaEdits,
        replyTo: MessageReplyReference?,
    ): String? = messaging.sendImage(source, peer, caption, quality, edits, replyTo)

    override suspend fun sendVideo(plan: VideoSendPlan, peer: UUID, replyTo: MessageReplyReference?): String? =
        messaging.sendVideo(plan, peer, replyTo)

    override suspend fun sendVoice(
        audio: ByteArray,
        durationMs: Int,
        peer: UUID,
        waveform: ByteArray?,
        replyTo: MessageReplyReference?,
        transcriptProvider: (suspend (messageId: UUID) -> String?)?,
    ): String? = messaging.sendVoice(audio, durationMs, peer, waveform, transcript = null, replyTo = replyTo, transcriptProvider = transcriptProvider)

    override fun setTyping(peer: UUID, isTyping: Boolean) = messaging.setTyping(peer, isTyping)
    override fun setRecording(peer: UUID, isRecording: Boolean) = messaging.setRecording(peer, isRecording)

    override suspend fun ensureImageLoaded(message: ChatMessage) = messaging.ensureImageLoaded(message)
    override suspend fun ensureVideoLoaded(message: ChatMessage) = messaging.ensureVideoLoaded(message)
    override fun cancelMediaDownload(messageId: UUID) = messaging.cancelMediaDownload(messageId)
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = messaging.registerArtifactSink(sink)
    override fun contactUsernames(): List<String> = container.contacts.controller.contacts.value.map { it.username }

    override val recorderState: StateFlow<VoiceRecorder.RecState> get() = recorder.state
    override fun hasMicPermission(): Boolean =
        container.appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override suspend fun startRecording(): Boolean = recorder.start()
    override suspend fun finishRecording(): VoiceRecorder.Recording? = recorder.finish()
    override fun cancelRecording() = recorder.cancel()
    override fun stopPlayback() = container.voice.playback.stop()

    override fun prepareTranscriptionModel() {
        // Low priority, detached from the chat: the download keeps going while they speak (CV:1427-1430).
        container.appScope.launch(Dispatchers.Default) {
            runCatching { container.transcription.voice.prepareModel() }
        }
    }

    override suspend fun transcriptionModelInstalled(): Boolean = container.transcription.voice.modelIsInstalled()

    override suspend fun transcribe(audio: ByteArray, hints: List<String>, conversationId: UUID, tracking: UUID): String =
        container.transcription.voice.transcribe(audio, VoiceFormat.MIME, hints, conversationId, tracking)

    override fun newLinkComposer(scope: CoroutineScope): LinkPreviewComposer = container.links.newComposer(scope)

    override fun hasCamera(): Boolean = container.appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override fun mimeType(uri: Uri): String? = runCatching { container.appContext.contentResolver.getType(uri) }.getOrNull()

    override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? =
        container.images.mediaImages.decodePreview(source, maxEdge)

    override suspend fun probeVideo(uri: Uri): VideoProbe? = withContext(Dispatchers.IO) { container.video.media.probe(uri) }

    override suspend fun videoPoster(uri: Uri, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) { container.video.media.poster(uri, maxEdge) }

    private val devicePrefs get() = container.appContext.getSharedPreferences(PrefsFiles.DEVICE, Context.MODE_PRIVATE)

    override fun photoAccessRequested(): Boolean = devicePrefs.getBoolean(PHOTO_ACCESS_REQUESTED_KEY, false)

    override fun markPhotoAccessRequested() {
        // Every prefs writer stops during a wipe (plan §1.4).
        if (container.storageSeal.isSealed) return
        devicePrefs.edit { putBoolean(PHOTO_ACCESS_REQUESTED_KEY, true) }
    }

    companion object {
        /** `shroud.device` key: the Recents strip asked for photo access once (a device fact, like `notifications.permissionAsked`). */
        const val PHOTO_ACCESS_REQUESTED_KEY = "photos.permissionRequested"
    }
}

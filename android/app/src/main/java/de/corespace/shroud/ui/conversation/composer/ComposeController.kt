package de.corespace.shroud.ui.conversation.composer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.links.LinkPreviewComposer
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.files.FileCategory
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileWarning
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.library.LibraryAccess
import de.corespace.shroud.core.media.share.FileOpenOutcome
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.media.share.ShareTarget
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.canBeQuoted
import de.corespace.shroud.core.model.fileType
import de.corespace.shroud.core.model.needsMediaDownload
import de.corespace.shroud.core.model.replyReference
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.voice.VoiceRecorderException
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.conversation.attach.ChatAttachOption
import de.corespace.shroud.ui.conversation.attach.RecentPhoto
import de.corespace.shroud.ui.conversation.attach.RecentPhotos
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteContent
import de.corespace.shroud.ui.conversation.pickers.PickerRequest
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.MAX_MEDIA_PER_SEND
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.media.PickedVideo
import de.corespace.shroud.ui.media.VideoComposeDraft
import de.corespace.shroud.ui.media.ViewerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The composer's state for one conversation — the compose half of iOS `ConversationView`
 * (`ConversationView.swift:22-90, 296-443, 477-689, 1235-1507, 1694-1934, 2305-2438`;
 * conversation-compose-media §3–§8, §19, §20, §22, §23). The conversation screen (W3-THREAD-LIST)
 * creates one per open chat with its own main-thread [scope] (the per-chat view model's), drives
 * it through the members below and places [ConversationComposeHost] to draw it.
 *
 * What it holds, all in memory only and dropped when the chat closes ([onLeave]) or the chats lock
 * ([onLock]; it also hears `MessageArtifactSinks.onSensitiveMemoryLocked` itself): the [draft]
 * text, the [replyTarget], the link preview ([linkComposer]), the hold-to-record [gesture], the
 * attach sheet, staged photos ([composeDraft]), videos ([videoDraft]) and files ([fileDraft]), the
 * camera, the media viewer ([viewingMedia]) and player ([viewingVideo]), and a file's warning
 * ([fileWarning]) (conversation-compose-media §22; docs/file-sharing.md §6, §7).
 *
 * Sends and downloads run on the app scope, so leaving the chat never cancels an upload (iOS
 * starts them in `Task`s the view does not cancel); their toasts and haptics only reach the chat
 * while it is open. Observable state is Compose snapshot state (reading it in composition
 * recomposes); [isRecording] is a flow for the screen's back handling (plan §1.7.13).
 *
 * Main-confined.
 *
 * @param peer the thread key (`NOTES_PEER_ID` for Notes).
 * @param peerUsername the chat's title; when empty, messaging's name for [peer] is used.
 */
class ComposeController internal constructor(
    val peer: UUID,
    val isNotes: Boolean,
    private val services: ComposeServices,
    private val scope: CoroutineScope,
    private val host: ComposeHost,
    peerUsername: String,
) {
    /** The constructor of the seam (plan §1.7.13); [peerUsername] is an optional addition (W3-COMPOSER). */
    constructor(
        peer: UUID,
        isNotes: Boolean,
        container: AppContainer,
        scope: CoroutineScope,
        host: ComposeHost,
        peerUsername: String = "",
    ) : this(peer, isNotes, ContainerComposeServices(container), scope, host, peerUsername)

    private val givenName = peerUsername

    /** The peer's name for reply quotes, the viewers' titles and the transcription hints. */
    val peerName: String get() = givenName.ifEmpty { services.username(peer).orEmpty() }

    // ---- Draft, reply, link preview ---------------------------------------------------------------

    /** The message being typed (`@State draft`, CV:22). Memory only. */
    val draft = TextFieldState()

    /** The message being answered: the snapshot from when the reply started (CV:80-81, 1280-1287). */
    var replyTarget: ChatMessage? by mutableStateOf(null)
        private set

    /** Bumped when a reply starts: the field takes focus and the keyboard comes up (CV:82-83, 1289-1294). */
    var focusToken: Int by mutableIntStateOf(0)
        private set

    /** The link-preview strip's model for the link in the draft (CV:90-91, W2-LINKS). */
    val linkComposer: LinkPreviewComposer = services.newLinkComposer(scope)

    /** The thread as it is now (oldest first): the live reply target, viewer items, tombstones. */
    var thread: List<ChatMessage> by mutableStateOf(services.threads.value[peer].orEmpty())
        private set

    // ---- Recording --------------------------------------------------------------------------------

    /** The mic's hold-to-record gesture; the composer row feeds it the finger. */
    val gesture: ComposerGesture = ComposerGesture(
        scope,
        object : ComposerGesture.Host {
            override suspend fun recordStart(): Boolean = startRecording()
            override fun recordCancel() = cancelRecording()
            override fun recordSend() = sendRecording()
            override fun haptic(haptic: Haptic) = playHaptic(haptic)
        },
    )

    /** This chat started the take now running (the recorder is one per process). */
    private var ownsTake = false

    private val recording = MutableStateFlow(false)

    /** A voice take is running (the screen keeps Back for [cancelVoiceTake] meanwhile and hides the jump control). */
    val isRecording: StateFlow<Boolean> = recording.asStateFlow()

    // ---- Attach sheet, pickers, camera -------------------------------------------------------------

    var showsAttachSheet: Boolean by mutableStateOf(false)
        private set

    /** Photos staged for caption, edits and send (CV:52). */
    var composeDraft: ComposeDraft? by mutableStateOf(null)
        private set

    /** Videos staged for trim, mute, caption and send (CV:53-54). */
    var videoDraft: VideoComposeDraft? by mutableStateOf(null)
        private set

    /** Files staged in the file composer sheet (docs/file-sharing.md §7). */
    var fileDraft: FileComposeDraft? by mutableStateOf(null)
        private set

    /** A received file's §6 warning, asked before the action it guards; null when none is up. */
    var fileWarning: FileWarningPrompt? by mutableStateOf(null)
        private set

    /** Photos from the same pick as videos: shown once the video compose closes (CV:55-56). */
    private var photosAfterVideoCompose: List<PickedPhoto> = emptyList()
    private var pendingPhotoCompose: Job? = null

    /** Files from the same file pick as photos or videos: the file composer once the media compose closes. */
    private var filesAfterMediaCompose: List<PickedFile> = emptyList()
    private var pendingFileCompose: Job? = null

    /** The picker was opened from a compose screen: its results join that send (CV:60-61). */
    private var pickerAppendsToDraft = false

    var showsCamera: Boolean by mutableStateOf(false)
        private set

    /** Staged photos are going out: the "Sending media…" card (CV:63, 600-610). */
    var isSendingMedia: Boolean by mutableStateOf(false)
        private set

    // ---- Viewers ---------------------------------------------------------------------------------

    /** The photo the viewer opened on (CV:49). */
    var viewingMedia: UUID? by mutableStateOf(null)
        private set

    /** The video the player plays (CV:50-51). */
    var viewingVideo: ViewingVideo? by mutableStateOf(null)
        private set

    /** Messages whose full media is downloading (CV:64-65). */
    private val mediaDownloadIds = HashSet<UUID>()

    /** Downloads stopped with the bubble's ring: ending empty-handed is no failure (CV:66-67). */
    private val cancelledDownloadIds = HashSet<UUID>()

    /**
     * A full-screen layer hides the composer (CV:202-206): the screen drops toasts to the bottom
     * edge and hides the jump-to-latest control. The camera counts too (iOS presents it full screen).
     */
    val coversComposer: Boolean
        get() = viewingMedia != null || viewingVideo != null || composeDraft != null || videoDraft != null || showsCamera

    /**
     * Toasts raised while a full-screen layer covers the composer: [ComposeMediaLayers] draws them on
     * that layer at the screen's bottom edge (iOS drops its toasts to the bottom while
     * `coversComposer`, CV:208-212); otherwise they go to the conversation's own toast host.
     */
    internal val coveredToasts = ToastState()

    // ---- Effects for the composable layer ---------------------------------------------------------

    private val mutableEffects = MutableSharedFlow<ComposeEffect>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What only the composable can do (activity launchers, the view's haptics); dropped while nobody draws the chat. */
    internal val effects: SharedFlow<ComposeEffect> = mutableEffects.asSharedFlow()

    /** The chat was left for good ([onLeave] without a pushed profile): results of work in flight stay silent. */
    private var left = false

    private val artifactSink = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            if (viewingMedia in messageIds) viewingMedia = null
            if (viewingVideo?.id in messageIds) viewingVideo = null
        }

        override fun onSensitiveMemoryLocked() = onLock()

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            if (viewingMedia == from) viewingMedia = to
            viewingVideo?.let { if (it.id == from) viewingVideo = it.copy(id = to) }
        }
    }
    private var sinkRegistration: AutoCloseable? = services.registerArtifactSink(artifactSink)

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { closeSink() }
        scope.launch { gesture.phase.collect { updateRecording() } }
        scope.launch {
            services.recorderState.map { it.recording }.distinctUntilChanged().collect(::onRecorderRecording)
        }
        scope.launch {
            services.threads.map { it[peer].orEmpty() }.distinctUntilChanged().collect(::onThreadChanged)
        }
    }

    // ---- Reply (CV:1268-1299) ---------------------------------------------------------------------

    /** Reply to [message]: the strip shows its quote, the keyboard opens at once (CV:1289-1294). */
    fun startReply(message: ChatMessage) {
        if (!message.canBeQuoted) return
        replyTarget = message
        focusToken++
    }

    /** Drops the reply; the draft stays (CV:1296-1299). */
    fun clearReply() {
        replyTarget = null
    }

    /** The live copy of the message being answered: an original deleted meanwhile reads "Message deleted" (CV:1268-1272). */
    val liveReplyTarget: ChatMessage?
        get() {
            val target = replyTarget ?: return null
            return thread.firstOrNull { it.id == target.id } ?: target
        }

    /** The quote above the field (CV:1274-1278). */
    val replyContent: ReplyQuoteContent?
        get() = liveReplyTarget?.let { ReplyQuoteContent.make(it, peerName, services.myUserId) }

    /** The reference sealed into the next send: the snapshot from when the reply started (CV:1280-1287). */
    val outgoingReplyReference: MessageReplyReference? get() = replyTarget?.replyReference

    /** The reply strip's quote was tapped: the thread jumps to the original (CV:320-322). */
    fun jumpToReplyTarget() {
        replyTarget?.let { host.jumpToQuoted(it.id) }
    }

    // ---- Text and Notes sends --------------------------------------------------------------------

    /**
     * Every draft change (CV:309-318): the link composer looks at it, and unless this is Notes the
     * peer sees "typing" while the trimmed draft is not empty (idle timing lives in messaging).
     */
    fun onDraftChanged(text: String) {
        linkComposer.draftChanged(text)
        if (!isNotes) services.setTyping(peer, isTyping = text.isNotBlank())
    }

    /**
     * Send (CV:1335-1345): the text, the reply reference and the finished preview of a link still in
     * the text go out; the draft and the reply clear at once, the peer stops seeing "typing".
     */
    fun sendDraft() {
        val text = draft.text.toString()
        if (text.isBlank()) return
        val reference = outgoingReplyReference
        // Only a preview that finished loading, for a link still in the text, goes along.
        val preview = linkComposer.takeAttachment(text)
        draft.clearText()
        clearReply()
        services.setTyping(peer, false)
        playHaptic(Haptic.Light)
        services.sendScope.launch { services.sendText(text, peer, reference, preview) }
    }

    /** Notes' "Todo" button (CV:1694-1721). */
    fun sendTodo() {
        val text = draft.text.toString().trim()
        if (text.isEmpty()) {
            showToast(Toast.info("Type a todo, then tap Todo."))
            return
        }
        services.sendTodo(text)
        draft.clearText()
        playHaptic(Haptic.Success)
        host.pinToBottom()
    }

    /** ✕ / "Remove Preview" on the link strip (CV:331-334). */
    fun removeLinkPreview() {
        playHaptic(Haptic.Light)
        linkComposer.dismiss()
    }

    // ---- Voice (CV:1414-1507) ---------------------------------------------------------------------

    /**
     * Begins a take (`startRecording`, CV:1418-1437). False makes the composer drop back to idle:
     * without the microphone permission the system asks first and this hold records nothing (the
     * first hold after the prompt never records, as on iOS; conversation-compose-media §4.7).
     */
    internal suspend fun startRecording(): Boolean {
        // Playback and recording cannot share the route; a note that is playing must yield.
        services.stopPlayback()
        if (!services.hasMicPermission()) {
            mutableEffects.tryEmit(ComposeEffect.RequestMicrophone)
            return false
        }
        val started = try {
            services.startRecording()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (!left) {
                showToast(Toast.failure(recorderMessage(e)))
                playHaptic(Haptic.Error)
            }
            return false
        }
        if (!started) return false
        ownsTake = true
        updateRecording()
        playHaptic(Haptic.Medium)
        if (!isNotes) services.setRecording(peer, true)
        // A clear sign a transcript is wanted: the model downloads while they speak (CV:1427-1430).
        services.prepareTranscriptionModel()
        return true
    }

    /** Throws the take away (slide to cancel, Discard, a release during the start) (CV:1439-1444). */
    internal fun cancelRecording() {
        // Released before the recorder reports the stop, so that report does not signal a second "stopped".
        ownsTake = false
        services.cancelRecording()
        updateRecording()
        if (!isNotes) services.setRecording(peer, false)
    }

    /**
     * Finishes the take and sends it (CV:1457-1507). A take under 0.6 s is a mis-tap: discarded with a
     * hint instead of an error. The note goes out at once; a transcript follows if the model is
     * already on the phone (never held back for the one-time download).
     */
    internal fun sendRecording() {
        services.sendScope.launch {
            // Each outcome below signals "stopped" once itself; the recorder's own stop report must not add a second.
            ownsTake = false
            val take = try {
                services.finishRecording()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                updateRecording()
                if (!isNotes) services.setRecording(peer, false)
                if (!left) {
                    showToast(Toast.failure(recorderMessage(e)))
                    playHaptic(Haptic.Error)
                }
                return@launch
            }
            updateRecording()
            if (take == null) {
                if (!isNotes) services.setRecording(peer, false)
                if (!left) {
                    showToast(Toast.info("Hold to record, release to send"))
                    playHaptic(Haptic.Warning)
                }
                return@launch
            }
            playHaptic(Haptic.Light)
            if (!isNotes) {
                services.setRecording(peer, false)
                services.setTyping(peer, false)
            }
            val reference = outgoingReplyReference
            clearReply()
            val hints = transcriptionHints()
            val audio = take.data
            val error = services.sendVoice(
                audio = audio,
                durationMs = take.durationMs,
                peer = peer,
                waveform = take.waveform.takeIf { it.isNotEmpty() },
                replyTo = reference,
                transcriptProvider = { messageId ->
                    // Never hold a note back for the one-time model download (CV:1484-1494).
                    if (!services.transcriptionModelInstalled()) {
                        null
                    } else {
                        try {
                            services.transcribe(audio, hints, peer, messageId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                },
            )
            if (left) return@launch
            if (error != null) {
                showToast(Toast.failure(error))
                playHaptic(Haptic.Error)
            } else {
                playHaptic(Haptic.Success)
            }
        }
    }

    /** Back during a take discards it and stays in the chat (thread D9 = compose Q10). */
    fun cancelVoiceTake() {
        if (gesture.phase.value.isActive) {
            gesture.finish(send = false)
        } else if (ownsTake) {
            cancelRecording()
        }
    }

    /**
     * Names to bias the recogniser toward: the peer and the contacts, sorted, at most 50 — a long
     * bias list dilutes each entry (CV:1446-1453).
     */
    internal fun transcriptionHints(): List<String> =
        (listOf(peerName) + services.contactUsernames()).filter { it.isNotEmpty() }.toSet().sorted().take(MAX_TRANSCRIPTION_HINTS)

    private fun onRecorderRecording(recordingNow: Boolean) {
        if (!recordingNow) {
            // The recorder ended the take itself (a call, the app leaving the foreground): back to idle (CCV:145-148).
            gesture.recorderStopped()
            if (ownsTake) {
                ownsTake = false
                if (!isNotes) services.setRecording(peer, false)
            }
        }
        updateRecording()
    }

    private fun updateRecording() {
        val now = gesture.phase.value.isActive || (ownsTake && services.recorderState.value.recording)
        if (recording.value != now) recording.value = now
    }

    // ---- Attach (CV:389-443, 1723-1738, 1931-1934) ---------------------------------------------

    /** The composer's "+" (CV:304). */
    fun openAttachSheet() {
        showsAttachSheet = true
    }

    /** Cancel, a swipe down, the scrim or Back. */
    fun closeAttachSheet() {
        showsAttachSheet = false
    }

    /** An option of the attach sheet, after the sheet closed (`handleAttach`, CV:1723-1738). */
    fun handleAttach(option: ChatAttachOption) {
        showsAttachSheet = false
        when (option) {
            ChatAttachOption.Photos -> {
                // A fresh pick, even if an "Add" picker was closed without picking.
                pickerAppendsToDraft = false
                mutableEffects.tryEmit(ComposeEffect.OpenPicker(PickerRequest.newPick()))
            }
            ChatAttachOption.Camera -> {
                if (services.hasCamera()) {
                    mutableEffects.tryEmit(ComposeEffect.RequestCamera)
                } else {
                    showToast(Toast.failure("Camera is not available on this device."))
                }
            }
            // The document picker: multiple, the §4 types (docs/file-sharing.md §7).
            ChatAttachOption.File -> mutableEffects.tryEmit(ComposeEffect.OpenFilePicker)
            else -> {
                showToast(Toast.info("${option.title} coming soon"))
                playHaptic(Haptic.Light)
            }
        }
    }

    /** A Recents tile's original arrived (CV:396-399). */
    fun pickRecentPhoto(photo: PickedPhoto) {
        showsAttachSheet = false
        presentMediaCompose(listOf(photo))
    }

    /** "Add" on a compose screen: the picker again, its picks joining that send (CV:684-689). */
    fun openPickerToAppend() {
        val staged = stagedCount
        if (staged >= MAX_MEDIA_PER_SEND) return
        pickerAppendsToDraft = true
        mutableEffects.tryEmit(
            ComposeEffect.OpenPicker(PickerRequest.append(staged, videoComposeOpen = videoDraft != null, photoComposeOpen = composeDraft != null)),
        )
    }

    /** Items already in the compose screen that "Add" extends (CV:670-673). */
    private val stagedCount: Int get() = videoDraft?.videos?.size ?: composeDraft?.photos?.size ?: 0

    /**
     * What the system picker handed back, in the order picked (`loadPickedMedia`, CV:1740-1801):
     * clips are probed (metadata only) and get a poster, photos a 2048-px preview; the URIs stay the
     * sources (C26, C27). "Add" extends the open compose screen; a mixed pick becomes two steps —
     * the clips first, then the photos.
     */
    suspend fun loadPickedMedia(uris: List<Uri>) {
        val appending = pickerAppendsToDraft
        pickerAppendsToDraft = false
        if (uris.isEmpty()) return
        val photos = ArrayList<PickedPhoto>()
        val videos = ArrayList<PickedVideo>()
        for (uri in uris) {
            if (services.mimeType(uri)?.startsWith("video/") == true) {
                val movie = PickedMovie(uri)
                // Probing is metadata-only, so compose opens already knowing the clip.
                val probe = services.probeVideo(uri)
                if (probe == null) {
                    movie.cleanup()
                    continue
                }
                val poster = services.videoPoster(uri, VIDEO_POSTER_MAX_EDGE)
                videos += PickedVideo(movie = movie, probe = probe, poster = poster)
            } else {
                val source = MediaImageSource.ContentUri(uri)
                val preview = services.decodePreview(source, PickedPhoto.PREVIEW_MAX_EDGE) ?: continue
                photos += PickedPhoto(preview = preview, source = source)
            }
        }
        if (left) {
            videos.forEach { it.movie.cleanup() }
            return
        }
        if (photos.isEmpty() && videos.isEmpty()) {
            showToast(Toast.failure(if (uris.size > 1) "Could not load those items." else "Could not load that item."))
            return
        }
        val openVideos = videoDraft
        if (appending && openVideos != null && videos.isNotEmpty()) {
            val merged = openVideos.videos + videos
            merged.drop(MAX_MEDIA_PER_SEND).forEach { it.movie.cleanup() }
            videoDraft = openVideos.copy(videos = merged.take(MAX_MEDIA_PER_SEND))
            return
        }
        val openPhotos = composeDraft
        if (appending && openPhotos != null && photos.isNotEmpty()) {
            videos.forEach { it.movie.cleanup() }
            composeDraft = openPhotos.copy(photos = (openPhotos.photos + photos).take(MAX_MEDIA_PER_SEND))
            return
        }
        if (videos.isNotEmpty()) {
            photosAfterVideoCompose = photos
            presentVideoCompose(videos)
            return
        }
        presentMediaCompose(photos)
    }

    // ---- Camera (CV:427-443, 1851-1860) ---------------------------------------------------------

    /** The camera permission is granted: the in-app camera opens. */
    fun presentCamera() {
        showsCamera = true
    }

    /** Closed without a capture. */
    fun closeCamera() {
        showsCamera = false
    }

    /** A photo was taken: compose opens with it (CV:432-433). */
    fun onCameraPhoto(photo: PickedPhoto) {
        showsCamera = false
        presentMediaCompose(listOf(photo))
    }

    /** A clip was recorded: the same trim screen as a library pick (CV:434-435). */
    fun onCameraVideo(movie: PickedMovie) {
        showsCamera = false
        scope.launch { presentCapturedMovie(movie) }
    }

    /** A camera movie → the video compose (`presentCapturedMovie`, CV:1851-1860). */
    suspend fun presentCapturedMovie(movie: PickedMovie) {
        val probe = services.probeVideo(movie.uri)
        if (probe == null || left) {
            movie.cleanup()
            if (!left) showToast(Toast.failure("Could not load that video."))
            return
        }
        val poster = services.videoPoster(movie.uri, VIDEO_POSTER_MAX_EDGE)
        presentVideoCompose(listOf(PickedVideo(movie = movie, probe = probe, poster = poster)))
    }

    // ---- Photo compose (CV:506-547, 1839-1843, 1875-1929) ----------------------------------------

    /** The photo compose opens with [photos] (at most ten) (`presentMediaCompose`, CV:1839-1843). */
    fun presentMediaCompose(photos: List<PickedPhoto>) {
        if (photos.isEmpty()) return
        photoComposeSent = false
        composeDraft = ComposeDraft(photos = photos.take(MAX_MEDIA_PER_SEND), peerName = peerName)
    }

    /** The photo compose closed by Send: it leaves faster (`easeOut 0.15` vs `0.2`, CV:513, 521). */
    internal var photoComposeSent: Boolean by mutableStateOf(false)
        private set

    /** The compose screen's Back tool / back (CV:512-516). */
    fun cancelMediaCompose() {
        composeDraft = null
        presentFilesAfterMediaCompose()
    }

    /** Removes a staged photo; the last one closes compose (CV:1875-1881). */
    fun removeComposePhoto(index: Int) {
        val draft = composeDraft ?: return
        if (index !in draft.photos.indices) return
        val photos = draft.photos.toMutableList().also { it.removeAt(index) }
        composeDraft = if (photos.isEmpty()) null else draft.copy(photos = photos)
        if (photos.isEmpty()) presentFilesAfterMediaCompose()
    }

    /**
     * Send from the photo compose (CV:517-533): the photos go out in order, each with its own edits;
     * the caption and the reply ride on the first only.
     */
    fun sendComposedPhotos(caption: String, quality: MediaComposeQuality, edits: List<MediaEdits>) {
        val photos = composeDraft?.photos ?: return
        val reference = outgoingReplyReference
        clearReply()
        photoComposeSent = true
        composeDraft = null
        presentFilesAfterMediaCompose()
        services.sendScope.launch { sendPickedPhotos(photos, edits, caption, quality, reference) }
    }

    /** `sendPickedPhotos` (CV:1896-1929): sequential, in order; the first error is shown, bubbles keep Retry. */
    private suspend fun sendPickedPhotos(
        photos: List<PickedPhoto>,
        edits: List<MediaEdits>,
        caption: String,
        quality: MediaComposeQuality,
        replyTo: MessageReplyReference?,
    ) {
        if (photos.isEmpty()) return
        isSendingMedia = true
        var firstError: String? = null
        try {
            photos.forEachIndexed { index, photo ->
                val error = services.sendImage(
                    source = photo.source,
                    peer = peer,
                    // Telegram puts the caption — and the reply — on the first item of an album.
                    caption = if (index == 0) caption else "",
                    quality = quality,
                    edits = edits.getOrElse(index) { MediaEdits.Identity },
                    replyTo = if (index == 0) replyTo else null,
                )
                if (firstError == null) firstError = error
            }
        } finally {
            isSendingMedia = false
        }
        if (left) return
        val error = firstError
        if (error != null) {
            // Up longer, so the reason can be read.
            showToast(Toast.failure(error, LONG_FAILURE_MS))
            playHaptic(Haptic.Error)
        } else {
            playHaptic(Haptic.Success)
        }
    }

    // ---- Video compose (CV:549-579, 1809-1892) ---------------------------------------------------

    fun presentVideoCompose(videos: List<PickedVideo>) {
        if (videos.isEmpty()) return
        videos.drop(MAX_MEDIA_PER_SEND).forEach { it.movie.cleanup() }
        videoDraft = VideoComposeDraft(videos = videos.take(MAX_MEDIA_PER_SEND), peerName = peerName)
    }

    /** The video compose's Back: every staged clip is dropped (CV:555-558). */
    fun cancelVideoCompose() {
        videoDraft?.videos?.forEach { it.movie.cleanup() }
        closeVideoCompose()
    }

    /** Removes a staged clip; the last one closes the screen (CV:1883-1892). */
    fun removeComposeVideo(index: Int) {
        val draft = videoDraft ?: return
        if (index !in draft.videos.indices) return
        val videos = draft.videos.toMutableList()
        videos.removeAt(index).movie.cleanup()
        if (videos.isEmpty()) closeVideoCompose() else videoDraft = draft.copy(videos = videos)
    }

    /**
     * Send from the video compose (CV:559-567): the bubbles land at once, so the thread pins before
     * the first encode starts; each bubble carries its own progress ring (no HUD).
     */
    fun sendComposedVideos(plans: List<VideoSendPlan>) {
        val movies = videoDraft?.videos?.map { it.movie } ?: return
        val reference = outgoingReplyReference
        clearReply()
        closeVideoCompose()
        host.pinToBottom()
        services.sendScope.launch { sendVideoPlans(plans, movies, reference) }
    }

    /** `sendVideoPlans` (CV:1811-1837): in order, the quote on the first clip only; the picked files go afterwards. */
    private suspend fun sendVideoPlans(plans: List<VideoSendPlan>, movies: List<PickedMovie>, replyTo: MessageReplyReference?) {
        var firstError: String? = null
        try {
            plans.forEachIndexed { index, plan ->
                val error = services.sendVideo(plan, peer, if (index == 0) replyTo else null)
                if (firstError == null) firstError = error
            }
        } finally {
            movies.forEach { it.cleanup() }
        }
        if (left || plans.isEmpty()) return
        val error = firstError
        if (error != null) {
            showToast(Toast.failure(error, LONG_FAILURE_MS))
            playHaptic(Haptic.Error)
        } else {
            playHaptic(Haptic.Success)
        }
    }

    /**
     * Closes the video compose and hands photos from the same pick to the photo compose once the
     * video surface has left (260 ms) — after Send and after Cancel alike (CV:1862-1873).
     */
    private fun closeVideoCompose() {
        videoDraft = null
        if (photosAfterVideoCompose.isEmpty()) {
            presentFilesAfterMediaCompose()
            return
        }
        val photos = photosAfterVideoCompose
        photosAfterVideoCompose = emptyList()
        pendingPhotoCompose?.cancel()
        pendingPhotoCompose = scope.launch {
            delay(VIDEO_TO_PHOTO_COMPOSE_MS)
            presentMediaCompose(photos)
        }
    }

    // ---- Files (docs/file-sharing.md §6, §7) ---------------------------------------------------

    /**
     * What the document picker handed back: each refused pick says why (one toast each). Images and
     * videos the photo/video pipeline can decode go out as photos and videos, so they show in the
     * chat (docs/file-sharing.md §4, §7): the video compose, then the photo compose, as a mixed
     * library pick. The rest — and any image or video it can't decode — open the file composer
     * after them, at most ten, in pick order.
     */
    suspend fun loadPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val result = services.inspectFiles(uris)
        if (left) return
        result.refusals.forEach { showToast(Toast.failure(it, LONG_FAILURE_MS)) }
        if (result.refusals.isNotEmpty()) playHaptic(Haptic.Error)
        val photos = ArrayList<PickedPhoto>()
        val videos = ArrayList<PickedVideo>()
        val files = ArrayList<PickedFile>()
        for (file in result.files) {
            val uri = file.uri
            when {
                uri != null && file.type.category == FileCategory.Image -> {
                    val source = MediaImageSource.ContentUri(uri)
                    val preview = services.decodePreview(source, PickedPhoto.PREVIEW_MAX_EDGE)
                    if (preview != null) photos += PickedPhoto(preview = preview, source = source) else files += file
                }
                uri != null && file.type.category == FileCategory.Video -> {
                    val probe = services.probeVideo(uri)
                    if (probe != null) {
                        val poster = services.videoPoster(uri, VIDEO_POSTER_MAX_EDGE)
                        videos += PickedVideo(movie = PickedMovie(uri), probe = probe, poster = poster)
                    } else {
                        files += file
                    }
                }
                else -> files += file
            }
        }
        if (left) return
        when {
            videos.isNotEmpty() -> {
                photosAfterVideoCompose = photos
                filesAfterMediaCompose = files
                presentVideoCompose(videos)
            }
            photos.isNotEmpty() -> {
                filesAfterMediaCompose = files
                presentMediaCompose(photos)
            }
            files.isNotEmpty() -> fileDraft = FileComposeDraft(files)
        }
    }

    /** The rest of a file pick that also held photos or videos, once the media compose has left (260 ms). */
    private fun presentFilesAfterMediaCompose() {
        if (filesAfterMediaCompose.isEmpty()) return
        val files = filesAfterMediaCompose
        filesAfterMediaCompose = emptyList()
        pendingFileCompose?.cancel()
        pendingFileCompose = scope.launch {
            delay(VIDEO_TO_PHOTO_COMPOSE_MS)
            fileDraft = FileComposeDraft(files)
        }
    }

    /** The composer's Remove on a row; the last one closes the sheet. */
    fun removeComposeFile(index: Int) {
        val draft = fileDraft ?: return
        if (index !in draft.files.indices) return
        val files = draft.files.toMutableList().also { it.removeAt(index) }
        fileDraft = if (files.isEmpty()) null else draft.copy(files = files)
    }

    /** Cancel, a swipe down, the scrim or Back. */
    fun cancelFileCompose() {
        fileDraft = null
    }

    /**
     * Send from the file composer: the files go out in order, each with its own bubble and ring; the
     * caption and the reply ride on the first only (docs/file-sharing.md §2).
     */
    fun sendComposedFiles(caption: String) {
        val files = fileDraft?.files ?: return
        val reference = outgoingReplyReference
        clearReply()
        fileDraft = null
        host.pinToBottom()
        services.sendScope.launch {
            var firstError: String? = null
            files.forEachIndexed { index, file ->
                val error = services.sendFile(file, peer, if (index == 0) caption else "", if (index == 0) reference else null)
                if (firstError == null) firstError = error
            }
            if (left) return@launch
            val error = firstError
            if (error != null) {
                showToast(Toast.failure(error, LONG_FAILURE_MS))
                playHaptic(Haptic.Error)
            } else {
                playHaptic(Haptic.Success)
            }
        }
    }

    /**
     * Open, Save to Downloads or Share of a file message — or, for an APK, which is never opened
     * here, its download followed by its message menu. A received file whose type warns asks first
     * (§6) before Open, Save and Share, the actions that hand its plaintext to another app; each
     * answer covers this one action. A download only fills the sealed cache, so it never asks. A
     * file not on this phone downloads first, with its ring.
     */
    fun requestFileAction(message: ChatMessage, action: FileAction) {
        // A tap is never the release of the hold that opened a menu (memory: *Hold release fires bubble controls*).
        val tap = action == FileAction.Open || action == FileAction.Download
        if (message.deleted || (host.isShowingMessageMenu && tap)) return
        val type = message.fileType ?: return
        val warning = type.warning
        if (!message.isMine && warning != null && action != FileAction.Download) {
            fileWarning = FileWarningPrompt(message, warning, action, peerName)
            return
        }
        performFileAction(message, action)
    }

    /** The warning's Continue on [prompt] (the sheet has closed by then): the action it guarded runs. */
    fun confirmFileWarning(prompt: FileWarningPrompt) {
        if (fileWarning === prompt) fileWarning = null
        if (left) return
        performFileAction(prompt.message, prompt.action)
    }

    /** The warning's Cancel, the scrim or Back: nothing happens, nothing is remembered. */
    fun dismissFileWarning() {
        fileWarning = null
    }

    private fun performFileAction(message: ChatMessage, action: FileAction) {
        // Messaging's thread, not [thread]: a download that just landed is there before the collector ran.
        val live = services.threads.value[peer]?.firstOrNull { it.id == message.id } ?: message
        if (live.needsMediaDownload) {
            downloadFile(live, then = action)
            return
        }
        if (!live.hasFullMedia) return
        if (action == FileAction.Download) {
            // An APK on this phone: its menu, never an Open (§6).
            if (!left) host.showMessageMenu(live)
            return
        }
        val name = live.fileName ?: return
        services.sendScope.launch {
            when (action) {
                FileAction.Open -> when (val outcome = services.fileOpenTarget(live.id, name)) {
                    is FileOpenOutcome.Ready -> if (!left) mutableEffects.tryEmit(ComposeEffect.OpenFile(outcome.target, outcome.extension))
                    is FileOpenOutcome.Refused -> fileFailure(outcome.message)
                }
                FileAction.Share -> {
                    val target = services.fileShareTarget(live.id, name)
                    if (target == null) fileFailure(FileCopy.COULD_NOT_SHARE) else if (!left) mutableEffects.tryEmit(ComposeEffect.ShareFile(target))
                }
                FileAction.Save -> when (val outcome = services.saveFileToDownloads(live.id, name)) {
                    SaveOutcome.Saved -> if (!left) {
                        showToast(Toast.success(FileCopy.SAVED_TO_DOWNLOADS))
                        playHaptic(Haptic.Success)
                    }
                    is SaveOutcome.Failed -> fileFailure(outcome.message)
                }
                FileAction.Download -> Unit
            }
        }
    }

    /** A file's download (tap, or before the action it was asked for); stopping it from the ring is no failure. */
    private fun downloadFile(message: ChatMessage, then: FileAction) {
        if (!mediaDownloadIds.add(message.id)) return
        services.sendScope.launch {
            try {
                services.ensureFileLoaded(message)
                if (cancelledDownloadIds.remove(message.id)) return@launch
                if (left) return@launch
                val live = services.threads.value[peer]?.firstOrNull { it.id == message.id }
                if (live?.hasFullMedia != true) {
                    fileFailure(FileCopy.COULD_NOT_DOWNLOAD)
                    return@launch
                }
                playHaptic(Haptic.Light)
                performFileAction(live, then)
            } finally {
                mediaDownloadIds.remove(message.id)
                cancelledDownloadIds.remove(message.id)
            }
        }
    }

    /** `ACTION_VIEW` found no app for the file's type (§7). */
    internal fun onNoAppForFile(extension: String) = fileFailure(FileCopy.noApp(extension))

    /** The share sheet could not start. */
    internal fun onShareFileFailed() = fileFailure(FileCopy.COULD_NOT_SHARE)

    private fun fileFailure(message: String) {
        if (left) return
        showToast(Toast.failure(message))
        playHaptic(Haptic.Error)
    }

    // ---- Media tap, download, viewers (CV:2305-2438) ---------------------------------------------

    /**
     * A tap on a photo, video or file bubble (`handleMediaTap`, CV:2332-2349): download what is not
     * here yet, otherwise open the viewer or the player; a file downloads and then opens
     * ([requestFileAction]). A tombstone or a failed send (which only has Retry) does nothing.
     */
    fun handleMediaTap(message: ChatMessage) {
        if (message.deleted || message.receipt == ReceiptStatus.Failed) return
        if (message.kind == ChatMessageKind.File) {
            // Download when needed, then open — an APK only downloads (docs/file-sharing.md §6, §7).
            val type = message.fileType ?: return
            requestFileAction(message, if (type.canOpen) FileAction.Open else FileAction.Download)
            return
        }
        if (message.needsMediaDownload) {
            downloadMedia(message)
            return
        }
        when (message.kind) {
            ChatMessageKind.Image -> openMediaViewer(message)
            ChatMessageKind.Video -> openVideoPlayer(message)
            else -> Unit
        }
    }

    /** The explicit full-media download — never on scroll or appear (CV:2351-2384). */
    fun downloadMedia(message: ChatMessage) {
        if (!message.needsMediaDownload) return
        val kind = message.kind
        if (kind != ChatMessageKind.Image && kind != ChatMessageKind.Video) return
        if (!mediaDownloadIds.add(message.id)) return
        services.sendScope.launch {
            try {
                if (kind == ChatMessageKind.Image) services.ensureImageLoaded(message) else services.ensureVideoLoaded(message)
                // Stopped from the ring: nothing went wrong.
                if (cancelledDownloadIds.remove(message.id)) return@launch
                if (left) return@launch
                val live = services.threads.value[peer]?.firstOrNull { it.id == message.id }
                if (live?.hasFullMedia != true) {
                    showToast(Toast.failure(if (kind == ChatMessageKind.Image) "Could not download that photo." else "Could not download that video."))
                    playHaptic(Haptic.Error)
                } else {
                    playHaptic(Haptic.Light)
                }
            } finally {
                mediaDownloadIds.remove(message.id)
                cancelledDownloadIds.remove(message.id)
            }
        }
    }

    /**
     * The bubble's ring ✕ (CV:2386-2394): stops the download without the "Could not download" an
     * empty-handed download ends with otherwise. The bubble claims the tap itself.
     */
    fun cancelDownload(message: ChatMessage) {
        if (message.id in mediaDownloadIds) cancelledDownloadIds += message.id
        services.cancelMediaDownload(message.id)
    }

    /** The viewer over the conversation (CV:2396-2407); never from the release of the hold that opened a message menu. */
    fun openMediaViewer(message: ChatMessage) {
        if (host.isShowingMessageMenu) return
        val live = thread.firstOrNull { it.id == message.id } ?: message
        if (!live.hasFullMedia) {
            downloadMedia(live)
            return
        }
        viewingMedia = message.id
    }

    /** The player over the conversation (CV:2409-2426). */
    fun openVideoPlayer(message: ChatMessage) {
        if (host.isShowingMessageMenu) return
        val live = thread.firstOrNull { it.id == message.id } ?: message
        if (!live.hasFullMedia) {
            downloadMedia(live)
            return
        }
        viewingVideo = ViewingVideo(
            id = message.id,
            title = if (message.isMine) YOU else peerName,
            dateLine = viewerDateLine(message.createdAt),
        )
    }

    /** The viewer's ✕, swipe down or Back; also the thread's delete performed from the viewer (CV:2270-2275). */
    fun closeMediaViewer() {
        viewingMedia = null
        viewerDeleteId = null
    }

    fun closeVideoPlayer() {
        viewingVideo = null
    }

    /**
     * Every downloaded photo of the thread, in thread order, for the viewer to page through
     * (`mediaViewerItems`, CV:2305-2330): never a tombstone or a failed send; the caption unless it
     * is the "Photo" stand-in; the aspect from the stored size (1 when unknown).
     */
    fun viewerItems(): List<ViewerItem> = viewerItems(thread, peerName)

    /**
     * The viewer's Delete: the thread's own "Delete message?" sheet with its scope choice
     * (CV:490-496, [ComposeHost.requestDelete]). The viewer leaves once that photo is deleted, whichever
     * page it was (iOS `performDelete` closes it first, CV:2270-2275; the thread calls [closeMediaViewer]).
     */
    fun requestDeleteFromViewer(id: UUID) {
        val message = thread.firstOrNull { it.id == id } ?: return
        viewerDeleteId = id
        host.requestDelete(message)
    }

    /** The photo the viewer asked to delete; the viewer closes when it is gone. */
    private var viewerDeleteId: UUID? = null

    private fun onThreadChanged(messages: List<ChatMessage>) {
        thread = messages
        // The open photo or clip was deleted for everyone (or left the thread): its viewer goes with it (CV:182-187, 265-271).
        viewingMedia?.let { id -> if (messages.isGone(id)) viewingMedia = null }
        viewingVideo?.let { video -> if (messages.isGone(video.id)) viewingVideo = null }
        viewerDeleteId?.let { id ->
            if (messages.isGone(id)) {
                viewerDeleteId = null
                viewingMedia = null
            }
        }
    }

    private fun List<ChatMessage>.isGone(id: UUID): Boolean = none { it.id == id && !it.deleted }

    // ---- Lifecycle (CV:363-388; conversation-compose-media §20, §22) ------------------------------

    /**
     * The chat leaves the screen (CV:363-379). A take is thrown away and the peer stops seeing typing
     * and recording. With the contact's profile pushed on top, the chat comes back: the draft, the
     * reply and the link preview stay. Otherwise everything staged is dropped and camera captures
     * deleted; sends in flight continue.
     */
    fun onLeave(profilePushed: Boolean) {
        dropTake()
        services.stopPlayback()
        if (!isNotes) {
            services.setTyping(peer, false)
            services.setRecording(peer, false)
        }
        if (profilePushed) return
        left = true
        linkComposer.reset()
        dropStaged()
        closeSink()
    }

    /**
     * The composer is on screen (again): a controller kept after [onLeave] — the screen reused it —
     * hears purges and locks again and speaks up again.
     */
    internal fun onShown() {
        if (!left) return
        left = false
        if (sinkRegistration == null) sinkRegistration = services.registerArtifactSink(artifactSink)
    }

    private fun closeSink() {
        sinkRegistration?.close()
        sinkRegistration = null
    }

    /**
     * The chats locked (invariant 5): the take, the draft, the reply, the link preview, staged media,
     * the viewers and the sheets leave memory (conversation-compose-media §20, §22). iOS gets this by
     * the lock screen replacing the view tree.
     */
    fun onLock() {
        dropTake()
        services.stopPlayback()
        if (!isNotes) {
            services.setTyping(peer, false)
            services.setRecording(peer, false)
        }
        draft.clearText()
        replyTarget = null
        linkComposer.reset()
        dropStaged()
    }

    private fun dropTake() {
        if (ownsTake || gesture.phase.value.isActive) services.cancelRecording()
        ownsTake = false
        gesture.reset()
        updateRecording()
    }

    private fun dropStaged() {
        showsAttachSheet = false
        showsCamera = false
        pickerAppendsToDraft = false
        pendingPhotoCompose?.cancel()
        pendingPhotoCompose = null
        photosAfterVideoCompose = emptyList()
        pendingFileCompose?.cancel()
        pendingFileCompose = null
        filesAfterMediaCompose = emptyList()
        composeDraft = null
        fileDraft = null
        fileWarning = null
        videoDraft?.videos?.forEach { it.movie.cleanup() }
        videoDraft = null
        viewingMedia = null
        viewingVideo = null
        viewerDeleteId = null
        coveredToasts.dismiss()
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun playHaptic(haptic: Haptic) {
        if (!left) mutableEffects.tryEmit(ComposeEffect.PlayHaptic(haptic))
    }

    private fun showToast(toast: Toast) {
        if (coversComposer) coveredToasts.show(toast) else host.showToast(toast)
    }

    /** The recorder's live readout (elapsed, levels) for the recording bars. */
    internal val recorderState: StateFlow<de.corespace.shroud.core.voice.VoiceRecorder.RecState> get() = services.recorderState

    /** The picker's result, in the order picked (an empty list when it was closed without a pick). */
    internal fun onPicked(uris: List<Uri>) {
        scope.launch { loadPickedMedia(uris) }
    }

    /** The document picker's result, in the order picked (empty when it was closed). */
    internal fun onFilesPicked(uris: List<Uri>) {
        scope.launch { loadPickedFiles(uris) }
    }

    /** A refused permission: the design's dark toast with "Settings" (u3il8T; conversation-compose-media §4.7). */
    internal fun showPermissionToast(text: String, onSettings: () -> Unit) {
        showToast(Toast.withAction(text, SETTINGS_ACTION, onSettings))
    }

    /** Decodes a Recents original for the attach sheet (W2-MEDIA-IMAGE). */
    internal suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? = services.decodePreview(source, maxEdge)

    internal fun photoAccessRequested(): Boolean = services.photoAccessRequested()

    /** The photo library's grant for the Recents strip (K4). */
    internal fun photoLibraryAccess(): LibraryAccess = services.photoLibraryAccess()

    /** The Recents strip's tiles: the newest images with their thumbnails (K4). */
    internal suspend fun loadRecentPhotos(): List<RecentPhoto> = RecentPhotos.load(services::recentPhotos, services::photoThumbnail)

    internal fun markPhotoAccessRequested() = services.markPhotoAccessRequested()

    /** The full-screen video the player shows (CV:641-647); the bytes stay sealed (plan C7). */
    data class ViewingVideo(val id: UUID, val title: String, val dateLine: String)

    companion object {
        private const val YOU = "You"
        private const val MAX_TRANSCRIPTION_HINTS = 50

        /** Poster edge of a picked clip (`VideoMedia.posterImage`, conversation-compose-media §8.2). */
        const val VIDEO_POSTER_MAX_EDGE = 640

        /** The video surface's exit before the photo compose rises (CV:1869-1870). */
        const val VIDEO_TO_PHOTO_COMPOSE_MS = 260L

        /** Media send failures stay up 4 s (CV:1832, 1924). */
        const val LONG_FAILURE_MS = 4_000L

        /** `dd.MM.yy`, fixed `en_GB` (CV:2428-2438). */
        private val viewerDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.UK)

        /** The viewer's date line for [instant] in [zone] (CV:2436-2438). */
        fun viewerDateLine(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = viewerDateFormat.format(instant.atZone(zone))

        /** `mediaViewerItems` (CV:2305-2330), pure. */
        fun viewerItems(thread: List<ChatMessage>, peerName: String, zone: ZoneId = ZoneId.systemDefault()): List<ViewerItem> =
            thread
                .filter { it.kind == ChatMessageKind.Image && !it.deleted && it.receipt != ReceiptStatus.Failed && it.hasFullMedia }
                .map { message ->
                    val caption = message.text.trim()
                    val width = message.imageWidth ?: 0
                    val height = message.imageHeight ?: 0
                    ViewerItem(
                        id = message.id,
                        title = if (message.isMine) YOU else peerName,
                        dateLine = viewerDateLine(message.createdAt, zone),
                        caption = caption.takeUnless { it == "Photo" },
                        aspect = if (height > 0) width.toFloat() / height else 1f,
                        isLoaded = true,
                    )
                }

        /**
         * What a recorder failure says (conversation-compose-media §4.7.5): the recorder's own copy
         * ("Already recording.", "Could not finish the recording.") — `SessionController.userMessage`
         * would turn it into the generic line, the iOS bug noted in §26.
         */
        fun recorderMessage(error: Throwable): String =
            if (error is VoiceRecorderException) error.reason.message else SessionController.userMessage(error)
    }
}

/** What only the composable layer can do for the controller. */
internal sealed interface ComposeEffect {
    data class OpenPicker(val request: PickerRequest) : ComposeEffect

    /** Hold without `RECORD_AUDIO`: the system asks; a refusal shows "Microphone access is off" + Settings (§4.7). */
    data object RequestMicrophone : ComposeEffect

    /** Camera chosen: ask `CAMERA`, then present the in-app camera (§8.3). */
    data object RequestCamera : ComposeEffect

    data class PlayHaptic(val haptic: Haptic) : ComposeEffect

    /** The attach sheet's File: `ACTION_OPEN_DOCUMENT`, multiple (docs/file-sharing.md §7). */
    data object OpenFilePicker : ComposeEffect

    /** `ACTION_VIEW` of a checked file grant; no app → [ComposeController.onNoAppForFile] with [extension]. */
    data class OpenFile(val target: ShareTarget, val extension: String) : ComposeEffect

    /** The share sheet over a file grant. */
    data class ShareFile(val target: ShareTarget) : ComposeEffect
}

/**
 * What a file message's action is (docs/file-sharing.md §6, §7): open it, save it, share it, or —
 * for an APK — download it and show its menu. Only the first three ask the §6 question.
 */
enum class FileAction { Open, Save, Share, Download }

/** Files staged in the file composer, in pick order (at most ten). */
data class FileComposeDraft(val files: List<PickedFile>)

/** The §6 dialog for [message]: its [warning], the [action] it guards, and [sender], the contact's display name. */
data class FileWarningPrompt(val message: ChatMessage, val warning: FileWarning, val action: FileAction, val sender: String) {
    val title: String get() = warning.dialogTitle
    val text: String get() = warning.dialogMessage(sender)
}

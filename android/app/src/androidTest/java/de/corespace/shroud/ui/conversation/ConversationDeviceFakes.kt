package de.corespace.shroud.ui.conversation

import android.content.Context
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.core.voice.VoicePlayer
import de.corespace.shroud.ui.conversation.bubble.BubbleServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * People, messages and recording stand-ins for the conversation's device tests (the JVM tests keep
 * their own copies under `test/`; the two source sets do not share code).
 */
internal object DeviceThread {
    val PEER: UUID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
    val ME: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")

    /** An hour ago: every bubble reads "today". */
    private val start: Instant = Instant.now().minusSeconds(3_600)

    fun message(
        at: Long,
        mine: Boolean = false,
        text: String = "hello",
        kind: ChatMessageKind = ChatMessageKind.Text,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = PEER,
        senderUserId = if (mine) ME else PEER,
        text = text,
        createdAt = start.plusSeconds(at),
        isMine = mine,
        receipt = ReceiptStatus.Delivered,
        kind = kind,
    )

    /** A downloaded photo with nothing decoded yet: the whole bubble is the photo. */
    fun photo(at: Long): ChatMessage =
        message(at, text = "Photo", kind = ChatMessageKind.Image).copy(imageWidth = 1_200, imageHeight = 900, hasFullMedia = true)

    /** A bubble that is one long link, so its first line is all link. */
    fun link(at: Long): ChatMessage = message(at, text = "https://example.com/a/fairly/long/path/to/hold/on")

    /** A link preview drawn above its text (Telegram's "Show above message"), so its block is the bubble's top. */
    fun preview(at: Long): ChatMessage = message(at, text = "https://example.org").copy(
        linkPreview = LinkPreview(
            url = "https://example.org",
            siteName = "Example",
            title = "An example page",
            summary = "Something long enough to read, so the block has some height to hold on.",
            showsAboveText = true,
        ),
    )

    /** A downloaded voice note: its play disc is enabled. */
    fun voice(at: Long): ChatMessage =
        message(at, text = "Voice message", kind = ChatMessageKind.Voice).copy(durationMs = 6_000, hasFullMedia = true)

    /** A text bubble carrying the peer's 👍 chip. */
    fun reacted(at: Long): ChatMessage =
        message(at, text = "nice").copy(reactions = listOf(MessageReaction(PEER, listOf("👍"), seq = 1)))
}

/** A recording stand-in for the engines behind the conversation. */
internal class DeviceConversationBackend : ConversationBackend {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    override val myUserId: UUID = DeviceThread.ME
    override fun isNotesChat(peer: UUID): Boolean = false
    override val threads = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())
    override val presence = MutableStateFlow<Map<UUID, PresenceDto>>(emptyMap())
    override val peerActivities = MutableStateFlow<Map<UUID, ChatPeerActivity>>(emptyMap())
    override val isOffline = MutableStateFlow(false)
    override val lastError = MutableStateFlow<String?>(null)
    override val activePeerId = MutableStateFlow<UUID?>(null)
    override val mediaTransfers = MutableStateFlow<Map<UUID, MediaTransfer>>(emptyMap())
    override val olderHistoryExhausted = MutableStateFlow<Set<UUID>>(setOf(DeviceThread.PEER))
    override val loadingOlderPeerIds = MutableStateFlow<Set<UUID>>(emptySet())
    override val reactionRevisions = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    override val reactionFailures = MutableSharedFlow<ReactionFailure>(extraBufferCapacity = 8)
    override val callMediaStarting = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override suspend fun loadThread(peer: UUID, reconcile: Boolean) = Unit
    override suspend fun loadOlderMessages(peer: UUID) {
        log += "loadOlder"
    }

    override fun setActivePeer(peer: UUID?) {
        activePeerId.value = peer
    }

    override fun setTyping(peer: UUID, isTyping: Boolean) = Unit
    override fun setRecording(peer: UUID, isRecording: Boolean) = Unit
    override fun canReact(message: ChatMessage): Boolean = !message.deleted
    override fun myReactions(message: ChatMessage): List<String> = emptyList()
    override fun toggleReaction(emoji: String, messageId: UUID, peer: UUID) {
        log += "toggleReaction"
    }

    override suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String? = null
    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome = ChatDeleteOutcome.ClearedForMe
    override fun cancelMediaDownload(messageId: UUID) {
        log += "cancelDownload"
    }

    override suspend fun retryFailedImage(messageId: UUID, peer: UUID): String? = null
    override suspend fun retryFailedVideo(messageId: UUID, peer: UUID): String? = null
    override suspend fun retryFailedFile(messageId: UUID, peer: UUID): String? = null
    override fun toggleTodo(messageId: UUID) = Unit
    override suspend fun startCall(peer: UUID, username: String, modality: CallModality): String? = null
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = AutoCloseable {}
    override fun copyToClipboard(text: String) = Unit
    override val clipboardConfirmsItself: Boolean = true
    override fun openLink(url: String): Boolean {
        log += "openLink"
        return true
    }

    override fun stopVoicePlayback() = Unit
}

/** A recording stand-in for the composer. */
internal class DeviceConversationCompose : ConversationCompose {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    override val isRecording: Boolean = false
    override val isViewingMedia: Boolean = false
    override fun startReply(message: ChatMessage) {
        log += "startReply"
    }

    override fun handleMediaTap(message: ChatMessage) {
        log += "mediaTap"
    }

    override fun onLeave(profilePushed: Boolean) = Unit
    override fun cancelVoiceTake() = Unit
    override fun closeMediaViewer() = Unit
    override fun cancelDownload(message: ChatMessage) {
        log += "cancelDownload"
    }
}

/** Bubbles' engines with nothing behind them; records every media request a control makes. */
internal class DeviceBubbleServices(override val context: Context, scope: CoroutineScope) : BubbleServices {
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    override val myUsername: String = "me"
    override fun canReact(message: ChatMessage): Boolean = !message.deleted
    override fun myReactions(message: ChatMessage): List<String> = emptyList()
    override suspend fun mediaBytes(messageId: UUID): ByteArray? {
        log += "mediaBytes"
        return null
    }

    override suspend fun ensureVoiceLoaded(message: ChatMessage) {
        log += "ensureVoiceLoaded"
    }

    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = Unit
    override suspend fun videoPoster(messageId: UUID): ByteArray? = null
    override suspend fun mediaDurationMs(messageId: UUID): Int? = null
    override val playback = VoicePlaybackCoordinator(SilentPlayer(), scope)
    override val transcription: VoiceTranscription = VoiceTranscription.Unavailable
    override fun transcriptionHints(peerName: String): List<String> = listOf(peerName)
    override fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID) = Unit
    override suspend fun transcribe(message: ChatMessage, peerName: String): String = ""
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = AutoCloseable {}

    private class SilentPlayer : VoicePlayer {
        override var listener: VoicePlayer.Listener? = null
        override fun load(data: ByteArray) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(rate: Float) = Unit
        override val positionMs: Long = 0L
        override val isAdvancing: Boolean = false
        override fun stop() = Unit
    }
}

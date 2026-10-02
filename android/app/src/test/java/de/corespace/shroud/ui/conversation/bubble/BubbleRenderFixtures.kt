package de.corespace.shroud.ui.conversation.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Rect
import androidx.emoji2.text.EmojiCompat
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.core.voice.VoicePlayer
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.MessageRowModel
import de.corespace.shroud.ui.conversation.MessageRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * The people, pictures and messages the bubble renders draw (conversation-thread §4–§14): a peer
 * called Jane Cooper, ourselves, a landscape "photo" painted in code (no binary fixtures), and a
 * builder for every message kind and state.
 */
internal object BubbleRenderFixtures {
    val ME: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    val PEER: UUID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
    const val PEER_NAME = "Jane Cooper"

    /** Yesterday, so no voice note counts as freshly arrived (it would land folded first). */
    val DAY: LocalDate = LocalDate.of(2026, 10, 1)

    fun at(hour: Int, minute: Int): Instant = DAY.atTime(LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant()

    /** A small landscape — sky, sun, two hills — as JPEG bytes of [width] × [height]. */
    fun landscape(width: Int, height: Int, quality: Int = 82, warm: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val sky = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                if (warm) intArrayOf(0xFFF6A86B.toInt(), 0xFFF9D9A8.toInt()) else intArrayOf(0xFF4F8FD8.toInt(), 0xFFBFE0F5.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, sky)
        canvas.drawCircle(w * 0.72f, h * 0.3f, h * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF1B8.toInt() })
        canvas.drawCircle(w * 0.2f, h * 1.25f, h * 0.75f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E7D4F.toInt() })
        canvas.drawCircle(w * 0.85f, h * 1.35f, h * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6A3F.toInt() })
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** A plausible voice envelope: two swells with some grain, 0…255 per bar. */
    fun envelope(count: Int = 44): Bytes = Bytes.of(
        ByteArray(count) { i ->
            val position = i / (count - 1f)
            val swell = 0.25 + 0.75 * (0.5 + 0.5 * sin(position * 2 * PI * 1.5 - PI / 2))
            val grain = 0.75 + 0.25 * sin(i * 2.3)
            (40 + swell * grain * 215).toInt().coerceIn(8, 255).toByte()
        },
    )

    fun message(
        text: String,
        mine: Boolean,
        time: Instant = at(10, 0),
        kind: ChatMessageKind = ChatMessageKind.Text,
        receipt: ReceiptStatus = if (mine) ReceiptStatus.Read else ReceiptStatus.Sent,
        deleted: Boolean = false,
        mediaObjectId: UUID? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
        hasFullMedia: Boolean = false,
        previewJpeg: ByteArray? = null,
        posterJpeg: ByteArray? = null,
        mediaByteCount: Long? = null,
        durationMs: Int? = null,
        voiceWaveform: Bytes? = null,
        transcript: String? = null,
        sendError: String? = null,
        todoDone: Boolean? = null,
        replyTo: MessageReplyReference? = null,
        linkPreview: LinkPreview? = null,
        reactions: List<MessageReaction> = emptyList(),
        peer: UUID = PEER,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        peerUserId = peer,
        senderUserId = if (mine) ME else peer,
        text = text,
        createdAt = time,
        isMine = mine,
        deleted = deleted,
        receipt = receipt,
        kind = kind,
        mediaObjectId = mediaObjectId,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        hasFullMedia = hasFullMedia,
        posterJpeg = posterJpeg?.let(Bytes::of),
        previewJpeg = previewJpeg?.let(Bytes::of),
        mediaByteCount = mediaByteCount,
        durationMs = durationMs,
        voiceWaveform = voiceWaveform,
        transcript = transcript,
        sendError = sendError,
        todoDone = todoDone,
        replyTo = replyTo,
        linkPreview = linkPreview,
        reactions = reactions,
    )

    /** The row the thread would build for [message]: its reaction chips, a quote, a transfer. */
    fun row(
        message: ChatMessage,
        quote: ReplyQuoteContent? = null,
        transfer: MediaTransfer? = null,
        transcriptTail: Boolean = false,
        isNotes: Boolean = false,
    ): MessageRowModel = MessageRowModel(
        message = message,
        isNotes = isNotes,
        peerName = PEER_NAME,
        replyQuote = quote,
        transfer = transfer,
        reactionChips = MessageRows.chips(message, ME),
        showsTranscriptTail = transcriptTail,
        highlighted = false,
    )

    fun reaction(user: UUID, vararg emojis: String, seq: Long = 1) = MessageReaction(user, emojis.toList(), seq)

    /**
     * Waits for the bundled emoji font a bubble started loading ([BubbleEmoji]) and lets its callbacks
     * run while [host]'s composition is still up. Left to finish during a later test, Compose's
     * "font loaded" write lands between compositions, and Robolectric then never sends global
     * snapshot changes again: every later test's taps stop recomposing.
     */
    fun awaitEmojiFont(host: ComposeHarness) {
        if (!EmojiCompat.isConfigured()) return
        val deadline = System.nanoTime() + 10_000_000_000L
        while (EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_LOADING && System.nanoTime() < deadline) {
            Thread.sleep(20)
            host.idle()
        }
        host.idle()
    }
}

/** A screen around the bubbles that only records what they asked for. */
internal class RecordingBubbleContext(override val myUserId: UUID? = BubbleRenderFixtures.ME) : BubbleContext {
    val log = ArrayList<String>()
    override fun allowsInnerTaps(messageId: UUID): Boolean = true
    override fun claimTap(messageId: UUID) {
        log += "claim"
    }
    override fun onTapMedia(message: ChatMessage) {
        log += "tapMedia"
    }
    override fun onCancelDownload(message: ChatMessage) {
        log += "cancel"
    }
    override fun onRetry(message: ChatMessage) {
        log += "retry"
    }
    override fun onTapQuote(messageId: UUID) {
        log += "quote"
    }
    override fun onOpenLink(url: String) {
        log += "open:$url"
    }
    override fun onToggleReaction(emoji: String, message: ChatMessage) {
        log += "react:$emoji"
    }
    override fun onToggleTodo(message: ChatMessage) {
        log += "todo"
    }
    override fun onTranscriptToggled(message: ChatMessage, expanded: Boolean) {
        log += "transcript:$expanded"
    }
    override fun reportBubbleBounds(messageId: UUID, boundsInRoot: Rect) = Unit
    override fun reportChipBounds(messageId: UUID, chipId: String, boundsInRoot: Rect) = Unit
}

/**
 * Engines behind the rendered bubbles: decrypted bytes per message, a voice player that is ready at
 * once, a transcriber whose progress and answer the test sets.
 */
internal class RenderBubbleServices(override val context: Context, scope: CoroutineScope) : BubbleServices {
    val media = HashMap<UUID, ByteArray>()
    val sinks = ArrayList<MessageArtifactSinks>()

    /** What loading a voice note does: by default it never finishes (the disc spins). */
    var voiceLoad: suspend (ChatMessage) -> Unit = { awaitCancellation() }

    /** The on-device transcription's answer: by default it never comes ("Transcribing…"). */
    var transcribeAnswer: suspend (ChatMessage) -> String = { awaitCancellation() }

    val install = MutableStateFlow(TranscriptionInstallState.Idle)

    override val myUsername: String = "Alex Morgan"
    override fun canReact(message: ChatMessage): Boolean = !message.deleted && message.receipt != ReceiptStatus.Failed
    override fun myReactions(message: ChatMessage): List<String> =
        message.reactions.filter { it.userId == BubbleRenderFixtures.ME }.flatMap { it.emojis }
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = media[messageId]
    override suspend fun ensureVoiceLoaded(message: ChatMessage) = voiceLoad(message)
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = Unit
    override suspend fun videoPoster(messageId: UUID): ByteArray? = null
    override suspend fun mediaDurationMs(messageId: UUID): Int? = null
    override val playback = VoicePlaybackCoordinator(ReadyVoicePlayer(), scope)
    override val transcription: VoiceTranscription = RenderTranscription(install)
    override fun transcriptionHints(peerName: String): List<String> = listOf(peerName)
    override fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID) = Unit
    override suspend fun transcribe(message: ChatMessage, peerName: String): String = transcribeAnswer(message)
    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable {
        sinks += sink
        return AutoCloseable { sinks -= sink }
    }
}

/** A player whose notes are 18 s long and 7 s in, ready as soon as they load. */
internal class ReadyVoicePlayer(private val durationMs: Long = 18_000, override val positionMs: Long = 7_000) : VoicePlayer {
    override var listener: VoicePlayer.Listener? = null
    override fun load(data: ByteArray) {
        listener?.onReady(durationMs)
    }
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(rate: Float) = Unit
    override fun stop() = Unit
}

/** A transcriber whose model download the test drives through [install]. */
internal class RenderTranscription(override val install: MutableStateFlow<TranscriptionInstallState>) : VoiceTranscription {
    override val isAvailable = MutableStateFlow(true)
    override suspend fun prepareModel(): Boolean = true
    override suspend fun modelIsInstalled(): Boolean = true
    override suspend fun transcribe(audio: ByteArray, mime: String, hints: List<String>, conversationId: UUID?, tracking: UUID?): String = ""
    override fun availableLocales(): List<Locale> = listOf(Locale.ENGLISH)
    override var languageOverride: Locale? = null
    override fun handOff(from: UUID, to: UUID) = Unit
}

package de.corespace.shroud.ui.conversation.bubble

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.transcription.TranscribeException
import de.corespace.shroud.core.transcription.VoiceTranscription
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.conversation.LinkPreviewImageCache
import de.corespace.shroud.ui.conversation.links.MessageLinkText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executor

/**
 * What a bubble needs from the engines beyond its row and the screen's [de.corespace.shroud.ui.conversation.BubbleContext]:
 * media bytes on demand (plan C8), the voice player, transcription, the reaction rules. The app reads
 * them from [AppContainer] ([forContainer]); tests provide fakes through [LocalBubbleServices].
 *
 * iOS hands these to each bubble as closures from `ConversationView.messageRow`
 * (`ConversationView.swift:1508-1677`); the Android seam (plan §1.7.13) keeps the screen's part in
 * `BubbleContext` and the engines' part here.
 */
@Stable
interface BubbleServices {
    /** Our name on our own reaction chips (`myUsername ?: "You"`, conversation-thread §14.1). */
    val myUsername: String?

    /** `MessagingController.canReact` (`MessagingController.swift:5012-5020`): no chip acts when false. */
    fun canReact(message: ChatMessage): Boolean

    /** Our emoji on [message], oldest first: the other person's emoji we have already are not added again. */
    fun myReactions(message: ChatMessage): List<String>

    /** The decrypted bytes of [messageId]'s media (photo, voice, large link image), or null (plan C8). */
    suspend fun mediaBytes(messageId: UUID): ByteArray?

    /** Loads a voice note's audio; returns once the attempt is over, loaded or not (`ConversationView.swift:1594-1598`). */
    suspend fun ensureVoiceLoaded(message: ChatMessage)

    /** Loads a link preview's large picture; small, so it loads on appear (`ConversationView.swift:1661-1666`). */
    suspend fun ensureLinkImageLoaded(message: ChatMessage)

    /** A still from a downloaded video that carries no poster (`VideoMessageBubble.swift:653-663`). */
    suspend fun videoPoster(messageId: UUID): ByteArray?

    /** The real length of a downloaded voice note whose payload says less than 300 ms (`VoiceMessageBubble.swift:694-708`). */
    suspend fun mediaDurationMs(messageId: UUID): Int?

    /** The app-wide voice player (one note at a time, survives scrolling). */
    val playback: VoicePlaybackCoordinator

    /** On-device transcription (unavailable until W3-TRANSCRIPTION). */
    val transcription: VoiceTranscription

    /**
     * Names to bias the recogniser toward: the peer and every contact, de-duplicated, sorted, the
     * first 50 (`ConversationView.swift:1446-1453`).
     */
    fun transcriptionHints(peerName: String): List<String>

    /** Shares a transcript made here as an annotation, in the background (`ConversationView.swift:1617-1623`). */
    fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID)

    /**
     * The voice bubble's "→A" on a note nobody shared a transcript for — iOS `onRequestTranscript`
     * (`ConversationView.swift:1607-1632`): transcribes the decrypted audio on this phone with
     * [transcriptionHints], shares a non-empty result in the background ([shareTranscript]) and returns
     * it (blank: no speech). Runs in the process scope, so a bubble scrolled away mid-way does not
     * cancel the share. Throws [de.corespace.shroud.core.transcription.TranscribeException] with a
     * user-facing message.
     */
    suspend fun transcribe(message: ChatMessage, peerName: String): String

    /** Purges, locks and re-keys reach the bubble caches through this ([BubbleMemory]). */
    fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable

    /** The process context (EmojiCompat). */
    val context: Context

    companion object {
        private val byContainer = java.util.WeakHashMap<AppContainer, BubbleServices>()

        /**
         * The services of the running app: one instance per container, so every bubble of every chat
         * shares it and [BubbleMemory] registers its sink with messaging once, not once per row.
         */
        @Synchronized
        fun forContainer(container: AppContainer): BubbleServices =
            byContainer.getOrPut(container) { ContainerBubbleServices(container) }

        /**
         * Names for [transcriptionHints]: [peerName] and [contacts], de-duplicated, sorted, at most 50
         * — iOS `Array(Set(names)).sorted().prefix(50)` (`ConversationView.swift:1448-1453`).
         */
        fun hints(peerName: String, contacts: List<String>): List<String> =
            (listOf(peerName) + contacts).toSet().sorted().take(MAX_HINTS)

        const val MAX_HINTS = 50
    }
}

/** Overrides the app's services (tests, previews). Null: read them from [LocalAppContainer]. */
val LocalBubbleServices = staticCompositionLocalOf<BubbleServices?> { null }

/**
 * Where a bubble's failure toasts go (a transcription that failed, `ConversationView.swift:1625-1629`).
 * The conversation screen (W3-THREAD-LIST) provides its toast host; the default drops them.
 */
val LocalBubbleToaster = staticCompositionLocalOf<(Toast) -> Unit> { {} }

/** The services for this composition: [LocalBubbleServices], else the app container's. */
@Composable
fun rememberBubbleServices(): BubbleServices {
    LocalBubbleServices.current?.let { return it }
    val container = LocalAppContainer.current
    return remember(container) { BubbleServices.forContainer(container) }
}

private class ContainerBubbleServices(private val container: AppContainer) : BubbleServices {
    private val messaging get() = container.messaging.controller

    override val myUsername: String? get() = messaging.myUsername
    override fun canReact(message: ChatMessage): Boolean = messaging.canReact(message)
    override fun myReactions(message: ChatMessage): List<String> = messaging.myReactions(message)
    override suspend fun mediaBytes(messageId: UUID): ByteArray? = messaging.mediaBytes(messageId)
    override suspend fun ensureVoiceLoaded(message: ChatMessage) = messaging.ensureVoiceLoaded(message)
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = messaging.ensureLinkImageLoaded(message)
    override suspend fun videoPoster(messageId: UUID): ByteArray? = container.video.pipeline.posterJpegFromLocal(messageId)

    /** Core reads the duration off the sealed cache (`VideoPipeline.durationMs`, no plaintext file). */
    override suspend fun mediaDurationMs(messageId: UUID): Int? = container.video.pipeline.durationMs(messageId)

    override val playback: VoicePlaybackCoordinator get() = container.voice.playback
    override val transcription: VoiceTranscription get() = container.transcription.voice

    override fun transcriptionHints(peerName: String): List<String> =
        BubbleServices.hints(peerName, container.contacts.controller.contacts.value.map { it.username })

    override fun shareTranscript(transcript: String, voiceMessageId: UUID, peer: UUID) {
        container.appScope.launch { messaging.shareTranscript(transcript, voiceMessageId, peer) }
    }

    override suspend fun transcribe(message: ChatMessage, peerName: String): String =
        container.appScope.async {
            val audio = messaging.mediaBytes(message.id) ?: throw TranscribeException(NOT_DOWNLOADED)
            val text = container.transcription.voice.transcribe(
                audio = audio,
                // The decoder sniffs WAV itself; everything else goes through MediaExtractor.
                mime = VOICE_MIME,
                hints = transcriptionHints(peerName),
                conversationId = message.peerUserId,
                tracking = message.id,
            ).trim()
            if (text.isNotEmpty()) shareTranscript(text, message.id, message.peerUserId)
            text
        }.await()

    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = messaging.registerArtifactSink(sink)
    override val context: Context get() = container.appContext

    private companion object {
        const val VOICE_MIME = "audio/mp4"
        const val NOT_DOWNLOADED = "This voice message isn’t on this phone yet."
    }
}

/**
 * The bubbles' memory hygiene (conversation-thread §19, invariant 5): decoded pictures, link
 * pictures, remembered link ranges and transcript fold state, wired to messaging's purges, locks and
 * re-keys through one [MessageArtifactSinks] ([artifactSink]).
 *
 * - purged messages → their decoded pictures, link pictures and fold state go;
 * - chats locked → every decoded picture, link picture and remembered link range leaves memory;
 * - a sent message re-keyed → its decoded picture and transcript fold follow the server id
 *   (`MessagingController.swift:3125, 3770`).
 *
 * [install] registers the sink with the process's messaging once (idempotent); the first bubble does
 * it, and W3-INT also registers it where the controller is built (contract change request) so nothing
 * decoded before the first bubble outlives a lock.
 */
object BubbleMemory {
    val artifactSink: MessageArtifactSinks = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            DecodedImageCache.remove(messageIds)
            LinkPreviewImageCache.remove(messageIds)
            runOnMain { VoiceTranscriptDisclosure.forget(messageIds) }
        }

        override fun onSensitiveMemoryLocked() {
            clearAll()
        }

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            DecodedImageCache.rekey(from, to)
            runOnMain { VoiceTranscriptDisclosure.handOff(from, to) }
        }
    }

    /** Everything decoded or remembered from message content leaves memory. */
    fun clearAll() {
        DecodedImageCache.clear()
        LinkPreviewImageCache.clear()
        MessageLinkText.clearCache()
    }

    @Volatile
    private var registeredWith: BubbleServices? = null
    private var registration: AutoCloseable? = null

    /** Registers [artifactSink] with [services]' messaging, once per services instance. */
    @Synchronized
    fun install(services: BubbleServices) {
        if (registeredWith === services) return
        registration?.close()
        registration = services.registerArtifactSink(artifactSink)
        registeredWith = services
    }

    private fun runOnMain(block: () -> Unit) {
        val looper = android.os.Looper.getMainLooper()
        if (looper == null || looper.isCurrentThread) block() else android.os.Handler(looper).post(block)
    }
}

/**
 * The bundled emoji font for Android 11–12, whose system font lacks the Unicode 14/15 emoji of the
 * reaction set (decision D3b, P16c). The manifest removes emoji2's startup initializer, which would ask
 * the Google downloadable-font provider; this installs [BundledEmojiCompatConfig] instead — a font in
 * the APK, no provider, no network. Compose text picks EmojiCompat up once it is configured.
 *
 * The font's metadata loads on [loader] (the IO pool), never on the main thread; the constructor
 * without an executor is deprecated. Idempotent; W3-INT also calls it at process start (contract
 * change request).
 */
object BubbleEmoji {
    @Volatile
    private var installed = false

    /** Where the bundled font's metadata is read. */
    private val loader: Executor = Dispatchers.IO.asExecutor()

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            if (EmojiCompat.isConfigured()) return
            val config = BundledEmojiCompatConfig(context.applicationContext, loader)
                // Only replace what the system font cannot draw: newer phones keep their own emoji.
                .setReplaceAll(false)
            EmojiCompat.init(config)
        }
    }
}

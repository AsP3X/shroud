package de.corespace.shroud.di

import android.content.Context
import android.content.SharedPreferences
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.media.ImagePipeline
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.media.video.VideoException
import de.corespace.shroud.core.messaging.MediaHydrator
import de.corespace.shroud.core.messaging.MediaLoader
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.messaging.MessagingStore
import de.corespace.shroud.core.messaging.PdfPreviewSource
import de.corespace.shroud.core.messaging.ReactionsEngine
import de.corespace.shroud.core.messaging.SendApi
import de.corespace.shroud.core.messaging.SendDependencies
import de.corespace.shroud.core.messaging.SendEngine
import de.corespace.shroud.core.messaging.SendHost
import de.corespace.shroud.core.messaging.SendKeyring
import de.corespace.shroud.core.messaging.SendPipeline
import de.corespace.shroud.core.messaging.ThreadState
import de.corespace.shroud.core.messaging.reactions.ReactionEngine
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.storage.PrefsFiles
import java.util.UUID

/**
 * Send pipelines (00-plan §1.7.7). Owner: W2-MSG-SEND — [SendPipeline], [MediaHydrator],
 * [ReactionEngine] and the `shroud.messaging` preferences (`shroud.reactions.maxPerUser`).
 *
 * `MessagingModule` (W2-MSG-CORE) builds the one `MessagingController` and its `ThreadStore`, then
 * calls [send], [reactions] and [media] once each with that `ThreadState`. Nobody else constructs this
 * package's classes (00-plan §2.0 rule 3).
 *
 * **Same-wave ports** (wired by W2-INT; each is read at the time of use, so tests may replace them):
 * - [store] → `MessagingStoreModule.store` (W2-MSG-STORE);
 * - [mediaStore], [transfers] → the `LocalMediaCache` and `MediaTransferService` of `MediaModule` (W2-MEDIA-STORE);
 * - [images] → `ImageModule.pipeline` (W2-MEDIA-IMAGE);
 * - [video] → `VideoModule.pipeline` (W2-VIDEO); [videoTooLarge] reads its `VideoException.isTooLarge` (CR-4);
 * - [peerIdentities] → `ContactsModule.peerIdentities` (W2-CONTACTS);
 * - [notifier] → `NotificationsModule.controller` (W2-NOTIF);
 * - [host] → [ControllerSendHost], an adapter over `MessagingModule.controller` (CR-1: the adapter
 *   keeps `SendHost` out of the controller's public supertypes).
 *
 * `MessagingController` registers [media]'s result as an artifact sink (it is a
 * `MessageArtifactSinks`: purges and locks stop downloads), and lets the store write threads through
 * [ReactionEngine.settled].
 */
class MessagingSendModule(container: AppContainer) : AppModule(container) {
    private val controllerHost: SendHost by lazy { ControllerSendHost(container.messaging.controller) }

    @Volatile var host: (ThreadState) -> SendHost = { controllerHost }
    @Volatile var store: () -> MessagingStore = { container.messagingStore.store }
    @Volatile var mediaStore: () -> LocalMediaStore = { container.media.localMedia }
    @Volatile var transfers: () -> MediaTransfers = { container.media.transfers }
    @Volatile var images: () -> ImagePipeline = { container.images.pipeline }
    @Volatile var video: () -> VideoPipeline = { container.video.pipeline }
    @Volatile var peerIdentities: () -> PeerIdentities = { container.contacts.peerIdentities }
    @Volatile var notifier: () -> MessageNotifier? = { container.notifications.controller }
    @Volatile var videoTooLarge: (Throwable) -> Boolean = { VideoException.isTooLarge(it) }

    /** The REST slice of the engines, on the process's one `ShroudApi` (W1-NET). */
    val api: SendApi by lazy { SendApi.of(container.net.api) }

    /** Our identity keys through the one `CryptoController` (W1-KEYS). */
    val keyring: SendKeyring by lazy { SendKeyring.of(container.keys.cryptoController) }

    /** `shroud.messaging`: the reaction limit (00-plan §1.5; wiped at Log Out). */
    val preferences: SharedPreferences by lazy { container.appContext.getSharedPreferences(PrefsFiles.MESSAGING, Context.MODE_PRIVATE) }

    /** The send paths for [state] (the [SendEngine] seam). */
    fun send(state: ThreadState): SendEngine = SendPipeline(state, { host(state) }, dependencies())

    /** Reactions for [state] (the [ReactionsEngine] seam). */
    fun reactions(state: ThreadState): ReactionsEngine =
        ReactionEngine(state, { host(state) }, dependencies(), preferences, container.storageSeal)

    /** Media downloads for [state] (the [MediaLoader] seam); also an artifact sink to register. */
    fun media(state: ThreadState): MediaLoader = MediaHydrator(state, dependencies())

    private fun dependencies() = SendDependencies(
        api = api,
        keyring = keyring,
        crypto = container.keys.messageCrypto,
        peerLocks = container.keys.peerLocks,
        peerIdentities = { peerIdentities() },
        store = { store() },
        media = { mediaStore() },
        transfers = { transfers() },
        images = { images() },
        video = { video() },
        isOnline = { container.net.connectivity.isOnline.value },
        clock = container.clock,
        scope = container.appScope,
        notifier = { notifier() },
        videoTooLarge = { videoTooLarge(it) },
        pdfPreview = { source ->
            when (source) {
                is PdfPreviewSource.Picked -> container.media.pdf.envelopePreview { source.file.openDescriptor() }
                is PdfPreviewSource.Cached -> container.media.pdf.envelopePreview(source.messageId)
            }
        },
    )
}

/**
 * The engines' view of the messaging controller (W2-MSG-SEND CR-1): each member forwards to the
 * controller's public API of the same name (`MessagingController.swift` members the iOS engines call
 * directly). Built lazily and only used after the controller exists: the engines read it at the
 * time of a send, never while the controller is being constructed.
 */
internal class ControllerSendHost(private val controller: MessagingController) : SendHost {
    override val conversations: List<ConversationItemDto> get() = controller.conversations.value
    override val activePeerId: UUID? get() = controller.activePeerId.value
    override suspend fun refreshConversations(force: Boolean) = controller.refreshConversations(force)
    override fun setOffline(offline: Boolean) = controller.setOffline(offline)
    override fun isMuted(storePeer: UUID): Boolean = controller.isMuted(storePeer)
    override fun username(storePeer: UUID): String? = controller.username(storePeer)
    override fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) =
        controller.editConversations(transform)
    override fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage> = controller.foldSharedTranscripts(thread)
}

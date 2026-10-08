package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.messaging.EnvelopeOpener
import de.corespace.shroud.core.messaging.MessagingBackend
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.messaging.MessagingDependencies
import de.corespace.shroud.core.messaging.MessagingSocket
import de.corespace.shroud.core.messaging.OwnKeys
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.DeleteConversationResponse
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.MuteChatResponse
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.realtime.RealtimeClient
import de.corespace.shroud.core.realtime.RealtimeEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Messaging engine (00-plan §1.7.7, C6). Owner: W2-MSG-CORE — the one [MessagingController] (chats,
 * threads, reads, mutes, typing, deletes; sends, media and reactions through the W2-MSG-SEND engines)
 * and its own engines (`ThreadStore`, `HistoryPager`, `MessageDecoder`, `DeleteEngine`,
 * `ReadStateEngine`, `TypingSignals`, `PollingLoop`).
 *
 * The other wave-2 packages are wired here by W2-INT (00-plan §2.0 rule 3): the sealed store
 * (W2-MSG-STORE), the SHRM1 media cache (W2-MEDIA-STORE), contacts, peer keys and privacy
 * (W2-CONTACTS), the notifier (W2-NOTIF), the send, reaction and media engines (W2-MSG-SEND, whose
 * `SendHost` is an adapter over [controller], see `MessagingSendModule`) and the call secrets
 * (W2-CALLS-CORE). The voice player hears purges, locks and re-keys through
 * [MessagingController.registerArtifactSink]. The interim root (`ui/ShroudApp.kt`) starts and stops it.
 */
class MessagingModule(container: AppContainer) : AppModule(container) {
    private val controllerLazy = lazy {
        MessagingController(dependencies()).also { controller ->
            // Purges stop the deleted voice note, locks stop playback and drop a take, re-keys move
            // the played mark (W2-VOICE; iOS tears the thread's player down, `ConversationView.swift:363-379`).
            controller.registerArtifactSink(container.voice.artifactSink)
            // Purged media loses its share and file grants (docs/file-sharing.md §8).
            controller.registerArtifactSink(container.media.shareArtifactSink)
        }
    }

    /** The controller every UI area reads (plan §1.7.7). Built on first use, on the main thread. */
    val controller: MessagingController by controllerLazy

    /** [controller] when something already built it: the wipe and the foreground glue never build it. */
    val controllerIfBuilt: MessagingController? get() = if (controllerLazy.isInitialized()) controller else null

    private fun dependencies(): MessagingDependencies {
        val keys = container.keys
        val realtime = container.realtime.client
        return MessagingDependencies(
            scope = container.appScope,
            session = container.auth.sessionController.session,
            backend = ShroudApiMessagingBackend(container.net.api),
            socket = RealtimeMessagingSocket(realtime, container),
            keys = CryptoOwnKeys(keys.cryptoController),
            opener = EnvelopeOpener(keys.messageCrypto::open),
            peerLocks = keys.peerLocks,
            store = container.messagingStore.store,
            hasMedia = { id -> container.media.localMedia.has(id) },
            contacts = container.contacts.controller,
            peerIdentities = container.contacts.peerIdentities,
            privacy = container.contacts.privacy,
            notifier = container.notifications.controller,
            sendEngine = container.messagingSend::send,
            reactionsEngine = container.messagingSend::reactions,
            mediaLoader = container.messagingSend::media,
            isOnline = { container.net.connectivity.isOnline.value },
            isResumed = { container.appPhase.isResumed },
            wipeKeyRecords = {
                keys.ratchetSessions.deleteAll()
                keys.deleteLegacySenderTags()
            },
            // Every contact's call secret from the pinned keys (plan C29; `refreshCallSecrets`, MC:486).
            refreshCallSecrets = { container.calls.secrets.refreshAll() },
            clock = container.clock,
            // The one definition of "push covers the background" (plan §1.7.10): a registered distributor.
            pushCovers = { container.push.registration.delivery.value.suppressesLocalAnnouncements },
        )
    }
}

/** [MessagingBackend] over the one [ShroudApi] (plan C3). */
private class ShroudApiMessagingBackend(private val api: ShroudApi) : MessagingBackend {
    override suspend fun conversations(token: String): List<ConversationItemDto> = api.conversations(token)
    override suspend fun messages(token: String, peer: UUID, limit: Int, beforeCreatedAt: String?, beforeId: UUID?): ListMessagesResponse =
        api.messages(token, peer, limit, beforeCreatedAt, beforeId)
    override suspend fun markDelivered(token: String, messageId: UUID) = api.markDelivered(token, messageId)
    override suspend fun markChatRead(token: String, peer: UUID) {
        api.markChatRead(token, peer)
    }
    override suspend fun markReadBulk(token: String, peer: UUID, upToMessageId: UUID) {
        api.markReadBulk(token, peer, upToMessageId)
    }
    override suspend fun deleteMessage(token: String, messageId: UUID, scope: MessageDeleteScope) = api.deleteMessage(token, messageId, scope)
    override suspend fun deleteConversation(token: String, peer: UUID, scope: ConversationDeleteScope): DeleteConversationResponse =
        api.deleteConversation(token, peer, scope)
    override suspend fun muteChat(token: String, peer: UUID, seconds: Long?): MuteChatResponse = api.muteChat(token, peer, seconds)
    override suspend fun unmuteChat(token: String, peer: UUID) = api.unmuteChat(token, peer)
}

/** [MessagingSocket] over the process's one [RealtimeClient], holder `Messaging`. */
private class RealtimeMessagingSocket(private val client: RealtimeClient, container: AppContainer) : MessagingSocket {
    override val events: SharedFlow<RealtimeEvent> = client.events
    override val isConnected: StateFlow<Boolean> = client.state
        .map { it is RealtimeClient.ConnectionState.Connected }
        .stateIn(container.appScope, SharingStarted.Eagerly, client.isConnected)
    override fun hold(token: String) = client.hold(RealtimeClient.Holder.Messaging, token)
    override fun release() = client.release(RealtimeClient.Holder.Messaging)
    override fun noteFocus(focused: Boolean) = client.noteFocus(focused)
    override suspend fun deliverFocus(): Boolean = client.deliverFocus()
    override fun sendTyping(peer: UUID, isTyping: Boolean) = client.sendTyping(peer, isTyping)
    override fun sendRecording(peer: UUID, isRecording: Boolean) = client.sendRecording(peer, isRecording)
}

/** [OwnKeys] over [CryptoController.withMaterial]: the arrays never leave the block. */
private class CryptoOwnKeys(private val crypto: CryptoController) : OwnKeys {
    override val isUnlocked: Boolean get() = crypto.isUnlocked
    override fun <T> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T? =
        crypto.withMaterial { block(it.agreementPrivateKey, it.identityPublicKey) }
}

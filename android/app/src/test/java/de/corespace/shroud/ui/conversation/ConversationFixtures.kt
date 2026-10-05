package de.corespace.shroud.ui.conversation

import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/** Shared people and messages of the conversation tests. */
internal object ConversationFixtures {
    val PEER: UUID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
    val ME: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
    val START: Instant = Instant.ofEpochSecond(1_800_000_000)

    fun message(
        at: Long,
        mine: Boolean = false,
        deleted: Boolean = false,
        text: String = "hello",
        kind: ChatMessageKind = ChatMessageKind.Text,
        receipt: ReceiptStatus = ReceiptStatus.Sent,
        pendingSync: Boolean = false,
        peer: UUID = PEER,
        id: UUID = UUID.randomUUID(),
    ): ChatMessage = ChatMessage(
        id = id,
        peerUserId = peer,
        senderUserId = if (mine) ME else peer,
        text = text,
        createdAt = START.plusSeconds(at),
        isMine = mine,
        deleted = deleted,
        receipt = receipt,
        kind = kind,
        pendingSync = pendingSync,
    )
}

/** A recording stand-in for the engines behind the conversation. */
internal class FakeConversationBackend(override val myUserId: UUID? = ConversationFixtures.ME) : ConversationBackend {
    val log = ArrayList<String>()

    override fun isNotesChat(peer: UUID): Boolean = peer == NOTES_PEER_ID

    override val threads = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())
    override val presence = MutableStateFlow<Map<UUID, PresenceDto>>(emptyMap())
    override val peerActivities = MutableStateFlow<Map<UUID, ChatPeerActivity>>(emptyMap())
    override val isOffline = MutableStateFlow(false)
    override val lastError = MutableStateFlow<String?>(null)
    override val activePeerId = MutableStateFlow<UUID?>(null)
    override val mediaTransfers = MutableStateFlow<Map<UUID, MediaTransfer>>(emptyMap())
    override val olderHistoryExhausted = MutableStateFlow<Set<UUID>>(emptySet())
    override val loadingOlderPeerIds = MutableStateFlow<Set<UUID>>(emptySet())
    override val reactionRevisions = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    override val reactionFailures = MutableSharedFlow<ReactionFailure>(extraBufferCapacity = 8)
    override val callMediaStarting = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** What a load does to the thread (and the shared error), called inside [loadThread]. */
    var onLoad: suspend (UUID) -> Unit = {}
    var reactable = true
    val myReactionsById = HashMap<UUID, List<String>>()
    var deleteError: String? = null
    var deleteConversationOutcome: ChatDeleteOutcome = ChatDeleteOutcome.ClearedForMe
    var callError: String? = null
    var clipboard: String? = null
    var confirmsCopies = false
    val sinks = ArrayList<MessageArtifactSinks>()

    fun setThread(peer: UUID, messages: List<ChatMessage>) {
        threads.value = threads.value + (peer to messages)
    }

    override suspend fun loadThread(peer: UUID, reconcile: Boolean) {
        log += "loadThread:reconcile=$reconcile"
        onLoad(peer)
    }

    override suspend fun loadOlderMessages(peer: UUID) {
        log += "loadOlder"
    }

    override fun setActivePeer(peer: UUID?) {
        log += "setActivePeer:${peer?.let { if (it == NOTES_PEER_ID) "notes" else "peer" }}"
        activePeerId.value = peer
    }

    override fun setTyping(peer: UUID, isTyping: Boolean) {
        log += "typing:$isTyping"
    }

    override fun setRecording(peer: UUID, isRecording: Boolean) {
        log += "recording:$isRecording"
    }

    override fun canReact(message: ChatMessage): Boolean = reactable && !message.deleted
    override fun myReactions(message: ChatMessage): List<String> = myReactionsById[message.id].orEmpty()

    override fun toggleReaction(emoji: String, messageId: UUID, peer: UUID) {
        log += "toggleReaction:$emoji"
    }

    override suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String? {
        log += "delete:$scope"
        return deleteError
    }

    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome {
        log += "deleteConversation:$scope"
        return deleteConversationOutcome
    }

    override fun cancelMediaDownload(messageId: UUID) {
        log += "cancelDownload"
    }

    override suspend fun retryFailedImage(messageId: UUID, peer: UUID): String? {
        log += "retryImage"
        return null
    }

    override suspend fun retryFailedVideo(messageId: UUID, peer: UUID): String? {
        log += "retryVideo"
        return null
    }

    override suspend fun retryFailedFile(messageId: UUID, peer: UUID): String? {
        log += "retryFile"
        return null
    }

    override fun toggleTodo(messageId: UUID) {
        log += "toggleTodo"
    }

    override suspend fun startCall(peer: UUID, username: String, modality: CallModality): String? {
        log += "call:$modality"
        return callError
    }

    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable {
        sinks += sink
        return AutoCloseable { sinks -= sink }
    }

    override fun copyToClipboard(text: String) {
        clipboard = text
    }

    override val clipboardConfirmsItself: Boolean get() = confirmsCopies

    override fun openLink(url: String): Boolean {
        log += "openLink"
        return true
    }

    override fun stopVoicePlayback() {
        log += "stopPlayback"
    }
}

/** A recording stand-in for the composer. */
internal class FakeConversationCompose : ConversationCompose {
    val log = ArrayList<String>()
    override var isRecording: Boolean = false
    override var isViewingMedia: Boolean = false

    override fun startReply(message: ChatMessage) {
        log += "startReply"
    }

    override fun handleMediaTap(message: ChatMessage) {
        log += "mediaTap"
    }

    override fun onLeave(profilePushed: Boolean) {
        log += "leave:profilePushed=$profilePushed"
    }

    override fun cancelVoiceTake() {
        log += "cancelTake"
    }

    override fun closeMediaViewer() {
        log += "closeViewer"
        isViewingMedia = false
    }

    override fun cancelDownload(message: ChatMessage) {
        log += "cancelDownload"
    }

    override fun saveFileToDownloads(message: ChatMessage) {
        log += "saveFile"
    }

    override fun shareFile(message: ChatMessage) {
        log += "shareFile"
    }
}

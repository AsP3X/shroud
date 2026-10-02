package de.corespace.shroud.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * What the conversation screen needs from the engines: the messaging controller's thread, presence,
 * typing, paging, reads, reactions and deletes; the contacts' presence; the call controller; the
 * clipboard, the link opener and voice playback. A thin port so [ConversationViewModel] is tested on
 * the JVM with a fake; [ConversationBackend.of] is the real one over the [AppContainer] (plan §1.7.7,
 * §1.7.8, §1.7.11).
 */
interface ConversationBackend {
    val myUserId: UUID?
    val myUsername: String?
    fun isNotesChat(peer: UUID): Boolean

    /** Thread key → messages, oldest first (`MessagingController.threads`). */
    val threads: StateFlow<Map<UUID, List<ChatMessage>>>
    val presence: StateFlow<Map<UUID, PresenceDto>>
    val peerActivities: StateFlow<Map<UUID, ChatPeerActivity>>
    val isOffline: StateFlow<Boolean>
    val lastError: StateFlow<String?>
    val activePeerId: StateFlow<UUID?>
    val mediaTransfers: StateFlow<Map<UUID, MediaTransfer>>
    val olderHistoryExhausted: StateFlow<Set<UUID>>
    val loadingOlderPeerIds: StateFlow<Set<UUID>>
    val reactionRevisions: StateFlow<Map<UUID, Int>>
    val reactionFailures: Flow<ReactionFailure>

    suspend fun loadThread(peer: UUID, reconcile: Boolean)
    suspend fun loadOlderMessages(peer: UUID)
    fun setActivePeer(peer: UUID?)
    fun setTyping(peer: UUID, isTyping: Boolean)
    fun setRecording(peer: UUID, isRecording: Boolean)
    fun canReact(message: ChatMessage): Boolean
    fun myReactions(message: ChatMessage): List<String>
    fun toggleReaction(emoji: String, messageId: UUID, peer: UUID)
    suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String?
    suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome
    fun cancelMediaDownload(messageId: UUID)
    suspend fun ensureLinkImageLoaded(message: ChatMessage)
    suspend fun retryFailedImage(messageId: UUID, peer: UUID): String?
    suspend fun retryFailedVideo(messageId: UUID, peer: UUID): String?
    fun toggleTodo(messageId: UUID)

    /** Places a call; the user-facing error, or null when it started (`startCall`, CV:821-833). */
    suspend fun startCall(peer: UUID, username: String, modality: CallModality): String?
    fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable

    /** The user's explicit copy (decision D8: not marked sensitive, iOS parity). */
    fun copyToClipboard(text: String)

    /** Whether the system shows its own "copied" confirmation (Android 13+), so the toast would repeat it (D8). */
    val clipboardConfirmsItself: Boolean

    /** Opens a tapped link in a Custom Tab (`LinkOpener`, C32); false when nothing could open it. */
    fun openLink(url: String): Boolean
    fun stopVoicePlayback()

    companion object {
        /**
         * The real backend over the process's engines. [activityContext] is the hosting activity:
         * Custom Tabs start from it (`LinksModule.openLink`).
         */
        fun of(container: AppContainer, activityContext: Context): ConversationBackend =
            ContainerConversationBackend(container, activityContext)
    }
}

private class ContainerConversationBackend(
    private val container: AppContainer,
    private val activityContext: Context,
) : ConversationBackend {
    private val messaging: MessagingController get() = container.messaging.controller

    override val myUserId: UUID? get() = messaging.myUserId
    override val myUsername: String? get() = messaging.myUsername
    override fun isNotesChat(peer: UUID): Boolean = messaging.isNotesChat(peer)
    override val threads get() = messaging.threads
    override val presence get() = container.contacts.controller.presence
    override val peerActivities get() = messaging.peerActivities
    override val isOffline get() = messaging.isOffline
    override val lastError get() = messaging.lastError
    override val activePeerId get() = messaging.activePeerId
    override val mediaTransfers get() = messaging.mediaTransfers
    override val olderHistoryExhausted get() = messaging.olderHistoryExhausted
    override val loadingOlderPeerIds get() = messaging.loadingOlderPeerIds
    override val reactionRevisions get() = messaging.reactionRevisions
    override val reactionFailures: Flow<ReactionFailure> get() = messaging.reactionFailures

    override suspend fun loadThread(peer: UUID, reconcile: Boolean) = messaging.loadThread(peer, activate = true, reconcile = reconcile)
    override suspend fun loadOlderMessages(peer: UUID) = messaging.loadOlderMessages(peer)
    override fun setActivePeer(peer: UUID?) = messaging.setActivePeer(peer)
    override fun setTyping(peer: UUID, isTyping: Boolean) = messaging.setTyping(peer, isTyping)
    override fun setRecording(peer: UUID, isRecording: Boolean) = messaging.setRecording(peer, isRecording)
    override fun canReact(message: ChatMessage): Boolean = messaging.canReact(message)
    override fun myReactions(message: ChatMessage): List<String> = messaging.myReactions(message)
    override fun toggleReaction(emoji: String, messageId: UUID, peer: UUID) = messaging.toggleReaction(emoji, messageId, peer)
    override suspend fun deleteMessage(message: ChatMessage, scope: MessageDeleteScope): String? = messaging.deleteMessage(message, scope)
    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome =
        messaging.deleteConversation(peer, scope)
    override fun cancelMediaDownload(messageId: UUID) = messaging.cancelMediaDownload(messageId)
    override suspend fun ensureLinkImageLoaded(message: ChatMessage) = messaging.ensureLinkImageLoaded(message)
    override suspend fun retryFailedImage(messageId: UUID, peer: UUID): String? = messaging.retryFailedImage(messageId, peer)
    override suspend fun retryFailedVideo(messageId: UUID, peer: UUID): String? = messaging.retryFailedVideo(messageId, peer)
    override fun toggleTodo(messageId: UUID) = messaging.toggleTodo(messageId)

    override suspend fun startCall(peer: UUID, username: String, modality: CallModality): String? {
        val calls = container.calls.controller
        calls.startCall(peer, username, modality)
        return calls.lastError.value
    }

    override fun registerArtifactSink(sink: MessageArtifactSinks): AutoCloseable = messaging.registerArtifactSink(sink)

    override fun copyToClipboard(text: String) {
        val clipboard = activityContext.getSystemService(ClipboardManager::class.java) ?: return
        // No label: the label is shown by some keyboards' clipboard strips and must not hold content.
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
    }

    override val clipboardConfirmsItself: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun openLink(url: String): Boolean = container.links.openLink(activityContext, url)

    override fun stopVoicePlayback() = container.voice.playback.stop()
}

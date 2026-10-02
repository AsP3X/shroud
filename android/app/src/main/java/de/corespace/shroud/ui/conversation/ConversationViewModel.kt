package de.corespace.shroud.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.canBeQuoted
import de.corespace.shroud.core.model.presentedKind
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.conversation.gestures.TapClaim
import de.corespace.shroud.ui.conversation.menu.MessageActions
import de.corespace.shroud.ui.conversation.menu.MessageMenuAction
import de.corespace.shroud.ui.conversation.menu.MessageMenuLayout
import de.corespace.shroud.ui.conversation.menu.MessageMenuState
import de.corespace.shroud.ui.conversation.menu.MessageReactionBarMetrics
import de.corespace.shroud.ui.conversation.reactions.ReactionFlightState
import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * What the conversation screen needs from the engines: the messaging controller's thread, typing,
 * paging, reads, reactions and deletes; the contacts' presence; the call controller; the clipboard,
 * the link opener and voice playback. A thin port so [ConversationViewModel] is tested on the JVM
 * with a fake; [ConversationBackend.of] is the real one over the [AppContainer] (plan §1.7.7,
 * §1.7.8, §1.7.11).
 */
interface ConversationBackend {
    val myUserId: UUID?
    fun isNotesChat(peer: UUID): Boolean

    /** Thread key → messages, oldest first (`MessagingController.threads`); read for the opening value. */
    val threads: StateFlow<Map<UUID, List<ChatMessage>>>

    /** One thread as it changes, oldest first (`MessagingController.thread(peer)`). */
    fun thread(peer: UUID): Flow<List<ChatMessage>> = threads.map { it[peer].orEmpty() }.distinctUntilChanged()
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

    /** A call's media is starting: a voice take and playback stop (`.shroudCallMediaStarting`, CV:380-383). */
    val callMediaStarting: Flow<Unit>

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
    override fun isNotesChat(peer: UUID): Boolean = messaging.isNotesChat(peer)
    override val threads get() = messaging.threads
    override fun thread(peer: UUID): Flow<List<ChatMessage>> = messaging.thread(peer)
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
    override val callMediaStarting: Flow<Unit> get() = container.calls.controller.callMediaStarting

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

/**
 * The composer as the thread drives it — the members of `ComposeController` (W3-COMPOSER) the
 * conversation calls (plan §1.7.13). The screen adapts the real controller; tests pass a fake.
 */
interface ConversationCompose {
    /** A voice take is running: Back discards it (thread D9) and the jump control hides (CV:619). */
    val isRecording: Boolean

    /** Reply to [message]: the strip shows its quote, the keyboard comes up (CV:1289-1294). */
    fun startReply(message: ChatMessage)

    /** A photo or video bubble was tapped: download it, or open the viewer / player (CV:2332-2349). */
    fun handleMediaTap(message: ChatMessage)

    /** The thread left the screen; [profilePushed] keeps the draft's link preview (CV:367-368). */
    fun onLeave(profilePushed: Boolean)

    /** Discards a voice take in progress (thread D9 = compose Q10; call media starting, CV:380-383). */
    fun cancelVoiceTake()

    /** The photo viewer is open (`viewingMedia != nil`, CV:2270-2275). */
    val isViewingMedia: Boolean

    /** Closes the photo viewer: a delete asked from it closes it first (CV:2270-2275). */
    fun closeMediaViewer()

    /** The bubble's ring ✕: stops the download, which then ends without a failure toast (CV:2386-2394). */
    fun cancelDownload(message: ChatMessage)

    /** Nothing to compose with (previews, tests). */
    object None : ConversationCompose {
        override val isRecording: Boolean = false
        override fun startReply(message: ChatMessage) = Unit
        override fun handleMediaTap(message: ChatMessage) = Unit
        override fun onLeave(profilePushed: Boolean) = Unit
        override fun cancelVoiceTake() = Unit
        override val isViewingMedia: Boolean = false
        override fun closeMediaViewer() = Unit
        override fun cancelDownload(message: ChatMessage) = Unit
    }
}

/** One scroll request to a quoted message; the nonce lets the same quote be tapped twice (CV:1237-1241). */
data class JumpTarget(val messageId: UUID, val nonce: Int)

/**
 * The state of one open conversation — the thread half of iOS `ConversationView` (its `@State`,
 * lifecycle, menu, reactions, deletes, jumps, calls; `ConversationView.swift:22-1355, 1930-2303`;
 * conversation-thread §1, §3.10, §7.3, §13–§16). The composer half is `ComposeController`
 * (W3-COMPOSER); the scroll mechanics are [ThreadScrollState].
 *
 * Human: the chat opens on its newest message, loads (and reconciles) the thread, marks it read
 * while it is on screen, and keeps the reader's place when they come back from the contact's
 * profile. A chat that has nothing on this phone yet shows a spinner, then either its messages or
 * "Can't load messages" with a retry — which also retries itself once the connection comes back.
 * Long-pressing a bubble opens its menu; a pick or a double tap sends the emoji flying to its chip.
 *
 * Agent: main thread only; one per open chat, created by the screen with the screen's own scope and
 * [close]d when it leaves. State is Compose snapshot state (reading it in composition recomposes).
 * Implements [BubbleContext] for the bubbles (W3-THREAD-BUBBLES). Holds message snapshots (the
 * menu's, the pending delete) only until the chats lock: a lock or purge drops them
 * ([MessageArtifactSinks]). Never logs content.
 *
 * @param peer the thread key (`NOTES_PEER_ID` for Notes).
 * @param username the peer's name (the chat's title).
 * @param haptic plays a haptic on the screen's view.
 * @param onBack leaves the chat (after "Delete all notes").
 * @param now the uptime clock (ms) of the tap claims and the menu's backdrop grace; injectable for tests.
 */
@Stable
class ConversationViewModel(
    val peer: UUID,
    val username: String,
    private val backend: ConversationBackend,
    private val scope: CoroutineScope,
    private val haptic: (Haptic) -> Unit = {},
    private val onBack: () -> Unit = {},
    now: () -> Long = { SystemClock.uptimeMillis() },
    val tapClaim: TapClaim = TapClaim(now),
) : BubbleContext {
    /** Notes to me: no presence, receipts, typing or calls (CV:154-156). */
    val isNotes: Boolean = backend.isNotesChat(peer)

    /** Wired by the screen once the composer exists. */
    var compose: ConversationCompose = ConversationCompose.None

    /** The screen's toast (CV:26, 255). */
    val toasts = ToastState()
    val menu = MessageMenuState(now)
    val flights = ReactionFlightState(scope)

    // ---- Thread state (CV:104-133, 158-175) ----------------------------------------------------------

    /** Oldest first, newest last (CV:104-106). */
    var messages: List<ChatMessage> by mutableStateOf(backend.threads.value[peer].orEmpty())
        private set

    /** The peer typing or recording; never in Notes (CV:158-161). */
    var peerActivity: ChatPeerActivity? by mutableStateOf(null)
        private set
    var presence: PresenceDto? by mutableStateOf(null)
        private set
    var isOffline: Boolean by mutableStateOf(backend.isOffline.value)
        private set
    var transfers: Map<UUID, MediaTransfer> by mutableStateOf(emptyMap())
        private set

    /** The server has nothing older than what is here (`olderHistoryExhausted`). */
    private var historyExhausted: Boolean by mutableStateOf(false)

    /** An older page is on its way (`isLoadingOlderHistory`). */
    var isLoadingOlder: Boolean by mutableStateOf(false)
        private set

    /** Bumped by every reaction change in this chat: bubbles grow with `Motion.bouncy` (CV:973-976). */
    var reactionRevision: Int by mutableIntStateOf(0)
        private set

    /** The server may have more above what is in memory (and the chat has started) (CV:130-133). */
    val hasOlderOnServer: Boolean get() = messages.isNotEmpty() && !historyExhausted

    // ---- Opening (CV:68-77, 345-362, 1038-1051) -------------------------------------------------------

    /** The chat has been on screen before: back from the profile keeps the reader's place (CV:70-72). */
    var didOpen: Boolean by mutableStateOf(false)
        private set

    /** The first page of a chat that isn't on this device yet is on its way (CV:73-74). */
    var loadingFirstPage: Boolean by mutableStateOf(false)
        private set

    /** Why that first page couldn't be fetched (CV:75-77). */
    var firstLoadError: String? by mutableStateOf(null)
        private set

    /** Bumped to pin the thread to its newest message without animation (CV:68-69, 1095-1098). */
    var pinToBottomToken: Int by mutableIntStateOf(0)
        private set

    /** The quoted message the thread should scroll to (CV:84-86). */
    var jumpTarget: JumpTarget? by mutableStateOf(null)
        private set
    private var jumpNonce = 0

    /** The row flashing after a jump (CV:87-89). */
    var highlightedId: UUID? by mutableStateOf(null)
        private set
    private var highlightJob: Job? = null

    /** The message waiting on "Delete message?" (CV:41-42). */
    var pendingDelete: ChatMessage? by mutableStateOf(null)
        private set

    /** "Delete all notes?" is up (CV:29-30). */
    var showsNotesDeleteConfirm: Boolean by mutableStateOf(false)
        private set

    /**
     * The contact's profile was pushed over the chat: leaving keeps the draft's preview (CV:57,
     * 367-368). Like iOS `profileDestination`, it stays set while the chat shows again under a back
     * gesture that may still be cancelled; the chat closing for good ([close]) leaves completely.
     */
    var profilePushed: Boolean = false
        private set

    /** The chat is on screen (placed by the stack, not covered by a pushed screen). */
    var isVisible: Boolean = false
        private set
    private var closed = false
    private var openTask: Job? = null

    /** The bubble whose hold opened (or is opening) a menu and whose finger is still down. */
    private var heldMessageId: UUID? = null

    /** Each bubble's drawn bounds, root px; read only when a menu opens (CV:43-48). Not snapshot state. */
    private val bubbleFrames = HashMap<UUID, Rect>()

    /** Each row's bounds, root px, for TalkBack's "Message options" (no press to measure one). */
    private val rowFrames = HashMap<UUID, Rect>()

    private val sink = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            menu.session?.let { if (it.message.id in messageIds) menu.clear() }
            pendingDelete?.let { if (it.id in messageIds) pendingDelete = null }
            messageIds.forEach {
                bubbleFrames.remove(it)
                rowFrames.remove(it)
            }
        }

        // Invariant 5: nothing readable stays in memory once the chats lock.
        override fun onSensitiveMemoryLocked() {
            menu.clear()
            flights.clear()
            pendingDelete = null
            showsNotesDeleteConfirm = false
            highlightJob?.cancel()
            highlightedId = null
            bubbleFrames.clear()
            rowFrames.clear()
        }

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            bubbleFrames.remove(from)?.let { bubbleFrames[to] = it }
            rowFrames.remove(from)?.let { rowFrames[to] = it }
        }
    }
    private val sinkRegistration: AutoCloseable = backend.registerArtifactSink(sink)

    init {
        scope.launch { backend.thread(peer).collect(::onThreadChanged) }
        scope.launch {
            backend.peerActivities.map { if (isNotes) null else it[peer] }.distinctUntilChanged().collect { peerActivity = it }
        }
        scope.launch {
            backend.presence.map { if (isNotes) null else it[peer] }.distinctUntilChanged().collect { presence = it }
        }
        scope.launch { backend.isOffline.collect { isOffline = it } }
        scope.launch { backend.mediaTransfers.collect { transfers = it } }
        scope.launch {
            backend.olderHistoryExhausted.map { peer in it }.distinctUntilChanged().collect { historyExhausted = it }
        }
        scope.launch {
            backend.loadingOlderPeerIds.map { peer in it }.distinctUntilChanged().collect { isLoadingOlder = it }
        }
        scope.launch {
            backend.reactionRevisions.map { it[peer] ?: 0 }.distinctUntilChanged().collect { reactionRevision = it }
        }
        scope.launch {
            // CV:422-426: the refusal of a reaction save, once, as a failure toast.
            backend.reactionFailures.collect { failure ->
                if (messages.any { it.id == failure.messageId }) failFeedback(failure.message)
            }
        }
        scope.launch {
            // CV:1044-1051: the shared error cleared while this chat sits on "Can't load messages":
            // the reconnect or poll worked, so reload it here and take that outcome.
            backend.lastError.map { it == null }.distinctUntilChanged().collect { cleared ->
                if (cleared && firstLoadError != null && backend.activePeerId.value == peer) loadThreadKeepingFailure()
            }
        }
        scope.launch {
            backend.callMediaStarting.collect {
                // CV:380-383: a call takes the microphone and the speaker.
                if (compose.isRecording) compose.cancelVoiceTake()
                backend.stopVoicePlayback()
            }
        }
    }

    private fun onThreadChanged(thread: List<ChatMessage>) {
        messages = thread
        // CV:1038-1043: messages came in anyway (an event, a background reload): no failed load left.
        if (thread.isNotEmpty()) firstLoadError = null
    }

    /** The thread's current copy of [message]: a menu's snapshot may predate a reaction that just landed (CV:2115-2118). */
    fun live(message: ChatMessage): ChatMessage = messages.firstOrNull { it.id == message.id } ?: message

    // ---- Lifecycle (CV:345-379) -----------------------------------------------------------------------

    /**
     * The chat came on screen — opened, or uncovered when the profile above it closed (`.onAppear` +
     * `.task`, CV:345-362). Marks it read, pins to the newest message on the first opening only, and
     * loads (reconciles) the thread.
     */
    fun onAppear() {
        if (isVisible || closed) return
        isVisible = true
        backend.setActivePeer(peer)
        // Opening a chat always starts at the newest message (Telegram/Signal/WhatsApp).
        if (!didOpen) pinToBottomToken++
        openTask?.cancel()
        openTask = scope.launch {
            val opening = !didOpen
            didOpen = true
            if (opening) pinToBottomToken++
            // Nothing on this device yet: a spinner rather than the brand-new-chat header (back from
            // the profile, only in place of a failed first load).
            loadingFirstPage = messages.isEmpty() && (opening || firstLoadError != null)
            try {
                loadThreadKeepingFailure()
            } finally {
                loadingFirstPage = false
            }
            if (opening) pinToBottomToken++
        }
    }

    /**
     * The chat left the screen — closed, or covered by the profile (`.onDisappear`, CV:363-379):
     * stops the load, playback, the highlight and the menu; says we stopped typing; no longer
     * reads incoming messages as they land.
     */
    fun onDisappear() {
        if (!isVisible) return
        isVisible = false
        openTask?.cancel()
        openTask = null
        highlightJob?.cancel()
        compose.onLeave(profilePushed)
        backend.stopVoicePlayback()
        menu.clear()
        if (!isNotes) {
            backend.setTyping(peer, false)
            backend.setRecording(peer, false)
        }
        if (backend.activePeerId.value == peer) backend.setActivePeer(null)
    }

    /**
     * The screen is gone for good (popped, replaced, or the chats locked): everything the composer
     * staged goes too, whether the chat was on screen or covered by the profile.
     */
    fun close() {
        if (closed) return
        val wasVisible = isVisible
        profilePushed = false
        onDisappear()
        // Hidden under the profile, the composer kept its draft's preview; the chat is gone now.
        if (!wasVisible) compose.onLeave(profilePushed = false)
        closed = true
        flights.clear()
        menu.clear()
        sinkRegistration.close()
    }

    /** The header was tapped: the profile goes over the chat (CV:771-776). */
    fun willOpenProfile() {
        profilePushed = true
    }

    /**
     * Loads the chat and keeps the outcome for the failed-first-load state: the error, read right
     * after the load, when it left the chat empty (CV:135-142).
     */
    suspend fun loadThreadKeepingFailure() {
        backend.loadThread(peer, reconcile = true)
        firstLoadError = if (messages.isEmpty()) backend.lastError.value else null
    }

    /** The reader neared the top: fetch the next older page (CV:1119-1134; the render window is not ported, D1). */
    fun loadOlder() {
        if (!hasOlderOnServer || isLoadingOlder) return
        scope.launch { backend.loadOlderMessages(peer) }
    }

    // ---- Replies and jumps (CV:1289-1331) -----------------------------------------------------------

    fun startReply(message: ChatMessage) {
        if (!message.canBeQuoted) return
        compose.startReply(message)
    }

    /**
     * Scrolls to a quoted message and flashes it; says so when it is no longer on the device (older
     * than the local window, or deleted just for us) (CV:1301-1321).
     */
    fun jumpToQuoted(messageId: UUID) {
        if (messages.none { it.id == messageId }) {
            toasts.show(Toast.failure(QUOTED_GONE))
            haptic(Haptic.Warning)
            return
        }
        jumpTarget = JumpTarget(messageId, ++jumpNonce)
    }

    /** The thread scrolled to [target]: flash it and forget the request (CV:1085-1094). */
    fun didJump(target: JumpTarget) {
        if (jumpTarget == target) jumpTarget = null
        flashHighlight(target.messageId)
    }

    /** Fade in, hold 1.1 s, fade out (the row animates both) (CV:1323-1331). */
    fun flashHighlight(messageId: UUID) {
        highlightJob?.cancel()
        highlightedId = messageId
        highlightJob = scope.launch {
            delay(HIGHLIGHT_HOLD_MS)
            highlightedId = null
        }
    }

    /** A send went out, or a to-do (CV:1705; `ComposeHost.pinToBottom`). */
    fun pinToBottom() {
        pinToBottomToken++
    }

    // ---- Menu (CV:1936-2086, 2204-2254) ---------------------------------------------------------------

    /**
     * A hold on [message]'s row opened its menu: the bubble lifts from its drawn frame when that is a
     * frame of this row, else from the row (CV:936-945, 1949-1955); a medium haptic (D7: long press).
     */
    fun openMessageMenu(message: ChatMessage, rowInRoot: Rect) {
        heldMessageId = message.id
        val source = MessageMenuLayout.sourceFrame(bubbleFrames[message.id], rowInRoot, message.isMine)
        menu.open(message, source)
        haptic(Haptic.LongPress)
    }

    /** The finger that opened the menu lifted: inner controls take taps again (they ignored the release). */
    fun holdReleased() {
        heldMessageId = null
    }

    fun dismissMessageMenu() = menu.dismiss()

    fun onBackdropTap() = menu.backdropTapped()

    /** A card row: the menu closes first, then the action runs (CV:2073-2081, 2204-2223). */
    fun onMenuAction(action: MessageMenuAction, message: ChatMessage) {
        menu.dismiss()
        handleMenu(action, message)
    }

    /** What a menu row (or TalkBack's row action) does (CV:2204-2223). */
    fun handleMenu(action: MessageMenuAction, message: ChatMessage) {
        when (action) {
            MessageMenuAction.Copy -> {
                val text = MessageActions.copyableText(live(message)) ?: return
                backend.copyToClipboard(text)
                // D8: Android 13+ confirms a copy itself; the toast would say it twice.
                if (!backend.clipboardConfirmsItself) toasts.show(Toast.success(COPIED))
                haptic(Haptic.Success)
            }
            MessageMenuAction.CopyLink -> {
                val link = MessageActions.copyableLink(message) ?: return
                backend.copyToClipboard(link)
                if (!backend.clipboardConfirmsItself) toasts.show(Toast.success(LINK_COPIED))
                haptic(Haptic.Success)
            }
            MessageMenuAction.Reply -> startReply(message)
            MessageMenuAction.Edit, MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Select,
            MessageMenuAction.MoreReactions,
            -> showComingSoon(action.title)
            MessageMenuAction.Delete -> requestDelete(message)
        }
    }

    /** "{feature} coming soon" (CV:1931-1934). */
    fun showComingSoon(feature: String) {
        toasts.show(Toast.info("$feature coming soon"))
        haptic(Haptic.Light)
    }

    // ---- Reactions (CV:2120-2202) ---------------------------------------------------------------------

    /**
     * A pick from the long-press menu (CV:2123-2137): the emoji leaves the bar at once; the chip
     * changes only once the bubble is back in its slot (menu drop + 30 ms), so the lifted copy never
     * lands on a bubble that already grew underneath it.
     */
    fun reactAfterMenu(emoji: String, message: ChatMessage, from: Rect?, reduceMotion: Boolean) {
        menu.dismiss()
        val live = live(message)
        if (from != null && !reduceMotion && backend.canReact(live) && emoji !in backend.myReactions(live)) {
            flights.begin(emoji, message.id, from)
        }
        val settle = if (reduceMotion) Motion.REDUCED_MS else Motion.MENU_DROP_MS
        scope.launch {
            delay(settle + REACT_AFTER_MENU_SLACK_MS)
            react(emoji, live(message))
        }
    }

    /**
     * Telegram's double-tap reaction on a text bubble, flying out from under the finger: a 44 dp
     * square at 1.6× (CV:2139-2147). [start] is that square in root px.
     */
    fun quickReact(message: ChatMessage, start: Rect, reduceMotion: Boolean) {
        if (menu.isOpen) return
        val emoji = MessageReactionBarMetrics.QUICK_REACTION
        if (!reduceMotion && emoji !in backend.myReactions(message)) {
            flights.begin(emoji, message.id, start, scale = QUICK_START_SCALE)
        }
        react(emoji, message)
    }

    /** Sets [emoji], or takes it back when it is already ours (CV:2193-2202). */
    fun react(emoji: String, message: ChatMessage) {
        if (!backend.canReact(message)) {
            toasts.show(Toast.info(REACT_LATER))
            return
        }
        haptic(Haptic.Light)
        backend.toggleReaction(emoji, message.id, peer)
    }

    /** Our emoji on the message as the thread holds it now, for the bar and grid. */
    fun selectedReactions(message: ChatMessage): Set<String> = backend.myReactions(live(message)).toSet()

    fun canReact(message: ChatMessage): Boolean = backend.canReact(live(message))

    // ---- Deletes (CV:229-254, 2256-2303) -------------------------------------------------------------

    /** "Delete message?" for [message] (menu, TalkBack, the photo viewer). */
    fun requestDelete(message: ChatMessage) {
        pendingDelete = message
    }

    fun cancelDelete() {
        pendingDelete = null
    }

    /** Deletes with the scope picked in the sheet (CV:2266-2285). */
    fun performDelete(message: ChatMessage, scope: MessageDeleteScope) {
        pendingDelete = null
        // Asked from the photo viewer: it leaves before the photo does, whichever the scope (CV:2270-2275).
        if (compose.isViewingMedia) compose.closeMediaViewer()
        this.scope.launch {
            val error = backend.deleteMessage(message, scope)
            if (error != null) {
                failFeedback(error)
            } else {
                toasts.show(Toast.success(DeleteMessageCopy.deleted(scope)))
                haptic(Haptic.Success)
            }
        }
    }

    fun askDeleteAllNotes() {
        showsNotesDeleteConfirm = true
    }

    fun cancelDeleteAllNotes() {
        showsNotesDeleteConfirm = false
    }

    /** Clears every note and leaves: there is nothing left to show (CV:2287-2303). */
    fun deleteAllNotes() {
        showsNotesDeleteConfirm = false
        scope.launch {
            val outcome = backend.deleteConversation(peer, ConversationDeleteScope.Me)
            if (outcome is ChatDeleteOutcome.Failed) {
                failFeedback(outcome.message)
                return@launch
            }
            haptic(Haptic.Success)
            onBack()
        }
    }

    // ---- Header (CV:154-200, 703-833) ------------------------------------------------------------------

    /** The peer is online; never in Notes (`isOnline`, CV:162-164). */
    val isOnline: Boolean get() = !isNotes && presence?.online == true

    /** The top bar's contents at [now] in the device's zone, locale and clock (`presenceLabel`, CV:167-175). */
    fun headerState(now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean): ConversationHeaderState {
        val activity = peerActivity
        return ConversationHeaderState(
            title = username,
            avatarSeed = AvatarPalette.seed(username, peer),
            isNotes = isNotes,
            subtitle = ConversationPresence.label(isNotes, activity, presence, isOffline, now, zone, locale, is24h),
            subtitleIsAccent = ConversationPresence.isAccent(isNotes, activity, presence),
            isOnline = isOnline,
            activity = activity,
        )
    }


    /** Places a call; a failure shows as a toast (CV:821-833). */
    fun startCall(modality: CallModality) {
        scope.launch {
            backend.startCall(peer, username, modality)?.let { toasts.show(Toast.failure(it)) }
        }
    }

    // ---- Row gestures (CV:921-945) ---------------------------------------------------------------------

    /** A photo or video row opens (or downloads) on a single tap (CV:924-928). */
    fun opensOnTap(message: ChatMessage): Boolean =
        message.presentedKind == ChatMessageKind.Image || message.presentedKind == ChatMessageKind.Video

    /** A text row that can take a reaction answers a double tap with ❤️ (CV:929-934). */
    fun reactsOnDoubleTap(message: ChatMessage): Boolean =
        message.presentedKind == ChatMessageKind.Text && backend.canReact(message)

    // ---- BubbleContext (plan §1.7.13; CV:1509-1677) ---------------------------------------------------

    override val myUserId: UUID? get() = backend.myUserId

    override fun allowsInnerTaps(messageId: UUID): Boolean = menu.session?.message?.id != messageId && heldMessageId != messageId

    override fun claimTap(messageId: UUID) = tapClaim.claim()

    /** Tap on media: download it, or open the viewer / player — refused while a menu is up (CV:2332-2349, 2396-2410). */
    override fun onTapMedia(message: ChatMessage) {
        if (menu.isOpen) return
        compose.handleMediaTap(message)
    }

    /** The ring's X: stops the download; claims the tap so the row doesn't start it again (CV:2386-2394). */
    override fun onCancelDownload(message: ChatMessage) {
        tapClaim.claim()
        compose.cancelDownload(message)
    }

    /** A failed photo or video upload, again (CV:1534-1548, 1572-1585). */
    override fun onRetry(message: ChatMessage) {
        scope.launch {
            val error = when (message.kind) {
                ChatMessageKind.Image -> backend.retryFailedImage(message.id, peer)
                ChatMessageKind.Video -> backend.retryFailedVideo(message.id, peer)
                else -> return@launch
            }
            if (error != null) failFeedback(error) else haptic(Haptic.Success)
        }
    }

    override fun onTapQuote(messageId: UUID) = jumpToQuoted(messageId)

    /**
     * A link in a bubble or a preview: the in-app browser, never while a menu is up — the hold's
     * release lands here too (CV:1355-1367).
     */
    override fun onOpenLink(url: String) {
        if (menu.isOpen) return
        // The row's double tap (quick reaction) sees a link's taps too.
        tapClaim.claim()
        backend.openLink(url)
    }

    override fun onToggleReaction(emoji: String, message: ChatMessage) = react(emoji, message)

    /** A Notes to-do's circle (CV:1667-1676). */
    override fun onToggleTodo(message: ChatMessage) {
        backend.toggleTodo(message.id)
        haptic(Haptic.Light)
    }

    /**
     * A transcript unfolded or folded. Nothing to do: the reversed list keeps its newest row anchored
     * at the bottom, so a reader there keeps seeing the unfolding text (iOS follows with a scroll,
     * CV:1025-1030).
     */
    override fun onTranscriptToggled(message: ChatMessage, expanded: Boolean) = Unit

    override fun reportBubbleBounds(messageId: UUID, boundsInRoot: Rect) {
        bubbleFrames[messageId] = boundsInRoot
    }

    override fun reportChipBounds(messageId: UUID, chipId: String, boundsInRoot: Rect) = flights.land(messageId, boundsInRoot)

    override fun accessibilityActions(message: ChatMessage): List<CustomAccessibilityAction> =
        rowActionLabels(message).map { label ->
            CustomAccessibilityAction(label) {
                performRowAction(label, message)
                true
            }
        }

    /** The row's frame (root px), for the menu TalkBack opens (no press to measure one). */
    fun reportRowBounds(messageId: UUID, boundsInRoot: Rect) {
        rowFrames[messageId] = boundsInRoot
    }

    /**
     * TalkBack's actions on a row, in order: "Reply" (the swipe), "Message options" (the hold),
     * "Copy", "Copy Link", "Delete" (CV:880-883, 2243-2254; MLP:132-134; STR:236-240).
     */
    fun rowActionLabels(message: ChatMessage): List<String> = buildList {
        if (message.canBeQuoted && !menu.isOpen) add(ACTION_REPLY)
        add(ACTION_OPTIONS)
        if (MessageActions.copyableText(message) != null) add(MessageMenuAction.Copy.title)
        if (MessageActions.copyableLink(message) != null) add(MessageMenuAction.CopyLink.title)
        add(MessageMenuAction.Delete.title)
    }

    private fun performRowAction(label: String, message: ChatMessage) {
        when (label) {
            ACTION_REPLY -> startReply(message)
            ACTION_OPTIONS -> openMessageMenu(message, rowFrames[message.id] ?: bubbleFrames[message.id] ?: Rect.Zero).also {
                // No finger is down: inner controls stay reachable.
                heldMessageId = null
            }
            MessageMenuAction.Copy.title -> handleMenu(MessageMenuAction.Copy, message)
            MessageMenuAction.CopyLink.title -> handleMenu(MessageMenuAction.CopyLink, message)
            MessageMenuAction.Delete.title -> handleMenu(MessageMenuAction.Delete, message)
        }
    }

    /** The double tap's start square around [pointInRoot] (px), [sidePx] wide (CV:2143). */
    fun quickReactStart(pointInRoot: Offset, sidePx: Float): Rect =
        Rect(pointInRoot.x - sidePx / 2, pointInRoot.y - sidePx / 2, pointInRoot.x + sidePx / 2, pointInRoot.y + sidePx / 2)

    private fun failFeedback(message: String) {
        toasts.show(Toast.failure(message))
        haptic(Haptic.Error)
    }

    companion object {
        /** CV:1305. */
        const val QUOTED_GONE = "The original message isn’t in this chat any more."

        /** CV:2209, 2214. */
        const val COPIED = "Copied"
        const val LINK_COPIED = "Link copied"

        /** CV:2197. */
        const val REACT_LATER = "You can react once the message is sent."

        /** TalkBack row actions (STR:236-240, MLP:132-134). */
        const val ACTION_REPLY = "Reply"
        const val ACTION_OPTIONS = "Message options"

        /** How long a jumped-to row stays lit before fading (CV:1327). */
        const val HIGHLIGHT_HOLD_MS = 1_100L

        /** The chip changes this long after the menu's drop (CV:2134). */
        const val REACT_AFTER_MENU_SLACK_MS = 30L

        /** A double tap's emoji starts big (CV:2144). */
        const val QUICK_START_SCALE = 1.6f
    }
}

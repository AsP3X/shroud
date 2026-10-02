package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.ListStatus
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.PresenceDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.util.UUID

/**
 * Everything the chat list shows at one moment, read from the messaging engine in one go
 * (`ChatsView.swift:31-54, 119-131`; shell-chats §8.2). Plain data: [ChatsUiState.derive] turns it
 * into rows, so the screen's rules are tested without an engine.
 *
 * The per-peer values iOS reads through `MessagingController` functions at render time
 * (`preview(for:)`, `unreadCount(for:)`, `hasUnseenReactions(for:)`, `isMuted(_:)`,
 * `peerActivity(for:)`) are evaluated here for every listed chat, each time any input changes.
 *
 * @property conversations the server's order, newest first; Notes are not in it.
 * @property previews subtitle per chat ([NOTES_PEER_ID] included), `ChatListFormatting.preview`.
 * @property unread chats with a positive unread count only.
 * @property unseenReactions chats whose heart badge shows (never the open one, MC:5563-5570).
 * @property mutedUntil chats muted right now → when the mute ends (null: until turned back on).
 *   A mute whose time has passed is not in it (`ChatMute.isActive`, NM:60-64).
 * @property activities typing / recording per chat (recording wins, MC:2225-2229).
 * @property activePeer the chat open on screen, if any (two-pane selection).
 */
data class ChatsSnapshot(
    val conversations: List<ConversationItemDto> = emptyList(),
    val status: ListStatus = ListStatus(),
    val isOffline: Boolean = false,
    val previews: Map<UUID, String> = emptyMap(),
    val notesLastActivity: Instant? = null,
    val unread: Map<UUID, Int> = emptyMap(),
    val unseenReactions: Set<UUID> = emptySet(),
    val mutedUntil: Map<UUID, Instant?> = emptyMap(),
    val activities: Map<UUID, ChatPeerActivity> = emptyMap(),
    val activePeer: UUID? = null,
)

/**
 * What New Chat shows (`NewChatSheet.swift:12-45`): the roster in the engine's order (username,
 * locale-aware), its load state and the contacts' presence.
 */
data class NewChatSnapshot(
    val contacts: List<ContactItemDto> = emptyList(),
    val listState: ContactsListState = ContactsListState(),
    val presence: Map<UUID, PresenceDto> = emptyMap(),
)

/**
 * What the Chats tab and New Chat read from and ask of the engines (iOS reads both straight off
 * `@Environment(MessagingController.self)`; on Android chats live in `MessagingController`, the
 * roster in `Contacts`, plan C6). One seam so the screen runs on a fake in tests and previews;
 * [MessagingChatsSource] is the real one. Main thread only.
 */
interface ChatsSource {
    /** Emits when anything [snapshot] reads changed, and once when collected. */
    val changes: Flow<Unit>

    /** The chat list as it is now. */
    fun snapshot(): ChatsSnapshot

    /** Emits when anything [contactsSnapshot] reads changed, and once when collected. */
    val contactChanges: Flow<Unit>

    /** The roster as it is now. */
    fun contactsSnapshot(): NewChatSnapshot

    /** `refreshConversations(force:)` (MC:1002-1014): [force] waits out a running fetch and fetches again. */
    suspend fun refreshConversations(force: Boolean)

    /** `refreshContacts(force:)` (MC:886-956). */
    suspend fun refreshContacts(force: Boolean)

    /** Row menu "Mark as Read" without opening the chat (`markChatRead`, messaging-core §16.2). */
    fun markChatRead(peer: UUID)

    /** The chat's mute while it lasts, a pending change included (`mute(for:)`, messaging-core §16.5). */
    fun mute(peer: UUID): ChatMuteDto?

    /** Null when saved, else the user-facing error (`muteChat`, MC:5781-5816). */
    suspend fun muteChat(peer: UUID, duration: MuteDuration): String?

    /** Null when saved, else the user-facing error. */
    suspend fun unmuteChat(peer: UUID): String?

    /** `deleteConversation(peerUserID:scope:)` (MC:1973-2050; messaging-core §14.3). */
    suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome
}

/** [ChatsSource] over the process's one [MessagingController] and [Contacts] (plan §1.7.7, §1.7.8). */
class MessagingChatsSource(
    private val messaging: MessagingController,
    private val contacts: Contacts,
) : ChatsSource {
    // Every flow a snapshot value is computed from: the list, its state, the threads behind the
    // previews and Notes' last activity, unread counts, activity and the open chat (heart badge).
    override val changes: Flow<Unit> = combine(
        listOf<Flow<Any?>>(
            messaging.conversations,
            messaging.listStatus,
            messaging.isOffline,
            messaging.threads,
            messaging.unreadCounts,
            messaging.peerActivities,
            messaging.activePeerId,
        ),
    ) { }

    override fun snapshot(): ChatsSnapshot {
        val conversations = messaging.conversations.value
        val peers = conversations.map { it.peer.id }
        val previews = HashMap<UUID, String>(peers.size + 1)
        previews[NOTES_PEER_ID] = messaging.preview(NOTES_PEER_ID)
        val unread = HashMap<UUID, Int>()
        val unseen = HashSet<UUID>()
        val muted = HashMap<UUID, Instant?>()
        val activities = HashMap<UUID, ChatPeerActivity>()
        for (peer in peers) {
            previews[peer] = messaging.preview(peer)
            messaging.unreadCount(peer).takeIf { it > 0 }?.let { unread[peer] = it }
            if (messaging.hasUnseenReactions(peer)) unseen += peer
            messaging.mute(peer)?.let { muted[peer] = it.until }
            messaging.peerActivity(peer)?.let { activities[peer] = it }
        }
        return ChatsSnapshot(
            conversations = conversations,
            status = messaging.listStatus.value,
            isOffline = messaging.isOffline.value,
            previews = previews,
            notesLastActivity = messaging.notesLastActivity(),
            unread = unread,
            unseenReactions = unseen,
            mutedUntil = muted,
            activities = activities,
            activePeer = messaging.activePeerId.value,
        )
    }

    override val contactChanges: Flow<Unit> = combine(contacts.contacts, contacts.listState, contacts.presence) { _, _, _ -> }

    override fun contactsSnapshot(): NewChatSnapshot =
        NewChatSnapshot(contacts.contacts.value, contacts.listState.value, contacts.presence.value)

    override suspend fun refreshConversations(force: Boolean) = messaging.refreshConversations(force)

    override suspend fun refreshContacts(force: Boolean) = contacts.refresh(force)

    override fun markChatRead(peer: UUID) = messaging.markChatRead(peer)

    override fun mute(peer: UUID): ChatMuteDto? = messaging.mute(peer)

    override suspend fun muteChat(peer: UUID, duration: MuteDuration): String? = messaging.muteChat(peer, duration)

    override suspend fun unmuteChat(peer: UUID): String? = messaging.unmuteChat(peer)

    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome =
        messaging.deleteConversation(peer, scope)
}

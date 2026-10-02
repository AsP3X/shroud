package de.corespace.shroud.ui.chats

import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ConversationPeerDto
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.shell.SettingsRoute
import de.corespace.shroud.ui.shell.ShellNavigation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/** A hand-driven [ChatsSource]: set [chats] / [roster], read what the screen asked for in [log]. */
class FakeChatsSource(
    chats: ChatsSnapshot = ChatsSnapshot(),
    roster: NewChatSnapshot = NewChatSnapshot(),
) : ChatsSource {
    val chats = MutableStateFlow(chats)
    val roster = MutableStateFlow(roster)
    val log = ArrayList<String>()
    var muteError: String? = null
    var mutes = HashMap<UUID, ChatMuteDto?>()
    var deleteOutcome: ChatDeleteOutcome = ChatDeleteOutcome.ClearedForMe

    override val changes: Flow<Unit> = this.chats.map { }
    override fun snapshot(): ChatsSnapshot = chats.value
    override val contactChanges: Flow<Unit> = this.roster.map { }
    override fun contactsSnapshot(): NewChatSnapshot = roster.value

    override suspend fun refreshConversations(force: Boolean) {
        log += "refreshConversations:$force"
    }

    override suspend fun refreshContacts(force: Boolean) {
        log += "refreshContacts:$force"
    }

    override fun markChatRead(peer: UUID) {
        log += "markChatRead:$peer"
    }

    override fun mute(peer: UUID): ChatMuteDto? = mutes[peer]

    override suspend fun muteChat(peer: UUID, duration: MuteDuration): String? {
        log += "muteChat:$peer:${duration.name}"
        return muteError
    }

    override suspend fun unmuteChat(peer: UUID): String? {
        log += "unmuteChat:$peer"
        return muteError
    }

    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome {
        log += "deleteConversation:$peer:${scope.wire}"
        return deleteOutcome
    }
}

/** Records the screen's navigation. */
class RecordingNavigation : ShellNavigation {
    val log = ArrayList<String>()
    override val selection: StateFlow<MainTab> = MutableStateFlow(MainTab.Chats)
    override fun select(tab: MainTab) {
        log += "select:$tab"
    }
    override fun push(route: ChatRoute) {
        log += "push:$route"
    }
    override fun push(route: SettingsRoute) {
        log += "push:$route"
    }
    override fun pop(): Boolean = false
    override fun popToRoot() = Unit
    override fun openChat(peerId: UUID, username: String) {
        log += "openChat:$peerId:$username"
    }
}

/** Fixed ids and DTOs of the chat tests. */
object ChatsFixtures {
    val jane: UUID = UUID.fromString("3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f")
    val mom: UUID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    val devon: UUID = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed")

    fun conversation(peer: UUID, username: String, last: Instant? = null, mute: ChatMuteDto? = null) = ConversationItemDto(
        id = UUID.nameUUIDFromBytes(username.toByteArray()),
        peer = ConversationPeerDto(peer, username),
        createdAt = Instant.parse("2026-09-01T10:00:00Z"),
        lastMessageAt = last,
        mute = mute,
    )
}

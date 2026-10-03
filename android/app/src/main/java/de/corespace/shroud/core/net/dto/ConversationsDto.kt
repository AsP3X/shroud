@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Conversations, presence, client config — iOS `Services/API/MessageModels.swift:173-234`,
// `NotificationModels.swift:89-99`, `MessageReaction.swift:80-91`; server `routes/conversations.rs`,
// `routes/presence.rs`, `routes/app_config.rs`. api-realtime §5.7.

/** The other participant of a chat (`MessageModels.swift:173-176`). */
@Serializable
data class ConversationPeerDto(
    val id: UUID,
    /** Filled on this phone. The server does not send a name. */
    val username: String = "Contact",
    /** The account was deleted. This is not a name. */
    val deleted: Boolean = false,
)

/**
 * One row of `GET /conversations` (`MessageModels.swift:178-202`). Older servers send none of the
 * optional counters; each is null then, never zero.
 */
@Serializable
data class ConversationItemDto(
    val id: UUID,
    val peer: ConversationPeerDto,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("last_message_at") val lastMessageAt: Instant? = null,
    /** The chat's latest reaction change; null from servers without reactions. */
    @SerialName("reaction_seq") val reactionSeq: Long? = null,
    /** The peer's reactions to our messages we have not marked seen (the heart badge). */
    @SerialName("unseen_reactions") val unseenReactions: Int? = null,
    /** Their messages after our read marker, the same on every device; null = server without read markers. */
    @SerialName("unread_count") val unreadCount: Int? = null,
    /** Set while we have the chat muted (`until` null = until unmuted); null = not muted. */
    val mute: ChatMuteDto? = null,
)

/** `GET /conversations` (`MessageModels.swift:220-222`). */
@Serializable
data class ConversationsResponse(val conversations: List<ConversationItemDto>)

/**
 * `POST /conversations/{peer}/read` (`NotificationModels.swift:89-99`). `read_at` is null when
 * there was nothing to read.
 */
@Serializable
data class MarkChatReadResponse(
    @SerialName("read_at") val readAt: Instant? = null,
    @SerialName("unread_count") val unreadCount: Int,
    /** Read receipts the server sent the peer for this marker. */
    val receipts: Long,
)

/**
 * `GET /presence/{user_id}` (`MessageModels.swift:224-234`). `last_seen_at` is null while online
 * or when the peer does not share presence.
 */
@Serializable
data class PresenceDto(
    @SerialName("user_id") val userId: UUID,
    val online: Boolean,
    @SerialName("last_seen_at") val lastSeenAt: Instant? = null,
)

/** `GET /config` — behaviour the server operator sets (`MessageReaction.swift:80-91`). */
@Serializable
data class ClientConfigDto(val reactions: Reactions) {
    @Serializable
    data class Reactions(
        /** Most emoji one person may leave on one message. */
        @SerialName("max_per_user") val maxPerUser: Int,
    )
}

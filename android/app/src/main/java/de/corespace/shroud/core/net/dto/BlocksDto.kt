@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Blocks — iOS `Services/API/BlockModels.swift`; server `routes/blocks.rs`. api-realtime §5.5.

/** A user this account has blocked (`GET /blocks`, `BlockModels.swift:4-15`). */
@Serializable
data class BlockItemDto(
    @SerialName("user_id") val userId: UUID,
    val username: String,
    @SerialName("created_at") val createdAt: Instant,
)

/** `BlocksListResponse`, `BlockModels.swift:17-19`. */
@Serializable
data class BlocksResponse(val blocks: List<BlockItemDto>)

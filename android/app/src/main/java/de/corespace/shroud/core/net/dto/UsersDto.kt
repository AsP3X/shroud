@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Users and contacts — iOS `Services/API/ContactModels.swift`, `PrivacyModels.swift:78-85`;
// server `routes/users.rs`, `routes/contacts.rs`. api-realtime §5.4.

/** `ContactModels.swift:3-14`. */
@Serializable
data class UserCardDto(
    val id: UUID,
    val username: String,
    /** Present on the `GET /users/…` lookups; absent on contact-request peer cards. */
    @SerialName("share_code") val shareCode: String? = null,
)

/** `ContactModels.swift:16-34`. Also the `request` of the `contact.*` socket events. */
@Serializable
data class ContactRequestDto(
    val id: UUID,
    @SerialName("from_user_id") val fromUserId: UUID,
    @SerialName("to_user_id") val toUserId: UUID,
    /** One of [ContactRequestStatus]. */
    val status: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("responded_at") val respondedAt: Instant? = null,
    /** The requester's card (absent on rejected / cancelled requests). */
    val user: UserCardDto? = null,
)

/** The values of [ContactRequestDto.status]. */
object ContactRequestStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"
    const val CANCELLED = "cancelled"
}

/** `GET /contacts/requests` (`ContactModels.swift:36-38`). */
@Serializable
data class ContactRequestsResponse(val requests: List<ContactRequestDto>)

/** `ContactModels.swift:40-51`. */
@Serializable
data class ContactItemDto(
    @SerialName("user_id") val userId: UUID,
    val username: String,
    @SerialName("created_at") val createdAt: Instant,
)

/** `GET /contacts` (`ContactsListResponse`, `ContactModels.swift:53-55`). */
@Serializable
data class ContactsResponse(val contacts: List<ContactItemDto>)

/** `POST /contacts/requests` and `POST /blocks` (`CreateContactRequestBody`, `BlockUserBody`). */
@Serializable
data class UserIdBody(@SerialName("user_id") val userId: UUID)

/** `POST /users/me/share-code` — the account's new share code (`ShareCodeDTO`, `PrivacyModels.swift:79-85`). */
@Serializable
data class ShareCodeResponse(@SerialName("share_code") val shareCode: String)

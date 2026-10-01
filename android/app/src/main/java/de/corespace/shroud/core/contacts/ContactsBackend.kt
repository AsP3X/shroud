package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import de.corespace.shroud.core.net.UserCardDto
import java.util.UUID

/**
 * The REST calls the contacts, peer-identity and privacy engines make — a narrow view of the one
 * [ShroudApi] facade (plan C3), so the engines' tests can script answers and hold them open
 * (coalescing, half failures) without a server. [ShroudContactsBackend] is the only production
 * implementation; it adds nothing to the API calls. Errors are `ApiError`s, as `ShroudApi` throws.
 */
interface ContactsBackend {
    /** `GET /contacts` (`ContactsService.swift:7-14`). */
    suspend fun contacts(token: String): List<ContactItemDto>

    /** `GET /contacts/requests?box=incoming&status=pending`, newest first (`ContactsService.swift:16-24`). */
    suspend fun incomingRequests(token: String): List<ContactRequestDto>

    suspend fun user(token: String, userId: UUID): UserCardDto
    suspend fun userByUsername(token: String, username: String): UserCardDto
    suspend fun userByShareCode(token: String, code: String): UserCardDto

    /** 201 pending, or 200 accepted when they had asked us already. */
    suspend fun createContactRequest(token: String, userId: UUID): ContactRequestDto
    suspend fun acceptContactRequest(token: String, requestId: UUID): ContactRequestDto
    suspend fun rejectContactRequest(token: String, requestId: UUID): ContactRequestDto

    suspend fun presence(token: String, userId: UUID): PresenceDto

    suspend fun blocks(token: String): List<BlockItemDto>
    suspend fun block(token: String, userId: UUID)
    suspend fun unblock(token: String, userId: UUID)

    /** `GET /keys/identity/{user}`: the Base64 identity key, as the server sends it (no prekey consumed). */
    suspend fun identityKey(token: String, userId: UUID): String

    suspend fun privacySettings(token: String): PrivacySettingsDto
    suspend fun updatePrivacySettings(token: String, change: UpdatePrivacySettingsBody): PrivacySettingsDto

    /** `POST /users/me/share-code` → the new code. */
    suspend fun rotateShareCode(token: String): String
}

/** [ContactsBackend] on the app's one [ShroudApi]. */
class ShroudContactsBackend(private val api: ShroudApi) : ContactsBackend {
    override suspend fun contacts(token: String) = api.contacts(token)
    override suspend fun incomingRequests(token: String) = api.contactRequests(token)
    override suspend fun user(token: String, userId: UUID) = api.user(token, userId)
    override suspend fun userByUsername(token: String, username: String) = api.userByUsername(token, username)
    override suspend fun userByShareCode(token: String, code: String) = api.userByShareCode(token, code)
    override suspend fun createContactRequest(token: String, userId: UUID) = api.createContactRequest(token, userId)
    override suspend fun acceptContactRequest(token: String, requestId: UUID) = api.acceptContactRequest(token, requestId)
    override suspend fun rejectContactRequest(token: String, requestId: UUID) = api.rejectContactRequest(token, requestId)
    override suspend fun presence(token: String, userId: UUID) = api.presence(token, userId)
    override suspend fun blocks(token: String) = api.blocks(token)
    override suspend fun block(token: String, userId: UUID) = api.block(token, userId)
    override suspend fun unblock(token: String, userId: UUID) = api.unblock(token, userId)
    override suspend fun identityKey(token: String, userId: UUID) = api.identityKey(token, userId).identityKey
    override suspend fun privacySettings(token: String) = api.privacySettings(token)
    override suspend fun updatePrivacySettings(token: String, change: UpdatePrivacySettingsBody) = api.updatePrivacySettings(token, change)
    override suspend fun rotateShareCode(token: String) = api.rotateShareCode(token)
}

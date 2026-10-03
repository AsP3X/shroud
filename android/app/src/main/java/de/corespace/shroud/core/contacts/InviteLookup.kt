package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.UserCardDto

/**
 * Finds the account an invite names (`MessagingController.addContact`,
 * `ios/shroud/Services/Messaging/MessagingController.swift:895-923`): a user id → `GET /users/{id}`,
 * a share code → `GET /users/by-code/{code}`. A username is not a way to find an account.
 */
object InviteLookup {
    private const val USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_"

    /** The card [invite] names; the backend's errors propagate (the caller shows their message). */
    suspend fun card(backend: ContactsBackend, token: String, invite: ContactInviteParser.Invite): UserCardDto = when (invite) {
        is ContactInviteParser.Invite.UserId -> backend.user(token, invite.id)
        is ContactInviteParser.Invite.Username -> throw ApiError.Server(
            "NOT_FOUND",
            "Add someone with their QR code or share code.",
            404,
        )
        is ContactInviteParser.Invite.ShareCode -> backend.userByShareCode(token, invite.code)
    }

    /** Looks a share code up. A miss stays a miss: the name is not sent anywhere. */
    suspend fun lookUpShareCode(
        code: String,
        byCode: suspend (String) -> UserCardDto,
    ): UserCardDto = byCode(code)

    /**
     * The username a share code may really be (`usernameFallback(forShareCode:)`,
     * `MessagingController.swift:952-962`): the code lower-cased when that is 3–32 of `a-z 0-9 _`
     * (counted in code points, as iOS counts `unicodeScalars`); null otherwise, e.g. `MÜLLERHANS`.
     */
    fun usernameFallback(code: String): String? {
        val name = code.lowercase()
        if (name.codePointCount(0, name.length) !in 3..32) return null
        return name.takeIf { candidate -> candidate.all { it in USERNAME_ALPHABET } }
    }
}

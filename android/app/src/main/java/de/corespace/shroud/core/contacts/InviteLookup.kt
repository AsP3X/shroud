package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.UserCardDto

/**
 * Finds the account an invite names (`MessagingController.addContact`,
 * `ios/shroud/Services/Messaging/MessagingController.swift:895-923`): a user id → `GET /users/{id}`,
 * a username → `GET /users/by-username/{name}`, a share code → `GET /users/by-code/{code}` and, when
 * no such code exists, the same text as a username ([lookUpShareCode], P10a, plan C15).
 */
object InviteLookup {
    private const val USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_"

    /** The card [invite] names; the backend's errors propagate (the caller shows their message). */
    suspend fun card(backend: ContactsBackend, token: String, invite: ContactInviteParser.Invite): UserCardDto = when (invite) {
        is ContactInviteParser.Invite.UserId -> backend.user(token, invite.id)
        is ContactInviteParser.Invite.Username -> backend.userByUsername(token, invite.name)
        is ContactInviteParser.Invite.ShareCode -> lookUpShareCode(
            invite.code,
            byCode = { backend.userByShareCode(token, it) },
            byUsername = { backend.userByUsername(token, it) },
        )
    }

    /**
     * Looks a share code up, and on a 404 tries it as a username (`lookUpShareCode`,
     * `MessagingController.swift:928-950`; web `web/src/api/client.ts:389-411`). The parser reads any
     * 8–16 letters and digits as a share code, so a username such as `noahvorberg` could never be
     * added by name otherwise; the server never has both readings for one input. Only a 404
     * ([ApiError.isNotFound]) with a username-shaped code falls back — offline, a rate limit or a
     * server error is the answer, and a code that cannot be a username keeps its 404.
     */
    suspend fun lookUpShareCode(
        code: String,
        byCode: suspend (String) -> UserCardDto,
        byUsername: suspend (String) -> UserCardDto,
    ): UserCardDto = try {
        byCode(code)
    } catch (e: ApiError) {
        val name = if (e.isNotFound) usernameFallback(code) else null
        if (name == null) throw e
        byUsername(name)
    }

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

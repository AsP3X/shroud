package de.corespace.shroud.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Privacy — iOS `Services/API/PrivacyModels.swift:12-76`; server `routes/privacy.rs`.
// api-realtime §5.12. These flags gate what a *peer* may do to or learn about this account, so
// only the server can enforce them; they carry no secret. `ShareCodeResponse` (iOS `:79-85`) is
// with the users (UsersDto.kt).

/**
 * Account-level privacy flags (`GET/PUT /privacy/settings`, `PrivacyModels.swift:12-58`). The
 * three visibility switches work both ways and the server enforces both directions.
 *
 * A server from before the switches sends only `allow_peer_chat_delete` and behaves as if all the
 * others were on, so that is what they read as (`:50-57`,
 * `ChatDeleteModelsTests.visibilitySwitchesDecodeAndDefaultToOnForOlderServers`). iOS resets to
 * `PrivacySettingsDto(allowPeerChatDelete = false)` — everything visible — when messaging stops
 * (`MessagingController.swift:543`).
 */
@Serializable
data class PrivacySettingsDto(
    /**
     * True: a contact deleting a chat "for both" also clears this account's copy. False: their
     * messages become "Message deleted" here and this account's own messages survive.
     */
    @SerialName("allow_peer_chat_delete") val allowPeerChatDelete: Boolean,
    /** Contacts see when this account read their messages, and it sees theirs. */
    @SerialName("send_read_receipts") val sendReadReceipts: Boolean = true,
    /** Typing and voice-recording indicators go out and come in. */
    @SerialName("send_typing") val sendTyping: Boolean = true,
    /** Contacts see "online" / "last seen", and this account sees theirs. */
    @SerialName("share_presence") val sharePresence: Boolean = true,
    /** Someone who only knows the username can find this account (off: QR or share code only). */
    @SerialName("discoverable_by_username") val discoverableByUsername: Boolean = true,
)

/**
 * Partial update of [PrivacySettingsDto] (`:60-76`): null fields are left as the server has them.
 * Encode with `ShroudApi.patchJson` (`explicitNulls = false`) so only the changed switch is sent
 * (`ChatDeleteModelsTests.privacyUpdateSendsOnlyTheChangedSwitch`).
 */
@Serializable
data class UpdatePrivacySettingsBody(
    @SerialName("allow_peer_chat_delete") val allowPeerChatDelete: Boolean? = null,
    @SerialName("send_read_receipts") val sendReadReceipts: Boolean? = null,
    @SerialName("send_typing") val sendTyping: Boolean? = null,
    @SerialName("share_presence") val sharePresence: Boolean? = null,
    @SerialName("discoverable_by_username") val discoverableByUsername: Boolean? = null,
)

@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Notifications and mutes — iOS `Services/API/NotificationModels.swift:4-106`; server
// `routes/notifications.rs`, `routes/push.rs`. api-realtime §5.11, notifications-push §5.1.1.
// `MarkChatReadResponse` (iOS `:89-99`) lives with the conversations (ConversationsDto.kt).
// `MuteChatBody` is written by hand in ShroudApi (W1-NET): its `seconds` must be an explicit
// `null` (`:67-77`), which no single Json configuration gives together with the patches below.
// The APNs `PushTokenBody` (`:108-122`) has no Android use (plan C30, decision record 1).

/** This device's push settings as the server stores them (`GET/PUT /notifications/settings`, `:4-21`). */
@Serializable
data class NotificationSettingsDto(
    val enabled: Boolean,
    @SerialName("show_sender") val showSender: Boolean,
    val reactions: Boolean,
    @SerialName("contact_requests") val contactRequests: Boolean,
    /** A `NotificationSound` server name (`NotificationSound.swift:9-41`). */
    val sound: String,
    val badge: Boolean,
    @SerialName("badge_includes_muted") val badgeIncludesMuted: Boolean,
)

/**
 * Partial update of [NotificationSettingsDto] (`:24-54`): null fields stay as the server has them.
 * Encode with `ShroudApi.patchJson` (`explicitNulls = false`) so unset fields are absent, as iOS's
 * `encodeIfPresent` leaves them out (`NotificationPayloadTests.testPatchEncodesOnlyWhatIsSet`).
 */
@Serializable
data class NotificationSettingsPatch(
    val enabled: Boolean? = null,
    @SerialName("show_sender") val showSender: Boolean? = null,
    val reactions: Boolean? = null,
    @SerialName("contact_requests") val contactRequests: Boolean? = null,
    val sound: String? = null,
    val badge: Boolean? = null,
    @SerialName("badge_includes_muted") val badgeIncludesMuted: Boolean? = null,
)

/**
 * A chat's mute (`:56-65`): present while muted; [until] null = until turned back on. The server
 * writes `until` with fractional seconds, any number of digits (`2026-09-24T19:00:00.5Z`).
 */
@Serializable
data class ChatMuteDto(val until: Instant? = null) {
    /** A mute whose time has passed is over before the next chat list says so (`:61-64`). */
    fun isActive(now: Instant = Instant.now()): Boolean = until == null || until.isAfter(now)
}

/** `PUT /conversations/{peer}/mute` (`:79-87`). */
@Serializable
data class MuteChatResponse(
    @SerialName("peer_user_id") val peerUserId: UUID,
    val mute: ChatMuteDto,
)

/**
 * `POST /push/test` — what happened to the test notification (`:101-106`; server
 * `push/mod.rs` `TestPushOutcome`).
 *
 * [channel]: `apns`, `web`, or `unifiedpush` once the server tells Android subscriptions apart
 * (X1-SRV-UP); null when this device registered for none. [status]: `sent`, `not_registered`,
 * `not_configured`, `misconfigured`, `rejected` or `failed`. [detail] is absent unless the relay
 * said why.
 */
@Serializable
data class TestPushOutcomeDto(
    val channel: String? = null,
    val status: String,
    val detail: String? = null,
)

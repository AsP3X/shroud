package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.net.wire.TextUnits
import de.corespace.shroud.core.net.wire.WireText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * What one push says, after RFC 8291 decryption (notifications-push §5.8.1, `fromWebPushJson`
 * only — Android has no FCM and no sealed name, plan C30; iOS `NotificationPayload.Contents`,
 * `ios/ShroudShared/NotificationPayload.swift:34-62`).
 *
 * The plaintext is the server's Web Push payload (`push/payload.rs` `web`, `web_read`; notifications-push
 * §3.4):
 *
 * ```json
 * {"v":1,"kind":"message","tag":"<conv>","silent":false,"conversation_id":"<conv>",
 *  "peer_user_id":"<peer>","message_id":"<msg>","call_id":"<call>","sender":"alice","badge":3}
 * {"v":1,"kind":"read","tag":"<conv>","conversation_id":"<conv>","badge":2}
 * ```
 *
 * The background connection (W3-PUSH) builds the same value from a socket event with the plain
 * constructor; its [plainName] then comes from [NotificationNameCache].
 *
 * Ids are read like Swift's `UUID(uuidString:)` (canonical 8-4-4-4-12 hex, any case, nothing
 * around it; `UUID.fromString` would take `1-1-1-1-1`): anything else is null for that field
 * alone. `tag` and `silent` are not read: Android derives its own tags (`SystemNotifier`) and
 * the channel owns the sound.
 *
 * @property kind null for a kind this build does not know ([rawKind] keeps it) and for [KIND_READ].
 * @property thread the server's grouping (`Notification::thread`, `payload.rs:80-95`).
 * @property plainName the sender's name — plaintext inside the RFC 8291 ciphertext, so the push
 *   service never reads it — trimmed and cut to 64 grapheme clusters ([clampName]).
 * @property badge the unread total when the device shows a badge, else null.
 */
data class PushContents(
    val kind: NotificationKind?,
    val rawKind: String,
    val thread: String,
    val conversationId: UUID? = null,
    val peerUserId: UUID? = null,
    val messageId: UUID? = null,
    val callId: UUID? = null,
    val plainName: String? = null,
    val badge: Int? = null,
) {
    /** A chat was read on another device: close its notifications (P11d; `payload.rs` `web_read`). */
    val isRead: Boolean get() = rawKind == KIND_READ

    companion object {
        /** `{"kind":"read"}` — the server's `web_read` (X1-SRV-UP, P11d). */
        const val KIND_READ = "read"

        /** `{"kind":"device_removed"}` — the server's `web_device_removed`; not a notification. */
        const val KIND_DEVICE_REMOVED = "device_removed"

        /** Swift `String.prefix(64)` of the trimmed name (`NotificationPayload.swift:84-85`). */
        const val MAX_NAME_GRAPHEMES = 64

        /**
         * The decrypted Web Push payload as [PushContents], or null when it is not one of ours (not a
         * JSON object, no string `kind` — `NotificationPayloadTests.testParsesTheAppPart`) or when it
         * is the removal wake ([isDeviceRemoval]), which is not a notification.
         */
        fun fromWebPushJson(json: JsonObject): PushContents? {
            val rawKind = LenientJson.string(json["kind"]) ?: return null
            if (rawKind == KIND_DEVICE_REMOVED) return null
            val kind = NotificationKind.fromWire(rawKind)
            val conversationId = uuid(json["conversation_id"])
            return PushContents(
                kind = kind,
                rawKind = rawKind,
                thread = threadFor(kind, conversationId),
                conversationId = conversationId,
                peerUserId = uuid(json["peer_user_id"]),
                messageId = uuid(json["message_id"]),
                callId = uuid(json["call_id"]),
                plainName = clampName(LenientJson.string(json["sender"])),
                badge = LenientJson.number(json["badge"])?.takeIf { it >= 0 },
            )
        }

        /** [fromWebPushJson] of the decrypted bytes; null when they are not a UTF-8 JSON object. */
        fun fromWebPushJson(plaintext: ByteArray): PushContents? = LenientJson.parseObject(plaintext)?.let(::fromWebPushJson)

        /** The removal wake `{"v":1,"kind":"device_removed"}` (`payload.rs` `web_device_removed`; settings-lock §14.6). */
        fun isDeviceRemoval(json: JsonObject): Boolean = LenientJson.string(json["kind"]) == KIND_DEVICE_REMOVED

        /**
         * The server's thread rule (`Notification::thread`, `push/payload.rs:80-95`; notifications-push §2):
         * contact requests share `contacts`, call kinds `calls`, the test `test`; messages and reactions
         * their conversation id, else `shroud`.
         */
        fun threadFor(kind: NotificationKind?, conversationId: UUID?): String = when (kind) {
            NotificationKind.ContactRequest -> "contacts"
            NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.MissedCall, NotificationKind.CallEnded -> "calls"
            NotificationKind.Test -> "test"
            else -> conversationId?.let(Ids::wire) ?: "shroud"
        }

        /**
         * A sender name as a notification may show it (`openName`, `NotificationPayload.swift:84-85`;
         * notifications-push §3.1): trimmed of whitespace and newlines, null when nothing is left,
         * else its first [MAX_NAME_GRAPHEMES] grapheme clusters — never a cut inside an emoji
         * (`String.take(64)` counts UTF-16 units).
         */
        fun clampName(raw: String?): String? {
            val trimmed = WireText.trimWhitespacesAndNewlines(raw ?: return null)
            if (trimmed.isEmpty()) return null
            if (trimmed.length <= MAX_NAME_GRAPHEMES) return trimmed
            val graphemes = TextUnits.current.graphemes(trimmed)
            return if (graphemes.size <= MAX_NAME_GRAPHEMES) trimmed else graphemes.take(MAX_NAME_GRAPHEMES).joinToString("")
        }

        private fun uuid(value: JsonElement?): UUID? = LenientJson.string(value)?.let(LenientJson::uuidExact)
    }
}

@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

// Auth — iOS `Services/API/AuthModels.swift`; server `routes/auth.rs`. api-realtime §5.1.
// DTOs live in core/net/dto/ but keep the package de.corespace.shroud.core.net (api-realtime §5).

/** `POST /auth/register` (`AuthModels.swift:6-9`). No device name: it is sealed later. */
@Serializable
data class RegisterRequest(
    @SerialName("username_hash") val usernameHash: String,
    val password: String,
)

/** `POST /auth/login` (`AuthModels.swift:12-22`). */
@Serializable
data class LoginRequest(
    @SerialName("username_hash") val usernameHash: String,
    val password: String,
    /** The device this phone had on the account, so a re-login reuses its row; `null` is sent as `null`. */
    @SerialName("device_id") val deviceId: UUID?,
    /**
     * The device to log out when every slot is signed in: one of the [DeviceLimitDto.candidates] of
     * a `409 DEVICE_LIMIT` answer (the oldest unless the user picked another), sent only once the
     * phrase checked out and the user agreed (server `routes/auth.rs`). Ignored while a slot is free.
     */
    @SerialName("replace_device_id") val replaceDeviceId: UUID? = null,
    /** SHA-256 of the same name, until this phone has seen the account move. Null is sent as null. */
    @SerialName("legacy_username_hash") val legacyUsernameHash: String? = null,
)

/** `GET /auth/username-kdf`. Clients refuse anything below the cost floor. */
@Serializable
data class UsernameKdfDto(
    val algorithm: String,
    val version: Int,
    val salt: String,
    @SerialName("memory_kib") val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    @SerialName("output_bytes") val outputBytes: Int,
)

/**
 * A device of a full account, offered for log-out in a login's `409 DEVICE_LIMIT` (server
 * `error.rs` `LimitDevice`). Its name is sealed as in `GET /devices`; the phone opens it with the
 * phrase's history key once the phrase checked out. `sealed_name` and `last_seen_at` are omitted
 * when null.
 */
@Serializable
data class LimitDeviceDto(
    val id: UUID,
    /** Base64, sealed by the account's devices (`DeviceNameSeal`); null when never named. */
    @SerialName("sealed_name") val sealedName: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("last_seen_at") val lastSeenAt: Instant? = null,
)

/**
 * What a login's `409 DEVICE_LIMIT` carries beside the envelope (server `error.rs` `ErrorBody`):
 * every device of the account least recently active first ([devices]; [oldestDevice] is the first),
 * any of which a retry with `replace_device_id` logs out, and the account's published identity key
 * (standard Base64, as `GET /keys/identity/{user}` sends it) so the phrase can be checked before that
 * is offered. The key is absent while no device of the account has published keys; older servers
 * send no [devices], or nothing at all.
 */
@Serializable
data class DeviceLimitDto(
    @SerialName("oldest_device") val oldestDevice: LimitDeviceDto? = null,
    val devices: List<LimitDeviceDto> = emptyList(),
    @SerialName("identity_key") val identityKey: String? = null,
) {
    /** The devices to choose from, oldest first: [devices], or just [oldestDevice] from a server without them. */
    val candidates: List<LimitDeviceDto> get() = devices.ifEmpty { listOfNotNull(oldestDevice) }

    companion object {
        private val bodyJson = Json { ignoreUnknownKeys = true }

        /** The extra fields of a `DEVICE_LIMIT` error body; null when it is unreadable. */
        fun fromErrorBody(body: String): DeviceLimitDto? = try {
            bodyJson.decodeFromString(serializer(), body)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            null
        }
    }
}

/** `AuthModels.swift:39-50`. */
@Serializable
data class UserDto(
    val id: UUID,
    /** Absent. The name stays on the device that typed it. */
    val username: String? = null,
    /** Short public code for QR / links (not a secret). iOS requires it; the server always sends it. */
    @SerialName("share_code") val shareCode: String? = null,
)

/** `AuthModels.swift:52-61`. */
@Serializable
data class DeviceDto(
    val id: UUID,
    /** Base64, sealed by the account's devices (`DeviceNameSeal`); null until one names it. */
    @SerialName("sealed_name") val sealedName: String? = null,
)

/** Register / log in success (`AuthModels.swift:27-31`). The token is shown once. */
@Serializable
data class AuthSessionResponse(val token: String, val user: UserDto, val device: DeviceDto)

/** `GET /auth/me` (`AuthModels.swift:34-37`). */
@Serializable
data class MeResponse(val user: UserDto, val device: DeviceDto)

/** `POST /auth/password` (no UI in v1, P18). */
@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/** `DELETE /auth/account`. */
@Serializable
data class DeleteAccountRequest(val password: String) {
    /** The password must not appear in logs. */
    override fun toString(): String = "DeleteAccountRequest"
}

/** `GET /health` (`APIClient.swift:471-475`; iOS requires `database`, Android tolerates its absence). */
@Serializable
data class HealthResponse(val status: String, val database: String? = null)

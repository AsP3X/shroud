@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

// Auth — iOS `Services/API/AuthModels.swift`; server `routes/auth.rs`. api-realtime §5.1.
// DTOs live in core/net/dto/ but keep the package de.corespace.shroud.core.net (api-realtime §5).

/** `POST /auth/register` (`AuthModels.swift:6-9`). No device name: it is sealed later. */
@Serializable
data class RegisterRequest(val username: String, val password: String)

/** `POST /auth/login` (`AuthModels.swift:12-22`). */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** The device this phone had on the account, so a re-login reuses its row; `null` is sent as `null`. */
    @SerialName("device_id") val deviceId: UUID?,
)

/** `AuthModels.swift:39-50`. */
@Serializable
data class UserDto(
    val id: UUID,
    val username: String,
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

/** `DELETE /auth/account` (no UI in v1, P18). */
@Serializable
data class DeleteAccountRequest(val password: String)

/** `GET /health` (`APIClient.swift:471-475`; iOS requires `database`, Android tolerates its absence). */
@Serializable
data class HealthResponse(val status: String, val database: String? = null)

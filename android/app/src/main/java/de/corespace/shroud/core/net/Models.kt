package de.corespace.shroud.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Auth — server-plan.md Milestone 1; server/crates/shroud-server/src/routes/auth.rs.

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** The device this phone had on the account, so a re-login reuses its row. */
    @SerialName("device_id") val deviceId: String?,
)

@Serializable
data class UserDto(val id: String, val username: String, @SerialName("share_code") val shareCode: String? = null)

@Serializable
data class DeviceDto(val id: String, @SerialName("sealed_name") val sealedName: String? = null)

@Serializable
data class AuthSessionResponse(val token: String, val user: UserDto, val device: DeviceDto)

@Serializable
data class MeResponse(val user: UserDto, val device: DeviceDto)

// Keys — src/routes/keys.rs. Keys are standard Base64 with padding.

@Serializable
data class SignedPreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class OneTimePreKeyDto(@SerialName("key_id") val keyId: Int, @SerialName("public_key") val publicKey: String)

@Serializable
data class PutKeyBundleRequest(
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_pre_key") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_pre_keys") val oneTimePreKeys: List<OneTimePreKeyDto>,
)

@Serializable
data class KeyStatusResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("has_identity") val hasIdentity: Boolean,
    @SerialName("signed_pre_key_id") val signedPreKeyId: Int? = null,
    @SerialName("otpk_count") val otpkCount: Long = 0,
)

@Serializable
data class IdentityKeyResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
)

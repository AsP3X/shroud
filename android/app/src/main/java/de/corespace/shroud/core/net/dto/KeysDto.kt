@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

// Keys — iOS `Services/API/KeyBundleModels.swift`; server `routes/keys.rs`. api-realtime §5.3.
// Keys are standard Base64 with padding (what the server's `STANDARD` engine decodes).

/** `KeyBundleModels.swift:18-28`. */
@Serializable
data class SignedPreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

/** `KeyBundleModels.swift:30-38`. */
@Serializable
data class OneTimePreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
)

/** `PUT /keys/bundle` (`KeyBundleModels.swift:4-16`). */
@Serializable
data class PutKeyBundleRequest(
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_pre_key") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_pre_keys") val oneTimePreKeys: List<OneTimePreKeyDto>,
)

/** `GET /keys/status` (`KeysStatusResponse`, `KeyBundleModels.swift:40-52`). */
@Serializable
data class KeyStatusResponse(
    @SerialName("device_id") val deviceId: UUID,
    @SerialName("has_identity") val hasIdentity: Boolean,
    @SerialName("signed_pre_key_id") val signedPreKeyId: Int? = null,
    @SerialName("otpk_count") val otpkCount: Long = 0,
)

/** `GET /keys/identity/{user_id}` — identity only, no one-time key consumed (iOS `PeerIdentityResponse`, `KeyBundleModels.swift:101-113`). */
@Serializable
data class IdentityKeyResponse(
    @SerialName("user_id") val userId: UUID,
    @SerialName("device_id") val deviceId: UUID,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
)

/**
 * `GET /keys/bundle/{user_id}` — one device's bundle; consumes a one-time prekey on the server
 * (`KeyBundleModels.swift:54-70`). Unused by iOS today (api-realtime §4.0 row 19, opt).
 */
@Serializable
data class PeerKeyBundleResponse(
    @SerialName("user_id") val userId: UUID,
    @SerialName("device_id") val deviceId: UUID,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_pre_key") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_pre_key") val oneTimePreKey: OneTimePreKeyDto? = null,
)

/** One device entry of `GET /keys/bundles/{user_id}` (`KeyBundleModels.swift:73-87`). */
@Serializable
data class PeerDeviceBundle(
    @SerialName("device_id") val deviceId: UUID,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_pre_key") val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_pre_key") val oneTimePreKey: OneTimePreKeyDto? = null,
)

/** `GET /keys/bundles/{user_id}` multi-device fan-out (`KeyBundleModels.swift:90-98`). */
@Serializable
data class PeerKeyBundlesResponse(
    @SerialName("user_id") val userId: UUID,
    val bundles: List<PeerDeviceBundle>,
)

/** `POST /keys/otpk` — more one-time prekeys. */
@Serializable
data class PostOtpkRequest(@SerialName("one_time_pre_keys") val oneTimePreKeys: List<OneTimePreKeyDto>)

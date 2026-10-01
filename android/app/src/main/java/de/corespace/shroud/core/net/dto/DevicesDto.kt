@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Devices — iOS `Services/API/DeviceModels.swift`; server `routes/devices.rs`. api-realtime §5.2.

/**
 * One linked device on the account (`GET /devices`, `DeviceModels.swift:7-22`). A row outlives a
 * logout — the server keeps it so the next sign-in can reuse it. The server omits `sealed_name`
 * and `last_seen_at` when they are null (`DeviceModelsTests`).
 */
@Serializable
data class LinkedDeviceDto(
    val id: UUID,
    /** Base64, sealed by the account's devices; open with `DeviceNameSeal`. */
    @SerialName("sealed_name") val sealedName: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("last_seen_at") val lastSeenAt: Instant? = null,
    @SerialName("is_current") val isCurrent: Boolean,
)

/** `GET /devices` (`DevicesListResponse`, `DeviceModels.swift:24-26`). */
@Serializable
data class DevicesResponse(val devices: List<LinkedDeviceDto>)

/** `PUT /devices/{id}/name` (`DeviceModels.swift:29-35`). */
@Serializable
data class PutDeviceNameRequest(@SerialName("sealed_name") val sealedName: String)

/** Devices one account may have (`DevicesService.swift:10`). */
const val DEVICE_LIMIT = 5

@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Media — iOS `Services/API/MediaModels.swift:4-38`; server `routes/media.rs`. api-realtime §5.9.
// The blobs are sealed before upload; the server only ever sees ciphertext and its size.

/** `POST /media/uploads` (`MediaModels.swift:4-13`). */
@Serializable
data class CreateMediaUploadRequest(
    /** Exact sealed size: the server compares it with the uploaded body (1 … [MAX_SEALED_MEDIA_BYTES]). */
    @SerialName("size_bytes") val sizeBytes: Long,
    /** Always opaque: the sealed blob reveals nothing about its type. */
    @SerialName("content_type") val contentType: String? = "application/octet-stream",
)

/** `POST /media/uploads` answer (`MediaModels.swift:15-28`). */
@Serializable
data class CreateMediaUploadResponse(
    @SerialName("media_object_id") val mediaObjectId: UUID,
    /** Relative to the API base: `media/<id>/content`. */
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("expires_at") val expiresAt: Instant,
)

/** `POST /media/{id}/download` (`MediaModels.swift:30-38`). Clients read `GET media/{id}/content` instead. */
@Serializable
data class MediaDownloadResponse(
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("expires_at") val expiresAt: Instant,
)

/** Largest sealed blob the server takes: 2 GiB (`Services/Crypto/VideoMedia.swift:121`, server `media.rs` `MAX_MEDIA_BYTES`). */
const val MAX_SEALED_MEDIA_BYTES: Long = 2L * 1024 * 1024 * 1024

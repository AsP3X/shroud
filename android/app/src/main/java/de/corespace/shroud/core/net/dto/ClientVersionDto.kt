package de.corespace.shroud.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Client version — server `routes/client_version.rs`, `client_version.rs` (`ClientVersionCheck`).
// DTOs live in core/net/dto/ but keep the package de.corespace.shroud.core.net (api-realtime §5).

/**
 * `GET /client-version?platform=android&version=…`: whether a newer release is out. [status] is
 * `current`, `update_available` or `update_required`, kept as text so a status a newer server adds
 * still decodes (the checker reads it as current). [updateUrl] is the operator's link to the
 * release (an APK page, F-Droid, a website), null when none is set.
 */
@Serializable
data class ClientVersionDto(
    val status: String,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("update_url") val updateUrl: String? = null,
)

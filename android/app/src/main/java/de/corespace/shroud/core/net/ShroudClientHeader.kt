package de.corespace.shroud.core.net

import de.corespace.shroud.BuildConfig

/**
 * `X-Shroud-Client: android/<versionName>`, which names this app on every request to the Shroud
 * API — every REST call and media transfer of [ApiClient] and the `/api/v1/ws` upgrade of
 * `RealtimeClient` (server `client_version.rs` `require_supported_client`). Once the operator sets
 * `ANDROID_MIN_VERSION`, an older build, or one that sends no header, gets `426 UPDATE_REQUIRED`
 * ([ApiError.isUpdateRequired]); `GET /client-version` stays open to every build.
 *
 * Only ever sent to the API host: the link-preview fetcher and the Whisper model download build
 * their own requests on the shared `OkHttpClient`, so this is no interceptor.
 */
object ShroudClientHeader {
    const val NAME = "X-Shroud-Client"

    /** The platform part, also the `platform` of `GET /client-version`. */
    const val PLATFORM = "android"

    /** `versionName` (`apk.sh`'s `VERSION_NAME`), never `versionCode`, as the version check sends. */
    val value: String = forVersion(BuildConfig.VERSION_NAME)

    /**
     * `android/<versionName>`, keeping only visible ASCII: OkHttp refuses any other header byte,
     * and a request must never fail over how a build was named.
     */
    internal fun forVersion(versionName: String): String = "$PLATFORM/" + versionName.filter { it in '!'..'~' }
}

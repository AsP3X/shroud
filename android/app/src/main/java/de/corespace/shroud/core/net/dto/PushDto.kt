@file:OptIn(ExperimentalSerializationApi::class)

package de.corespace.shroud.core.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Push — UnifiedPush over the server's Web Push routes (plan decision record 1–3, §1.7.10;
// notifications-push §5.1.1 restricted to Web Push). Server `routes/push.rs`
// (`WebPushKeyResponse`, `WebSubscriptionRequest`); the web client sends the same subscription
// (`web/src/api/client.ts:533-541`). There are no FCM DTOs: Android never talks to Google's push service.

/**
 * `GET /push/web/key` (server `push.rs` `WebPushKeyResponse`). A 404 means the server has no Web
 * Push configured.
 */
@Serializable
data class WebPushKeyResponse(
    /** The VAPID public key (Base64url, uncompressed P-256) — the `REGISTER` intent's VAPID extra. */
    @SerialName("public_key") val publicKey: String,
)

/**
 * `PUT /push/web/subscription` — this phone's UnifiedPush subscription (server
 * `WebSubscriptionRequest`, the shape of a browser's `PushSubscription.toJSON()`).
 *
 * [client] tells the server this is an Android distributor endpoint rather than a browser's, so
 * it applies the Android host policy and sends rings with `Urgency: high` (X1-SRV-UP). It is
 * always written, whatever the Json's `encodeDefaults`. A 400 `VALIDATION_ERROR` means the
 * server refused the distributor's host.
 */
@Serializable
data class WebPushSubscriptionBody(
    /** The distributor's endpoint URL (from `NEW_ENDPOINT`). */
    val endpoint: String,
    val keys: Keys,
    @EncodeDefault val client: String = CLIENT_ANDROID,
) {
    /** RFC 8291 subscription keys, both Base64url without padding. */
    @Serializable
    data class Keys(
        /** The subscription's P-256 public key, uncompressed (65 bytes). */
        val p256dh: String,
        /** The 16-byte authentication secret. */
        val auth: String,
    )

    companion object {
        /** `web_push_subscriptions.client` for Android subscriptions (X1-SRV-UP). */
        const val CLIENT_ANDROID = "android"
    }
}

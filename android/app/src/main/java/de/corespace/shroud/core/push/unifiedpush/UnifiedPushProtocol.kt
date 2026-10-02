package de.corespace.shroud.core.push.unifiedpush

import java.security.MessageDigest

/**
 * UnifiedPush Android spec AND_3.1.0 (re-verified against
 * https://unifiedpush.org/developers/spec/android/ before these strings were fixed).
 *
 * A request that carries an extra the spec does not list MUST be ignored, so REGISTER sends only
 * [EXTRA_TOKEN], [EXTRA_VAPID], [EXTRA_MESSAGE] and, below API 34, [EXTRA_PI]. The plan's
 * `application` extra is the connector 3.3.5 name; AND_3.1.0 does not list it, so it is not sent.
 * API 34+ uses [FLAG_SHARE_IDENTITY] instead of `pi`. The dummy pending intent targets
 * [DUMMY_PI_PACKAGE].
 */
object UnifiedPushProtocol {
    const val SPEC = "AND_3.1.0"

    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
    const val ACTION_RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_ID = "id"
    const val EXTRA_VAPID = "vapid"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_PI = "pi"
    const val EXTRA_REASON = "reason"
    const val EXTRA_USE_DISTRIBUTOR = "useDistributor"

    const val REASON_INTERNAL_ERROR = "INTERNAL_ERROR"
    const val REASON_NETWORK = "NETWORK"
    const val REASON_ACTION_REQUIRED = "ACTION_REQUIRED"
    const val REASON_VAPID_REQUIRED = "VAPID_REQUIRED"

    /** Short description the distributor may show (spec MAY extra, at most 100 bytes). */
    const val REGISTRATION_MESSAGE = "Shroud"

    /** Pending-intent target below API 34 (`org.unifiedpush.dummy_app`). */
    const val DUMMY_PI_PACKAGE = "org.unifiedpush.dummy_app"

    const val DUMMY_PI_ACTION = "org.unifiedpush.android.dummy_app"

    /** VAPID public key: uncompressed P-256, base64url, 87 characters, no padding. */
    val VAPID_PATTERN = Regex("^[A-Za-z0-9_-]{87}$")

    /** Extras REGISTER is allowed to carry at [sdkInt]. `pi` is required below API 34 only. */
    fun registerExtraNames(sdkInt: Int): Set<String> = buildSet {
        add(EXTRA_TOKEN)
        add(EXTRA_VAPID)
        add(EXTRA_MESSAGE)
        if (sdkInt < 34) add(EXTRA_PI)
    }

    /**
     * Constant-time match of the stored connection token. A missing or different token is not ours:
     * the receiver drops that intent (spec: an unknown token is ignored).
     */
    fun tokenMatches(stored: String?, presented: String?): Boolean {
        if (stored.isNullOrEmpty() || presented == null) return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }

    /** VAPID extra value, or null when [raw] is not an 87-character base64url P-256 key. */
    fun vapidExtra(raw: String): String? {
        val cleaned = raw.trim().trimEnd('=')
        return cleaned.takeIf { VAPID_PATTERN.matches(it) }
    }
}

/** One distributor → app broadcast, already pulled off the [android.content.Intent]. */
data class DistributorEvent(
    val action: String?,
    val token: String?,
    val endpoint: String?,
    val bytes: ByteArray?,
    val id: String?,
    val reason: String?,
    val useDistributor: String?,
)

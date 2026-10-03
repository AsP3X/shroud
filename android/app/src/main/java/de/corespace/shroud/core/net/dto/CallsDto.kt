@file:UseSerializers(UuidSerializer::class, InstantIsoSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.ApiTime
import de.corespace.shroud.core.net.wire.InstantIsoSerializer
import de.corespace.shroud.core.net.wire.StringOrListSerializer
import de.corespace.shroud.core.net.wire.UuidSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Calls — iOS `Services/API/CallModels.swift:4-154`; server `routes/calls.rs`, flow in
// `docs/calls.md`. api-realtime §5.10. The server sees call metadata only — no media, no keys.

/** Call modality (`CallModels.swift:4-7`); an unknown wire value reads as [Voice] (`:92-94`). */
enum class CallModality(val wire: String) {
    Voice("voice"),
    Video("video"),
    ;

    companion object {
        fun of(wire: String): CallModality = entries.firstOrNull { it.wire == wire } ?: Voice
    }
}

/** Server call status (`CallModels.swift:10-18`); an unknown wire value reads as [Ended] (`:96-98`). */
enum class CallStatus(val wire: String) {
    Ringing("ringing"),
    Active("active"),
    Ended("ended"),
    Rejected("rejected"),
    Busy("busy"),
    Missed("missed"),
    Cancelled("cancelled"),
    ;

    companion object {
        fun of(wire: String): CallStatus = entries.firstOrNull { it.wire == wire } ?: Ended
    }
}

/** `signal_type` values (`Services/Calls/CallSignal.swift:49-55`; server `calls.rs` allow-list). */
object CallSignalType {
    const val SDP_OFFER = "sdp_offer"
    const val SDP_ANSWER = "sdp_answer"
    const val ICE_CANDIDATE = "ice_candidate"
    const val RENEGOTIATE = "renegotiate"
    const val MEDIA_STATE = "media_state"
}

/** Protocol 2: media is negotiated after the answer (`CallModels.swift:20, 33-37`; `docs/calls.md`). */
const val CALL_PROTOCOL = 2

/** `POST /calls` (`CallModels.swift:21-38`). `protocol` is always written. */
@Serializable
data class CreateCallRequest(
    @SerialName("peer_user_id") val peerUserId: UUID,
    /** [CallModality.wire]. */
    val modality: String,
    @EncodeDefault @SerialName("protocol") val callProtocol: Int = CALL_PROTOCOL,
)

/**
 * `{}` — the body of `POST /calls/{id}/accept` (`AcceptCallRequest`, `CallModels.swift:40`) and of
 * the contact request accept/reject calls: the server answers 415 without a JSON body.
 */
@Serializable
object EmptyBody

/** `POST /calls/{id}/signal` — a sealed signal (`CallModels.swift:43-51`). */
@Serializable
data class CallSignalRequest(
    /** One of [CallSignalType]. */
    @SerialName("signal_type") val signalType: String,
    /** Sealed by `CallCrypto`; opaque to the server. */
    val payload: String,
)

/**
 * Call metadata from the server (`CallModels.swift:54-103`) — `POST /calls`, `GET /calls`,
 * `GET /calls/{id}`, the accept/reject/hangup/heartbeat answers and the `call.*` socket events.
 *
 * [createdAtWire] keeps the server's text: it is the `before` cursor of `GET /calls` (web passes
 * the raw string, `web/src/api/client.ts:599-605`; api-realtime §2.7). [createdAt] is parsed from
 * it once; an unparsable date fails the decode as on iOS.
 */
@Serializable
data class CallDto(
    val id: UUID,
    @SerialName("caller_user_id") val callerUserId: UUID,
    @SerialName("caller_device_id") val callerDeviceId: UUID,
    /** Null once that account is deleted. */
    @SerialName("caller_username") val callerUsername: String? = null,
    @SerialName("caller_deleted") val callerDeleted: Boolean = false,
    @SerialName("callee_user_id") val calleeUserId: UUID,
    /** Absent until a device of the callee answered. */
    @SerialName("callee_device_id") val calleeDeviceId: UUID? = null,
    /** Null once that account is deleted. */
    @SerialName("callee_username") val calleeUsername: String? = null,
    @SerialName("callee_deleted") val calleeDeleted: Boolean = false,
    /** [CallModality.wire]; read through [callModality]. */
    val modality: String,
    /** [CallStatus.wire]; read through [callStatus]. */
    val status: String,
    /** `hangup`, `declined`, `timeout`, … (server `calls.rs`). */
    @SerialName("ended_reason") val endedReason: String? = null,
    @SerialName("protocol") val callProtocol: Int? = null,
    /** The server's `created_at` text, verbatim. */
    @SerialName("created_at") val createdAtWire: String,
    @SerialName("answered_at") val answeredAt: Instant? = null,
    @SerialName("ended_at") val endedAt: Instant? = null,
    /**
     * Only in `GET /calls/{id}` and heartbeat answers to one of the two devices in a live call: the
     * other device's latest sealed `media_state`, so a camera switch missed in a socket gap is
     * caught up (`CallModels.swift:70-73`).
     */
    @SerialName("peer_media_state") val peerMediaState: PeerMediaStateDto? = null,
) {
    /** [createdAtWire] parsed once (all its digits kept). */
    @Transient
    val createdAt: Instant = ApiTime.parse(createdAtWire)
        ?: throw SerializationException("Invalid ISO-8601 date: $createdAtWire")

    /** `CallModels.swift:91-94`. */
    val callModality: CallModality get() = CallModality.of(modality)

    /** `CallModels.swift:95-98`. */
    val callStatus: CallStatus get() = CallStatus.of(status)

    /** Ringing or active (`CallModels.swift:99-102`). */
    val isLive: Boolean get() = status == CallStatus.Ringing.wire || status == CallStatus.Active.wire
}

/** A sealed `media_state` signal the server kept for the other device in the call (`CallModels.swift:106-115`). */
@Serializable
data class PeerMediaStateDto(
    @SerialName("from_device_id") val fromDeviceId: UUID,
    val payload: String,
)

/** `GET /calls` (`CallModels.swift:117-120`). */
@Serializable
data class CallListResponse(val calls: List<CallDto>)

/**
 * One ICE server of `GET /calls/ice-servers` (`CallModels.swift:122-146`). The server may send
 * `urls` as one string or as an array; anything else (or no key) reads as an empty list, as iOS
 * falls back to `[]`.
 */
@Serializable
data class IceServerDto(
    @Serializable(with = StringOrListSerializer::class) val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

/** `CallModels.swift:148-154`. */
@Serializable
data class IceServersResponse(@SerialName("ice_servers") val iceServers: List<IceServerDto>)

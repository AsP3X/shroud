package de.corespace.shroud.core.calls.media

import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.bitrate
import de.corespace.shroud.core.calls.keepsResolution
import de.corespace.shroud.core.net.IceServerDto
import kotlin.math.abs
import kotlin.math.min

/**
 * Section order, send direction, relay choice and sender bitrates — the part of the media engine
 * that does not need a peer connection (calls §5, §7; iOS `CallMediaEngine` sections and
 * `tuneSenders`). WebRTC's own objects are decided with these rules.
 *
 * A call's first audio section is the microphone and its first video the camera; the second of
 * each is the shared screen's sound and picture. Both ends see that order, so neither names them.
 */

const val CAMERA_TRACK_ID = "shroud-video"
const val SCREEN_TRACK_ID = "shroud-screen"

/** `webrtc::Priority`: the audio device module's default network priority is low (1). */
const val NETWORK_PRIORITY_LOW = 1
const val NETWORK_PRIORITY_MEDIUM = 2
const val NETWORK_PRIORITY_HIGH = 3

const val DEGRADE_MAINTAIN_RESOLUTION = "MAINTAIN_RESOLUTION"
const val DEGRADE_BALANCED = "BALANCED"

enum class MediaSection { MIC, CAMERA, SCREEN, SCREEN_SOUND }

/** What ICE may use. Relay only when this call asked for it and the server actually offered a TURN. */
enum class IceTransportChoice { ALL, RELAY }

data class CaptureChoice(val width: Int, val height: Int, val maxFps: Int)

data class SenderTune(
    val maxBitrateBps: Int,
    val maxFramerate: Int?,
    val scaleResolutionDownBy: Double?,
    val networkPriority: Int,
    val bitratePriority: Double,
    /** Null leaves the sender's degradation preference alone (speech has none). */
    val degradation: String?,
)

data class SdpMLine(val kind: String, val section: MediaSection?, val direction: String, val canSend: Boolean)

/** A `turn:` or `turns:` URL, whatever the case (`CallMediaEngine.offersRelay`). */
fun offersRelay(servers: List<IceServerDto>): Boolean = servers.any { server ->
    server.urls.any { url ->
        val lower = url.lowercase()
        lower.startsWith("turn:") || lower.startsWith("turns:")
    }
}

/**
 * Relay candidates only when [relayOnly] was asked and [hasTurn] is true. Without a relay, forcing
 * it would make a connection that can never connect (ME:186-187).
 */
fun iceTransportChoice(relayOnly: Boolean, hasTurn: Boolean): IceTransportChoice =
    if (relayOnly && hasTurn) IceTransportChoice.RELAY else IceTransportChoice.ALL

/**
 * The section at [index] among sections of one kind. Null past the second: an older offer has no
 * screen, and a voice offer from an older app has no camera either.
 */
fun sectionAt(index: Int, video: Boolean): MediaSection? = when {
    video && index == 0 -> MediaSection.CAMERA
    video && index == 1 -> MediaSection.SCREEN
    !video && index == 0 -> MediaSection.MIC
    !video && index == 1 -> MediaSection.SCREEN_SOUND
    else -> null
}

/**
 * Whether our side can put media on a section (ME:119-124).
 *
 * Before a remote description exists, the negotiated direction is meaningless (it is often
 * inactive), so the direction we asked for counts. Afterwards the negotiated one does: an older
 * peer answers the camera or the screen `recvonly` or `inactive`, and then we cannot send.
 */
fun canSend(configured: String, negotiated: String?, hasRemoteDescription: Boolean, stopped: Boolean): Boolean {
    if (stopped) return false
    val direction = if (!hasRemoteDescription || negotiated == null) configured else negotiated
    return direction == "sendrecv" || direction == "sendonly"
}

/**
 * A section we did not add ourselves would otherwise be answered receive-only, and a camera or a
 * screen switched on later would need a new offer (ME:440-447). `recvonly` becomes both ways;
 * `inactive` becomes send-only.
 */
fun bothWays(direction: String): String = when (direction) {
    "recvonly" -> "sendrecv"
    "inactive" -> "sendonly"
    else -> direction
}

/**
 * Media sections in offer order, with the direction attribute that applies to each (`sendrecv`
 * when the section names none). Used to lock the same rules the transceivers follow.
 */
fun sdpMLines(sdp: String): List<SdpMLine> {
    val lines = sdp.replace("\r\n", "\n").split("\n")
    var audio = 0
    var video = 0
    val out = ArrayList<SdpMLine>()
    var kind: String? = null
    var section: MediaSection? = null
    var direction = "sendrecv"
    fun flush() {
        val current = kind ?: return
        out += SdpMLine(current, section, direction, canSend(direction, direction, hasRemoteDescription = true, stopped = false))
        kind = null
    }
    for (line in lines) {
        val lower = line.lowercase()
        if (lower.startsWith("m=")) {
            flush()
            kind = when {
                lower.startsWith("m=audio ") || lower == "m=audio" -> "audio"
                lower.startsWith("m=video ") || lower == "m=video" -> "video"
                else -> null
            }
            section = when (kind) {
                "audio" -> sectionAt(audio, video = false).also { audio++ }
                "video" -> sectionAt(video, video = true).also { video++ }
                else -> null
            }
            direction = "sendrecv"
            continue
        }
        if (kind == null) continue
        when (lower.trim()) {
            "a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive" -> direction = lower.trim().removePrefix("a=")
        }
    }
    flush()
    return out
}

/** The capture size closest to 1280×720 (ME:1060-1063). */
fun nearestCapture(formats: List<CaptureChoice>): CaptureChoice? =
    formats.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }

/**
 * Frames per second for `startCapture`. WebRTC stores a format's max rate in milli-fps (30000 for
 * 30) when the camera reports fps, and plain fps otherwise. Never above 30 (ME:1062).
 */
fun captureFps(maxRate: Int): Int {
    val fps = if (maxRate >= 1000) maxRate / 1000 else maxRate
    return min(30, fps).coerceAtLeast(1)
}

/**
 * Speech near 32 kbps first, the camera near 1.2 Mbps (a tile's worth while the screen is up),
 * the screen at [quality] (ME:608-642).
 */
fun senderTune(trackId: String, kind: String, screenOn: Boolean, quality: ScreenShareQuality): SenderTune? {
    if (trackId == SCREEN_TRACK_ID) {
        return SenderTune(
            maxBitrateBps = quality.bitrate,
            maxFramerate = quality.frameRate,
            scaleResolutionDownBy = null,
            networkPriority = NETWORK_PRIORITY_MEDIUM,
            bitratePriority = 2.0,
            degradation = if (quality.keepsResolution) DEGRADE_MAINTAIN_RESOLUTION else DEGRADE_BALANCED,
        )
    }
    if (kind == "audio") {
        return SenderTune(
            maxBitrateBps = 32_000,
            maxFramerate = null,
            scaleResolutionDownBy = null,
            networkPriority = NETWORK_PRIORITY_HIGH,
            bitratePriority = 4.0,
            degradation = null,
        )
    }
    if (kind == "video") {
        return SenderTune(
            maxBitrateBps = if (screenOn) 350_000 else 1_200_000,
            maxFramerate = if (screenOn) 15 else 30,
            scaleResolutionDownBy = if (screenOn) 2.0 else 1.0,
            networkPriority = NETWORK_PRIORITY_LOW,
            bitratePriority = 1.0,
            degradation = DEGRADE_BALANCED,
        )
    }
    return null
}

/**
 * A screen frame is due when none has gone out, or the last one was at least `0.9 / fps` seconds
 * ago (the uploader's slack, calls §7.2).
 */
fun screenFrameDue(nowNs: Long, lastSentNs: Long, fps: Int): Boolean {
    if (fps <= 0) return false
    if (lastSentNs == 0L) return true
    val minGapNs = (900_000_000.0 / fps).toLong()
    return nowNs - lastSentNs >= minGapNs
}

/** A still screen repeats its last frame once nothing has gone out for 450 ms (calls §7.2). */
fun screenRepeatDue(nowNs: Long, lastSentNs: Long): Boolean =
    lastSentNs != 0L && nowNs - lastSentNs >= 450_000_000L

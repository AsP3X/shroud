package de.corespace.shroud.core.calls.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How sharp our camera goes out, step by step as the link allows (docs/calls.md, "Camera
 * quality"; iOS `CallVideoQuality`, web `videoQuality.ts`). The camera is opened at up to 1080p;
 * the encoder sends one rung of the ladder below, picked every two seconds from the sender's
 * stats. An encoder short of bits on a link whose bandwidth estimate is below the rung steps down
 * after two readings in a row, as far as the estimate needs at once; so does loss, one rung.
 * Clean readings step up one rung at a time after a stretch of them, a longer one each time an
 * upgrade did not hold. WebRTC keeps adapting within a rung on its own.
 *
 * Going up does not wait for the estimate to show room: while the camera sends less than the
 * link could carry, the estimate only grows as far as what is sent, so it would never show it. A
 * higher cap makes WebRTC probe the link instead, and a try the link cannot carry steps back down.
 *
 * The iPhone, the web and Android run the same ladder with the same numbers; each side decides
 * only what it sends, so nothing goes on the wire. No WebRTC here, so the JVM tests drive it.
 */

data class CameraRung(
    val name: String,
    /** The picture's longer side, in pixels (1920 for 1080p, portrait or landscape). */
    val long: Int,
    val fps: Int,
    /** The most the encoder may use, bits per second. */
    val maxBitrate: Int,
    /** The least the link must have room for to keep this rung. */
    val minBitrate: Int,
)

val CAMERA_LADDER: List<CameraRung> = listOf(
    CameraRung("180p", long = 320, fps = 15, maxBitrate = 150_000, minBitrate = 0),
    CameraRung("270p", long = 480, fps = 20, maxBitrate = 300_000, minBitrate = 150_000),
    CameraRung("360p", long = 640, fps = 30, maxBitrate = 600_000, minBitrate = 300_000),
    CameraRung("540p", long = 960, fps = 30, maxBitrate = 1_200_000, minBitrate = 600_000),
    CameraRung("720p", long = 1280, fps = 30, maxBitrate = 2_200_000, minBitrate = 1_100_000),
    CameraRung("1080p", long = 1920, fps = 30, maxBitrate = 3_800_000, minBitrate = 2_000_000),
)

/** Where a call starts: 720p, until the link has shown what it can carry. */
const val CAMERA_START = 4

/** Our camera while our screen is shared: they show it as a small tile, so a thumbnail's worth. */
val CAMERA_TILE = CameraRung("tile", long = 640, fps = 15, maxBitrate = 350_000, minBitrate = 0)

/**
 * The camera as a tile while our screen is shared: a thumbnail's worth, and never more than its
 * rung, so a camera the link had pushed below the tile stays there while the screen competes.
 */
fun tileOf(rung: CameraRung): CameraRung = CameraRung(
    name = CAMERA_TILE.name,
    long = min(CAMERA_TILE.long, rung.long),
    fps = min(CAMERA_TILE.fps, rung.fps),
    maxBitrate = min(CAMERA_TILE.maxBitrate, rung.maxBitrate),
    minBitrate = 0,
)

/** How often the stats are read. */
const val QUALITY_SAMPLE_MS = 2_000L

/** Kept off the camera's share of the link: speech, and RTCP. */
private const val AUDIO_RESERVE_BPS = 50_000.0

/** Readings in a row that step down. */
private const val DOWN_AFTER = 2

/** Readings in a row that step up, at first and after an upgrade held. */
private const val UP_AFTER = 4

/** The most an upgrade that keeps failing waits (about a minute). */
private const val UP_AFTER_MAX = 32

/** An upgrade held this many readings: the next one waits [UP_AFTER] again. */
private const val UP_PROVEN = 15

/** Readings ignored at the start, while the estimate ramps up from WebRTC's 300 kbps. */
private const val START_SETTLE = 3

/** Readings ignored after a change or a pause, while the link and the encoder settle. */
private const val SETTLE = 2

/** Loss that steps down, and the most that lets a step up. */
private const val LOSS_DOWN = 0.10
private const val LOSS_UP = 0.03

enum class QualityLimitation { NONE, CPU, BANDWIDTH, OTHER }

/** One reading of the camera's sender. Null where the stats have no such value. */
data class CameraSample(
    /** The bandwidth estimate for the whole link (`availableOutgoingBitrate`), bits per second. */
    val estimate: Double?,
    /** What the encoder says holds it back (`qualityLimitationReason`). */
    val limitation: QualityLimitation?,
    /** The share of our packets the other side lost lately (`fractionLost`), 0…1. */
    val loss: Double?,
)

/** A rung's pixels: its longer side by its shorter, 16:9 (1920×1080 for 1080p). */
fun rungPixels(rung: CameraRung): Long = rung.long.toLong() * Math.round(rung.long * 9 / 16.0)

/** A picture's pixels, 0 when either side is unknown or not positive. */
private fun pixelsOf(size: FrameSize?): Long =
    if (size == null || size.width <= 0 || size.height <= 0) 0L else size.width.toLong() * size.height

/**
 * The top rung for a picture of [size]: the highest whose pixels it has, give or take a quarter.
 * By pixels rather than by the longer side, since a framed picture takes the other side's shape
 * ("Framing and Center Stage"): a tall 886×1920 cut has 1080p's pixels near enough, and a squat
 * 1080×810 one 720p's, though its longer side is only 1080. Unknown: [CAMERA_START].
 */
fun ceilingFor(size: FrameSize?): Int {
    val pixels = pixelsOf(size)
    if (pixels <= 0) return CAMERA_START
    var top = 0
    CAMERA_LADDER.forEachIndexed { index, rung -> if (rungPixels(rung) <= pixels * 1.25) top = index }
    return top
}

/** The highest rung whose minimum fits [budget]. */
private fun fitting(budget: Double): Int {
    var top = 0
    CAMERA_LADDER.forEachIndexed { index, rung -> if (rung.minBitrate <= budget) top = index }
    return top
}

/**
 * The camera's rung for one call; [sample] moves it. The link's rung (`index`) is kept apart from
 * the picture's top rung (`ceiling`): a picture that turns smaller for a while (their view turned
 * sideways) holds the rung down only while it lasts, and the link's rung comes back with it.
 * Not thread-safe: the engine uses it on main.
 */
class CameraQuality(capture: FrameSize? = null) {
    private var index = CAMERA_START
    private var ceiling = ceilingFor(capture)
    private var low = 0
    private var high = 0
    private var settle = START_SETTLE
    private var upAfter = UP_AFTER

    /** Readings since the last step up, until it has held. */
    private var sinceUp: Int? = null

    /** The rung that goes out: the link's, no higher than the picture's top rung. */
    val rung: CameraRung get() = CAMERA_LADDER[rungIndex]

    val rungIndex: Int get() = min(index, ceiling)

    /** The picture going out has a new size (another camera, their view): its top rung. True when the rung changed. */
    fun setCapture(size: FrameSize?): Boolean {
        if (pixelsOf(size) <= 0) return false
        val before = rungIndex
        ceiling = ceilingFor(size)
        return rungIndex != before
    }

    /**
     * The camera went off, out as a tile, or the link is reconnecting: the next readings start
     * counting afresh. A call that has not been read yet keeps its longer opening settle.
     */
    fun pause() {
        low = 0
        high = 0
        settle = max(settle, SETTLE)
    }

    /** One reading; true when the rung changed. */
    fun sample(sample: CameraSample): Boolean {
        sinceUp?.let { counted ->
            val next = counted + 1
            sinceUp = next
            if (next > UP_PROVEN) {
                sinceUp = null
                upAfter = UP_AFTER
            }
        }
        if (settle > 0) {
            settle -= 1
            return false
        }
        val current = rungIndex
        val budget = sample.estimate?.let { it - AUDIO_RESERVE_BPS }
        // The budget of an encoder short of bits on a link whose estimate is below the rung.
        val starved = budget?.takeIf { sample.limitation == QualityLimitation.BANDWIDTH && it < rung.minBitrate }
        val lossy = sample.loss != null && sample.loss >= LOSS_DOWN

        var down: Int? = null
        // As far down as the estimate needs, at once; loss steps down one.
        if (starved != null) down = fitting(starved)
        if (lossy) down = max(0, min(down ?: current, current - 1))
        if (down != null && down < current) {
            high = 0
            low += 1
            if (low < DOWN_AFTER) return false
            // An upgrade that did not hold: the next one waits twice as long.
            if (sinceUp != null) upAfter = min(upAfter * 2, UP_AFTER_MAX)
            sinceUp = null
            move(down)
            return true
        }
        low = 0

        val clean = current < ceiling &&
            sample.limitation != QualityLimitation.CPU &&
            sample.limitation != QualityLimitation.BANDWIDTH &&
            (sample.loss == null || sample.loss < LOSS_UP)
        if (!clean) {
            high = 0
            return false
        }
        high += 1
        if (high < upAfter) return false
        move(current + 1)
        sinceUp = 0
        return true
    }

    private fun move(to: Int) {
        index = to
        low = 0
        high = 0
        settle = SETTLE
    }
}

/**
 * What the encoder is told for a rung: its bitrate, its frame rate, and how far to shrink the
 * picture to the rung's pixels (any shape keeps its shape and gets the rung's pixel count).
 */
data class CameraEncoding(val maxBitrate: Int, val maxFramerate: Int, val scaleResolutionDownBy: Double)

/** The encoding for [rung] from a picture of [capture] (null or 0 when unknown: sent as it is). */
fun cameraEncoding(rung: CameraRung, capture: FrameSize? = null): CameraEncoding {
    val pixels = pixelsOf(capture)
    return CameraEncoding(
        maxBitrate = rung.maxBitrate,
        maxFramerate = rung.fps,
        scaleResolutionDownBy = if (pixels > 0) max(1.0, sqrt(pixels.toDouble() / rungPixels(rung))) else 1.0,
    )
}

/** A finite number, whatever boxed type the stats carry it as (Double, Long, BigInteger…). */
private fun num(value: Any?): Double? {
    val number = (value as? Number)?.toDouble() ?: return null
    return if (number.isFinite()) number else null
}

/**
 * The camera's reading from its sender's stats (`getStats(sender)`): its `outbound-rtp`, the
 * `remote-inbound-rtp` the other side reports for it, and the link's selected candidate pair.
 * Null while nothing goes out yet (no `outbound-rtp`).
 */
fun readCameraSample(rows: Collection<StatRow>): CameraSample? {
    val byId = rows.associateBy { it.id }
    val outbound = rows.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] != "audio" } ?: return null
    val remote = rows.firstOrNull { it.type == "remote-inbound-rtp" && it.members["kind"] != "audio" }
    val transport = rows.firstOrNull { it.type == "transport" }
    val selected = transport?.let { byId[it.members["selectedCandidatePairId"]] }
    val pair = selected
        ?: rows.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        ?: rows.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true }
    val limitation = when (outbound.members["qualityLimitationReason"]) {
        "none" -> QualityLimitation.NONE
        "cpu" -> QualityLimitation.CPU
        "bandwidth" -> QualityLimitation.BANDWIDTH
        "other" -> QualityLimitation.OTHER
        else -> null
    }
    return CameraSample(
        estimate = num(pair?.members?.get("availableOutgoingBitrate")),
        limitation = limitation,
        loss = num(remote?.members?.get("fractionLost")),
    )
}

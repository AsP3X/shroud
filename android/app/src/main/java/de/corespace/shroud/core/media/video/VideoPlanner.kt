package de.corespace.shroud.core.media.video

import de.corespace.shroud.core.crypto.MediaCrypto
import kotlin.math.abs

/**
 * What a video send will be: passthrough or the size, bitrate and frame rate of the re-encode
 * (`VideoMedia.previewPlan`, `ios/shroud/Services/Crypto/VideoMedia.swift:478-555`; media-voice-links
 * §6.3, conversation-compose-media §15.8). Pure: the compose screen calls [previewPlan] on every
 * trim, mute and quality change, and the encoder (`VideoEncoder`) exports exactly the plan it
 * returns (media D6: no presets or size estimates on Android, iOS's `exportAtPlanBitrate` model).
 *
 * Ported exactly, with two deliberate rules:
 * - **Rounding.** Swift's `.rounded()` is half away from zero; every `round` here is [Math.round]
 *   (half up, the same for these positive values), never Kotlin's half-even `round` — a 333 px edge
 *   becomes 334, as on iOS (media §6.3 "Rounding pitfall").
 * - **Codecs (media D7).** Passthrough additionally needs an H.264 video track and AAC or no audio
 *   (`VideoProbe.videoMime`/`audioMime`), because web recipients cannot play HEVC everywhere.
 */
object VideoPlanner {
    /**
     * Largest file before encryption (`VideoMedia.swift:117-119`): the server's 2 GiB sealed cap
     * minus a mebibyte for the AES-GCM tag and encoder overshoot.
     */
    const val MAX_PLAINTEXT_BYTES: Long = MediaCrypto.MAX_PLAINTEXT_BYTES

    // Same bitrate model as web `videoPlan.ts`, at an assumed 30 fps (`VideoMedia.swift:562-572`).
    private const val TARGET_BPP = 0.085
    private const val FLOOR_BPP = 0.028
    private const val MIN_VIDEO_BITRATE = 120_000.0
    private const val LONG_MIN_VIDEO_BITRATE = 40_000.0
    private const val LONG_AUDIO_BITRATE = 32_000.0
    private const val MAX_VIDEO_BITRATE = 3_200_000.0
    private const val ORIGINAL_MAX_VIDEO_BITRATE = 12_000_000.0
    private const val HEADROOM = 0.9
    private const val ASSUMED_FPS = 30.0

    /** A box the sheet may promise, best first (`VideoMedia.swift:574-609`). */
    private data class PlanBox(val long: Double, val short: Double, val tier: Int)

    private fun encodeBoxes(quality: VideoUploadQuality): List<PlanBox> = when (quality) {
        VideoUploadQuality.Original -> listOf(PlanBox(1920.0, 1080.0, 0))
        VideoUploadQuality.High -> listOf(
            PlanBox(1280.0, 720.0, 0),
            PlanBox(960.0, 540.0, 1),
            PlanBox(640.0, 480.0, 2),
            PlanBox(480.0, 270.0, 3),
            PlanBox(480.0, 270.0, 4),
        )
        VideoUploadQuality.Medium -> listOf(
            PlanBox(960.0, 540.0, 1),
            PlanBox(640.0, 480.0, 2),
            PlanBox(480.0, 270.0, 3),
            PlanBox(480.0, 270.0, 4),
        )
        VideoUploadQuality.Small -> listOf(
            PlanBox(640.0, 480.0, 2),
            PlanBox(480.0, 270.0, 3),
            PlanBox(480.0, 270.0, 4),
        )
    }

    /** Largest frame that may be sent unchanged; null means any size (`VideoMedia.swift:611-619`). */
    private fun passthroughLimit(quality: VideoUploadQuality): Pair<Int, Int>? = when (quality) {
        VideoUploadQuality.Original -> null
        VideoUploadQuality.High -> 1280 to 720
        VideoUploadQuality.Medium -> 960 to 540
        VideoUploadQuality.Small -> 640 to 480
    }

    /**
     * Size and resolution the compose sheet shows, and what the encoder writes
     * (`VideoMedia.swift:480-555`). [fileExtension] defaults to the probe's (derived from the MIME
     * type on Android, media §6.3). Throws [VideoPlanError] with the sheet's exact copy when even the
     * smallest rung of [quality] cannot fit.
     */
    fun previewPlan(
        probe: VideoProbe,
        fileExtension: String = probe.fileExtension,
        trim: VideoTrim?,
        removeAudio: Boolean,
        quality: VideoUploadQuality,
    ): VideoOutgoingPlan {
        val shape = Shape.of(probe, trim, removeAudio)
        if (canPassthrough(probe, fileExtension, shape, quality)) {
            return VideoOutgoingPlan(
                width = shape.width,
                height = shape.height,
                estimatedBytes = maxOf(1L, probe.fileSizeBytes),
                resolutionLabel = resolutionLabel(shape.width, shape.height, keptSource = quality == VideoUploadQuality.Original),
                passthrough = true,
                videoBitrate = 0,
                audioBitrate = 0,
                frameRate = 0,
            )
        }
        return encodeLadder(probe, trim, removeAudio, quality).first()
    }

    /**
     * Every re-encode rung of [quality] that fits the budget, best first: element 0 is what
     * [previewPlan] promises when it does not pass through, element 1 the encoder's one retry when
     * the encoder overshoots the cap (media §6.4 step 7). Never empty: throws [VideoPlanError] like
     * [previewPlan] (`VideoMedia.swift:546-554`).
     */
    internal fun encodeLadder(
        probe: VideoProbe,
        trim: VideoTrim?,
        removeAudio: Boolean,
        quality: VideoUploadQuality,
    ): List<VideoOutgoingPlan> {
        val shape = Shape.of(probe, trim, removeAudio)
        val hasSound = probe.hasAudio && !removeAudio
        val budgetBits = (MAX_PLAINTEXT_BYTES.toDouble() * 8 * HEADROOM) / shape.duration
        val bitrateCap = if (quality == VideoUploadQuality.Original) ORIGINAL_MAX_VIDEO_BITRATE else MAX_VIDEO_BITRATE
        var fallbackFloor = MIN_VIDEO_BITRATE
        var fallbackAudio = 0.0
        val ladder = ArrayList<VideoOutgoingPlan>()
        for (box in encodeBoxes(quality)) {
            val longForm = box.tier >= 4
            val fitted = fit(shape.width, shape.height, box)
            val rateCap = if (longForm) 15.0 else if (box.tier >= 2) 30.0 else 60.0
            val rate = minOf(ASSUMED_FPS, rateCap)
            val pixels = (fitted.first * fitted.second).toDouble() * rate
            val minRate = if (longForm) LONG_MIN_VIDEO_BITRATE else MIN_VIDEO_BITRATE
            val audioBitrate = if (hasSound) (if (longForm) LONG_AUDIO_BITRATE else if (box.tier >= 2) 96_000.0 else 128_000.0) else 0.0
            val target = minOf(bitrateCap, maxOf(minRate, pixels * TARGET_BPP))
            val floor = maxOf(minRate, pixels * FLOOR_BPP)
            val budget = budgetBits - audioBitrate
            fallbackFloor = floor
            fallbackAudio = audioBitrate
            if (budget < floor) continue
            val videoBitrate = minOf(target, budget)
            val bytes = Math.round(((videoBitrate + audioBitrate) * shape.duration) / 8 * 1.02)
            val keptSource = quality == VideoUploadQuality.Original &&
                abs(maxOf(fitted.first, fitted.second) - maxOf(shape.width, shape.height)) <= 4 &&
                abs(minOf(fitted.first, fitted.second) - minOf(shape.width, shape.height)) <= 4
            ladder += VideoOutgoingPlan(
                width = fitted.first,
                height = fitted.second,
                estimatedBytes = maxOf(1L, bytes),
                resolutionLabel = resolutionLabel(fitted.first, fitted.second, keptSource),
                passthrough = false,
                videoBitrate = Math.round(videoBitrate).toInt(),
                audioBitrate = Math.round(audioBitrate).toInt(),
                frameRate = if (ASSUMED_FPS > rate + 1) rate.toInt() else 0,
            )
        }
        if (ladder.isNotEmpty()) return ladder
        // Swift `Int(x)` truncates toward zero, as `toLong()` does.
        val maxSeconds = ((MAX_PLAINTEXT_BYTES.toDouble() * 8 * HEADROOM) / (fallbackFloor + fallbackAudio)).toLong()
        val seconds = maxOf(1L, maxSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val clock = clock(seconds)
        throw VideoPlanError(
            if (quality == VideoUploadQuality.Original) {
                "Original quality won’t fit. Trim it to $clock or choose a lower quality."
            } else {
                "This video is too long to send. Trim it to $clock or less."
            },
            seconds,
        )
    }

    /**
     * The share of the 0…1 ring that export attempt [attempt] fills (`VideoMedia.swift:688-700`):
     * the first gets 0…0.9, each later one 90 % of what is left, so the ring only moves forward and
     * reaches 1 only once an export is accepted.
     */
    fun progressWindow(attempt: Int): ClosedFloatingPointRange<Double> {
        var lower = 0.0
        var span = 0.9
        repeat(maxOf(0, attempt)) {
            lower += span
            span = (1 - lower) * 0.9
        }
        return lower..(lower + span)
    }

    /** Duration, size and rewrite need, shared by both entry points (`VideoMedia.swift:487-491`). */
    private class Shape(val duration: Double, val width: Int, val height: Int, val mustRewrite: Boolean) {
        companion object {
            fun of(probe: VideoProbe, trim: VideoTrim?, removeAudio: Boolean): Shape {
                val full = trim?.isFullRange(probe.durationSeconds) ?: true
                val kept = if (full) probe.durationSeconds else (trim?.duration ?: probe.durationSeconds)
                return Shape(
                    duration = maxOf(0.1, kept),
                    width = maxOf(1, probe.width),
                    height = maxOf(1, probe.height),
                    mustRewrite = !full || removeAudio,
                )
            }
        }
    }

    /** `canPassthrough` (`VideoMedia.swift:621-636`) plus the Android codec rule (media D7). */
    private fun canPassthrough(probe: VideoProbe, fileExtension: String, shape: Shape, quality: VideoUploadQuality): Boolean {
        val size = probe.fileSizeBytes
        if (shape.mustRewrite || size <= 0 || size > MAX_PLAINTEXT_BYTES) return false
        val ext = fileExtension.lowercase()
        if (ext != "mp4" && ext != "m4v") return false
        if (!VideoFormats.isPassthroughCodec(probe.videoMime, probe.audioMime, probe.hasAudio)) return false
        val limit = passthroughLimit(quality) ?: return true
        return maxOf(shape.width, shape.height) <= limit.first && minOf(shape.width, shape.height) <= limit.second
    }

    /** Scales into [box] without stretching or upscaling, even edges (`VideoMedia.swift:638-646`). */
    private fun fit(width: Int, height: Int, box: PlanBox): Pair<Int, Int> {
        val w = maxOf(width, 1).toDouble()
        val h = maxOf(height, 1).toDouble()
        val landscape = w >= h
        val long = if (landscape) w else h
        val short = if (landscape) h else w
        val scale = minOf(1.0, box.long / maxOf(long, 1.0), box.short / maxOf(short, 1.0))
        return evenDimension(w * scale) to evenDimension(h * scale)
    }

    /** `max(2, round(n / 2) × 2)` with Swift rounding (`VideoMedia.swift:648-650`). */
    internal fun evenDimension(n: Double): Int = maxOf(2, Math.round(n / 2).toInt() * 2)

    /** "Original", a named rung within ±16 of the short edge, or "{short}p" (`VideoMedia.swift:652-658`). */
    internal fun resolutionLabel(width: Int, height: Int, keptSource: Boolean): String {
        if (keptSource) return "Original"
        val short = minOf(width, height)
        val named = listOf(1080 to "1080p", 720 to "720p", 540 to "540p", 480 to "480p", 360 to "360p", 270 to "270p")
        for ((edge, label) in named) if (abs(short - edge) <= 16) return label
        return "${short}p"
    }

    /** `m:ss`, minutes unpadded (`VideoMedia.swift:660-664`). */
    internal fun clock(seconds: Int): String {
        val total = maxOf(0, seconds)
        val rest = total % 60
        return "${total / 60}:${if (rest < 10) "0" else ""}$rest"
    }
}

package de.corespace.shroud.core.calls

import kotlin.math.floor
import kotlin.math.max

// The resolution and frame rate our shared screen goes out at, as Discord offers them — iOS
// `ScreenShareQuality` (`ios/ShroudShared/ScreenShareWire.swift:235-297`) and `wireSize`
// (`:216-223`), web `web/src/calls/screenQuality.ts`; calls §7.1. A resolution names the longest
// side of the picture, as a landscape 720p or 1080p video has it: a phone's tall screen at 1080p
// is 1920 pixels high. Source is the screen's own pixels. The raw values match the web's.

/** The offered frame rates (`ScreenShareQuality.FrameRate`, `ScreenShareWire.swift:263-269`). */
val ScreenShareQuality.Companion.FRAME_RATES: List<Int> get() = listOf(15, 30, 60)

/**
 * 1080p at 15 fps, what sharing did before there was a choice (`ScreenShareWire.swift:271-272`): a
 * phone screen is mostly still. The web defaults to 30 fps; Android follows iOS (calls §18).
 */
val ScreenShareQuality.Companion.Standard: ScreenShareQuality
    get() = ScreenShareQuality(ScreenShareQuality.Resolution.P1080, 15)

/**
 * The stored choice (`ScreenShareQuality.saved`, `CallController.swift:2155-2162`): each part falls
 * back to [Standard]'s alone when it is missing or not offered.
 */
fun ScreenShareQuality.Companion.fromStored(resolution: String?, frameRate: Int?): ScreenShareQuality = ScreenShareQuality(
    resolution = ScreenShareQuality.Resolution.entries.firstOrNull { it.raw == resolution } ?: Standard.resolution,
    frameRate = frameRate?.takeIf { it in FRAME_RATES } ?: Standard.frameRate,
)

/** "720p", "1080p", "Source" (`ScreenShareWire.swift:240-247`). */
val ScreenShareQuality.Resolution.label: String
    get() = when (this) {
        ScreenShareQuality.Resolution.P720 -> "720p"
        ScreenShareQuality.Resolution.P1080 -> "1080p"
        ScreenShareQuality.Resolution.Source -> "Source"
    }

/** The longest side in pixels; null for the screen's own size (`ScreenShareWire.swift:250-256`). */
val ScreenShareQuality.Resolution.maxSide: Int?
    get() = when (this) {
        ScreenShareQuality.Resolution.P720 -> 1280
        ScreenShareQuality.Resolution.P1080 -> 1920
        ScreenShareQuality.Resolution.Source -> null
    }

/** "15 fps" (`ScreenShareWire.swift:268`). */
fun screenFrameRateLabel(frameRate: Int): String = "$frameRate fps"

/** "1080p · 15 fps" (`ScreenShareWire.swift:274-275`). */
val ScreenShareQuality.label: String get() = "${resolution.label} · ${screenFrameRateLabel(frameRate)}"

/**
 * The most the screen may use, in bits per second (`ScreenShareWire.swift:277-292`; the same table
 * as the web's): more pixels and more frames need more to stay sharp.
 *
 * | | 15 fps | 30 fps | 60 fps |
 * | --- | --- | --- | --- |
 * | 720p | 1.2 M | 1.8 M | 2.8 M |
 * | 1080p | 1.8 M | 2.5 M | 4 M |
 * | Source | 3 M | 4.5 M | 6.5 M |
 *
 * A frame rate that is not offered reads as the offered one at or below it (15 at least).
 */
val ScreenShareQuality.bitrate: Int
    get() {
        val row = when (resolution) {
            ScreenShareQuality.Resolution.P720 -> intArrayOf(1_200_000, 1_800_000, 2_800_000)
            ScreenShareQuality.Resolution.P1080 -> intArrayOf(1_800_000, 2_500_000, 4_000_000)
            ScreenShareQuality.Resolution.Source -> intArrayOf(3_000_000, 4_500_000, 6_500_000)
        }
        return row[
            when {
                frameRate >= 60 -> 2
                frameRate >= 30 -> 1
                else -> 0
            },
        ]
    }

/**
 * Up to 30 fps a screen is text and edges: it keeps its sharpness and gives up frames
 * (`MAINTAIN_RESOLUTION`). At 60 it was asked for motion and gives up some of each (`BALANCED`)
 * (`ScreenShareWire.swift:294-296`).
 */
val ScreenShareQuality.keepsResolution: Boolean get() = frameRate < 60

/**
 * The size a frame goes out at (`ScreenShareWire.wireSize`, `ScreenShareWire.swift:216-223`): at
 * most [maxSide] on its longest side (null: the screen's own size), never enlarged, and even in
 * both directions (video encoders want that), at least 2. The default is 1080p's 1920.
 */
fun screenWireSize(width: Int, height: Int, maxSide: Int? = 1920): Pair<Int, Int> {
    val longest = max(width, height).toDouble()
    val scale = if (maxSide != null && longest > maxSide) maxSide / longest else 1.0
    fun even(value: Int): Int = max(2, floor(value * scale).toInt() and 1.inv())
    return even(width) to even(height)
}

/** The picture size and frame rate a running share should send for [quality]. */
data class ScreenOutput(val width: Int, val height: Int, val frameRate: Int)

/**
 * What goes out after a quality change. A frame-rate-only change keeps [width] and [height]
 * (the virtual display does not need a new size) and still carries the new [frameRate]: the
 * capturer and the encoder both have to take that rate, or a share that started at 15 fps
 * stays there.
 */
fun screenOutput(width: Int, height: Int, quality: ScreenShareQuality): ScreenOutput {
    val (wireW, wireH) = screenWireSize(width, height, quality.resolution.maxSide)
    return ScreenOutput(wireW, wireH, quality.frameRate)
}

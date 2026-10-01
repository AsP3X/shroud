package de.corespace.shroud.core.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the plan and progress cases of `VideoMediaEncodeTests`
 * (`ios/shroudTests/VideoMediaEncodeTests.swift:42-229`; media-voice-links §12.6), names kept.
 *
 * iOS's probes carry no codecs; Android's passthrough also needs H.264 and AAC or no audio
 * (media D7), so [probe] describes the iOS fixtures as what they are — H.264 with AAC sound.
 *
 * iOS-only, not ported (no export presets or size estimates on Android, media D6):
 * `skipsPresetsEstimatedOverTheMargin`, `keepsPresetsWithoutAnEstimate`,
 * `triesTheSmallestPresetWhenOnlyTheMarginRulesItOut`, `presetsFollowTheChosenQuality`,
 * `givesUpWhenEvenTheSmallestEstimateIsOverTheCap`. The encode cases run on a device
 * (`VideoEncoderDeviceTest`).
 */
class VideoPlannerTest {
    private fun probe(
        seconds: Double,
        width: Int,
        height: Int,
        bytes: Long,
        hasAudio: Boolean = true,
        ext: String = "mp4",
        videoMime: String? = "video/avc",
        audioMime: String? = if (hasAudio) "audio/mp4a-latm" else null,
    ) = VideoProbe(seconds, width, height, bytes, hasAudio, ext, videoMime, audioMime)

    private fun plan(
        probe: VideoProbe,
        ext: String,
        quality: VideoUploadQuality,
        trim: VideoTrim? = null,
        removeAudio: Boolean = false,
    ) = VideoPlanner.previewPlan(probe, fileExtension = ext, trim = trim, removeAudio = removeAudio, quality = quality)

    // MARK: - iOS vectors (VideoMediaEncodeTests.swift:42-210)

    @Test
    fun originalKeepsAFittingMp4() {
        val plan = plan(probe(8.0, 1920, 1080, 2_000_000), "mp4", VideoUploadQuality.Original)
        assertTrue(plan.passthrough)
        assertEquals("Original", plan.resolutionLabel)
        assertEquals(1920, plan.width)
        assertEquals(2_000_000L, plan.estimatedBytes)
    }

    @Test
    fun highScalesA1080pFileDownTo720() {
        val plan = plan(probe(8.0, 1920, 1080, 2_000_000), "mp4", VideoUploadQuality.High)
        assertFalse(plan.passthrough)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals("720p", plan.resolutionLabel)
    }

    @Test
    fun mediumAndSmallUseTheirBoxes() {
        val probe = probe(8.0, 1280, 720, 4_000_000)
        val medium = plan(probe, "mov", VideoUploadQuality.Medium)
        assertEquals(960, medium.width)
        assertEquals(540, medium.height)
        assertEquals("540p", medium.resolutionLabel)
        val small = plan(probe, "mov", VideoUploadQuality.Small)
        assertEquals(640, small.width)
        assertEquals(360, small.height)
        assertEquals("360p", small.resolutionLabel)
        // 4:3 cannot become 360p: the preset that Small exports with fits inside 640×480.
        val fourByThree = plan(probe(8.0, 1440, 1080, 4_000_000), "mov", VideoUploadQuality.Small)
        assertEquals(640, fourByThree.width)
        assertEquals(480, fourByThree.height)
        assertEquals("480p", fourByThree.resolutionLabel)
        val kept480 = plan(probe(8.0, 640, 480, 1_000_000), "mp4", VideoUploadQuality.Small)
        assertTrue(kept480.passthrough)
        assertEquals("480p", kept480.resolutionLabel)
    }

    @Test
    fun originalReencodeOf4KStopsAt1080() {
        val plan = plan(probe(8.0, 3840, 2160, 20_000_000), "mov", VideoUploadQuality.Original)
        assertFalse(plan.passthrough)
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertEquals("1080p", plan.resolutionLabel)
    }

    @Test
    fun muteAndTrimStopPassthrough() {
        val probe = probe(8.0, 1280, 720, 1_000_000)
        val muted = plan(probe, "mp4", VideoUploadQuality.High, removeAudio = true)
        assertFalse(muted.passthrough)
        val trimmed = plan(probe, "mp4", VideoUploadQuality.Original, trim = VideoTrim(1.0, 4.0))
        assertFalse(trimmed.passthrough)
        assertEquals(1280, trimmed.width)
        assertEquals(720, trimmed.height)
    }

    @Test
    fun halfHourFitsUnderTheCap() {
        val plan = plan(probe(30.0 * 60, 1920, 1080, 200_000_000), "mov", VideoUploadQuality.High)
        assertFalse(plan.passthrough)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals("720p", plan.resolutionLabel)
        assertTrue(plan.estimatedBytes <= VideoPlanner.MAX_PLAINTEXT_BYTES)
    }

    @Test
    fun aFourMinuteClipAtSmallStaysUnderHigh() {
        val probe = probe(240.0, 1920, 1080, 80_000_000)
        val small = plan(probe, "mov", VideoUploadQuality.Small)
        val high = plan(probe, "mov", VideoUploadQuality.High)
        assertTrue(maxOf(small.width, small.height) <= 640)
        assertTrue(maxOf(high.width, high.height) > 640)
        assertTrue(small.estimatedBytes <= VideoPlanner.MAX_PLAINTEXT_BYTES)
        assertTrue(high.estimatedBytes <= VideoPlanner.MAX_PLAINTEXT_BYTES)
    }

    @Test
    fun originalRefusesAClipThatCannotStayFullSize() {
        assertThrows(VideoPlanError::class.java) {
            plan(probe(4.0 * 3600, 3840, 2160, 80_000_000), "mov", VideoUploadQuality.Original)
        }
    }

    // MARK: - Progress (VideoMediaEncodeTests.swift:219-229)

    @Test
    fun progressWindowsMoveForwardAndStayUnderOne() {
        var previousUpper = 0.0
        for (attempt in 0 until 5) {
            val window = VideoPlanner.progressWindow(attempt)
            assertTrue(window.start >= previousUpper - 1e-9)
            assertTrue(window.endInclusive > window.start)
            assertTrue(window.endInclusive < 1)
            previousUpper = window.endInclusive
        }
        assertEquals(0.0..0.9, VideoPlanner.progressWindow(0))
    }

    // MARK: - Exact numbers of the same model (VideoMedia.swift:513-554; values from the Swift formulas)

    @Test
    fun planCarriesTheModelsBitratesAndSize() {
        val plan = plan(probe(8.0, 1920, 1080, 2_000_000), "mp4", VideoUploadQuality.High)
        // 1280×720 at 30 fps: target 0.085 bpp = 2 350 080 bps, AAC 128 kbps, 8 s × 1.02.
        assertEquals(2_350_080, plan.videoBitrate)
        assertEquals(128_000, plan.audioBitrate)
        assertEquals(2_527_642L, plan.estimatedBytes)
        assertEquals(0, plan.frameRate)
    }

    @Test
    fun smallRungsUseTheLowerAudioRate() {
        val small = plan(probe(8.0, 1280, 720, 4_000_000), "mov", VideoUploadQuality.Small)
        assertEquals(587_520, small.videoBitrate)
        assertEquals(96_000, small.audioBitrate)
        assertEquals(697_190L, small.estimatedBytes)
    }

    @Test
    fun aVeryLongClipDropsToTheLongFormRungAt15Fps() {
        val plan = plan(probe(100_000.0, 1920, 1080, 80_000_000), "mov", VideoUploadQuality.Small)
        assertEquals(480, plan.width)
        assertEquals(270, plan.height)
        assertEquals("270p", plan.resolutionLabel)
        assertEquals(15, plan.frameRate)
        assertEquals(32_000, plan.audioBitrate)
        assertEquals(122_543, plan.videoBitrate)
        assertEquals(1_970_427_396L, plan.estimatedBytes)
    }

    @Test
    fun portraitClipsFitTheBoxTurned() {
        val plan = plan(probe(8.0, 720, 1280, 4_000_000), "mov", VideoUploadQuality.Small)
        assertEquals(360, plan.width)
        assertEquals(640, plan.height)
        assertEquals("360p", plan.resolutionLabel)
    }

    @Test
    fun evenEdgesRoundHalfUpLikeSwift() {
        // 333 / 2 = 166.5 → 167 → 334 (Swift `.rounded()`); Kotlin's half-even round would give 332.
        val plan = plan(probe(8.0, 333, 333, 80_000_000), "mov", VideoUploadQuality.Small)
        assertEquals(334, plan.width)
        assertEquals(334, plan.height)
        assertEquals("334p", plan.resolutionLabel)
        assertEquals(334, VideoPlanner.evenDimension(333.0))
        assertEquals(2, VideoPlanner.evenDimension(0.4))
    }

    @Test
    fun planErrorsCarryTheSheetsCopyAndTheLongestClip() {
        val original = assertThrows(VideoPlanError::class.java) {
            plan(probe(4.0 * 3600, 3840, 2160, 80_000_000), "mov", VideoUploadQuality.Original)
        }
        assertEquals("Original quality won’t fit. Trim it to 137:45 or choose a lower quality.", original.message)
        assertEquals(8265, original.maxSeconds)

        val small = assertThrows(VideoPlanError::class.java) {
            plan(probe(1_000_000.0, 1920, 1080, 80_000_000), "mov", VideoUploadQuality.Small)
        }
        assertEquals("This video is too long to send. Trim it to 2980:03 or less.", small.message)
        assertEquals(178_803, small.maxSeconds)
    }

    @Test
    fun resolutionLabelsNameTheNearestRung() {
        assertEquals("Original", VideoPlanner.resolutionLabel(1920, 1080, keptSource = true))
        assertEquals("1080p", VideoPlanner.resolutionLabel(1920, 1080, keptSource = false))
        assertEquals("720p", VideoPlanner.resolutionLabel(1280, 704, keptSource = false))
        assertEquals("480p", VideoPlanner.resolutionLabel(640, 496, keptSource = false))
        assertEquals("240p", VideoPlanner.resolutionLabel(320, 240, keptSource = false))
        assertEquals("0:00", VideoPlanner.clock(0))
        assertEquals("1:05", VideoPlanner.clock(65))
        assertEquals("137:45", VideoPlanner.clock(8265))
    }

    @Test
    fun aFullRangeTrimIsNoTrim() {
        val probe = probe(8.0, 1280, 720, 1_000_000)
        assertTrue(plan(probe, "mp4", VideoUploadQuality.High, trim = VideoTrim(0.04, 7.96)).passthrough)
        assertFalse(plan(probe, "mp4", VideoUploadQuality.High, trim = VideoTrim(0.06, 8.0)).passthrough)
        // A real trim plans the kept length: 3 s instead of 8.
        val trimmed = plan(probe, "mp4", VideoUploadQuality.High, trim = VideoTrim(1.0, 4.0))
        assertEquals(Math.round((2_350_080.0 + 128_000.0) * 3 / 8 * 1.02), trimmed.estimatedBytes)
    }

    // MARK: - Android passthrough rules (media D7, §6.3)

    @Test
    fun anHevcMp4NeverPassesThrough() {
        val hevc = probe(8.0, 640, 360, 1_000_000, videoMime = "video/hevc")
        val plan = plan(hevc, "mp4", VideoUploadQuality.Original)
        assertFalse(plan.passthrough)
        assertEquals(640, plan.width)
        assertEquals("Original", plan.resolutionLabel)
    }

    @Test
    fun passthroughNeedsAacOrNoAudio() {
        assertTrue(plan(probe(8.0, 640, 360, 1_000_000, hasAudio = false), "mp4", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(probe(8.0, 640, 360, 1_000_000, audioMime = "audio/opus"), "mp4", VideoUploadQuality.Small).passthrough)
        // Sound without a known codec, or an unknown video codec, fails closed.
        assertFalse(plan(probe(8.0, 640, 360, 1_000_000, audioMime = null), "mp4", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(probe(8.0, 640, 360, 1_000_000, videoMime = null), "mp4", VideoUploadQuality.Small).passthrough)
    }

    @Test
    fun passthroughNeedsAnMp4ContainerOfAKnownSize() {
        val ok = probe(8.0, 640, 360, 1_000_000)
        assertTrue(plan(ok, "M4V", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(ok, "mov", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(ok, "", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(probe(8.0, 640, 360, 0), "mp4", VideoUploadQuality.Small).passthrough)
        assertFalse(plan(probe(8.0, 640, 360, VideoPlanner.MAX_PLAINTEXT_BYTES + 1), "mp4", VideoUploadQuality.Original).passthrough)
        // The extension defaults to the probe's.
        val viaProbe = VideoPlanner.previewPlan(ok, trim = null, removeAudio = false, quality = VideoUploadQuality.Small)
        assertTrue(viaProbe.passthrough)
    }

    @Test
    fun passthroughStaysInsideTheQualitysFrame() {
        val p720 = probe(8.0, 1280, 720, 1_000_000)
        assertTrue(plan(p720, "mp4", VideoUploadQuality.High).passthrough)
        assertFalse(plan(p720, "mp4", VideoUploadQuality.Medium).passthrough)
        val portrait = probe(8.0, 480, 640, 1_000_000)
        assertTrue(plan(portrait, "mp4", VideoUploadQuality.Small).passthrough)
        assertEquals("480p", plan(portrait, "mp4", VideoUploadQuality.Small).resolutionLabel)
    }

    // MARK: - The encoder's retry ladder (media §6.4 step 7)

    @Test
    fun theLadderStartsAtThePromisedPlanAndStepsDown() {
        val probe = probe(8.0, 1920, 1080, 2_000_000, ext = "mov")
        val ladder = VideoPlanner.encodeLadder(probe, trim = null, removeAudio = false, quality = VideoUploadQuality.High)
        assertEquals(plan(probe, "mov", VideoUploadQuality.High), ladder[0])
        assertEquals(listOf(1280 to 720, 960 to 540, 640 to 360, 480 to 270, 480 to 270), ladder.map { it.width to it.height })
        assertEquals(15, ladder.last().frameRate)
        assertEquals(1, VideoPlanner.encodeLadder(probe, null, false, VideoUploadQuality.Original).size)
    }

    @Test
    fun theLadderOfAPassthroughClipIsItsReencode() {
        val probe = probe(8.0, 640, 360, 1_000_000)
        assertTrue(plan(probe, "mp4", VideoUploadQuality.Small).passthrough)
        val first = VideoPlanner.encodeLadder(probe, null, false, VideoUploadQuality.Small).first()
        assertFalse(first.passthrough)
        assertEquals(640 to 360, first.width to first.height)
    }
}

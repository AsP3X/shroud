package de.corespace.shroud.ui.media.video

import android.net.Uri
import de.corespace.shroud.core.media.video.VideoPlanError
import de.corespace.shroud.core.media.video.VideoPlanner
import de.corespace.shroud.core.media.video.VideoProbe
import de.corespace.shroud.core.media.video.VideoTrim
import de.corespace.shroud.core.media.video.VideoUploadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.UUID

/**
 * The video compose screen's labels, Send gate and plans (`VideoComposeOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoComposeOverlay.swift:60-98, 497-549, 674-730`;
 * conversation-compose-media §15.2, §15.6, §15.7), on the planner vectors of `VideoMediaEncodeTests`
 * (§21.1): an 8 s 1080p 2 MB mp4 is "720p" at High and kept whole at Original; a 4 h 4K clip does
 * not fit at Original.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoComposeRulesTest {
    private fun probe(seconds: Double, width: Int, height: Int, bytes: Long, hasAudio: Boolean = true, ext: String = "mp4") =
        VideoProbe(seconds, width, height, bytes, hasAudio, ext, "video/avc", if (hasAudio) "audio/mp4a-latm" else null)

    private val short = ComposeClip(UUID.randomUUID(), probe(8.0, 1920, 1080, 2_000_000), Uri.parse("content://picker/1"))
    private val long4k = ComposeClip(UUID.randomUUID(), probe(4.0 * 3600, 3840, 2160, 80_000_000, ext = "mov"), Uri.parse("content://picker/2"))

    @Test
    fun selectionLabelShowsKeptLengthResolutionAndSize() {
        val trim = VideoComposeRules.fullTrim(short.probe)
        val plan = VideoComposeRules.plan(short.probe, trim, removeAudio = false, quality = VideoUploadQuality.High)
        assertEquals(2_527_642L, plan.getOrThrow().estimatedBytes)
        // Two spaces each side of the middle dot, ≈ before the size.
        assertEquals("0:08  ·  720p  ·  ≈2.5 MB", VideoComposeRules.selectionLabel(trim, plan, Locale.US))
        val original = VideoComposeRules.plan(short.probe, trim, removeAudio = false, quality = VideoUploadQuality.Original)
        assertEquals("0:08  ·  Original  ·  ≈2 MB", VideoComposeRules.selectionLabel(trim, original, Locale.US))
        assertEquals("0:08", VideoComposeRules.selectionLabel(trim, null))
    }

    @Test
    fun aClipThatCannotFitSaysTooLongAndBlocksSend() {
        val trim = VideoComposeRules.fullTrim(long4k.probe)
        val plan = VideoComposeRules.plan(long4k.probe, trim, removeAudio = false, quality = VideoUploadQuality.Original)
        assertTrue(plan.isFailure)
        assertEquals("240:00  ·  Too long", VideoComposeRules.selectionLabel(trim, plan))
        val sentence = assertThrows(VideoPlanError::class.java) {
            VideoPlanner.previewPlan(long4k.probe, trim = trim, removeAudio = false, quality = VideoUploadQuality.Original)
        }.message!!
        assertTrue(sentence.startsWith("Original quality won’t fit. Trim it to "))
        val clips = listOf(short, long4k)
        // The clip on screen gives its own sentence; another clip is named as "one video".
        assertEquals(sentence, VideoComposeRules.sendBlocked(clips, long4k.id, emptyMap(), emptySet(), VideoUploadQuality.Original))
        assertEquals(
            "One video won’t fit at this quality. $sentence",
            VideoComposeRules.sendBlocked(clips, short.id, emptyMap(), emptySet(), VideoUploadQuality.Original),
        )
        // At High both fit.
        assertNull(VideoComposeRules.sendBlocked(clips, short.id, emptyMap(), emptySet(), VideoUploadQuality.High))
    }

    @Test
    fun qualityMenuRowsAndBanner() {
        val trim = VideoComposeRules.fullTrim(short.probe)
        val high = VideoComposeRules.plan(short.probe, trim, false, VideoUploadQuality.High)
        assertEquals("High · 720p  ≈2.5 MB", VideoComposeRules.qualityTitle(VideoUploadQuality.High, high, Locale.US))
        val original = VideoComposeRules.plan(short.probe, trim, false, VideoUploadQuality.Original)
        // Original that keeps the source frame reads "Full size".
        assertEquals("Original · Full size  ≈2 MB", VideoComposeRules.qualityTitle(VideoUploadQuality.Original, original, Locale.US))
        assertEquals("Original · Full size", VideoComposeRules.qualityBanner(VideoUploadQuality.Original, original))
        val failed = VideoComposeRules.plan(long4k.probe, VideoComposeRules.fullTrim(long4k.probe), false, VideoUploadQuality.Original)
        assertEquals("Original · Too long", VideoComposeRules.qualityTitle(VideoUploadQuality.Original, failed))
        assertEquals("Original", VideoComposeRules.qualityBanner(VideoUploadQuality.Original, failed))
        // A 4K re-encode at Original is "1080p", not "Full size".
        val fourK = ComposeClip(UUID.randomUUID(), probe(8.0, 3840, 2160, 20_000_000, ext = "mov"), Uri.EMPTY)
        val reencode = VideoComposeRules.plan(fourK.probe, VideoComposeRules.fullTrim(fourK.probe), false, VideoUploadQuality.Original)
        assertEquals("1080p", VideoComposeRules.hint(VideoUploadQuality.Original, reencode.getOrThrow()))
    }

    @Test
    fun trimmedFlagPosterTileAndBanners() {
        assertFalse(VideoComposeRules.isTrimmed(VideoTrim(0.0, 8.0), 8.0))
        assertFalse(VideoComposeRules.isTrimmed(VideoTrim(0.02, 7.98), 8.0))
        assertTrue(VideoComposeRules.isTrimmed(VideoTrim(0.0, 7.9), 8.0))
        assertEquals(0, VideoComposeRules.posterIndex(0.0, 8.0, 14))
        assertEquals(7, VideoComposeRules.posterIndex(4.0, 8.0, 14))
        assertEquals(13, VideoComposeRules.posterIndex(8.0, 8.0, 14))
        assertNull(VideoComposeRules.posterIndex(1.0, 8.0, 0))
        assertNull(VideoComposeRules.posterIndex(1.0, 0.0, 14))
        assertEquals("Sound will be removed", VideoComposeRules.muteBanner(true))
        assertEquals("Sound will be kept", VideoComposeRules.muteBanner(false))
        assertEquals("Send video", VideoComposeRules.sendLabel(1))
        assertEquals("Send 3 videos", VideoComposeRules.sendLabel(3))
    }

    @Test
    fun trimsFollowTheStagedClips() {
        val third = ComposeClip(UUID.randomUUID(), probe(3.0, 640, 480, 500_000), Uri.EMPTY)
        val kept = VideoTrim(1.0, 5.0)
        val synced = VideoComposeRules.syncTrims(listOf(short, third), mapOf(short.id to kept, long4k.id to VideoTrim(0.0, 9.0)))
        assertEquals(mapOf(short.id to kept, third.id to VideoTrim(0.0, 3.0)), synced)
        assertEquals(setOf(third.id), VideoComposeRules.syncMuted(listOf(short, third), setOf(third.id, long4k.id)))
    }

    @Test
    fun removingTheClipOnScreenMovesToItsNeighbour() {
        val ids = List(3) { UUID.randomUUID() }
        assertEquals(ids[2], VideoComposeRules.selectionAfterRemoval(ids, 1, 1, ids[1]))
        assertEquals(ids[1], VideoComposeRules.selectionAfterRemoval(ids, 2, 2, ids[2]))
        assertEquals(ids[0], VideoComposeRules.selectionAfterRemoval(ids, 0, 2, ids[0]))
        assertNull(VideoComposeRules.selectionAfterRemoval(ids, 0, 1, null))
        assertEquals(0, VideoComposeRules.selection(ids, null))
        assertEquals(2, VideoComposeRules.selection(ids, ids[2]))
        assertEquals(0, VideoComposeRules.selection(ids, UUID.randomUUID()))
    }

    @Test
    fun plansCarryTheCaptionOnTheFirstClipOnlyAndThePlannersNumbers() {
        val trims = mapOf(short.id to VideoTrim(1.0, 5.0))
        val second = ComposeClip(UUID.randomUUID(), probe(3.0, 640, 480, 500_000), Uri.parse("content://picker/3"))
        val plans = VideoComposeRules.plans(listOf(short, second), "  hello  ", trims, setOf(second.id), VideoUploadQuality.High) { clip, _ ->
            if (clip.id == short.id) byteArrayOf(1, 2, 3) else null
        }
        assertEquals(2, plans.size)
        assertEquals("hello", plans[0].caption)
        assertEquals("", plans[1].caption)
        assertEquals(VideoTrim(1.0, 5.0), plans[0].trim)
        assertEquals(VideoTrim(0.0, 3.0), plans[1].trim)
        assertFalse(plans[0].removeAudio)
        assertTrue(plans[1].removeAudio)
        assertEquals(4_000, plans[0].durationMs)
        assertEquals(3_000, plans[1].durationMs)
        assertEquals(1280, plans[0].width)
        assertEquals(720, plans[0].height)
        assertEquals(3, plans[0].posterJpeg?.size)
        assertNull(plans[1].posterJpeg)
        assertEquals(Uri.parse("content://picker/3"), plans[1].sourceUri)
        assertTrue(plans.all { it.quality == VideoUploadQuality.High })
        assertTrue(plans[0].estimatedBytes!! > 0)
    }

    @Test
    fun anyOtherPlanningFailureIsTooLong() {
        assertEquals("This video is too long to send.", VideoComposeRules.TOO_LONG)
        assertEquals(VideoComposeRules.TOO_LONG, VideoComposeRules.failureMessage(Result.failure(IllegalStateException())))
        assertNull(VideoComposeRules.failureMessage(Result.success(VideoPlanner.previewPlan(short.probe, trim = null, removeAudio = false, quality = VideoUploadQuality.High))))
    }
}

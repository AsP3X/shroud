package de.corespace.shroud.core.media.video

import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.XmpData
import de.corespace.shroud.testing.FakeAppClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pieces around the plan: the "extension" Android derives from a MIME type
 * (conversation-compose-media §15.8), the H.264/AAC passthrough rule (media D7), display sizes, the
 * still box, the trim semantics of the seam (`VideoMedia.swift:18-35`) and the metadata-clearing
 * muxer's provider (media §5.3).
 */
class VideoFormatsTest {
    @Test
    fun extensionsComeFromTheContainerType() {
        assertEquals("mp4", VideoFormats.extensionForMime("video/mp4"))
        assertEquals("mp4", VideoFormats.extensionForMime("VIDEO/MP4; codecs=avc1"))
        assertEquals("m4v", VideoFormats.extensionForMime("video/x-m4v"))
        assertEquals("mov", VideoFormats.extensionForMime("video/quicktime"))
        assertEquals("3gp", VideoFormats.extensionForMime("video/3gpp"))
        assertEquals("webm", VideoFormats.extensionForMime("video/webm"))
        assertEquals("mkv", VideoFormats.extensionForMime("video/x-matroska"))
        assertEquals("", VideoFormats.extensionForMime("video/something"))
        assertEquals("", VideoFormats.extensionForMime(null))
        assertEquals("mp4", VideoFormats.extensionOfName("VID_20260901.MP4"))
        assertEquals("mov", VideoFormats.extensionOfName("/storage/x/clip.mov"))
        assertEquals("", VideoFormats.extensionOfName("42"))
        assertEquals("", VideoFormats.extensionOfName(".hidden"))
        assertEquals("", VideoFormats.extensionOfName("name."))
        assertEquals("", VideoFormats.extensionOfName(null))
    }

    @Test
    fun onlyH264WithAacOrNoAudioPassesThrough() {
        assertTrue(VideoFormats.isPassthroughCodec("video/avc", "audio/mp4a-latm", hasAudio = true))
        assertTrue(VideoFormats.isPassthroughCodec("video/avc", null, hasAudio = false))
        assertFalse(VideoFormats.isPassthroughCodec("video/hevc", "audio/mp4a-latm", hasAudio = true))
        assertFalse(VideoFormats.isPassthroughCodec("video/av01", null, hasAudio = false))
        assertFalse(VideoFormats.isPassthroughCodec(null, null, hasAudio = false))
        assertFalse(VideoFormats.isPassthroughCodec("video/avc", "audio/opus", hasAudio = true))
        assertFalse(VideoFormats.isPassthroughCodec("video/avc", null, hasAudio = true))
    }

    @Test
    fun aQuarterTurnSwapsTheDisplayEdges() {
        assertEquals(1920 to 1080, VideoFormats.displaySize(1920, 1080, 0))
        assertEquals(1080 to 1920, VideoFormats.displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, VideoFormats.displaySize(1920, 1080, 180))
        assertEquals(1080 to 1920, VideoFormats.displaySize(1920, 1080, 270))
        assertEquals(1080 to 1920, VideoFormats.displaySize(1920, 1080, -90))
        assertEquals(1 to 1, VideoFormats.displaySize(0, -5, 0))
    }

    @Test
    fun stillsNeverUpscale() {
        assertEquals(720, VideoFormats.frameBox(1920, 1080, 720))
        assertEquals(640, VideoFormats.frameBox(360, 640, 720))
        assertEquals(160, VideoFormats.frameBox(1280, 720, 160))
        assertEquals(1, VideoFormats.frameBox(0, 0, 0))
    }

    @Test
    fun trimsCoverTheirRange() {
        val trim = VideoTrim(1.0, 4.5)
        assertEquals(3.5, trim.duration, 0.0)
        assertEquals(0.0, VideoTrim(3.0, 2.0).duration, 0.0)
        assertTrue(VideoTrim(0.05, 9.95).isFullRange(10.0))
        assertFalse(VideoTrim(0.06, 10.0).isFullRange(10.0))
        assertFalse(VideoTrim(0.0, 9.94).isFullRange(10.0))
        // The export range is at least 0.1 s (`timeRange`, `VideoMedia.swift:29-34`).
        assertEquals(2.1, VideoTrim(2.0, 2.0).effectiveEnd, 1e-12)
        assertEquals(4.5, trim.effectiveEnd, 0.0)
    }

    @Test
    fun theMuxerKeepsOnlyOrientationAndFreshTimes() {
        val entries = linkedSetOf<Metadata.Entry>(
            Mp4LocationData(52.52f, 13.405f),
            Mp4TimestampData(3_870_000_000L, 3_870_000_000L),
            MdtaMetadataEntry("com.apple.quicktime.model", "Test Phone".toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING),
            MdtaMetadataEntry("com.android.capture.fps", byteArrayOf(0x42, 0xF0.toByte(), 0, 0), MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32),
            XmpData("<x:xmpmeta><xmp:CreateDate>2026-09-01</xmp:CreateDate></x:xmpmeta>".toByteArray()),
            Mp4OrientationData(90),
        )
        val clock = FakeAppClock(wallMillis = 1_790_000_000_000L)
        MetadataClearingMuxer.provider(clock).updateMetadataEntries(entries)

        val fresh = Mp4TimestampData.unixTimeToMp4TimeSeconds(1_790_000_000_000L)
        assertEquals(setOf<Metadata.Entry>(Mp4OrientationData(90), Mp4TimestampData(fresh, fresh)), entries.toSet())
    }
}

package de.corespace.shroud.ui.conversation.bubble

import de.corespace.shroud.core.model.MediaTransfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The photo and video bubbles' sizes and badge copy (`ImageMessageBubble.swift:40-87`,
 * `VideoMessageBubble.swift:57-125`) and the transfer disc's copy (`MediaTransferControl.swift:151-166`)
 * — the new Android tests of conversation-thread §21 ("photo/video displaySize table", "VideoBubble size
 * label for each transfer phase; duration m:ss").
 */
class MediaBubbleMathTest {
    private val cap = 268f
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun usLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun assertSize(expected: MediaSize, actual: MediaSize) {
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    @Test
    fun captionsSkipTheStandInLabels() {
        assertEquals("a cat", MediaBubbleMath.caption("  a cat \n", "Photo"))
        assertNull(MediaBubbleMath.caption("Photo", "Photo"))
        assertNull(MediaBubbleMath.caption("Media", "Video"))
        assertNull(MediaBubbleMath.caption("Video", "Video"))
        assertNull(MediaBubbleMath.caption("   ", "Photo"))
        // A video captioned "Photo" keeps it: only its own label is a stand-in.
        assertEquals("Photo", MediaBubbleMath.caption("Photo", "Video"))
    }

    @Test
    fun photoSizes() {
        // Landscape 4000×3000 → width-bound: 268 × 201.
        assertSize(MediaSize(268f, 201f), MediaBubbleMath.photoSize(cap, 4000, 3000, wide = false))
        // Portrait 3000×4000 → height-bound at 320: 240 × 320.
        assertSize(MediaSize(240f, 320f), MediaBubbleMath.photoSize(cap, 3000, 4000, wide = false))
        // Small images never scale up; the minimum side is 120.
        assertSize(MediaSize(120f, 120f), MediaBubbleMath.photoSize(cap, 50, 40, wide = false))
        assertSize(MediaSize(200f, 120f), MediaBubbleMath.photoSize(cap, 200, 100, wide = false))
        // Unknown size: 240 × 240.
        assertSize(MediaSize(240f, 240f), MediaBubbleMath.photoSize(cap, null, null, wide = false))
        // Nonsense sizes: 180 × 180.
        assertSize(MediaSize(180f, 180f), MediaBubbleMath.photoSize(cap, 0, 100, wide = false))
        assertSize(MediaSize(180f, 180f), MediaBubbleMath.photoSize(cap, 100, -1, wide = false))
        // A caption or a reply widens it to the cap, keeping the height.
        assertSize(MediaSize(268f, 320f), MediaBubbleMath.photoSize(cap, 3000, 4000, wide = true))
        assertSize(MediaSize(268f, 180f), MediaBubbleMath.photoSize(cap, 0, 0, wide = true))
    }

    @Test
    fun videoSizes() {
        // Landscape 1920×1080 fills the width: 268 × 150.75.
        assertSize(MediaSize(268f, 150.75f), MediaBubbleMath.videoSize(cap, 1920, 1080, wide = false))
        // Portrait 1080×1920 bounded by height 340: 191.25 × 340.
        assertSize(MediaSize(191.25f, 340f), MediaBubbleMath.videoSize(cap, 1080, 1920, wide = false))
        // Small clips scale up (min(cap/w, 340/h)), at least 150 × 110.
        assertSize(MediaSize(268f, 201f), MediaBubbleMath.videoSize(cap, 160, 120, wide = false))
        assertSize(MediaSize(150f, 340f), MediaBubbleMath.videoSize(cap, 10, 100, wide = false))
        // Unknown: 240 × 180 → 268 × 201.
        assertSize(MediaSize(268f, 201f), MediaBubbleMath.videoSize(cap, null, null, wide = false))
        // Nonsense: 16:9 at the cap.
        assertSize(MediaSize(268f, 150.75f), MediaBubbleMath.videoSize(cap, 0, 0, wide = false))
        assertSize(MediaSize(268f, 340f), MediaBubbleMath.videoSize(cap, 1080, 1920, wide = true))
    }

    @Test
    fun durationReadsMinutesAndTruncatedSeconds() {
        assertEquals("0:00", MediaBubbleMath.durationLabel(null))
        assertEquals("0:12", MediaBubbleMath.durationLabel(12_999))
        assertEquals("1:05", MediaBubbleMath.durationLabel(65_000))
        assertEquals("0:00", MediaBubbleMath.durationLabel(-5))
    }

    @Test
    fun theBadgeSizeFollowsTheTransferPhase() {
        val total = 4_200_000L
        assertEquals("Compressing", MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Preparing, true, 0.5, total), true, false, total))
        assertEquals(
            "1.1 MB / 4.2 MB",
            MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Transferring, false, 1_100_000.0 / total, total), false, true, total),
        )
        // Moved bytes unknown: the total alone.
        assertEquals("4.2 MB", MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Transferring, false, null, total), false, true, total))
        // Total unknown: nothing.
        assertNull(MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Transferring, false, 0.5, null), false, true, null))
        assertEquals("Sending", MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Finishing, true), true, false, total))
        assertEquals("Decrypting", MediaBubbleMath.videoSizeLabel(MediaTransfer(MediaTransfer.Phase.Finishing, false), false, true, total))
        // No transfer: the payload size until downloaded.
        assertEquals("4.2 MB", MediaBubbleMath.videoSizeLabel(null, false, needsDownload = true, byteCount = total))
        assertNull(MediaBubbleMath.videoSizeLabel(null, false, needsDownload = false, byteCount = total))
        assertNull(MediaBubbleMath.videoSizeLabel(null, false, needsDownload = true, byteCount = 0))
    }

    // ---- Transfer disc copy (`MediaTransferControl.swift:151-166`) ----

    @Test
    fun theDiscSpeaksItsState() {
        assertEquals("Download media", TransferCopy.label(null))
        assertEquals("Sending media", TransferCopy.label(MediaTransfer(MediaTransfer.Phase.Transferring, true, 0.1)))
        assertEquals("Cancel download", TransferCopy.label(MediaTransfer(MediaTransfer.Phase.Transferring, false, 0.1)))
        assertEquals("812 KB", TransferCopy.value(TransferMode.Idle(812_000)))
        assertEquals("", TransferCopy.value(TransferMode.Idle(null)))
        assertEquals("Compressing", TransferCopy.value(TransferMode.Busy(MediaTransfer(MediaTransfer.Phase.Preparing, true, 0.5))))
        // Upload: 0.3 + 0.5 × 0.7 = 0.65 → 65.
        assertEquals("65 percent", TransferCopy.value(TransferMode.Busy(MediaTransfer(MediaTransfer.Phase.Transferring, true, 0.5))))
        assertEquals("Finishing", TransferCopy.value(TransferMode.Busy(MediaTransfer(MediaTransfer.Phase.Finishing, false))))
    }

    @Test
    fun theRingShowsAHairOfArcAtZeroAndTruncatesPercent() {
        assertEquals(0.03f, TransferCopy.arcFraction(MediaTransfer(MediaTransfer.Phase.Transferring, false, 0.0)), 1e-6f)
        assertEquals(1f, TransferCopy.arcFraction(MediaTransfer(MediaTransfer.Phase.Finishing, false)), 1e-6f)
        assertEquals(99, TransferCopy.percent(MediaTransfer(MediaTransfer.Phase.Transferring, false, 0.999)))
    }
}

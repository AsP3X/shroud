package de.corespace.shroud.core.media.capture

import androidx.camera.video.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HD-only cameras must record HD. The old fallback treated HD as the excluded boundary. */
class RecordingQualityTest {
    @Test
    fun hdOnlySelectsHd() {
        assertEquals(Quality.HD, selectRecordingQuality(setOf(Quality.HD)))
    }

    @Test
    fun fhdWinsWhenHdIsAlsoSupported() {
        assertEquals(Quality.FHD, selectRecordingQuality(setOf(Quality.UHD, Quality.FHD, Quality.HD)))
    }

    @Test
    fun sdIsUsedWhenNothingHigherIsSupported() {
        assertEquals(Quality.SD, selectRecordingQuality(setOf(Quality.SD)))
    }

    @Test
    fun anEmptyCameraSelectsNothing() {
        assertNull(selectRecordingQuality(emptySet()))
    }

    @Test
    fun uhdAloneIsNotARecordingQuality() {
        assertNull(selectRecordingQuality(setOf(Quality.UHD)))
    }
}

package de.corespace.shroud.ui.media.compose

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.edit.FilterRecipe
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter
import de.corespace.shroud.core.media.edit.TextOverlay
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.pickedPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The photo compose screen's state rules (conversation-compose-media §9.2–§9.6;
 * `MediaComposeOverlay.swift:94-122, 391-399, 495, 686-695, 750-809`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaComposeStateTest {
    /**
     * These tests write snapshot state outside any composition; Compose's global write observer
     * then posts its apply notification to the main looper. Run it before Robolectric resets the
     * looper, or the dropped post strands Compose's shared main-thread dispatcher and every later
     * Compose test in this JVM stops recomposing.
     *
     * Bounded on purpose: an endless animation in a composition another class left alive would
     * keep a plain `idle()` running frames forever (Robolectric delivers each vsync at once).
     */
    @After
    fun drainMainLooper() {
        Snapshot.sendApplyNotifications()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(MAX_DRAIN_TASKS) {
            if (looper.isIdle) return
            looper.runOneTask()
        }
    }

    private companion object {
        /** Far more than the one apply notification needs; a cap, not a count. */
        const val MAX_DRAIN_TASKS = 64
    }

    @Test
    fun untouchedPhotosSendTheEmptyEditsInOrder() {
        val photos = List(3) { pickedPhoto() }
        val state = MediaComposeState(ComposeDraft(photos))
        val edits = state.sendEdits()
        assertEquals(3, edits.size)
        edits.forEach { assertSame(MediaEdits.Identity, it) }
    }

    @Test
    fun aFilterSwitchedBackToOriginalSendsTheEmptyValueToo() {
        val state = MediaComposeState(ComposeDraft(listOf(pickedPhoto())))
        state.setEdits(MediaEdits(filter = MediaFilter.Vivid, filterIntensity = 0.4f))
        state.updateEdits { it.copy(filter = MediaFilter.None) }
        assertEquals(0.4f, state.currentEdits.filterIntensity)
        assertSame(MediaEdits.Identity, state.sendEdits().single())
    }

    @Test
    fun editsFollowThePhotoNotTheIndex() {
        val photos = List(3) { pickedPhoto() }
        val state = MediaComposeState(ComposeDraft(photos))
        state.select(photos[2].id)
        val crop = MediaEdits(cropRect = Rect(0f, 0f, 0.5f, 0.5f))
        state.setEdits(crop)
        // The first photo leaves: the third keeps its crop at index 1.
        state.syncPhotos(photos.drop(1))
        assertEquals(listOf(MediaEdits.Identity, crop), state.sendEdits())
        assertEquals(1, state.selection)
        assertEquals(crop, state.currentEdits)
    }

    @Test
    fun removedPhotosLoseTheirEditsAndRenders() {
        val photos = List(2) { pickedPhoto() }
        val state = MediaComposeState(ComposeDraft(photos))
        state.setEdits(MediaEdits(mirrored = true))
        state.rendered[photos[0].id] = photos[0].preview
        state.syncPhotos(listOf(photos[1]))
        assertTrue(state.edits.isEmpty())
        assertTrue(state.rendered.isEmpty())
        // A photo coming back is a new start.
        state.syncPhotos(photos)
        assertSame(MediaEdits.Identity, state.sendEdits()[0])
    }

    @Test
    fun removingThePhotoOnScreenSelectsItsRightNeighbourElseItsLeft() {
        val photos = List(3) { pickedPhoto() }
        val state = MediaComposeState(ComposeDraft(photos))
        state.select(photos[1].id)
        state.prepareRemoval(1)
        assertEquals(photos[2].id, state.selectedId)

        state.syncPhotos(listOf(photos[0], photos[2]))
        state.prepareRemoval(1)
        assertEquals(photos[0].id, state.selectedId)

        // Removing another photo leaves the selection alone.
        state.prepareRemoval(1)
        assertEquals(photos[0].id, state.selectedId)
    }

    @Test
    fun theSelectionFallsBackToTheFirstPhoto() {
        val photos = List(2) { pickedPhoto() }
        val state = MediaComposeState(ComposeDraft(photos))
        assertNull(state.selectedId)
        assertEquals(0, state.selection)
        assertSame(photos[0], state.current)
        state.select(photos[1].id)
        state.syncPhotos(listOf(photos[0]))
        assertEquals(0, state.selection)
        assertSame(photos[0].preview, state.displayed)
    }

    @Test
    fun theAlbumHoldsTenAndAddStopsThere() {
        val state = MediaComposeState(ComposeDraft(List(12) { pickedPhoto() }))
        assertEquals(10, state.photos.size)
        assertFalse(state.canAddMore)
        state.syncPhotos(List(9) { pickedPhoto() })
        assertTrue(state.canAddMore)
    }

    @Test
    fun theDraftSeedsCaptionAndQuality() {
        val state = MediaComposeState(ComposeDraft(listOf(pickedPhoto()), caption = "Lunch was amazing", quality = MediaComposeQuality.HD))
        assertEquals("Lunch was amazing", state.caption)
        assertEquals(MediaComposeQuality.HD, state.quality)
    }

    @Test
    fun theQualityBadgeTogglesAndSaysWhatItMeans() {
        val state = MediaComposeState(ComposeDraft(listOf(pickedPhoto())))
        assertEquals(MediaComposeQuality.Original, state.quality)
        assertEquals("HD — smaller file", state.toggleQuality())
        assertEquals(MediaComposeQuality.HD, state.quality)
        assertEquals("Original quality · location removed", state.toggleQuality())
        assertEquals(MediaComposeQuality.Original, state.quality)
    }

    @Test
    fun annotationCanvasesDropTheStickersAndTheMarkupForDraw() {
        val state = MediaComposeState(ComposeDraft(listOf(pickedPhoto())))
        val drawing = object : de.corespace.shroud.core.media.edit.DrawingData {
            override val canvasWidth = 1f
            override val canvasHeight = 1f
            override fun draw(canvas: android.graphics.Canvas, outWidth: Int, outHeight: Int) = Unit
        }
        state.setEdits(MediaEdits(rotationQuarters = 1, filter = MediaFilter.Noir, drawing = drawing, texts = listOf(TextOverlay(string = "Hi"))))
        val forDraw = state.annotationEdits(includingDrawing = false)
        assertNull(forDraw.drawing)
        assertTrue(forDraw.texts.isEmpty())
        assertEquals(1, forDraw.rotationQuarters)
        assertEquals(MediaFilter.Noir, forDraw.filter)
        val forText = state.annotationEdits(includingDrawing = true)
        assertSame(drawing, forText.drawing)
        assertTrue(forText.texts.isEmpty())
    }

    @Test
    fun copyMatchesIos() {
        assertEquals("Send", MediaComposeState.sendLabel(1))
        assertEquals("Send 4 photos", MediaComposeState.sendLabel(4))
        assertEquals("1 photo", MediaComposeState.countLabel(1))
        assertEquals("2 photos", MediaComposeState.countLabel(2))
        assertEquals("Add a caption...", CAPTION_PLACEHOLDER)
        assertEquals("Emoji keyboard: use your keyboard\u2019s emoji key", EMOJI_BANNER)
    }

    @Test
    fun theStripTakesTheRenderersFiltersInItsOrder() {
        val recipes = listOf(FilterRecipe("Mono", "Mono"), FilterRecipe("None", "Original"), FilterRecipe("Sepia", "Sepia"))
        assertEquals(listOf(MediaFilter.Mono to "Mono", MediaFilter.None to "Original"), filterChoices(recipes))
        assertEquals("100%", intensityLabel(1f))
        assertEquals("0%", intensityLabel(0f))
        assertEquals("29%", intensityLabel(0.29f))
        assertEquals("99%", intensityLabel(0.999f))
    }
}

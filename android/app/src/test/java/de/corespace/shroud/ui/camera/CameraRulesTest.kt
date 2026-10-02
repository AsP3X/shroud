package de.corespace.shroud.ui.camera

import de.corespace.shroud.core.media.capture.CameraBindState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app camera's rules (conversation-compose-media §8.3, P8, Q1; K9). */
class CameraRulesTest {
    @Test
    fun pinchStaysInsideTheLensRange() {
        // K9's zoomRange for the bound lens (an ultra-wide phone goes below 1×).
        val lens = 0.6f..10f
        assertEquals(2f, CameraRules.pinch(1f, 2f, lens), 0f)
        assertEquals(0.6f, CameraRules.pinch(1f, 0.2f, lens), 0f)
        assertEquals(10f, CameraRules.pinch(6f, 3f, lens), 0f)
        assertEquals(3f, CameraRules.pinch(3f, 1f, 1f..3f), 0f)
        assertEquals(3f, CameraRules.pinch(3f, Float.NaN, lens), 0f)
        assertEquals(3f, CameraRules.pinch(3f, 0f, lens), 0f)
    }

    @Test
    fun pinchFallsBackToTheCapBeforeABind() {
        assertEquals(2f, CameraRules.pinch(1f, 2f, null), 0f)
        assertEquals(1f, CameraRules.pinch(1.5f, 0.2f, null), 0f)
        assertEquals(CameraRules.MAX_ZOOM, CameraRules.pinch(6f, 3f, null), 0f)
        // A range that is not a usable interval counts as none.
        assertEquals(CameraRules.MIN_ZOOM..CameraRules.MAX_ZOOM, CameraRules.zoomBounds(0f..4f))
        assertEquals(CameraRules.MIN_ZOOM..CameraRules.MAX_ZOOM, CameraRules.zoomBounds(1f..Float.NaN))
        assertEquals(CameraRules.MIN_ZOOM..CameraRules.MAX_ZOOM, CameraRules.zoomBounds(1f..Float.POSITIVE_INFINITY))
        assertEquals(1f..1f, CameraRules.zoomBounds(1f..1f))
    }

    @Test
    fun theResetChipShowsOnceZoomedInOrOut() {
        assertFalse(CameraRules.showsZoomReset(1f))
        assertFalse(CameraRules.showsZoomReset(1.005f))
        assertFalse(CameraRules.showsZoomReset(0.995f))
        assertTrue(CameraRules.showsZoomReset(1.2f))
        assertTrue(CameraRules.showsZoomReset(0.6f))
    }

    @Test
    fun noCameraComesOnlyFromK9() {
        val both = CameraBindState.Bound(hasFront = true, hasBack = true)
        assertEquals(CameraBind.Waiting, CameraRules.bindView(CameraBindState.Unbound, ownBind = true))
        assertEquals(CameraBind.Waiting, CameraRules.bindView(CameraBindState.Binding, ownBind = true))
        assertEquals(CameraBind.Ready, CameraRules.bindView(both, ownBind = true))
        assertEquals(CameraBind.Ready, CameraRules.bindView(CameraBindState.Bound(hasFront = true, hasBack = false), ownBind = true))
        assertEquals(CameraBind.Ready, CameraRules.bindView(CameraBindState.Bound(hasFront = false, hasBack = true), ownBind = true))
        assertEquals(CameraBind.NoCamera, CameraRules.bindView(CameraBindState.Bound(hasFront = false, hasBack = false), ownBind = true))
        assertEquals(CameraBind.NoCamera, CameraRules.bindView(CameraBindState.Failed, ownBind = true))
        // Until the screen asked for the lens and mode it shows, an earlier bind's state is not its answer.
        listOf(CameraBindState.Unbound, CameraBindState.Binding, both, CameraBindState.Failed).forEach { state ->
            assertEquals(CameraBind.Waiting, CameraRules.bindView(state, ownBind = false))
        }
    }

    @Test
    fun recordTimerReadsLikeEveryVideoTime() {
        assertEquals("0:00", CameraRules.recordingLabel(0))
        assertEquals("0:09", CameraRules.recordingLabel(9_999))
        assertEquals("1:05", CameraRules.recordingLabel(65_000))
        assertEquals("0:00", CameraRules.recordingLabel(-5))
    }

    @Test
    fun lensesTorchAndFlip() {
        // K9's hasFlashUnit, for a bound lens only.
        assertTrue(CameraRules.showsTorch(ready = true, hasFlashUnit = true))
        assertFalse(CameraRules.showsTorch(ready = true, hasFlashUnit = false))
        assertFalse(CameraRules.showsTorch(ready = false, hasFlashUnit = true))
        assertTrue(CameraRules.flipped(front = false, hasFront = true, hasBack = true))
        assertFalse(CameraRules.flipped(front = true, hasFront = true, hasBack = true))
        assertTrue(CameraRules.flipped(front = true, hasFront = true, hasBack = false)) // nowhere to go
        assertFalse(CameraRules.initialFront(hasFront = true, hasBack = true))
        assertTrue(CameraRules.initialFront(hasFront = true, hasBack = false))
        assertTrue(CameraRules.canFlip(hasFront = true, hasBack = true, recording = false))
        assertFalse(CameraRules.canFlip(hasFront = true, hasBack = true, recording = true))
        assertFalse(CameraRules.canFlip(hasFront = false, hasBack = true, recording = false))
    }

    @Test
    fun shutterAndPermissionCopy() {
        assertEquals("Take photo", CameraRules.shutterLabel(CameraMode.Photo, recording = false))
        assertEquals("Start recording", CameraRules.shutterLabel(CameraMode.Video, recording = false))
        assertEquals("Stop recording", CameraRules.shutterLabel(CameraMode.Video, recording = true))
        assertEquals(CameraAccess.Granted, CameraRules.access(granted = true, permanentlyDenied = false))
        assertEquals(CameraAccess.Denied, CameraRules.access(granted = false, permanentlyDenied = false))
        assertEquals(CameraAccess.DeniedPermanently, CameraRules.access(granted = false, permanentlyDenied = true))
        assertEquals("Open Settings", CameraRules.deniedAction(CameraAccess.DeniedPermanently))
        assertEquals("Allow Camera Access", CameraRules.deniedAction(CameraAccess.Denied))
        // iOS's capture failures (`CameraPicker.swift:76, 85`).
        assertEquals("Could not load that photo.", CameraRules.PHOTO_FAILED)
        assertEquals("Could not load that video.", CameraRules.VIDEO_FAILED)
        assertEquals("PHOTO", CameraMode.Photo.title)
        assertEquals("VIDEO", CameraMode.Video.title)
    }
}

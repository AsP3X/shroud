package de.corespace.shroud.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app camera's rules (conversation-compose-media §8.3, P8, Q1; K9). */
class CameraRulesTest {
    @Test
    fun pinchStaysBetweenOneAndTheCeiling() {
        assertEquals(2f, CameraRules.pinch(1f, 2f), 0f)
        assertEquals(1f, CameraRules.pinch(1.5f, 0.2f), 0f)
        assertEquals(CameraRules.MAX_ZOOM, CameraRules.pinch(6f, 3f), 0f)
        assertEquals(3f, CameraRules.pinch(3f, Float.NaN), 0f)
        assertEquals(3f, CameraRules.pinch(3f, 0f), 0f)
        assertFalse(CameraRules.showsZoomReset(1f))
        assertFalse(CameraRules.showsZoomReset(1.005f))
        assertTrue(CameraRules.showsZoomReset(1.2f))
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
        assertTrue(CameraRules.showsTorch(front = false))
        assertFalse(CameraRules.showsTorch(front = true))
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

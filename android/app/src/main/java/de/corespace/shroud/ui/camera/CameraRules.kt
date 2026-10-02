package de.corespace.shroud.ui.camera

import de.corespace.shroud.core.media.video.ChatVideoPlayer

/** What the shutter does (iOS lets the user switch the system camera between the two, `CameraPicker.swift:24-37`). */
enum class CameraMode(val title: String) {
    Photo("PHOTO"),
    Video("VIDEO"),
}

/** Where the camera permission stands for this screen. */
enum class CameraAccess {
    /** Not known yet, or the system dialog is up: the screen stays black. */
    Asking,
    Granted,

    /** Refused; Android will ask again. */
    Denied,

    /** Refused for good: only Settings can change it. */
    DeniedPermanently,
}

/**
 * The in-app camera's rules (conversation-compose-media §8.3, P8, Q1; K9). Pure.
 *
 * iOS uses the system camera (`UIImagePickerController`); Android draws its own on CameraX so a
 * capture never reaches the gallery or a third-party camera app (decision Q1 / P8).
 */
internal object CameraRules {
    /**
     * The pinch's upper bound. K9 does not publish the lens's zoom range (contract gap); core
     * clamps every [de.corespace.shroud.core.media.capture.CameraCapture.setZoom] to the real
     * range, so this only stops a pinch from running far past it.
     */
    const val MAX_ZOOM = 8f
    const val MIN_ZOOM = 1f

    /** How long a bind may take before the screen says there is no camera (K9 binds asynchronously). */
    const val BIND_GRACE_MS = 4_000L

    /** How often the bind flags are re-read while waiting. */
    const val BIND_POLL_MS = 100L

    /** … and once the screen already says there is no camera (CameraX may still come up). */
    const val BIND_SLOW_POLL_MS = 500L

    /** A pinch's new zoom ratio. */
    fun pinch(current: Float, zoomChange: Float): Float {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return current
        return (current * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** The "1×" chip that puts the zoom back shows once the picture is zoomed. */
    fun showsZoomReset(ratio: Float): Boolean = ratio > MIN_ZOOM + 0.01f

    /** The record timer, "m:ss" like every other video time. */
    fun recordingLabel(elapsedMillis: Long): String = ChatVideoPlayer.timeLabel(elapsedMillis.coerceAtLeast(0L) / 1000.0)

    /**
     * Front cameras have no torch to speak of: the control shows for the back lens only (K9 does
     * not say whether a lens has a flash unit — contract gap).
     */
    fun showsTorch(front: Boolean): Boolean = !front

    /** The lens after a flip: the other one when it exists, else the same. */
    fun flipped(front: Boolean, hasFront: Boolean, hasBack: Boolean): Boolean = when {
        front && hasBack -> false
        !front && hasFront -> true
        else -> front
    }

    /** The lens to open first: back, unless the phone only has a front camera. */
    fun initialFront(hasFront: Boolean, hasBack: Boolean): Boolean = !hasBack && hasFront

    /** Whether the flip control does anything. */
    fun canFlip(hasFront: Boolean, hasBack: Boolean, recording: Boolean): Boolean = hasFront && hasBack && !recording

    /** The shutter's TalkBack label. */
    fun shutterLabel(mode: CameraMode, recording: Boolean): String = when {
        mode == CameraMode.Photo -> "Take photo"
        recording -> "Stop recording"
        else -> "Start recording"
    }

    /** Access after a permission answer ([rememberPermissionRequest]'s two flags). */
    fun access(granted: Boolean, permanentlyDenied: Boolean): CameraAccess = when {
        granted -> CameraAccess.Granted
        permanentlyDenied -> CameraAccess.DeniedPermanently
        else -> CameraAccess.Denied
    }

    /** The denied screen's button: Settings once Android stopped asking, else ask again. */
    fun deniedAction(access: CameraAccess): String = if (access == CameraAccess.DeniedPermanently) OPEN_SETTINGS else ALLOW_ACCESS

    // Copy. iOS copy where iOS has it (`CameraPicker.swift:76, 85`); the denied and no-camera
    // states follow the Android design's scanner frames (oGuCt, u3il8T) — see DESIGN NOTES.
    const val PHOTO_FAILED = "Could not load that photo."
    const val VIDEO_FAILED = "Could not load that video."
    const val RECORD_FAILED = "Could not start recording."
    const val DENIED_TITLE = "Camera access is off"
    const val DENIED_BODY = "Shroud needs the camera to take photos and videos for this chat. Nothing is saved to your gallery."
    const val OPEN_SETTINGS = "Open Settings"
    const val ALLOW_ACCESS = "Allow Camera Access"
    const val NO_CAMERA = "No camera available"
    const val MIC_OFF = "Microphone access is off"
    const val SETTINGS = "Settings"
    const val NO_SOUND = "NO SOUND"
}

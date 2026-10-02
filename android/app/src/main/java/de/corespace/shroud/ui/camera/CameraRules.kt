package de.corespace.shroud.ui.camera

import de.corespace.shroud.core.media.capture.CameraBindState
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import kotlin.math.abs

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

/** What the camera screen makes of K9's [CameraBindState] for the lens and mode on screen. */
enum class CameraBind {
    /** No answer yet (CameraX can take seconds to start): black, the shutter off, nothing said. */
    Waiting,

    /** A lens is up: the shutter works. */
    Ready,

    /** K9 says there is no camera: "No camera available". */
    NoCamera,
}

/**
 * The in-app camera's rules (conversation-compose-media §8.3, P8, Q1; K9). Pure.
 *
 * iOS uses the system camera (`UIImagePickerController`); Android draws its own on CameraX so a
 * capture never reaches the gallery or a third-party camera app (decision Q1 / P8).
 */
internal object CameraRules {
    /**
     * The pinch's bounds while K9 has no [de.corespace.shroud.core.media.capture.CameraCapture.zoomRange]
     * (before a bind finished). Core clamps every `setZoom` to the lens's real range as well.
     */
    const val MAX_ZOOM = 8f
    const val MIN_ZOOM = 1f

    /**
     * The screen's reading of a bind (K9 `bindState`, gap #14). [ownBind] is false until this
     * screen has asked for the lens and mode it shows: a state left by an earlier bind (the other
     * lens, the other mode, a camera screen that closed) is not this screen's answer. "No camera"
     * comes only from K9 — a failed bind, or one that finished with no lens — never from a timer,
     * so a slow CameraX start never flashes it.
     */
    fun bindView(state: CameraBindState, ownBind: Boolean): CameraBind = when {
        !ownBind -> CameraBind.Waiting
        state is CameraBindState.Bound -> if (state.hasFront || state.hasBack) CameraBind.Ready else CameraBind.NoCamera
        state == CameraBindState.Failed -> CameraBind.NoCamera
        else -> CameraBind.Waiting
    }

    /**
     * The pinch's bounds: the bound lens's [range] (K9 `zoomRange`; below 1× on a phone with an
     * ultra-wide), else [MIN_ZOOM]..[MAX_ZOOM]. A range that is not a usable interval counts as none.
     */
    fun zoomBounds(range: ClosedFloatingPointRange<Float>?): ClosedFloatingPointRange<Float> {
        if (range == null) return MIN_ZOOM..MAX_ZOOM
        val low = range.start
        val high = range.endInclusive
        if (!low.isFinite() || !high.isFinite() || low <= 0f || high < low) return MIN_ZOOM..MAX_ZOOM
        return range
    }

    /** A pinch's new zoom ratio, inside the lens's [range] (K9 `zoomRange`, null before a bind). */
    fun pinch(current: Float, zoomChange: Float, range: ClosedFloatingPointRange<Float>?): Float {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return current
        return (current * zoomChange).coerceIn(zoomBounds(range))
    }

    /** The "1×" chip that puts the zoom back shows once the picture is zoomed in or out. */
    fun showsZoomReset(ratio: Float): Boolean = abs(ratio - 1f) > 0.01f

    /** The record timer, "m:ss" like every other video time. */
    fun recordingLabel(elapsedMillis: Long): String = ChatVideoPlayer.timeLabel(elapsedMillis.coerceAtLeast(0L) / 1000.0)

    /**
     * The torch control shows only for a bound lens with a flash unit (K9 `hasFlashUnit`); most
     * front cameras have none, so it usually goes when the user flips.
     */
    fun showsTorch(ready: Boolean, hasFlashUnit: Boolean): Boolean = ready && hasFlashUnit

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

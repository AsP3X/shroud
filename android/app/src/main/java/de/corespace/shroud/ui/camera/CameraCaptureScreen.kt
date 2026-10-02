package de.corespace.shroud.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.media.ImageEncodeException
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.capture.CameraCapture
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.Appear
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.media.PickedMovie
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.media.video.MediaViewIcons
import de.corespace.shroud.ui.media.viewer.MediaCircleButton
import de.corespace.shroud.ui.media.viewer.MediaLayer
import de.corespace.shroud.ui.media.viewer.mediaBottomInset
import de.corespace.shroud.ui.media.viewer.mediaTopInset
import de.corespace.shroud.ui.permissions.isPermissionGranted
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.permissions.rememberPermissionGranted
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The in-app camera: photo and video capture with CameraX (conversation-compose-media §8.3; iOS
 * `CameraPicker`, `ios/shroud/Features/Main/CameraPicker.swift`; P8, Q1). A recorded clip is a
 * [PickedMovie] owning its `cacheDir/shroud-*` file.
 *
 * Human: a full-screen viewfinder with Close and the torch at the top, PHOTO / VIDEO above the
 * shutter, and flip beside it; pinch to zoom, "1×" puts it back. A photo or a clip goes straight
 * to the compose screen; nothing is saved to the gallery. The camera permission is asked when the
 * screen opens; the microphone only when switching to video (P8) — refused, clips record without
 * sound and the screen says so.
 *
 * Agent: binds `media.camera` (K9) to this composition's lifecycle with a viewfinder surface
 * provider and unbinds when it leaves (which also deletes an unfinished clip). [onPhoto] gets a
 * [PickedPhoto] whose source is the capture's FileProvider URI (never MediaStore); [onVideo] a
 * [PickedMovie] owning the recorded file, which the host cleans up after send or cancel. A capture
 * that could not be read shows its failure toast here and the camera stays open.
 */
@Composable
fun CameraCaptureScreen(onPhoto: (PickedPhoto) -> Unit, onVideo: (PickedMovie) -> Unit, onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val services = remember(container) { ContainerCameraServices(container) }
    CameraCaptureContent(onPhoto, onVideo, onClose, services)
}

/** What the camera screen reads from core (R4: forwarded 1:1 by [ContainerCameraServices]). Tests pass a fake. */
internal interface CameraServices {
    /** `media.camera` (K9). */
    val camera: CameraCapture

    /** The compose screen's preview of a capture (`images.mediaImages.decodePreview`, K1). */
    suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap?
}

internal class ContainerCameraServices(private val container: AppContainer) : CameraServices {
    override val camera: CameraCapture get() = container.media.camera

    override suspend fun decodePreview(source: MediaImageSource, maxEdge: Int): Bitmap? =
        container.images.mediaImages.decodePreview(source, maxEdge)
}

/** [CameraCaptureScreen] on explicit [services]; the entry point and the tests call it. */
@Composable
internal fun CameraCaptureContent(
    onPhoto: (PickedPhoto) -> Unit,
    onVideo: (PickedMovie) -> Unit,
    onClose: () -> Unit,
    services: CameraServices,
) {
    MediaLayer {
        CameraBody(onPhoto, onVideo, onClose, services)
    }
}

@Composable
private fun CameraBody(
    onPhoto: (PickedPhoto) -> Unit,
    onVideo: (PickedMovie) -> Unit,
    onClose: () -> Unit,
    services: CameraServices,
) {
    val context = LocalContext.current
    val camera = services.camera
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnVideo by rememberUpdatedState(onVideo)

    var access by remember {
        mutableStateOf(if (isPermissionGranted(context, Manifest.permission.CAMERA)) CameraAccess.Granted else CameraAccess.Asking)
    }
    val cameraGranted by rememberPermissionGranted(Manifest.permission.CAMERA)
    val micGranted by rememberPermissionGranted(Manifest.permission.RECORD_AUDIO)
    val askCamera = rememberPermissionRequest(Manifest.permission.CAMERA) { granted, permanently ->
        access = CameraRules.access(granted, permanently)
    }
    val askMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted, _ ->
        if (!granted) {
            // Clips still record, silently; the screen says so (P8, design u3il8T).
            toasts.show(Toast.withAction(CameraRules.MIC_OFF, CameraRules.SETTINGS, { openAppSettings(context) }))
        }
    }
    // Asked once as the screen opens (the scanner's flow, contacts §8); granted in Settings → back on resume.
    LaunchedEffect(Unit) {
        if (access == CameraAccess.Asking) askCamera()
    }
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) access = CameraAccess.Granted
    }

    var mode by remember { mutableStateOf(CameraMode.Photo) }
    var front by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var bound by remember { mutableStateOf(false) }
    var noCamera by remember { mutableStateOf(false) }
    var hasFront by remember { mutableStateOf(false) }
    var hasBack by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val flash = remember { Animatable(0f) }

    val requests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by requests.collectAsState()
    val provider = remember { Preview.SurfaceProvider { request -> requests.value = request } }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bind for the lens and mode on screen; K9 binds asynchronously, so its flags are read until
    // they say a camera is up, and only after a grace period does the screen call it missing.
    LaunchedEffect(access, front, mode, lifecycleOwner) {
        if (access != CameraAccess.Granted) return@LaunchedEffect
        bound = false
        noCamera = false
        torch = false
        zoom = 1f
        camera.bind(lifecycleOwner, provider, front, video = mode == CameraMode.Video)
        val started = SystemClock.uptimeMillis()
        while (isActive) {
            hasFront = camera.hasFrontCamera
            hasBack = camera.hasBackCamera
            if (hasFront || hasBack) {
                bound = true
                break
            }
            if (SystemClock.uptimeMillis() - started > CameraRules.BIND_GRACE_MS) {
                noCamera = true
                break
            }
            delay(CameraRules.BIND_POLL_MS)
        }
        // A phone with only a front camera opens on it.
        if (bound && !front && !hasBack && hasFront) front = true
    }
    DisposableEffect(camera) {
        onDispose { camera.unbind() }
    }
    // The record timer: the clip's own length is K9's; this only counts what the screen shows.
    LaunchedEffect(recordingSince) {
        val since = recordingSince ?: return@LaunchedEffect
        while (isActive) {
            elapsed = SystemClock.uptimeMillis() - since
            delay(250)
        }
    }

    BackHandler(enabled = true) { onClose() }

    val recording = recordingSince != null

    fun failure(message: String) {
        haptic(Haptic.Error)
        toasts.show(Toast.failure(message))
    }

    fun takePhoto() {
        if (capturing || !bound) return
        capturing = true
        scope.launch {
            launch {
                flash.snapTo(0.85f)
                flash.animateTo(0f, Motion.easeOut(220))
            }
            try {
                val source = camera.takePhoto()
                val preview = services.decodePreview(source, PREVIEW_MAX_EDGE)
                if (preview == null) {
                    failure(CameraRules.PHOTO_FAILED)
                } else {
                    currentOnPhoto(PickedPhoto(preview = preview, source = source))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageEncodeException) {
                failure(e.message ?: CameraRules.PHOTO_FAILED)
            } catch (_: Exception) {
                failure(CameraRules.PHOTO_FAILED)
            } finally {
                capturing = false
            }
        }
    }

    fun toggleRecording() {
        if (capturing || !bound) return
        if (!recording) {
            if (camera.startRecording(withAudio = micGranted)) {
                elapsed = 0L
                recordingSince = SystemClock.uptimeMillis()
                haptic(Haptic.Medium)
            } else {
                failure(CameraRules.RECORD_FAILED)
            }
            return
        }
        capturing = true
        recordingSince = null
        haptic(Haptic.Medium)
        scope.launch {
            try {
                val clip = camera.stopRecording()
                if (clip == null) {
                    failure(CameraRules.VIDEO_FAILED)
                } else {
                    currentOnVideo(PickedMovie(Uri.fromFile(clip.file), clip.file))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure(CameraRules.VIDEO_FAILED)
            } finally {
                capturing = false
            }
        }
    }

    fun chooseMode(next: CameraMode) {
        if (next == mode || recording || capturing) return
        haptic(Haptic.Light)
        mode = next
        // The microphone is asked for only when the user turns to video (P8).
        if (next == CameraMode.Video && !micGranted) askMic()
    }

    val topInset = mediaTopInset()
    val bottomInset = mediaBottomInset()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (access) {
            CameraAccess.Granted -> {
                surfaceRequest?.let { request ->
                    CameraXViewfinder(
                        surfaceRequest = request,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Camera preview" }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoomChange, _ ->
                                    val next = CameraRules.pinch(zoom, zoomChange)
                                    if (next != zoom) {
                                        zoom = next
                                        camera.setZoom(next)
                                    }
                                }
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.Black))
                if (noCamera) NoCameraState()

                CameraControls(
                    topInset = topInset,
                    bottomInset = bottomInset,
                    mode = mode,
                    recording = recording,
                    elapsed = elapsed,
                    busy = capturing,
                    ready = bound,
                    showsTorch = bound && CameraRules.showsTorch(front),
                    torch = torch,
                    canFlip = bound && CameraRules.canFlip(hasFront, hasBack, recording),
                    zoom = zoom,
                    silent = mode == CameraMode.Video && !micGranted,
                    onClose = onClose,
                    onTorch = {
                        torch = !torch
                        camera.setTorch(torch)
                    },
                    onFlip = {
                        haptic(Haptic.Light)
                        front = CameraRules.flipped(front, hasFront, hasBack)
                    },
                    onMode = ::chooseMode,
                    onShutter = { if (mode == CameraMode.Photo) takePhoto() else toggleRecording() },
                    onZoomReset = {
                        zoom = 1f
                        camera.setZoom(1f)
                    },
                )
            }
            CameraAccess.Denied, CameraAccess.DeniedPermanently -> DeniedState(
                access = access,
                topInset = topInset,
                bottomInset = bottomInset,
                onClose = onClose,
                onAction = {
                    if (access == CameraAccess.DeniedPermanently) openAppSettings(context) else askCamera()
                },
            )
            CameraAccess.Asking -> Box(Modifier.fillMaxSize()) {
                MediaCircleButton(
                    ShroudIcons.X,
                    "Close camera",
                    onClose,
                    modifier = Modifier.padding(start = 12.dp, top = topInset + 6.dp),
                    iconSize = 15.dp,
                )
            }
        }
        ToastHost(toasts, bottomInset = 150.dp)
    }
}

@Composable
private fun CameraControls(
    topInset: Dp,
    bottomInset: Dp,
    mode: CameraMode,
    recording: Boolean,
    elapsed: Long,
    busy: Boolean,
    ready: Boolean,
    showsTorch: Boolean,
    torch: Boolean,
    canFlip: Boolean,
    zoom: Float,
    silent: Boolean,
    onClose: () -> Unit,
    onTorch: () -> Unit,
    onFlip: () -> Unit,
    onMode: (CameraMode) -> Unit,
    onShutter: () -> Unit,
    onZoomReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = topInset + 6.dp),
        ) {
            MediaCircleButton(ShroudIcons.X, "Close camera", onClose, modifier = Modifier.align(Alignment.CenterStart), iconSize = 15.dp)
            Appear(
                visible = recording,
                enter = fadeIn(Motion.fade()),
                exit = fadeOut(Motion.fade()),
                modifier = Modifier.align(Alignment.Center),
            ) {
                RecordTimer(elapsed)
            }
            if (showsTorch) {
                MediaCircleButton(
                    if (torch) MediaViewIcons.LightningFill else MediaViewIcons.LightningSlashFill,
                    if (torch) "Torch on" else "Torch off",
                    onTorch,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    tint = if (torch) MediaColors.blue else Color.White,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().padding(bottom = bottomInset + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(visible = CameraRules.showsZoomReset(zoom), enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                    Pill("1×", "Reset zoom", onZoomReset)
                }
                AnimatedVisibility(visible = silent, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                    SilentBadge()
                }
            }
            AnimatedVisibility(visible = !recording, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                ModeSwitch(mode, enabled = !busy, onMode)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(48.dp))
                Shutter(mode, recording, enabled = ready && !busy, onShutter)
                MediaCircleButton(
                    ShroudIcons.CameraRotateFill,
                    "Switch camera",
                    onFlip,
                    iconSize = 22.dp,
                    enabled = canFlip,
                    modifier = Modifier.alpha(if (canFlip) 1f else 0.35f),
                )
            }
        }
    }
}

/** Red dot and "m:ss" while a clip records. */
@Composable
private fun RecordTimer(elapsed: Long) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.danger))
        ShroudText(CameraRules.recordingLabel(elapsed), inter(15f, FontWeight.SemiBold, tabularDigits = true), Color.White)
    }
}

/**
 * The shutter: a 76 dp white ring around a 62 dp disc — white for a photo, red for video, a red
 * rounded square while recording.
 */
@Composable
private fun Shutter(mode: CameraMode, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, Motion.snappy(), label = "shutterInner")
    val corner by animateDpAsState(if (recording) 8.dp else 31.dp, Motion.snappy(), label = "shutterCorner")
    Box(
        Modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .pressable(enabled = enabled, scale = 0.92f, dimming = 0f, haptic = Haptic.None, onClick = onClick)
            .semantics {
                contentDescription = CameraRules.shutterLabel(mode, recording)
                role = Role.Button
            }
            .border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(RoundedCornerShape(corner))
                .background(if (mode == CameraMode.Photo) Color.White else colors.danger),
        )
    }
}

/** PHOTO / VIDEO in a dark capsule; the current one sits on the chrome fill. */
@Composable
private fun ModeSwitch(mode: CameraMode, enabled: Boolean, onMode: (CameraMode) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CameraMode.entries.forEach { item ->
            val selected = item == mode
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) MediaColors.chrome else Color.Transparent)
                    .pressable(enabled = enabled, scale = 0.94f, dimming = 0f, haptic = Haptic.None) { onMode(item) }
                    .semantics {
                        this.selected = selected
                        role = Role.Tab
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                ShroudText(item.title, inter(13f, FontWeight.SemiBold), if (selected) Color.White else Color.White.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable
private fun Pill(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pressable(scale = 0.94f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        ShroudText(text, inter(13f, FontWeight.SemiBold), Color.White)
    }
}

/** Video mode without the microphone: clips record without sound. */
@Composable
private fun SilentBadge() {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.SpeakerSlashFill, Color.White, size = 11.dp)
        ShroudText(CameraRules.NO_SOUND, inter(11f, FontWeight.Bold), Color.White)
    }
}

@Composable
private fun NoCameraState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CameraOffTile()
        ShroudText(CameraRules.NO_CAMERA, inter(20f, FontWeight.Bold), Color.White, textAlign = TextAlign.Center)
    }
}

/**
 * Camera refused (design oGuCt, "Camera — Denied", drawn for the QR scanner): black; Close; the
 * camera-off tile, title and line in the middle; one primary action at the bottom.
 */
@Composable
private fun DeniedState(access: CameraAccess, topInset: Dp, bottomInset: Dp, onClose: () -> Unit, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .padding(start = 16.dp, top = topInset + 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .pressable(scale = 0.92f, dimming = 0f, onClick = onClose)
                .semantics { contentDescription = "Close camera" },
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.X, Color.White, size = 20.dp)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CameraOffTile()
            ShroudText(CameraRules.DENIED_TITLE, inter(20f, FontWeight.Bold), Color.White, textAlign = TextAlign.Center)
            ShroudText(CameraRules.DENIED_BODY, inter(15f), Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center)
        }
        PrimaryButton(
            title = CameraRules.deniedAction(access),
            onClick = onAction,
            showsArrow = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 12.dp),
        )
    }
}

@Composable
private fun CameraOffTile() {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.CameraOff, Color.White, size = 30.dp)
    }
}

/** The compose screen's preview edge (conversation-compose-media §8.2: `decodePreview(…, 2048)`). */
private const val PREVIEW_MAX_EDGE = 2048

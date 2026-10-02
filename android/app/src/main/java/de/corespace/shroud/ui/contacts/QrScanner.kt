package de.corespace.shroud.ui.contacts

import android.Manifest
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.isOverlayUp
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberOverlayTransition
import de.corespace.shroud.ui.permissions.findActivity
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.permissions.rememberPermissionGranted
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/**
 * What the scanner shows (iOS `ScannerViewController`, `QRCodeScannerView.swift:41-65, 169-200`;
 * contacts §5.5 [AND], design oGuCt).
 */
enum class ScannerPhase {
    /** The camera runs. */
    Live,

    /** No camera access: the designed *Camera — Denied* screen (first or permanent denial alike). */
    Denied,

    /** No camera on this device, or it could not be opened: the hint says so (iOS "notDenied"). */
    NoCamera,
}

/** The pure transitions of [ScannerPhase], tested on the JVM. */
object ScannerPhases {
    /** What "Scan QR code" does first. */
    sealed interface Start {
        /** The camera permission's system dialog, over the Add Contact sheet (design w6957). */
        data object AskFirst : Start

        /** The scanner, straight away, in [phase]. */
        data class Open(val phase: ScannerPhase) : Start
    }

    /**
     * "Scan QR code" tapped: no camera → the scanner with the no-camera hint; allowed → the live
     * scanner; else the system dialog first, over the sheet, as w6957 draws it (iOS asks with the
     * cover already up, `:50-61`; the Android design keeps the sheet behind the dialog).
     */
    fun onScanTapped(hasCamera: Boolean, granted: Boolean): Start = when {
        !hasCamera -> Start.Open(ScannerPhase.NoCamera)
        granted -> Start.Open(ScannerPhase.Live)
        else -> Start.AskFirst
    }

    /** The dialog's answer opens the scanner; a denial (first or "don't ask again") on the denied screen. */
    fun afterRequest(granted: Boolean): ScannerPhase = if (granted) ScannerPhase.Live else ScannerPhase.Denied

    /** Back from Settings (`ON_RESUME`): access granted there starts the camera; nothing else changes. */
    fun onResume(phase: ScannerPhase, granted: Boolean): ScannerPhase =
        if (phase == ScannerPhase.Denied && granted) ScannerPhase.Live else phase

    /** Binding the camera failed (none usable): the no-camera hint (`:47-49`). */
    fun onBindFailure(phase: ScannerPhase): ScannerPhase = if (phase == ScannerPhase.Live) ScannerPhase.NoCamera else phase
}

/**
 * The full-screen QR scanner over Add Contact (iOS `QRCodeScannerView` in a `.fullScreenCover`,
 * always dark — `AddContactSheet.swift:62-77`; contacts §5.5).
 *
 * Human: A live camera with a glass ✕ and the hint "Point at their Shroud QR code". The first code
 * it reads closes the scanner and goes into the Add Contact field. Without camera access the
 * designed denied screen offers Settings or "Enter code instead"; access granted in Settings starts
 * the camera on return. Without a camera the hint says to paste the link instead.
 *
 * Agent: Up while [phase] is set (the sheet asked for the camera first, [ScannerPhases.onScanTapped]);
 * drawn in the [OverlayLayer] above the sheet; back (also predictive) cancels. The camera is bound
 * to the activity's lifecycle (it stops in the background) and unbound when the scanner closes or
 * before a read code is handed on ([QrScanHandoff]); the status and navigation bar icons are light
 * while it shows. [onPhaseChange] reports a camera that could not be opened and access granted in
 * Settings; [onCancel]'s flag asks the sheet to focus its field ("Enter code instead").
 */
@Composable
internal fun QrScannerOverlay(
    phase: ScannerPhase?,
    onPhaseChange: (ScannerPhase) -> Unit,
    onCode: (String) -> Unit,
    onCancel: (focusField: Boolean) -> Unit,
) {
    val visible = phase != null
    val visibility = rememberOverlayTransition(visible)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnPhaseChange by rememberUpdatedState(onPhaseChange)
    // The last phase shown, kept while the overlay slides away.
    var shown by remember { mutableStateOf(phase ?: ScannerPhase.Live) }
    if (phase != null) shown = phase
    OverlayLayer(active = visibility.isOverlayUp) {
        val transition = rememberTransition(visibility, label = "qrScanner")
        val reduceMotion = ShroudTheme.reduceMotion
        BackHandler(enabled = visible) { currentOnCancel(false) }
        transition.AnimatedVisibility(
            visible = { it },
            enter = if (reduceMotion) fadeIn(Motion.reduced()) else slideInVertically(Motion.gentle()) { it },
            exit = if (reduceMotion) fadeOut(Motion.reduced()) else slideOutVertically(Motion.standard()) { it },
        ) {
            // Always dark, like the other full-screen camera surfaces (`AddContactSheet.swift:74-76`).
            // The ✕ floats over the camera, which no backdrop can sample: flat glass, never a blur
            // of the sheet and list underneath.
            ShroudTheme(dark = true) {
                CompositionLocalProvider(LocalGlassBackdrop provides null) {
                    LightSystemBars()
                    QrScannerContent(
                        phase = shown,
                        active = visible,
                        onPhaseChange = { currentOnPhaseChange(it) },
                        onCode = { currentOnCode(it) },
                        onCancel = { currentOnCancel(it) },
                    )
                }
            }
        }
    }
}

/** Light status / navigation bar icons over the black scanner, restored when it goes (contacts §5.5). */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

@Composable
private fun QrScannerContent(
    phase: ScannerPhase,
    active: Boolean,
    onPhaseChange: (ScannerPhase) -> Unit,
    onCode: (String) -> Unit,
    onCancel: (focusField: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val granted by rememberPermissionGranted(Manifest.permission.CAMERA)
    // Back from Settings with access on: the camera starts (contacts §5.5 [AND], ON_RESUME).
    LaunchedEffect(granted) {
        val next = ScannerPhases.onResume(phase, granted)
        if (active && next != phase) onPhaseChange(next)
    }

    when (phase) {
        ScannerPhase.Denied -> CameraDeniedScreen(
            onClose = { onCancel(false) },
            onOpenSettings = { openAppSettings(context) },
            onEnterCode = { onCancel(true) },
        )
        else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (phase == ScannerPhase.Live && active) {
                CameraPreview(onCode = onCode, onUnavailable = { if (active) onPhaseChange(ScannerPhases.onBindFailure(phase)) })
            }
            ScannerChrome(
                hint = if (phase == ScannerPhase.NoCamera) ContactsCopy.SCANNER_NO_CAMERA else ContactsCopy.SCANNER_HINT,
                onClose = { onCancel(false) },
            )
        }
    }
}

/**
 * The live chrome (`QRCodeScannerView.swift:121-167`): a 44 dp glass ✕ (15 Bold white, "Cancel")
 * 12 dp under the status bar and 16 from the leading edge; the hint capsule (black @ 55 %, radius
 * 16, 15 Medium white, padding 8 / 14) centred 32 dp above the navigation bar, at least 24 from the
 * sides. The hint is a polite live region, so its change to the no-camera text is read out (`:199`).
 */
@Composable
internal fun ScannerChrome(hint: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            Modifier
                .padding(start = 16.dp, top = 12.dp)
                .pressable(scale = 0.92f, onClick = onClose)
                .size(44.dp)
                .glassSurface(CircleShape, GlassStyle.Soft)
                .clearAndSetSemantics { contentDescription = ContactsCopy.CANCEL },
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.XBold, Color.White, size = 15.dp)
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            ShroudText(hint, inter(15f, FontWeight.Medium), Color.White, textAlign = TextAlign.Center)
        }
    }
}

/**
 * CameraX: a `Preview` drawn by `CameraXViewfinder` (crop, as `resizeAspectFill`) and an
 * `ImageAnalysis` at about 1280 × 720 keeping only the latest frame, read by [QrFrameAnalyzer] on
 * its own thread. Back camera, else the front one (iOS takes the system default device). Bound on
 * the main thread to the activity's lifecycle; unbound — and the analysis thread shut down — when
 * this leaves composition, and unbound before a read code is handed on (`:213-216`).
 */
@Composable
private fun CameraPreview(onCode: (String) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (_: Exception) {
            currentOnUnavailable()
            return@LaunchedEffect
        }
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().also { use -> use.setSurfaceProvider { request -> surfaceRequest = request } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        var bound = false
        // Stops the camera, then hands the first code on; closed once this effect ends, so a code
        // read just before the scanner closed is dropped.
        val handoff = QrScanHandoff(
            unbind = {
                if (bound) {
                    provider.unbind(preview, analysis)
                    bound = false
                }
            },
            onCode = { currentOnCode(it) },
        )
        analysis.setAnalyzer(executor, QrFrameAnalyzer { text -> main.execute { handoff.deliver(text) } })
        try {
            val selector = cameraSelector(provider)
            if (selector == null) {
                currentOnUnavailable()
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                bound = true
            }
            awaitCancellation()
        } catch (e: IllegalArgumentException) {
            currentOnUnavailable()
        } catch (e: IllegalStateException) {
            currentOnUnavailable()
        } finally {
            handoff.close()
            if (bound) provider.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
            surfaceRequest = null
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * The scanner's one hand-over of a read code (iOS `metadataOutput(_:didOutput:from:)`,
 * `QRCodeScannerView.swift:202-217`): the first code [deliver]ed stops the camera ([unbind]) and
 * only then goes to [onCode] — the cover's dismissal never waits on a running camera; every later
 * code, and any after [close] (the scanner left), is dropped. Main thread only. The analyzer
 * ([QrFrameAnalyzer]) already emits once per bind; this guards the hop to the main thread, where a
 * frame read during the close could otherwise still land.
 */
internal class QrScanHandoff(private val unbind: () -> Unit, private val onCode: (String) -> Unit) {
    private var done = false

    /** True once a code went out or the scanner closed. */
    val isClosed: Boolean get() = done

    /** Hands [text] on if it is the first; false when it was dropped. */
    fun deliver(text: String): Boolean {
        if (done || text.isEmpty()) return false
        done = true
        unbind()
        onCode(text)
        return true
    }

    /** The scanner left: nothing more is handed on. */
    fun close() {
        done = true
    }
}

/** Back camera, else front; null when neither can be used. */
private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector? {
    fun has(selector: CameraSelector): Boolean = try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }
    return when {
        has(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        has(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> null
    }
}

/** About 720p for decoding: enough modules per pixel for a code at arm's length, cheap to binarise. */
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * *Camera — Denied* (design oGuCt; Android form of `showCameraUnavailable(denied: true)`,
 * `QRCodeScannerView.swift:171-200`): black; the ✕ (44 dp, white @ 12 %, Lucide `x` 20) top-left;
 * centred, 40 dp from the sides and 12 apart: a 72 dp white @ 12 % circle with Lucide `camera-off`
 * 30, "Camera access is off" 20 Bold, the reason 15 white @ 65 %; at the bottom (20 dp sides, 12
 * apart, 12 above the navigation bar) "Open Settings" (54 dp `accent` capsule, 17 SemiBold) and
 * "Enter code instead" (54 dp white @ 12 % capsule) — back to the sheet with the field focused.
 */
@Composable
internal fun CameraDeniedScreen(onClose: () -> Unit, onOpenSettings: () -> Unit, onEnterCode: () -> Unit) {
    val colors = ShroudTheme.colors
    val veil = Color.White.copy(alpha = 0.12f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(
            Modifier
                .padding(start = 16.dp, top = 12.dp)
                .pressable(scale = 0.92f, onClick = onClose)
                .size(44.dp)
                .clip(CircleShape)
                .background(veil)
                .clearAndSetSemantics { contentDescription = ContactsCopy.CANCEL },
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.X, Color.White, size = 20.dp)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(veil), contentAlignment = Alignment.Center) {
                ShroudIcon(ShroudIcons.CameraOff, Color.White, size = 30.dp)
            }
            ShroudText(
                ContactsCopy.CAMERA_OFF_TITLE,
                inter(20f, FontWeight.Bold),
                Color.White,
                Modifier.semantics { heading() },
                textAlign = TextAlign.Center,
            )
            ShroudText(ContactsCopy.CAMERA_OFF_BODY, inter(15f), Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeniedButton(ContactsCopy.OPEN_SETTINGS, colors.bubbleOutgoing, onOpenSettings)
            DeniedButton(ContactsCopy.ENTER_CODE_INSTEAD, veil, onEnterCode)
        }
    }
}

@Composable
private fun DeniedButton(title: String, fill: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.975f, dimming = 0.05f, onClick = onClick)
            .heightIn(min = 54.dp)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(title, inter(17f, FontWeight.SemiBold), Color.White, maxLines = 1)
    }
}

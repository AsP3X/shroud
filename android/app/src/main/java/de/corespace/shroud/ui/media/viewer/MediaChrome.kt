package de.corespace.shroud.ui.media.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.permissions.findActivity
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * Chrome shared by the full-screen media surfaces of W3-MEDIA-VIEW: the photo viewer, the video
 * player, the video compose screen and the camera. They are dark in both appearances (iOS sets
 * `.environment(\.colorScheme, .dark)` inside each, `MediaImageViewerOverlay.swift:139-141`).
 */

/**
 * A full-window media surface: its own root layer above the app (an [OverlayLayer], so TalkBack
 * only reaches it and its back handlers outrank the chat's), dark tokens inside only, light system
 * bar icons over the black, and — with [hideStatusBar] — no status bar while it is up
 * (`.statusBarHidden(true)`; the compose screens keep theirs, drawn light, as iOS does).
 */
@Composable
internal fun MediaLayer(hideStatusBar: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    OverlayLayer(active = true, modal = true) {
        ShroudTheme(dark = true) {
            DarkSystemBars(hideStatusBar)
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * Light status and navigation bar icons while composed, and the status bar hidden (transient on a
 * swipe) when [hideStatusBar]. Both go back when the last surface that asked leaves — a player
 * opened over the viewer shares one count with it.
 */
@Composable
internal fun DarkSystemBars(hideStatusBar: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hideStatusBar) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            MediaSystemBars.acquire(controller, hideStatusBar)
            onDispose { MediaSystemBars.release(controller, hideStatusBar) }
        }
    }
}

/** Reference counts of the media surfaces holding the system bars (main thread only). */
internal object MediaSystemBars {
    private var holders = 0
    private var hiders = 0
    private var lightStatus = false
    private var lightNavigation = false
    private var behavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

    fun acquire(controller: WindowInsetsControllerCompat, hideStatusBar: Boolean) {
        if (holders++ == 0) {
            lightStatus = controller.isAppearanceLightStatusBars
            lightNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        if (hideStatusBar && hiders++ == 0) {
            behavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    fun release(controller: WindowInsetsControllerCompat, hideStatusBar: Boolean) {
        if (hideStatusBar && hiders > 0 && --hiders == 0) {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = behavior
        }
        if (holders > 0 && --holders == 0) {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }

    val isStatusBarHidden: Boolean get() = hiders > 0
}

/**
 * The top band the media chrome keeps clear: the status bar's height even while it is hidden, or
 * the display cutout, whichever is taller. iOS floors this at 47 pt because its geometry reader
 * can report 0 (`MediaImageViewerOverlay.swift:103-104`); Android's insets are exact.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun mediaTopInset(): Dp {
    val density = LocalDensity.current
    val status = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val cutout = WindowInsets.displayCutout.getTop(density)
    return with(density) { max(status, cutout).toDp() }
}

/** The bottom band: the navigation bar, at least 8 dp (`max(bottomInset, 8)`). */
@Composable
internal fun mediaBottomInset(minimum: Dp = 8.dp): Dp {
    val density = LocalDensity.current
    val nav = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return if (nav > minimum) nav else minimum
}

/**
 * A 40 dp interactive glass circle with a white glyph, padded to a 48 dp target (`circleButton` /
 * `actionCircle`, `MediaImageViewerOverlay.swift:483-519`). [haptic] plays on press-down;
 * [Haptic.None] where the action fires its own.
 */
@Composable
internal fun MediaCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 17.dp,
    circle: Dp = 40.dp,
    padding: Dp = 4.dp,
    haptic: Haptic = Haptic.Light,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .pressable(enabled = enabled, scale = 1f, dimming = 0f, haptic = haptic, onClickLabel = null, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(circle)
                .glassSurface(CircleShape, GlassStyle.Regular, interactive = true),
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(icon, tint, size = iconSize)
        }
    }
}

/** The transient line a media surface flashes ("Copied", "Sound will be removed"). */
@Stable
internal class MediaBannerState {
    var text: String? by mutableStateOf(null)
        private set
    private var job: Job? = null

    /** Shows [message] for [millis]; a newer banner replaces it and its timer (`flashBanner`, `:595-606`). */
    fun flash(scope: CoroutineScope, message: String, millis: Long) {
        job?.cancel()
        text = message
        job = scope.launch {
            delay(millis)
            if (text == message) text = null
        }
    }

    fun clear() {
        job?.cancel()
        text = null
    }
}

@Composable
internal fun rememberMediaBanner(): MediaBannerState = remember { MediaBannerState() }

/**
 * The banner capsule (`MediaImageViewerOverlay.swift:126-137`, `VideoComposeOverlay.swift:139-152`):
 * 14 SemiBold white, padding 16 / 10, on glass ([fill] null) or a solid fill; fades in over
 * 150 ms and out over 200 ms; never takes a touch.
 */
@Composable
internal fun MediaBanner(text: String?, bottomPadding: Dp, modifier: Modifier = Modifier, fill: Color? = null) {
    // The last text stays on the capsule while it fades out (not state: it never drives a recomposition).
    val last = remember { arrayOfNulls<String>(1) }
    if (text != null) last[0] = text
    Box(modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = text != null,
            enter = fadeIn(Motion.easeOut(150)),
            exit = fadeOut(Motion.easeOut(200)),
        ) {
            val shape = CircleShape
            val surface = if (fill == null) {
                Modifier.glassSurface(shape, GlassStyle.Regular)
            } else {
                Modifier.clip(shape).background(fill)
            }
            Box(surface.padding(horizontal = 16.dp, vertical = 10.dp)) {
                ShroudText(last[0].orEmpty(), inter(14f, FontWeight.SemiBold), Color.White)
            }
        }
    }
}

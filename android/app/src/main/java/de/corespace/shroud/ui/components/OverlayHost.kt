package de.corespace.shroud.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The root layer every menu, sheet and dialog of the app is drawn in (00-plan §1.7.12; W1-INT puts
 * it around the whole of `ShroudApp`).
 *
 * Human: iOS presents sheets, menus and alerts above everything — the tab bar, toasts, the call
 * pill — wherever the code that opens them sits. Android has no such layer without separate
 * windows, and separate windows (`Dialog`, `Popup`) would not carry the activity's
 * `FLAG_SECURE` (plan P5), could not blur the app behind them and break edge-to-edge insets.
 * So overlays are drawn here, inside the one window, above the app's content, in the order
 * they opened.
 *
 * Agent: Components declare their surface with [OverlayLayer] where they are used; the layer is
 * rendered here with the composition locals of the place that declared it (theme, insets, back
 * dispatcher, tab-bar clearance…). While a modal layer is up, everything below it is hidden from
 * TalkBack (the content and lower layers), and on API 31+ the app content can be blurred behind
 * it ([OverlayLayer]'s `backdropBlur`, used by the light context menu's blur scrim, shell-chats
 * §8.7). Without a host above it (previews, tests, today's `ShroudApp` until W1-INT wraps it) an
 * [OverlayLayer] draws in place as a full-size box, which is what `ShroudSheet` always did.
 */
@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    if (LocalOverlayHost.current != null) {
        // A host inside a host adds nothing: the outer one already covers the window.
        content()
        return
    }
    val state = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides state) {
        Box(Modifier.fillMaxSize()) {
            val topModal = state.topModalIndex()
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.backdropBlur(state) else Modifier)
                    .then(if (topModal >= 0) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                content()
            }
            state.layers.forEachIndexed { index, entry ->
                key(entry.id) { OverlayLayerContent(entry, hiddenFromAccessibility = index < topModal) }
            }
        }
    }
}

/** The host of the current window; null outside an [OverlayHost]. */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/** The layers an [OverlayHost] draws, bottom to top in the order they opened. */
@Stable
class OverlayHostState {
    private var nextId = 0L
    internal val layers = mutableStateListOf<OverlayLayerEntry>()

    /** Number of layers up (shown or still animating out). */
    val layerCount: Int get() = layers.size

    /** True while any modal layer is up; the content below is then hidden from TalkBack. */
    val hasModalLayer: Boolean get() = layers.any { it.modal }

    internal fun newEntry(): OverlayLayerEntry = OverlayLayerEntry(nextId++)

    internal fun show(entry: OverlayLayerEntry) {
        if (entry !in layers) layers.add(entry)
    }

    internal fun hide(entry: OverlayLayerEntry) {
        layers.remove(entry)
    }

    /** Index of the topmost modal layer, or −1: TalkBack only reaches it and what lies above it. */
    internal fun topModalIndex(): Int = layers.indexOfLast { it.modal }

    /** The strongest blur any layer asks for behind it (0 when none). */
    internal fun backdropBlur(): Dp {
        var max = 0.dp
        for (layer in layers) {
            val blur = layer.blur()
            if (blur > max) max = blur
        }
        return max
    }
}

/** One layer: what to draw and the locals of the place that declared it. */
@Stable
internal class OverlayLayerEntry(val id: Long) {
    var modal by mutableStateOf(true)
    var blur: () -> Dp by mutableStateOf({ 0.dp })
    var locals: CompositionLocalContext? by mutableStateOf(null)
    var content: @Composable () -> Unit by mutableStateOf({})
}

/**
 * Declares a full-window layer drawn by the nearest [OverlayHost] while [active] is true — the
 * building block of every overlay here, public for overlays of later packages (message menu,
 * viewers) that need the same root layer.
 *
 * Keep [active] true while the layer animates out ([rememberOverlayTransition] and
 * [isOverlayUp] do that), or the exit is cut off. [modal] layers hide what is below from
 * TalkBack. [backdropBlur] is read while drawing (animate it without recomposing) and blurs the
 * app content below on API 31+ ([overlayCanBlur]).
 */
@Composable
fun OverlayLayer(
    active: Boolean,
    modal: Boolean = true,
    backdropBlur: () -> Dp = NoBackdropBlur,
    content: @Composable () -> Unit,
) {
    val host = LocalOverlayHost.current
    if (host == null) {
        if (active) Box(Modifier.fillMaxSize()) { content() }
        return
    }
    val locals = currentCompositionLocalContext
    val entry = remember(host) { host.newEntry() }
    SideEffect {
        entry.modal = modal
        entry.blur = backdropBlur
        entry.locals = locals
        entry.content = content
    }
    if (active) {
        DisposableEffect(host, entry) {
            host.show(entry)
            onDispose { host.hide(entry) }
        }
    }
}

/**
 * True when an [OverlayLayer] declared here can blur the app behind it: API 31+ (RenderEffect)
 * and an [OverlayHost] to apply it. Otherwise overlays use their no-blur fills (design
 * `Glass — Without Blur`, Gwp1b).
 */
@Composable
fun overlayCanBlur(): Boolean = LocalOverlayHost.current != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The visibility of an overlay that animates in and out: drive its enter/exit transitions from
 * it, and keep its [OverlayLayer] up while [isOverlayUp] (shown, or still running its exit).
 */
@Composable
fun rememberOverlayTransition(visible: Boolean): MutableTransitionState<Boolean> =
    remember { MutableTransitionState(false) }.apply { targetState = visible }

/** Shown, or still animating out. */
val MutableTransitionState<Boolean>.isOverlayUp: Boolean get() = currentState || targetState

private val NoBackdropBlur: () -> Dp = { 0.dp }

@Composable
private fun OverlayLayerContent(entry: OverlayLayerEntry, hiddenFromAccessibility: Boolean) {
    val locals = entry.locals ?: return
    CompositionLocalProvider(locals) {
        Box(Modifier.fillMaxSize().then(if (hiddenFromAccessibility) Modifier.clearAndSetSemantics {} else Modifier)) {
            entry.content()
        }
    }
}

/** API 31+: blurs the app content behind a layer that asks for it (RenderEffect, no Haze needed). */
private fun Modifier.backdropBlur(state: OverlayHostState): Modifier = graphicsLayer {
    val radius = state.backdropBlur().toPx()
    renderEffect = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
}

// ---- Shared pieces of the overlay components (internal) ---------------------------------------

/**
 * Overlay colours, light and dark — the shell-chats §15.1 tokens (`menuScrim`, `sheetScrim`,
 * `cardGlass`, `cardStroke`, `cardOpaque`, `chevron`, `rowPressed`), the design's no-blur menu
 * scrim (`Glass — Without Blur`, design-inventory §2) and the attach sheet's scrim
 * (`MediaColors.sheetScrim`, conversation-compose-media §2.3). W1-UI-THEME adds the same values
 * to `ShroudColors` / `MediaColors`; they live here until W1-INT points these components at the
 * tokens (contract change request in the W1-UI-OVERLAYS report).
 */
@Immutable
internal data class OverlayPalette(
    /** Behind the light context menu, with a 12 dp blur (API 31+); design `Blur Scrim`. */
    val menuScrim: Color,
    /** Behind the light context menu without blur (API 30); design `Gwp1b`. */
    val menuScrimNoBlur: Color,
    /** Behind action sheets, alerts, inset sheets and large sheets; design `New Chat` `Scrim`. */
    val sheetScrim: Color,
    /** Behind the compact (attach) sheet; design `Attach Open` `Scrim` (35 %). */
    val compactSheetScrim: Color,
    /** Light menu card over a blurred backdrop. */
    val cardGlass: Color,
    val cardStroke: Color,
    /** Card fill where nothing behind it is blurred. */
    val cardOpaque: Color,
    /** Grabber of inset sheets (iOS `systemGray3`). */
    val chevron: Color,
    /** Pressed row on a card (iOS `systemGray5`). */
    val rowPressed: Color,
)

internal val LightOverlayPalette = OverlayPalette(
    menuScrim = Color(0x59F2F2F7),
    menuScrimNoBlur = Color(0x590B0B12),
    sheetScrim = Color(0x470B0B12),
    compactSheetScrim = Color(0x590B0B12),
    cardGlass = Color(0xD1FFFFFF),
    cardStroke = Color(0x99FFFFFF),
    cardOpaque = Color(0xF5FFFFFF),
    chevron = Color(0xFFC7C7CC),
    rowPressed = Color(0xFFE5E5EA),
)

internal val DarkOverlayPalette = OverlayPalette(
    menuScrim = Color(0x470F0F14),
    menuScrimNoBlur = Color(0x590B0B12),
    sheetScrim = Color(0x470B0B12),
    compactSheetScrim = Color(0x590B0B12),
    cardGlass = Color(0xF01F1F24),
    cardStroke = Color(0x14FFFFFF),
    cardOpaque = Color(0xF51F1F24),
    chevron = Color(0xFF48484A),
    rowPressed = Color(0xFF2C2C2E),
)

@Composable
internal fun overlayPalette(): OverlayPalette =
    if (ShroudTheme.colors.isDark) DarkOverlayPalette else LightOverlayPalette

/** Shadow colours of the design's overlay surfaces (`#0B0B12` at the alpha the design gives). */
internal object OverlayShadows {
    /** Card glass: 0/12/32 #0B0B1229 (design `Light context menu`). */
    val card = Color(0x290B0B12)

    /** Lifted context-menu row: 0/8/24 #0B0B121F (shell-chats §8.7). */
    val liftedRow = Color(0x1F0B0B12)

    /** Inset sheet (Device Details nUbf0): 0/−4/30 #0B0B1229. */
    val insetSheet = Color(0x290B0B12)

    /** Compact sheet (Attach w4lZ1): 0/−8/32 #0B0B1233. */
    val compactSheet = Color(0x330B0B12)

    /** Onboarding sheet (aNX3S): 0/−12/40 #0B0B1240. */
    val fullSheet = Color(0x400B0B12)
}

/**
 * Menu springs of `Motion.swift:36-39` and the ease curves of `.easeInOut` (W1-UI-THEME adds them
 * to `Motion` as `menuLift()` / `menuDrop()` / `easeInOut(ms)`; same numbers).
 */
internal object OverlayMotion {
    /** Telegram's context-menu spring: mass 5, stiffness 900, damping 104 (MOT:36). */
    fun <T> menuLift(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7752f, stiffness = 180f)

    /** SwiftUI `.easeInOut`. */
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Putting it back: 0.2 s ease-in-out (MOT:38-39). */
    const val MENU_DROP_MS = 200

    fun <T> menuDrop(): FiniteAnimationSpec<T> = tween(MENU_DROP_MS, easing = EaseInOut)
}

/**
 * The few haptics the overlays fire, with the mapping of 00-plan §1.7.12 (C19). W1-UI-THEME's
 * `View.perform(Haptic)` holds the full table; these stay identical to it (W1-INT may route them
 * through it).
 */
internal object OverlayHaptics {
    /** `Haptic.Light`: iOS impact light. */
    fun light(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** `Haptic.SegmentTick`: pull-to-refresh crossing its threshold. */
    fun segmentTick(view: View) {
        view.performHapticFeedback(segmentTickConstant(Build.VERSION.SDK_INT))
    }

    /** `SEGMENT_TICK` on Android 14+, else `CLOCK_TICK` (00-plan §1.7.12). */
    fun segmentTickConstant(sdk: Int): Int =
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
}

/**
 * Back for an overlay: system back, the back gesture and predictive back all call [onBack]; while
 * a predictive back gesture runs, the returned state follows its progress (0…1) so the surface
 * can lean back. It settles back to 0 when the gesture is cancelled and after [onBack] ran (an
 * overlay that stays up — a submenu going back to its first list — must not stay leaning).
 */
@Composable
internal fun rememberOverlayBack(enabled: Boolean, onBack: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(enabled) {
        if (enabled) progress.snapTo(0f)
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event -> progress.snapTo(event.progress) }
            currentOnBack()
            scope.launch { progress.animateTo(0f, tween(BACK_SETTLE_MS)) }
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(0f, tween(BACK_SETTLE_MS)) }
            throw cancelled
        }
    }
    return progress.asState()
}

private const val BACK_SETTLE_MS = 150

/**
 * How far a surface leans away while a predictive back gesture runs: it shrinks by up to
 * [maxShrink] of its size. Pure, so the overlays share one curve.
 */
internal fun backLeanScale(progress: Float, maxShrink: Float = 0.06f): Float = 1f - maxShrink * progress.coerceIn(0f, 1f)

/**
 * A scrim's tap: dismisses, except within [guardMillis] of opening — the lift of the finger that
 * opened a long-press menu must not close it again (`ConversationView.swift:1936-1937`,
 * memory "hold release fires bubble controls"). The scrim also swallows every other touch, so
 * nothing below an open overlay can be reached.
 */
internal fun Modifier.dismissOnTap(openedAt: Long, guardMillis: Long = 0, onDismiss: () -> Unit): Modifier = composed {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    pointerInput(openedAt, guardMillis) {
        detectTapGestures {
            if (tapDismisses(openedAt, SystemClock.uptimeMillis(), guardMillis)) currentOnDismiss()
        }
    }
}

/** True when a scrim tap at [now] closes an overlay opened at [openedAt] (both `uptimeMillis`). */
internal fun tapDismisses(openedAt: Long, now: Long, guardMillis: Long): Boolean = now - openedAt >= guardMillis

/** The long-press menus ignore scrim taps this long after opening (`ConversationView.swift:1936-1937`). */
internal const val MENU_OPEN_TAP_GUARD_MS = 400L

/**
 * The modal pane of an overlay for TalkBack: announced by [title] when it appears, read as one
 * traversal group, closable with TalkBack's dismiss action (the scrim tap and back do it for
 * everyone else).
 */
internal fun Modifier.overlayPane(title: String, onDismiss: (() -> Unit)?): Modifier = semantics {
    paneTitle = title
    isTraversalGroup = true
    if (onDismiss != null) {
        dismiss {
            onDismiss()
            true
        }
    }
}

package de.corespace.shroud.ui.calls

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.corespace.shroud.core.calls.ActiveCall
import de.corespace.shroud.core.calls.CallPermissionPrompt
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The call screen over the app while a call runs (iOS `InCallOverlay` in `RootView`,
 * `RootView.swift:93-102`; calls §8.1). Draws nothing without a call.
 *
 * - **Shown** whenever `calls.controller.ui.active` exists: fades in (scale 0.98 → 1,
 *   `Motion.gentle`; fade only under reduce motion). It fades out only after "Call ended"; from a
 *   ring (decline, cancel, sign-out) it goes at once.
 * - **Back leaves the call screen** (P17b, calls D9): the call goes on, and the screen
 *   [minimises][InCallPresentation.minimize] to a pill at the top — the app's form of iOS's green
 *   call pill — whose tap brings the screen back. A new call always shows in full.
 * - **TalkBack** focus moves onto the call when one appears (`RootView.swift:238-243`).
 * - The **status bar** turns light once the screen covers the app (600 ms, `fadeInCover`) and back
 *   when it goes.
 * - It registers the call's **permission prompt** (`calls.permissions.prompt`): the controller asks
 *   for the microphone and the camera through the system dialog of this window.
 *
 * Entry-point signature frozen by plan §1.7.13; the shell places it above its content.
 */
@Composable
fun InCallOverlay(modifier: Modifier = Modifier) {
    val ports = rememberCallPorts()
    CallPermissionPromptHost(ports)
    val state by ports.ui.collectAsState()
    val minimized by InCallPresentation.minimizedCall.collectAsState()
    val call = state.active
    val full = call != null && minimized != call.id
    val reduceMotion = ShroudTheme.reduceMotion
    val focus = remember { FocusRequester() }

    // The last call and phase, kept while the screen fades out after "Call ended" (:47-50, 79-86).
    // Plain holders: what was last shown is read in this composition, never observed.
    val last = remember { LastCall() }
    if (call != null) last.call = call
    val shownCall = last.call
    val lastPhase = shownCall?.phase

    val enter: EnterTransition = if (reduceMotion) {
        fadeIn(Motion.reduced())
    } else {
        fadeIn(Motion.gentle()) + scaleIn(Motion.gentle(), initialScale = 0.98f)
    }
    val exit: ExitTransition = when {
        call != null -> fadeOut(Motion.respecting(reduceMotion, Motion.gentle()))
        lastPhase == CallPhase.Ending -> fadeOut(Motion.respecting(reduceMotion, Motion.gentle())) +
            if (reduceMotion) ExitTransition.None else scaleOut(Motion.gentle(), targetScale = 0.98f)
        else -> ExitTransition.None
    }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = full, enter = enter, exit = exit) {
            val visibleCall = shownCall ?: return@AnimatedVisibility
            BackHandler(enabled = call != null) { InCallPresentation.minimize(visibleCall.id) }
            CallScreen(ports = ports, state = state, call = visibleCall, focusRequester = focus)
        }
        AnimatedVisibility(
            visible = call != null && !full,
            enter = slideInVertically(Motion.snappy()) { -it } + fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.fade()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            shownCall?.let { pillCall ->
                MinimizedCallPill(call = pillCall, onRestore = { InCallPresentation.restore() })
            }
        }
    }
    // A new call shows in full, and TalkBack moves onto it.
    LaunchedEffect(call?.id) {
        val id = call?.id ?: return@LaunchedEffect
        InCallPresentation.onCall(id)
        // Once the screen is laid out (a frame), so the focus target is attached.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    LightStatusBarWhileShown(full)
}

/**
 * Whether the call screen is minimised to its pill, process-wide (one call controller, one call).
 * The shell reads [coversApp] to keep its content out of TalkBack's reach only while the full call
 * screen covers it (merge note: `RootScreen` hides the shell for `callActive`).
 */
object InCallPresentation {
    private val minimized = MutableStateFlow<UUID?>(null)

    /** The call whose screen is minimised to the pill; null while a call shows in full (or none runs). */
    val minimizedCall: StateFlow<UUID?> = minimized.asStateFlow()

    /** Back on the call screen: the call goes on under the pill. */
    fun minimize(callId: UUID) {
        minimized.value = callId
    }

    /** The pill's tap: the call screen again. */
    fun restore() {
        minimized.value = null
    }

    /** A call appeared: a different one than the minimised call shows in full. */
    fun onCall(callId: UUID) {
        if (minimized.value != null && minimized.value != callId) minimized.value = null
    }

    /** The full call screen covers the app: [active] runs and is not minimised. */
    fun coversApp(active: ActiveCall?): Boolean = active != null && minimized.value != active.id
}

/**
 * The minimised call (P17b): a green capsule at the top, under the status bar — a pulsing dot,
 * "<name> · <time or status>" — the app's form of iOS's system call pill. A tap brings the call
 * screen back. One TalkBack element, "Return to call with <name>" (web `CallPill`).
 */
@Composable
internal fun MinimizedCallPill(call: ActiveCall, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pulse = rememberInfiniteTransition(label = "pillDot")
    val dot by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800, easing = Motion.IosEaseInOut), RepeatMode.Reverse), label = "pillDotAlpha")
    val now by rememberCallClock(call)
    val status = CallScreenRules.status(call, now).text
    Row(
        modifier
            .padding(top = statusTop + 4.dp, start = 16.dp, end = 16.dp)
            .heightIn(min = PILL_HEIGHT.dp)
            .widthIn(max = 320.dp)
            .background(colors.successFill, CircleShape)
            .pressable(scale = 0.96f, onClick = onRestore)
            .clearAndSetSemantics {
                contentDescription = "Return to call with ${call.peerUsername}"
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = if (reduceMotion) 1f else dot }
                .background(Color.White, CircleShape),
        )
        ShroudIcon(ShroudIcons.PhoneFill, Color.White, size = 14.dp)
        ShroudText(
            text = "${call.peerUsername} · $status",
            style = inter(13f, FontWeight.SemiBold, tabularDigits = true),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The call last on screen, for the fade out after it is gone. */
private class LastCall {
    var call: ActiveCall? = null
}

private const val PILL_HEIGHT = 32

/**
 * Registers the controller's permission prompt for this window (`AndroidCallPermissions.prompt`,
 * `CallsModule`): the microphone before placing or answering, the camera for video (calls §6.8).
 * The system dialog shows over the lock screen too, from `CallActivity`. One request at a time; a
 * window that goes away answers a pending request with "refused".
 */
@Composable
internal fun CallPermissionPromptHost(ports: CallPorts) {
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.complete(granted)
        pending = null
    }
    DisposableEffect(ports, launcher) {
        val prompt = CallPermissionPrompt { permission ->
            pending?.complete(false)
            val request = CompletableDeferred<Boolean>()
            pending = request
            try {
                launcher.launch(permission)
            } catch (_: android.content.ActivityNotFoundException) {
                request.complete(false)
            }
            request.await()
        }
        ports.permissionPrompt = prompt
        onDispose {
            if (ports.permissionPrompt === prompt) ports.permissionPrompt = null
            pending?.complete(false)
            pending = null
        }
    }
}

/**
 * Light status-bar icons over the dark call screen once it has covered the app
 * (`darkenWindow`, :96-109), back to what they were when it goes.
 */
@Composable
internal fun LightStatusBarWhileShown(shown: Boolean) {
    val view = LocalView.current
    var covered by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (!shown) {
            covered = false
            return@LaunchedEffect
        }
        delay(CallScreenRules.FADE_IN_COVER_MS)
        covered = true
    }
    DisposableEffect(view, covered) {
        val window = view.context.findActivity()?.window
        if (!covered || window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

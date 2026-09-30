package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay

/** A transient capsule notice (`ToastBanner.swift`). */
data class Toast(val message: String, val style: Style = Style.Success, val durationMillis: Long = 1800) {
    enum class Style { Success, Info, Failure }

    companion object {
        fun info(message: String, durationMillis: Long = 1800) = Toast(message, Style.Info, durationMillis)
        fun failure(message: String, durationMillis: Long = 2400) = Toast(message, Style.Failure, durationMillis)
    }
}

@Stable
class ToastState {
    var current by mutableStateOf<Toast?>(null)
        private set
    private var serial by mutableStateOf(0)

    fun show(toast: Toast) {
        current = toast
        serial++
    }

    internal fun key() = serial

    internal fun clear() {
        current = null
    }
}

@Composable
fun rememberToastState() = remember { ToastState() }

/** Floats the current toast over the bottom of the screen and clears it after its duration. */
@Composable
fun BoxScope.ToastHost(state: ToastState) {
    val toast = state.current
    var shown by remember { mutableStateOf<Toast?>(null) }
    if (toast != null) shown = toast
    LaunchedEffect(state.key()) {
        val current = state.current ?: return@LaunchedEffect
        delay(current.durationMillis)
        state.clear()
    }
    AnimatedVisibility(
        visible = toast != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        enter = slideInVertically(Motion.standard()) { it } + scaleIn(Motion.standard(), 0.9f, TransformOrigin(0.5f, 1f)) + fadeIn(Motion.fade()),
        exit = slideOutVertically(Motion.standard()) { it } + scaleOut(Motion.standard(), 0.9f, TransformOrigin(0.5f, 1f)) + fadeOut(Motion.fade()),
    ) {
        shown?.let { ToastBanner(it) }
    }
}

@Composable
private fun ToastBanner(toast: Toast) {
    val colors = ShroudTheme.colors
    val (icon, tint) = when (toast.style) {
        Toast.Style.Success -> ShroudIcons.CheckCircleFill to colors.online
        Toast.Style.Info -> ShroudIcons.InfoFill to colors.accent
        Toast.Style.Failure -> ShroudIcons.WarningCircleFill to colors.danger
    }
    // Opaque: without a backdrop blur the translucent glass let the button under it show through.
    Box(
        Modifier
            .glass(if (colors.isDark) colors.bubbleIncoming else colors.background, colors.glassStroke)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShroudIcon(icon, tint, size = 16.dp)
            ShroudText(toast.message, inter(14f, FontWeight.SemiBold), colors.textPrimary)
        }
    }
}

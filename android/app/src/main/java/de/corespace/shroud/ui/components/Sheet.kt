package de.corespace.shroud.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlinx.coroutines.launch

/**
 * The app's own large sheet (the design's sheets, not Material's): scrim, 22 dp top corners,
 * a 36 × 5 handle, dragged down or backed out to close. Lies over the whole window.
 */
@Composable
fun ShroudSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ShroudTheme.colors
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(Motion.scrim()), exit = fadeOut(Motion.scrim())) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.gentle()) { it },
            exit = slideOutVertically(Motion.standard()) { it },
        ) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val dismissDistance = with(LocalDensity.current) { 120.dp.toPx() }
            LaunchedEffect(Unit) { drag.snapTo(0f) }
            Column(
                modifier
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = drag.value }
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(colors.background)
                    .semantics { dismiss { onDismiss(); true } }
                    .imePadding(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                            },
                            onDragStopped = { velocity ->
                                if (drag.value > dismissDistance || velocity > 2000f) {
                                    onDismiss()
                                } else {
                                    drag.animateTo(0f, Motion.snappy())
                                }
                            },
                        )
                        .padding(top = 10.dp, bottom = 8.dp)
                        .semantics { contentDescription = "Drag down to close" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(colors.textSecondary.copy(alpha = 0.35f)))
                }
                content()
            }
        }
    }
}

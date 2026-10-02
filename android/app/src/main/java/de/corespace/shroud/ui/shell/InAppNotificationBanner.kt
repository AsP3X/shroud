package de.corespace.shroud.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.notifications.InAppNotification
import de.corespace.shroud.ui.components.Avatar
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** The banner's gesture thresholds (`InAppNotificationBanner.swift:42-52`). */
object InAppBannerGesture {
    /** A drag starts after 6 dp. */
    const val MIN_DRAG_DP = 6f

    /** Released this far up, the banner goes. */
    const val DISMISS_DP = -24f

    /** Or when the flick would carry it this far (iOS `predictedEndTranslation`). */
    const val PREDICTED_DISMISS_DP = -60f

    /** iOS's predicted end ≈ translation + velocity · 0.1 s. */
    const val PREDICTION_SECONDS = 0.1f

    /** Only upward movement shows (`offset(y: min(0, dragOffset))`, `:36`). */
    fun visibleOffset(dragY: Float): Float = min(0f, dragY)

    /** A release dismisses: far enough up, or flicked up hard enough. */
    fun dismisses(dragYDp: Float, velocityYDpPerSecond: Float): Boolean =
        dragYDp < DISMISS_DP || dragYDp + velocityYDpPerSecond * PREDICTION_SECONDS < PREDICTED_DISMISS_DP
}

/**
 * Telegram's in-app notification card (iOS `InAppNotificationBanner`, `InAppNotificationBanner.swift:9-72`;
 * design `Chats — In-App Banner` qvEKj; shell-chats §10.9, notifications-push §5.13): glass card
 * (radius 24, card tier: blur 24 on API 31+, near-opaque without), avatar 40 + title 15 SemiBold +
 * body 14 (two lines). Tap: light haptic, then [onOpen]. A drag upward follows the finger and, past
 * 24 dp or with a flick, [onDismiss]es; otherwise it springs back ([Motion.snappy]).
 *
 * TalkBack: one button, "Opens the chat", custom action "Dismiss".
 */
@Composable
fun InAppNotificationBanner(notification: InAppNotification, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth()
            .graphicsLayer { translationY = InAppBannerGesture.visibleOffset(drag.value) }
            .glassSurface(shape, GlassStyle.LightMenu, interactive = true)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var totalY = 0f
                    var tapped = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        totalY += change.positionChange().y
                        val moved = change.position - down.position
                        if (!dragging && (abs(moved.x) > InAppBannerGesture.MIN_DRAG_DP.dp.toPx() || abs(moved.y) > InAppBannerGesture.MIN_DRAG_DP.dp.toPx())) {
                            dragging = true
                            tapped = false
                        }
                        if (dragging) {
                            change.consume()
                            scope.launch { drag.snapTo(totalY) }
                        }
                    }
                    if (tapped) {
                        view.perform(Haptic.Light)
                        currentOnOpen()
                    } else {
                        val velocity = tracker.calculateVelocity().y / density
                        if (InAppBannerGesture.dismisses(totalY / density, velocity)) {
                            currentOnDismiss()
                        } else {
                            scope.launch { drag.animateTo(0f, Motion.snappy()) }
                        }
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Opens the chat") {
                    currentOnOpen()
                    true
                }
                customActions = listOf(CustomAccessibilityAction("Dismiss") { currentOnDismiss(); true })
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The sender's initials only when the title is the sender (`:59-71`); else the mark.
        val username = notification.username
        if (username != null && notification.title == username) {
            Avatar(AvatarPalette.initials(username), 40.dp, AvatarPalette.brush(username), fontSize = 15.sp)
        } else {
            BrandLogoMark(40.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(notification.title, inter(15f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ShroudText(notification.body, inter(14f), colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Hosts the current banner above the app, under the in-call overlay (iOS `InAppNotificationHost`,
 * `InAppNotificationBanner.swift:74-105`; shell-chats §10.10): top-aligned, padding h 8, top =
 * status bar + 4; keyed by banner id, it slides in from the top and fades ([Motion.standard]; reduce
 * motion: fade only). A new banner is announced politely ("{title}: {body}"), focus stays put.
 * Lifetime, dismissal on background and lock are the controller's (W2-NOTIF).
 */
@Composable
fun InAppNotificationHost(banner: InAppNotification?, onOpen: (InAppNotification) -> Unit, onDismiss: (InAppNotification) -> Unit, modifier: Modifier = Modifier) {
    val reduce = ShroudTheme.reduceMotion
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = banner,
            contentKey = { it?.id },
            transitionSpec = {
                val spec = Motion.respecting(reduce, Motion.standard<Float>())
                if (reduce) {
                    fadeIn(spec) togetherWith fadeOut(spec)
                } else {
                    (slideInVertically(Motion.standard()) { -it } + fadeIn(spec)) togetherWith
                        (slideOutVertically(Motion.standard()) { -it } + fadeOut(spec))
                }
            },
            label = "inAppBanner",
        ) { current ->
            if (current != null) {
                InAppNotificationBanner(
                    notification = current,
                    onOpen = { onOpen(current) },
                    onDismiss = { onDismiss(current) },
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp, top = top)
                        // TalkBack hears the arrival like a system banner (`:99-103`).
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "${current.title}: ${current.body}"
                        },
                )
            } else {
                Box(Modifier)
            }
        }
    }
}

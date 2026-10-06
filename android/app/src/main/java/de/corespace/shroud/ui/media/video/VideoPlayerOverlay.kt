package de.corespace.shroud.ui.media.video

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.lifecycle.compose.LifecycleEventEffect
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.VideoSource
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.RollingText
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.overlayPane
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberOverlayBack
import de.corespace.shroud.ui.media.viewer.MediaCircleButton
import de.corespace.shroud.ui.media.viewer.MediaLayer
import de.corespace.shroud.ui.media.viewer.blockTouches
import de.corespace.shroud.ui.media.viewer.mediaBottomInset
import de.corespace.shroud.ui.media.viewer.mediaTopInset
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The full-screen video player (conversation-compose-media §17; iOS `VideoPlayerOverlay`,
 * `ios/shroud/ShroudUI/Components/VideoPlayerOverlay.swift`), on a `ChatVideoPlayer` from
 * `VideoModule.newPlayer()`, which plays the same [VideoSource] (`Message` = a message's sealed
 * video, `Content` = a picked or captured file).
 *
 * Human: Telegram's player, not the system's — black, sender and date at the top, a slim scrubber
 * with elapsed and remaining time at the bottom, a large play / pause disc in the middle. The
 * chrome hides itself 2.8 s into playback (never while TalkBack runs, and later when the user's
 * accessibility timeout asks for it); a tap brings it back. Drag the clip down (or back) to close.
 *
 * Agent: plays at once; the player is torn down when the overlay leaves composition. A sealed
 * message video plays through the decrypting source — no decrypted file is written.
 */
@Composable
fun VideoPlayerOverlay(source: VideoSource, title: String, subtitle: String, onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val player = remember(container) { container.video.newPlayer() }
    VideoPlayerOverlayContent(source, title, subtitle, onClose, player)
}

/** [VideoPlayerOverlay] on an explicit [player]; the entry point and the tests call it. */
@Composable
internal fun VideoPlayerOverlayContent(source: VideoSource, title: String, subtitle: String, onClose: () -> Unit, player: ChatVideoPlayer) {
    MediaLayer {
        PlayerBody(source, title, subtitle, onClose, player)
    }
}

@Composable
private fun PlayerBody(source: VideoSource, title: String, subtitle: String, onClose: () -> Unit, player: ChatVideoPlayer) {
    val state by player.state.collectAsState()
    val surface by player.player.collectAsState()
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduceMotion = ShroudTheme.reduceMotion
    val currentOnClose by rememberUpdatedState(onClose)
    val assistive by rememberTouchExploration()
    val composeAccessibility = LocalAccessibilityManager.current
    val idleMillis = remember(composeAccessibility) {
        composeAccessibility?.calculateRecommendedTimeoutMillis(
            VideoPlayerRules.CHROME_IDLE_MS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: VideoPlayerRules.CHROME_IDLE_MS
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var drag by remember { mutableFloatStateOf(0f) } // dp
    var scrubTime by remember { mutableStateOf<Double?>(null) }
    var closing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }

    fun scheduleHide() {
        hideJob?.cancel()
        if (!VideoPlayerRules.autoHides(player.state.value.isPlaying, scrubTime != null, assistive)) return
        if (idleMillis == Long.MAX_VALUE) return
        hideJob = scope.launch {
            delay(idleMillis)
            if (VideoPlayerRules.autoHides(player.state.value.isPlaying, scrubTime != null, assistive)) chromeVisible = false
        }
    }

    fun showChrome() {
        chromeVisible = true
        scheduleHide()
    }

    fun toggleChrome() {
        if (chromeVisible) {
            hideJob?.cancel()
            chromeVisible = false
        } else {
            showChrome()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        currentOnClose()
    }

    LaunchedEffect(player, source) {
        player.start(source)
        scheduleHide()
    }
    DisposableEffect(player) {
        onDispose {
            hideJob?.cancel()
            player.teardown()
        }
    }
    // Playing schedules the hide, pausing brings the chrome back (`:113-115`).
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) scheduleHide() else showChrome()
    }
    // Leaving the app pauses the clip; it does not play on behind the home screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    // TalkBack turned on mid-clip: bring back the controls it needs (`:107-109`).
    LaunchedEffect(assistive) {
        if (assistive) showChrome()
    }

    val backRef = remember { arrayOfNulls<State<Float>>(1) }
    val backState = rememberOverlayBack(enabled = !closing) {
        drag = VideoPlayerRules.DISMISS_DISTANCE * (backRef[0]?.value ?: 0f)
        close()
    }
    backRef[0] = backState
    val effectiveDrag = if (backState.value > 0f && !closing) VideoPlayerRules.DISMISS_DISTANCE * backState.value else drag

    val shown = scrubTime ?: state.currentTime
    val topInset = mediaTopInset()
    val bottomInset = mediaBottomInset()

    Box(Modifier.fillMaxSize().overlayPane(title = "Video", onDismiss = { close() })) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = VideoPlayerRules.backdropOpacity(effectiveDrag) }
                .background(Color.Black),
        )

        // The stage owns the dismiss drag: a drag that starts on the scrubber must scrub (`:172-174`).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = effectiveDrag * density.density
                    val scale = VideoPlayerRules.dragScale(effectiveDrag, reduceMotion)
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) { detectTapGestures { toggleChrome() } }
                .pointerInput(Unit) {
                    val threshold = VideoPlayerRules.DRAG_MIN_DISTANCE.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val total = change.position - down.position
                            if (!dragging && total.getDistance() > threshold) dragging = true
                            if (dragging) {
                                // Sideways swipes belong to nothing here; only a vertical drag moves the clip (`:320-324`).
                                if (abs(total.y) > abs(total.x) && !closing) drag = total.y / density.density
                                change.consume()
                            }
                        }
                        if (dragging && !closing) {
                            val velocity = tracker.calculateVelocity().y / density.density
                            if (VideoPlayerRules.shouldDismiss(drag, VideoPlayerRules.predictedEnd(drag, velocity))) {
                                haptic(Haptic.Light)
                                close()
                            } else {
                                val from = drag
                                scope.launch {
                                    animate(from, 0f, animationSpec = Motion.standard()) { value, _ -> drag = value }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Stage(
                failed = state.failed,
                ready = state.isReady,
                surface = surface,
            )
            CentreControl(
                visible = VideoPlayerRules.showsCentreControl(state.isReady, state.isPlaying, chromeVisible),
                playing = state.isPlaying,
                onToggle = {
                    player.toggle()
                    haptic(Haptic.Light)
                    showChrome()
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(Motion.easeInOut(VideoPlayerRules.CHROME_FADE_MS)),
            exit = fadeOut(Motion.easeInOut(VideoPlayerRules.CHROME_FADE_MS)),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = VideoPlayerRules.chromeOpacity(effectiveDrag) },
        ) {
            Column(Modifier.fillMaxSize().blockTouches(abs(effectiveDrag) >= 1f)) {
                TopBar(
                    title = title,
                    subtitle = subtitle,
                    topInset = topInset,
                    muted = muted,
                    onMute = {
                        muted = !muted
                        player.setMuted(muted)
                        showChrome()
                    },
                    onClose = {
                        haptic(Haptic.Light)
                        close()
                    },
                )
                Spacer(Modifier.weight(1f))
                BottomBar(
                    shown = shown,
                    duration = state.duration,
                    scrubbing = scrubTime != null,
                    bottomInset = bottomInset,
                    onScrub = { target ->
                        if (state.duration > 0) {
                            if (scrubTime == null) {
                                player.isScrubbing = true
                                haptic(Haptic.Light)
                            }
                            scrubTime = target
                            player.seek(target)
                            showChrome()
                        }
                    },
                    onScrubEnd = {
                        scrubTime?.let { player.seek(it, precise = true) }
                        scrubTime = null
                        player.isScrubbing = false
                        if (player.state.value.isPlaying) scheduleHide()
                    },
                    onStep = { target ->
                        player.seek(target, precise = true)
                        showChrome()
                    },
                )
            }
        }
    }
}

/** The picture, a spinner until it can play, or the failure line (`stage`, `:120-175`). */
@Composable
private fun Stage(failed: Boolean, ready: Boolean, surface: Player?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            // Failure first: the player exists before the clip is known to be playable (`:123-131`).
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShroudIcon(ShroudIcons.WarningFill, Color.White.copy(alpha = 0.85f), size = 30.dp)
                ShroudText(PLAYER_FAILED, inter(15f, FontWeight.Medium), Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            }
        } else {
            AnimatedVisibility(visible = surface != null, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                surface?.let { VideoSurface(it, Modifier.fillMaxSize()) }
            }
            if (!ready) Spinner(Color.White, size = 24.dp)
        }
    }
}

/** The 74 dp glass disc: always up while paused, else it follows the chrome (`:146-168`). */
@Composable
private fun CentreControl(visible: Boolean, playing: Boolean, onToggle: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(Motion.fade(), initialScale = 0.8f) + fadeIn(Motion.fade()),
        exit = scaleOut(Motion.fade(), targetScale = 0.8f) + fadeOut(Motion.fade()),
    ) {
        Box(
            Modifier
                .size(74.dp)
                .pressable(scale = 1f, dimming = 0f, haptic = Haptic.None, onClick = onToggle)
                .glassSurface(CircleShape, GlassStyle.Regular, interactive = true)
                .semantics { contentDescription = if (playing) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(targetState = playing, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) }, label = "playPause") { isPlaying ->
                ShroudIcon(
                    if (isPlaying) ShroudIcons.PauseFill else ShroudIcons.PlayFill,
                    Color.White,
                    size = 30.dp,
                    modifier = Modifier.offset(x = if (isPlaying) 0.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, topInset: Dp, muted: Boolean, onMute: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(start = 12.dp, end = 12.dp, top = topInset, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 40 dp circle with a 44 dp target; the light haptic fires with the close (`:181-197`).
        MediaCircleButton(ShroudIcons.X, "Close video", onClose, iconSize = 15.dp, padding = 2.dp, haptic = Haptic.None)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (title.isNotEmpty()) {
                ShroudText(title, inter(15f, FontWeight.SemiBold), Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    ShroudText(subtitle, inter(12f), Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Android addition (C13 asks for it): sound off / on for this clip; iOS's player has no mute control.
        MediaCircleButton(
            if (muted) ShroudIcons.SpeakerSlashFill else ShroudIcons.SpeakerHighFill,
            if (muted) "Unmute" else "Mute",
            onMute,
            iconSize = 16.dp,
            padding = 2.dp,
        )
    }
}

@Composable
private fun BottomBar(
    shown: Double,
    duration: Double,
    scrubbing: Boolean,
    bottomInset: Dp,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    onStep: (Double) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = bottomInset + 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both roll once a second while the clip plays (the remaining time downwards); a scrub jumps
        // straight to the time under the finger.
        val style = inter(12f, FontWeight.Medium, tabularDigits = true)
        RollingText(ChatVideoPlayer.timeLabel(shown), style, Color.White, Modifier.widthIn(min = 38.dp), animated = !scrubbing)
        Scrubber(shown, duration, scrubbing, onScrub, onScrubEnd, onStep, Modifier.weight(1f))
        Box(Modifier.widthIn(min = 42.dp), contentAlignment = Alignment.CenterEnd) {
            RollingText(
                VideoPlayerRules.remainingLabel(duration, shown),
                style,
                Color.White.copy(alpha = 0.75f),
                countsDown = true,
                animated = !scrubbing,
            )
        }
    }
}

/**
 * Track 3 dp white 28 %, fill white, knob 11 dp (15 while scrubbing) with a soft shadow, in a
 * 44 dp touch band; one adjustable TalkBack node (`scrubber`, `:278-317`).
 */
@Composable
private fun Scrubber(
    shown: Double,
    duration: Double,
    scrubbing: Boolean,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    onStep: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDuration by rememberUpdatedState(duration)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val knob by animateDpAsState(if (scrubbing) 15.dp else 11.dp, Motion.snappy(), label = "knob")
    val fraction = VideoPlayerRules.fraction(shown, duration)
    BoxWithConstraints(
        modifier
            .height(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    currentOnScrub(VideoPlayerRules.scrubTarget(down.position.x, width, currentDuration))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        currentOnScrub(VideoPlayerRules.scrubTarget(change.position.x, width, currentDuration))
                        change.consume()
                    }
                    currentOnScrubEnd()
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Playback position"
                stateDescription = VideoPlayerRules.positionValue(shown, duration)
                if (duration > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..duration.toFloat())
                    setProgress { target ->
                        val step = VideoPlayerRules.accessibilityStep(duration)
                        val next = if (target > shown) minOf(duration, shown + step) else maxOf(0.0, shown - step)
                        onStep(next)
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.size(width * fraction, 3.dp).clip(CircleShape).background(Color.White))
        Box(
            Modifier
                .offset { IntOffset((width * fraction - knob / 2).roundToPx(), 0) }
                .size(knob)
                .dropShadow(CircleShape, Shadow(radius = 2.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 1.dp)))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** Whether TalkBack (touch exploration) is on, followed live (iOS `isVoiceOverRunning`, `:48-51`). */
@Composable
private fun rememberTouchExploration(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    val enabled = remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on -> enabled.value = on }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** iOS copy (`VideoPlayerOverlay.swift:128`). */
internal const val PLAYER_FAILED = "This video could not be opened."

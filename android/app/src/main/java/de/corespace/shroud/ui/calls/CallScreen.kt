package de.corespace.shroud.ui.calls

import android.app.Activity
import android.content.ActivityNotFoundException
import android.media.projection.MediaProjectionManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.calls.ActiveCall
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.CallUiState
import de.corespace.shroud.core.calls.ScreenCaptureGrant
import de.corespace.shroud.core.calls.ShareAction
import de.corespace.shroud.ui.components.Avatar
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.CallColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The call screen (iOS `InCallOverlay`, `ios/shroud/ShroudUI/Components/InCallOverlay.swift`;
 * calls §8): always dark. Back to front — the navy stage; their camera opening out of their face
 * ([RevealedVideo]); their shared screen ([SharedScreen]); the top shade under a picture; the stage
 * (face and the name block, [CallStageLayout]) over the control row; the tiles (Share, their
 * camera beside their screen, ours); the "Not verified" badge; the sharing pill.
 *
 * [focusRequester] sits on the name and the status line: the host moves TalkBack there when a call
 * appears (`RootView.swift:238-243`). Everything here reads [state] and calls [ports]; nothing
 * talks to the engine except the renderers' EGL context.
 */
@Composable
internal fun CallScreen(
    ports: CallPorts,
    state: CallUiState,
    call: ActiveCall,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    ShroudTheme(dark = true) {
        CallScreenContent(ports, state, call, focusRequester, modifier)
    }
}

@Composable
private fun CallScreenContent(
    ports: CallPorts,
    state: CallUiState,
    call: ActiveCall,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onEarpiece by ports.isOnEarpiece.collectAsState()
    val safetyNumber = ports.safetyNumberForActiveCall()
    var chromeHidden by remember { mutableStateOf(false) }
    var chromeTouch by remember { mutableIntStateOf(0) }
    var safetyShownFor by remember { mutableStateOf<UUID?>(null) }
    var safetyClosedFor by remember { mutableStateOf<UUID?>(null) }
    var badgeBounds by remember { mutableStateOf(Rect.Zero) }
    val flags = CallScreenRules.flags(state, call, safetyNumber != null, chromeHidden)
    val face = remember { FaceSpot() }
    val eglContext = remember(ports) { { ports.eglContext() } }
    val touch = { chromeTouch += 1 }

    // ---- Where the name block goes (`placeName`, :653-693) ----
    var placedCall by remember { mutableStateOf<UUID?>(null) }
    var inCorner by remember { mutableStateOf(flags.picture) }
    var blockHidden by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (flags.picture) 1f else 0f) }
    // Ending can drop their picture at once: the name holds where it is for that last moment (:139).
    val videoOn = if (flags.ending && placedCall == call.id) inCorner else flags.picture
    LaunchedEffect(call.id, videoOn) {
        val target = if (videoOn) 1f else 0f
        if (placedCall != call.id) {
            placedCall = call.id
            inCorner = videoOn
            blockHidden = false
            progress.snapTo(target)
            return@LaunchedEffect
        }
        if (!reduceMotion) {
            inCorner = videoOn
            blockHidden = false
            progress.animateTo(target, Motion.standard())
            return@LaunchedEffect
        }
        if (inCorner == videoOn) {
            blockHidden = false
            return@LaunchedEffect
        }
        if (!blockHidden) {
            blockHidden = true
            delay(Motion.REDUCED_MS)
        }
        inCorner = videoOn
        progress.snapTo(target)
        blockHidden = false
    }
    val cornerDrop by animateFloatAsState(
        targetValue = if (flags.badgeRoom) CallScreenMetrics.SAFETY_BADGE_RESERVE else 0f,
        animationSpec = Motion.respecting(reduceMotion, Motion.standard()),
        label = "cornerDrop",
    )

    // ---- The controls step aside over their screen (`lingerChrome`, :390-417) ----
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val safetyOpen = safetyShownFor == call.id
    LaunchedEffect(flags.showsRemoteScreen, chromeHidden, chromeTouch, safetyOpen) {
        if (!flags.showsRemoteScreen) {
            if (chromeHidden) chromeHidden = false
            return@LaunchedEffect
        }
        val talkBack = accessibility?.isTouchExplorationEnabled == true
        if (!CallScreenRules.chromeShouldLinger(true, chromeHidden, safetyOpen, talkBack)) return@LaunchedEffect
        delay(CallScreenRules.CHROME_LINGER_MS)
        chromeHidden = true
    }
    // The number stays in reach while it is read out; the timer starts over once it closes (:270).
    LaunchedEffect(safetyShownFor) { touch() }
    val toggleChrome = {
        chromeHidden = !chromeHidden
        touch()
    }

    // ---- Share: the system consent, then the controller (calls §7.2) ----
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        ports.onScreenCaptureConsent(if (result.resultCode == Activity.RESULT_OK && data != null) ScreenCaptureGrant(result.resultCode, data) else null)
    }
    val toggleShare: () -> Unit = {
        val callId = call.id
        if (ports.toggleScreenShare() == ShareAction.RequestConsent) {
            scope.launch {
                // The menu goes first (`toggleShare`, :478-485); then only for the same, still running call.
                delay(CallScreenRules.CONSENT_DELAY_MS)
                val now = ports.ui.value.active
                if (now != null && now.id == callId && (now.phase == CallPhase.Active || now.phase == CallPhase.Connecting)) {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    try {
                        if (manager != null) consent.launch(manager.createScreenCaptureIntent()) else ports.onScreenCaptureConsent(null)
                    } catch (_: ActivityNotFoundException) {
                        ports.onScreenCaptureConsent(null)
                    }
                }
            }
        }
    }

    // ---- TalkBack hears what the screen says in passing (:271-280) ----
    var announcement by remember { mutableStateOf<String?>(null) }
    val currentCall by rememberUpdatedState(call)
    LaunchedEffect(Unit) {
        launch { snapshotFlow { currentCall.notice }.drop(1).collect { notice -> if (notice != null) announcement = notice } }
        launch {
            snapshotFlow { currentCall.reconnecting }.drop(1).collect { on ->
                if (on && currentCall.phase == CallPhase.Active) announcement = "Reconnecting"
            }
        }
        launch {
            snapshotFlow { currentCall.phase }.drop(1).collect { phase ->
                if (phase == CallPhase.Ending) announcement = CallScreenRules.statusLine(currentCall)
            }
        }
    }

    // A picture on screen keeps the display on (calls §6.6, an Android addition).
    val view = LocalView.current
    val keepOn = flags.showsRemoteVideo || flags.showsRemoteScreen || flags.showsLocalVideo
    DisposableEffect(view, keepOn) {
        if (keepOn) view.keepScreenOn = true
        onDispose { if (keepOn) view.keepScreenOn = false }
    }

    val sharingInset by animateDpAsState(
        if (flags.sharing) CallScreenMetrics.SHARING_INSET.dp else 0.dp,
        Motion.snappy(),
        label = "sharingInset",
    )
    val chromeAlpha by animateFloatAsState(if (flags.chromeAway) 0f else 1f, Motion.easeOut(250), label = "chromeAlpha")
    val blockAlpha by animateFloatAsState(if (blockHidden) 0f else 1f, Motion.reduced(), label = "blockAlpha")

    Box(modifier.fillMaxSize().background(CallColors.stageGradient)) {
        // 2. Their camera, mounted for the whole call (:156-166).
        state.remoteVideoTrack?.let { track ->
            RevealedVideo(
                track = track,
                eglContext = eglContext,
                open = flags.showsRemoteVideo && !flags.showsRemoteScreen,
                warm = !call.remoteCameraOff,
                face = face,
            )
        }
        // 3. Their screen, mounted as soon as they say they share (:168-179).
        if (CallScreenRules.mountsRemoteScreen(state, call)) {
            state.remoteScreenTrack?.let { track ->
                val shown = flags.showsRemoteScreen
                val screenAlpha by animateFloatAsState(if (shown) 1f else 0f, Motion.easeOut(280), label = "screenAlpha")
                val screenScale by animateFloatAsState(if (shown || reduceMotion) 1f else 0.97f, Motion.easeOut(280), label = "screenScale")
                SharedScreen(
                    track = track,
                    eglContext = eglContext,
                    onTap = toggleChrome,
                    interactive = shown,
                    modifier = Modifier.graphicsLayer {
                        alpha = screenAlpha
                        scaleX = screenScale
                        scaleY = screenScale
                    },
                )
            }
        }
        // 4. The top shade under a picture (:181-185).
        TopShade(
            visible = flags.picture && !flags.chromeAway,
            drop = CallScreenRules.shadeDrop(flags.badgeRoom, flags.sharing),
        )
        // 5. The stage over the control row (:187-214).
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = sharingInset),
        ) {
            CallStageLayout(
                progress = { progress.value },
                cornerDrop = { cornerDrop },
                face = { CallFace(call, open = flags.showsRemoteVideo || flags.showsRemoteScreen, face = face) },
                block = {
                    InfoBlock(
                        call = call,
                        flags = flags,
                        ports = ports,
                        focusRequester = focusRequester,
                        alpha = { blockAlpha * chromeAlpha },
                    )
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Box(Modifier.height(CallScreenMetrics.COLUMN_SPACING.dp))
            val rowHidden = flags.ending || flags.chromeAway
            ControlRow(
                call = call,
                speakerOn = CallScreenRules.speakerShownOn(call.speakerOn, onEarpiece),
                ports = ports,
                onTouch = touch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = CallScreenMetrics.CONTROLS_BOTTOM.dp)
                    // Ending, the row goes at once but keeps its room (:205-211).
                    .graphicsLayer { alpha = if (flags.ending) 0f else chromeAlpha }
                    .then(if (rowHidden) Modifier.clearAndSetSemantics {}.blockTouches() else Modifier),
            )
        }
        // 6. Share and the pictures in the top-trailing corner (:286-347).
        Tiles(
            call = call,
            state = state,
            flags = flags,
            ports = ports,
            eglContext = eglContext,
            onShare = toggleShare,
            onTouch = touch,
            chromeAlpha = { chromeAlpha },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = sharingInset),
        )
        // 7. "Not verified" in the top-leading corner (:219-233).
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = sharingInset)
                .padding(top = CallScreenMetrics.INSET_TOP.dp, start = CallScreenMetrics.INSET_TRAILING.dp),
        ) {
            AnimatedVisibility(
                visible = flags.unverified && safetyNumber != null,
                enter = scaleIn(Motion.standard(), 0.6f, TransformOrigin(0f, 0f)) + fadeIn(Motion.standard()),
                // The badge goes at once when the call ends; compared, it dissolves (:142-144, :260-261).
                exit = if (flags.ending) ExitTransition.None else scaleOut(Motion.standard(), 0.6f, TransformOrigin(0f, 0f)) + fadeOut(Motion.standard()),
                modifier = Modifier
                    .graphicsLayer { alpha = chromeAlpha }
                    .then(if (flags.chromeAway) Modifier.clearAndSetSemantics {}.blockTouches() else Modifier),
            ) {
                SafetyBadge(
                    name = call.peerUsername,
                    closed = safetyClosedFor == call.id,
                    onOpen = { safetyShownFor = call.id },
                    modifier = Modifier.onGloballyPositioned { badgeBounds = it.boundsInRoot() },
                )
            }
            SafetyBadgeTimer(callId = call.id, open = safetyOpen, closed = safetyClosedFor == call.id) { safetyClosedFor = call.id }
        }
        if (safetyNumber != null) {
            SafetyNumberPopover(
                visible = safetyOpen && flags.unverified,
                anchor = badgeBounds,
                name = call.peerUsername,
                number = safetyNumber,
                onDismiss = { safetyShownFor = null },
                onConfirm = {
                    safetyShownFor = null
                    ports.confirmSafety()
                },
            )
        }
        // 8. Our screen goes out: the red pill at the top centre (:235-245).
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        AnimatedVisibility(
            visible = flags.sharing,
            enter = slideInVertically(Motion.snappy()) { -it } + fadeIn(Motion.snappy()),
            exit = if (flags.ending) ExitTransition.None else slideOutVertically(Motion.snappy()) { -it } + fadeOut(Motion.snappy()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusTop + 4.dp),
        ) {
            SharingPill(starting = !call.isSharingScreen, onStop = { ports.toggleScreenShare() })
        }
        CallAnnouncer(announcement, Modifier.align(Alignment.BottomStart))
    }
}

/** Swallows every touch, for chrome that keeps its room while it is gone. */
private fun Modifier.blockTouches(): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = {},
)

/**
 * The shade from the very top to below the docked name (`topShade(drop:)`, :613-643): keeps the
 * white text readable over any picture. No touch, not in TalkBack.
 */
@Composable
private fun TopShade(visible: Boolean, drop: Float) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, Motion.easeOut(300), label = "shadeAlpha")
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    val stops = CallScreenRules.shadeStops(topInset, drop)
    Box(
        Modifier
            .fillMaxWidth()
            .height(CallScreenRules.shadeHeight(topInset, drop).dp)
            .clearAndSetSemantics {}
            .graphicsLayer { this.alpha = alpha }
            .background(Brush.verticalGradient(*stops.toTypedArray())),
    )
}

/**
 * Their face (`face(for:)`, :695-734): 104 dp, breathing while it rings or connects, the muted
 * badge on its corner. It keeps its place while their picture shows and only swells (1.14) and
 * fades; its resting rect is measured into [face] for the reveal, outside the scale. Hidden from
 * TalkBack while a picture is open.
 */
@Composable
private fun CallFace(call: ActiveCall, open: Boolean, face: FaceSpot) {
    val reduceMotion = ShroudTheme.reduceMotion
    // Gives way at once as the circle opens; closing, it comes straight back (:731-734).
    val spec = if (open || reduceMotion || call.phase == CallPhase.Ending) {
        Motion.easeOut<Float>(200)
    } else {
        tween(260, delayMillis = 60, easing = Motion.IosEaseOut)
    }
    val faceScale by animateFloatAsState(if (open && !reduceMotion) 1.14f else 1f, spec, label = "faceScale")
    val faceAlpha by animateFloatAsState(if (open) 0f else 1f, spec, label = "faceAlpha")
    Box(
        Modifier
            .onGloballyPositioned { face.boundsInRoot = it.boundsInRoot() }
            .then(if (open) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = faceScale
                scaleY = faceScale
                alpha = faceAlpha
            },
        ) {
            BreathingAvatar(call)
            if (call.remoteMicMuted) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(4.dp, 4.dp)
                        .background(ShroudTheme.colors.danger, CircleShape)
                        .padding(6.dp)
                        .semantics { contentDescription = CallScreenRules.mutedText(call.peerUsername) },
                ) {
                    ShroudIcon(ShroudIcons.MicrophoneSlashFill, Color.White, size = 14.dp)
                }
            }
        }
    }
}

/**
 * The avatar: breathing between 0.97 with a 15 % accent glow and 1.05 with 45 %, 1.1 s each way,
 * while ringing or connecting (`avatar(for:)`, :799-817); still under reduce motion, dimmed to 92 %
 * until connected. The glow is drawn, its strength read while drawing (no recomposition per frame).
 */
@Composable
private fun BreathingAvatar(call: ActiveCall) {
    val reduceMotion = ShroudTheme.reduceMotion
    val accent = ShroudTheme.colors.accent
    val initials = AvatarPalette.initials(call.peerUsername)
    val brush = AvatarPalette.brush(call.peerUsername)
    if (CallScreenRules.isRinging(call.phase) && !reduceMotion) {
        val breath = rememberInfiniteTransition(label = "breathing")
        val big by breath.animateFloat(0f, 1f, infiniteRepeatable(Motion.easeInOut(1100), RepeatMode.Reverse), label = "breath")
        Avatar(
            initials = initials,
            size = CallScreenMetrics.FACE.dp,
            brush = brush,
            fontSize = 36.sp,
            modifier = Modifier
                .graphicsLayer {
                    val scale = 0.97f + 0.08f * big
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind {
                    val glow = (14f + 20f * big).dp.toPx()
                    val alpha = 0.15f + 0.30f * big
                    val radius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to accent.copy(alpha = alpha),
                            radius / (radius + glow) to accent.copy(alpha = alpha),
                            1f to accent.copy(alpha = 0f),
                            center = center,
                            radius = radius + glow,
                        ),
                        radius = radius + glow,
                    )
                },
        )
    } else {
        Avatar(
            initials = initials,
            size = CallScreenMetrics.FACE.dp,
            brush = brush,
            fontSize = 36.sp,
            modifier = Modifier.graphicsLayer { alpha = if (call.phase == CallPhase.Active) 1f else 0.92f },
        )
    }
}

/**
 * The name, the status line, the muted line, the speaking meter and a notice (`info(for:)`,
 * :565-611), centred on each other. The name and the status are one TalkBack stop and the focus
 * target of a new call.
 */
@Composable
private fun InfoBlock(
    call: ActiveCall,
    flags: CallScreenFlags,
    ports: CallPorts,
    focusRequester: FocusRequester,
    alpha: () -> Float,
) {
    val density = LocalDensity.current
    val nameShadow = with(density) { Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2.dp.toPx()), 8.dp.toPx()) }
    val lineShadow = with(density) { Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1.dp.toPx()), 3.dp.toPx()) }
    Column(
        Modifier
            .graphicsLayer { this.alpha = alpha() }
            .then(if (flags.chromeAway) Modifier.clearAndSetSemantics {} else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .focusRequester(focusRequester)
                .focusable()
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // One line in both places: a long handle comes down to 70 % rather than breaking (:571-580).
            BasicText(
                text = call.peerUsername,
                style = inter(26f, FontWeight.SemiBold).copy(color = Color.White, shadow = nameShadow, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 18.2.sp, maxFontSize = 26.sp, stepSize = 0.2.sp),
            )
            StatusLine(call, lineShadow)
        }
        AnimatedVisibility(
            visible = CallScreenRules.showsMutedLine(call, flags),
            enter = fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.snappy()),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ShroudIcon(ShroudIcons.MicrophoneSlashFill, Color.White.copy(alpha = 0.85f), size = 14.dp)
                BasicText(
                    text = CallScreenRules.mutedText(call.peerUsername),
                    style = inter(13f, FontWeight.Medium).copy(color = Color.White.copy(alpha = 0.85f), shadow = lineShadow),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = CallScreenRules.showsSpeaking(call),
            enter = fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), 0.9f),
            exit = fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), 0.9f),
        ) {
            SpeakingIndicator(level = { ports.localAudioLevel() }, modifier = Modifier.padding(top = 6.dp))
        }
        val notice = call.notice
        if (notice != null && !flags.ending) {
            BasicText(
                text = notice,
                style = inter(13f).copy(color = Color.White.copy(alpha = 0.85f), shadow = lineShadow, textAlign = TextAlign.Center),
            )
        }
    }
}

private const val TIMER_KEY = "\u0000timer"

/**
 * The status line (`statusLabel(for:)`, :823-846): "Reconnecting…", the running time (tabular
 * digits rolling each second), or the phase's words; 15 sp white 85 %. Changes cross-fade on
 * `Motion.snappy`.
 */
@Composable
private fun StatusLine(call: ActiveCall, shadow: Shadow) {
    val start = call.startedAt
    val ticking = call.phase == CallPhase.Active && start != null && !call.reconnecting
    val now by produceState(Instant.now(), ticking, start) {
        while (ticking && start != null) {
            val current = Instant.now()
            value = current
            val intoSecond = Duration.between(start, current).toMillis().mod(1000L)
            delay(1000L - intoSecond)
        }
        value = Instant.now()
    }
    val status = CallScreenRules.status(call, now)
    val style = inter(15f, tabularDigits = status.isTimer).copy(shadow = shadow)
    val color = Color.White.copy(alpha = 0.85f)
    AnimatedContent(
        targetState = if (status.isTimer) TIMER_KEY else status.text,
        transitionSpec = { fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.snappy()) },
        label = "callStatus",
    ) { key ->
        if (key == TIMER_KEY) {
            RollingText(status.text, style, color)
        } else {
            ShroudText(key, style, color, maxLines = 1)
        }
    }
}

/**
 * The control row (`controlRow(for:)`, :746-794): Mute · Video · Speaker · End, or Decline ·
 * Accept while it rings in; 22 dp apart. Any press keeps the controls up ([onTouch]).
 */
@Composable
private fun ControlRow(call: ActiveCall, speakerOn: Boolean, ports: CallPorts, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = ShroudTheme.reduceMotion
    val colors = ShroudTheme.colors
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit): () -> Unit = {
        onTouch()
        scope.launch { action() }
    }
    AnimatedContent(
        targetState = call.phase == CallPhase.IncomingRinging,
        transitionSpec = { Motion.iconSwap(Motion.standard()).respecting(reduceMotion).content },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        label = "controlRow",
    ) { ringingIn ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top,
        ) {
            if (ringingIn) {
                CallControlButton(ShroudIcons.PhoneDisconnectFill, "Decline", colors.danger, act { ports.rejectIncoming() })
                CallControlButton(ShroudIcons.PhoneFill, "Accept", colors.online, act { ports.acceptIncoming() })
            } else {
                CallControlButton(
                    icon = if (call.isMuted) ShroudIcons.MicrophoneSlashFill else ShroudIcons.MicrophoneFill,
                    label = if (call.isMuted) "Unmute" else "Mute",
                    tint = if (call.isMuted) colors.danger else null,
                    onClick = act { ports.toggleMute() },
                )
                CallControlButton(
                    icon = if (call.isVideoEnabled) ShroudIcons.VideoCameraFill else ShroudIcons.VideoCameraSlashFill,
                    label = "Video",
                    tint = if (call.isVideoEnabled) colors.accent else null,
                    accessibilityLabel = if (call.isVideoEnabled) "Turn video off" else "Turn video on",
                    enabled = CallScreenRules.videoAvailable(call),
                    onClick = act { ports.toggleVideo() },
                )
                CallControlButton(
                    icon = if (speakerOn) ShroudIcons.SpeakerSimpleHighFill else ShroudIcons.SpeakerSimpleNoneFill,
                    label = "Speaker",
                    tint = if (speakerOn) colors.accent else null,
                    accessibilityLabel = if (speakerOn) "Turn speaker off" else "Turn speaker on",
                    onClick = {
                        onTouch()
                        ports.toggleSpeaker()
                    },
                )
                CallControlButton(ShroudIcons.PhoneDisconnectFill, "End", colors.danger, act { ports.hangup() })
            }
        }
    }
}

/**
 * The top-trailing corner (`tiles(for:screen:)`, :286-347): Share (not while it rings in), their
 * camera as a tile while their screen fills the view, and ours — 108 × 164, or 90 × 136 beside
 * their screen — mirrored for the front camera; a tap on ours flips the camera. Tiles grow from
 * the corner (scale 0.8 + fade). Share keeps its room while it steps aside, so the pictures never move.
 */
@Composable
private fun Tiles(
    call: ActiveCall,
    state: CallUiState,
    flags: CallScreenFlags,
    ports: CallPorts,
    eglContext: () -> org.webrtc.EglBase.Context?,
    onShare: () -> Unit,
    onTouch: () -> Unit,
    chromeAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val screen = flags.showsRemoteScreen
    val width by animateDpAsState(
        (if (screen) CallScreenMetrics.tile.width else CallScreenMetrics.selfView.width).dp,
        Motion.standard(),
        label = "tileWidth",
    )
    val height by animateDpAsState(
        (if (screen) CallScreenMetrics.tile.height else CallScreenMetrics.selfView.height).dp,
        Motion.standard(),
        label = "tileHeight",
    )
    val away = flags.chromeAway || flags.ending
    val corner = TransformOrigin(1f, 0f)
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.padding(top = CallScreenMetrics.INSET_TOP.dp, end = CallScreenMetrics.INSET_TRAILING.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (call.phase != CallPhase.IncomingRinging) {
                ShareControl(
                    call = call,
                    quality = state.screenShareQuality,
                    onShare = onShare,
                    onQuality = ports::setScreenShareQuality,
                    onTouch = onTouch,
                    modifier = Modifier
                        // Ending, it goes at once; over their screen it fades (:292-301).
                        .graphicsLayer { alpha = if (flags.ending) 0f else chromeAlpha() }
                        .then(if (away) Modifier.clearAndSetSemantics {}.blockTouches() else Modifier),
                )
            }
            val theirs = state.remoteVideoTrack
            AnimatedVisibility(
                visible = screen && flags.showsRemoteVideo && theirs != null,
                enter = scaleIn(Motion.standard(), 0.8f, corner) + fadeIn(Motion.standard()),
                exit = scaleOut(Motion.standard(), 0.8f, corner) + fadeOut(Motion.standard()),
            ) {
                val track = remember(theirs) { theirs }
                if (track != null) {
                    CallVideo(
                        track = track,
                        eglContext = eglContext,
                        modifier = Modifier
                            .size(width, height)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, CallColors.tileBorder, RoundedCornerShape(16.dp))
                            .semantics { contentDescription = "${call.peerUsername}’s camera" },
                    )
                }
            }
            val ours = state.localVideoTrack
            AnimatedVisibility(
                visible = flags.showsLocalVideo && ours != null,
                enter = scaleIn(Motion.standard(), 0.8f, corner) + fadeIn(Motion.standard()),
                exit = scaleOut(Motion.standard(), 0.8f, corner) + fadeOut(Motion.standard()),
            ) {
                val track = remember(ours) { ours }
                if (track != null) {
                    SelfView(
                        track = track,
                        eglContext = eglContext,
                        mirror = state.usesFrontCamera,
                        canSwitch = state.canSwitchCamera,
                        onSwitch = ports::switchCamera,
                        width = width,
                        height = height,
                    )
                }
            }
        }
    }
}

/** Our camera: a tap flips it (one element: "Switch camera" or "Your camera"; :315-341). */
@Composable
private fun SelfView(
    track: org.webrtc.VideoTrack,
    eglContext: () -> org.webrtc.EglBase.Context?,
    mirror: Boolean,
    canSwitch: Boolean,
    onSwitch: () -> Unit,
    width: Dp,
    height: Dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSwitch)
            .clearAndSetSemantics {
                contentDescription = if (canSwitch) "Switch camera" else "Your camera"
                role = if (canSwitch) Role.Button else Role.Image
                onClick {
                    onSwitch()
                    true
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        CallVideo(track = track, eglContext = eglContext, mirror = mirror, modifier = Modifier.fillMaxSize())
        if (canSwitch) {
            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(6.dp),
            ) {
                ShroudIcon(ShroudIcons.CameraRotateFill, Color.White, size = 14.dp)
            }
        }
        Box(Modifier.fillMaxSize().border(1.dp, CallColors.tileBorder, shape))
    }
}

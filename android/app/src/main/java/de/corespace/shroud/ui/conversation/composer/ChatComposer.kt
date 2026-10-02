package de.corespace.shroud.ui.conversation.composer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.voice.VoiceRecorder
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteContent
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** The composer's sizes (`ChatComposerView.swift:51-54, 264-266`). */
object ComposerMetrics {
    /** Composer control diameter — lighter than the 44 dp bar controls above the thread. */
    val controlSize: Dp = 40.dp

    /** The trailing slot (send / mic), so the hit target survives the first points of drag travel (`:264-266`). */
    val slotSize: Dp = 44.dp

    /** Every control's touch target: iOS's 44 pt raised to Android's 48 dp, visuals unchanged (plan §2.0 rule 8). */
    val touchSize: Dp = 48.dp

    /** The field: a capsule at one line, a rounded rect as it grows. */
    val fieldRadius: Dp = 20.dp

    /** The field scrolls inside past five lines (`:209`). */
    const val MAX_LINES = 5
}

/**
 * The bottom composer (iOS `ChatComposerView`, `ChatComposerView.swift:19-397`; design hc3Jf, RueI9,
 * UM352; conversation-compose-media §3). Three states: idle (attach + field + mic, or send once
 * there is a draft), recording (hold the mic: live timer, "Slide to cancel", a lock pill above the
 * thumb) and locked (Discard · waveform · Send). A reply or link strip rides above the row.
 *
 * The gesture state lives in [gesture] (pure, [ComposerGesture]); this draws it and feeds it the
 * finger in window coordinates. Back during a take discards it (thread D9 = compose Q10).
 */
@Composable
internal fun ChatComposer(
    draft: TextFieldState,
    recorder: VoiceRecorder.RecState,
    phase: ComposerPhase,
    gesture: ComposerGesture,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    reply: ReplyQuoteContent?,
    onTapReply: (() -> Unit)?,
    onCancelReply: () -> Unit,
    focusToken: Int,
    linkBar: ChatLinkBarState?,
    linkShowsAboveText: Boolean,
    linkCanToggleImageSize: Boolean,
    linkUsesLargeImage: Boolean,
    onToggleLinkAboveText: () -> Unit,
    onToggleLinkImageSize: () -> Unit,
    onRemoveLinkPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = ShroudTheme.reduceMotion
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Starting a reply focuses the field, exactly as tapping it would (`:141-143`). Only a change of
    // the token counts: coming back to the chat must not raise the keyboard again.
    var handledToken by remember { mutableIntStateOf(focusToken) }
    LaunchedEffect(focusToken) {
        if (focusToken == handledToken) return@LaunchedEffect
        handledToken = focusToken
        // The field is not there during a take; the reply waits for the next tap then.
        if (runCatching { focusRequester.requestFocus() }.isSuccess) keyboard?.show()
    }

    // The take swaps the field out, and the keyboard drops with it (`ChatComposerView.swift:305-307`):
    // the bar slides down under the still finger, which the gesture reads in window space. Compose
    // keeps the keyboard up when a focused field merely leaves composition, so it is told.
    LaunchedEffect(phase.isActive) {
        if (phase.isActive) keyboard?.hide()
    }

    // Back during a take discards it and stays in the chat (thread D9 = compose Q10).
    BackHandler(enabled = phase.isActive) { gesture.finish(send = false) }

    Box(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        // No spacing here: the strip carries its own 8 dp gap, so an absent strip leaves none (`VStack(spacing: 8)`, `:86`).
        Column {
            ComposerStrip(
                linkBar = linkBar.takeIf { !phase.isActive },
                reply = reply,
                linkShowsAboveText = linkShowsAboveText,
                linkCanToggleImageSize = linkCanToggleImageSize,
                linkUsesLargeImage = linkUsesLargeImage,
                onToggleLinkAboveText = onToggleLinkAboveText,
                onToggleLinkImageSize = onToggleLinkImageSize,
                onRemoveLinkPreview = onRemoveLinkPreview,
                onTapReply = onTapReply,
                onCancelReply = onCancelReply,
            )
            AnimatedContent(
                targetState = phase.isLocked,
                transitionSpec = { riseSwap(reduce) },
                label = "composerRow",
            ) { locked ->
                // A leaving bar must not keep a repeating animation (memory: stuck removal transition eats touches).
                val leaving = transition.targetState == EnterExitState.PostExit
                if (locked) {
                    VoiceLockedBar(
                        elapsedSeconds = recorder.elapsedSeconds,
                        levels = recorder.liveLevels,
                        blinking = !reduce && !leaving,
                        onDiscard = { gesture.finish(send = false) },
                        onSend = { gesture.finish(send = true) },
                    )
                } else {
                    ComposerRow(
                        draft = draft,
                        recorder = recorder,
                        phase = phase,
                        gesture = gesture,
                        focusRequester = focusRequester,
                        onAttach = onAttach,
                        onSend = onSend,
                        blinking = !reduce && !leaving,
                    )
                }
            }
        }
        // The lock pill floats above the thumb — and above a reply strip, which stays up during a
        // take — centred over the 44 dp trailing slot, its bottom 14 dp above the top-most row (`:119-132`).
        // Leaving, it keeps the finger's last progress, or shows the lock shut when the take locked.
        // A plain holder, not state: written while composing, so a snapshot write would be a backwards write.
        val lastLockProgress = remember { FloatArray(1) }
        if (phase is ComposerPhase.Recording) lastLockProgress[0] = phase.lockProgress
        val shownLockProgress = if (phase.isLocked) 1f else lastLockProgress[0]
        AnimatedVisibility(
            visible = phase.isActive && !phase.isLocked,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).overlayAbove(LOCK_PILL_RISE),
            enter = scaleIn(Motion.snappy(), initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(Motion.snappy()),
            exit = scaleOut(Motion.snappy(), targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(Motion.snappy()),
        ) {
            VoiceLockIndicator(progress = shownLockProgress)
        }
    }
}

/**
 * Places this [rise] above its slot's top edge without taking any room (iOS `.overlay(alignment:
 * .topTrailing) { … .offset(y: -74) }`, `ChatComposerView.swift:119-132`): it reports no height, so
 * the 60 dp lock pill never grows the 44 dp composer row it floats over — the bar keeps its height
 * when a take starts, and the reported height stays the row's.
 */
private fun Modifier.overlayAbove(rise: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, -rise.roundToPx()) }
}

/** `.transition(.move(edge: .bottom).combined(with: .opacity))` animated with `Motion.standard` (`:113-116, 137`). */
private fun AnimatedContentTransitionScope<Boolean>.riseSwap(reduce: Boolean): ContentTransform =
    if (reduce) {
        fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced()) using SizeTransform(clip = false)
    } else {
        (slideInVertically(Motion.standard()) { it } + fadeIn(Motion.standard())) togetherWith
            (slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard())) using
            SizeTransform(clip = false)
    }

/**
 * The idle / recording row (`composerRow`, `ChatComposerView.swift:163-186`): while the finger is
 * down the recording bar replaces the attach button and the field; the trailing slot is always
 * there. Its height follows the field as the draft wraps ([Motion.snappy]).
 */
@Composable
private fun ComposerRow(
    draft: TextFieldState,
    recorder: VoiceRecorder.RecState,
    phase: ComposerPhase,
    gesture: ComposerGesture,
    focusRequester: FocusRequester,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    blinking: Boolean,
) {
    val reduce = ShroudTheme.reduceMotion
    val canSend = draft.text.isNotBlank()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(
            targetState = phase.isActive,
            modifier = Modifier.weight(1f),
            animationSpec = Motion.respecting(reduce, Motion.snappy()),
            label = "composerLeading",
        ) { recording ->
            if (recording) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ComposerMetrics.controlSize)
                        .glassSurface(CapsuleShape, GlassStyle.Regular)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    VoiceRecordingBar(
                        elapsedSeconds = recorder.elapsedSeconds,
                        cancelProgress = phase.cancelProgress,
                        blinking = blinking && phase.isActive,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerCircleButton(
                        contentDescription = "Attach",
                        style = GlassStyle.Regular,
                        // Interactive glass swells under the finger; the press only adds the tick (`:197-198`).
                        pressHaptic = Haptic.Light,
                        onClick = onAttach,
                    ) {
                        ShroudIcon(ShroudIcons.Plus, ShroudTheme.colors.accent, size = 22.dp)
                    }
                    MessageField(draft = draft, focusRequester = focusRequester, modifier = Modifier.weight(1f))
                }
            }
        }
        TrailingControl(
            showsSend = canSend && !phase.isActive,
            phase = phase,
            level = recorder.liveLevels.lastOrNull() ?: 0f,
            gesture = gesture,
            onSend = onSend,
        )
    }
}

/**
 * The field (`textField`, `ChatComposerView.swift:204-240`): "Message", 1–5 lines then it scrolls
 * inside; the smiley is decoration. Taps on the capsule's padding or the smiley focus the field
 * (behind the text, so taps on the text still place the cursor). Focused → a whisper of accent in
 * the glass instead of a stroke. Enter inserts a newline; sending is the button only. The keyboard
 * does not learn from it (`IME_FLAG_NO_PERSONALIZED_LEARNING`, plan P5).
 */
@Composable
private fun MessageField(draft: TextFieldState, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .heightIn(min = ComposerMetrics.controlSize)
            .glassSurface(
                RoundedCornerShape(ComposerMetrics.fieldRadius),
                GlassStyle.Regular,
                tint = if (focused) colors.accent.copy(alpha = 0.12f) else null,
            )
            .pointerInputFocus(focusRequester) { keyboard?.show() }
            .animateContentSize(Motion.respecting(reduce, Motion.snappy()))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            NoLearningTextInput {
                BasicTextField(
                    state = draft,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = inter(15f).copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = MessageKeyboard.options,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = ComposerMetrics.MAX_LINES),
                    interactionSource = interaction,
                    decorator = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            // Read by TalkBack as the empty field's hint ("Message", §3.10).
                            if (draft.text.isEmpty()) {
                                ShroudText(MESSAGE_PLACEHOLDER, inter(15f), colors.textSecondary, maxLines = 1)
                            }
                            field()
                        }
                    },
                )
            }
        }
        ShroudIcon(ShroudIcons.Smile, if (focused) colors.accent else colors.textSecondary, size = 20.dp)
    }
}

/** The composer's keyboard (conversation-compose-media §3.4): sentences, autocorrect, Enter = newline. */
object MessageKeyboard {
    val options = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrectEnabled = true,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Default,
    )
}

/** A tap on the field's padding focuses it; taps the text field consumes never reach this. No semantics of its own. */
private fun Modifier.pointerInputFocus(focusRequester: FocusRequester, onFocus: () -> Unit): Modifier =
    pointerInput(focusRequester) {
        detectTapGestures {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) onFocus()
        }
    }

/**
 * Send once there is a draft, otherwise the hold-to-record mic (`trailingControl`,
 * `ChatComposerView.swift:242-268`): a 44 dp slot so the hit target survives the first points of
 * drag travel. Send appears with [Motion.bouncy] — "the send button appearing is the 'you can send
 * now' moment" (`:184-185`) — swapping with a 6 dp drop.
 */
@Composable
private fun TrailingControl(showsSend: Boolean, phase: ComposerPhase, level: Float, gesture: ComposerGesture, onSend: () -> Unit) {
    val reduce = ShroudTheme.reduceMotion
    val dropPx = with(LocalDensity.current) { SEND_DROP.roundToPx() }
    Box(Modifier.size(ComposerMetrics.slotSize), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = showsSend,
            transitionSpec = {
                val transform = if (reduce) {
                    fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced())
                } else {
                    val swap = Motion.iconSwap(Motion.bouncy())
                    // Send arrives with the swap plus a 6 dp drop (`Motion.iconSwap.combined(with: .offset(y: 6))`, `:258`).
                    if (targetState) {
                        (swap.enter + slideInVertically(Motion.bouncy<IntOffset>()) { dropPx }) togetherWith swap.exit
                    } else {
                        swap.enter togetherWith (swap.exit + slideOutVertically(Motion.bouncy<IntOffset>()) { dropPx })
                    }
                }
                // Unclipped: the live mic grows to 1.25 and its level halo reaches far past the 44 dp slot,
                // and the 48 dp touch target overhangs it (`:284-292`).
                transform using SizeTransform(clip = false)
            },
            label = "trailing",
        ) { send ->
            if (send) {
                ComposerCircleButton(contentDescription = "Send", style = GlassStyle.Prominent, onClick = onSend) {
                    ShroudIcon(ShroudIcons.ArrowUp, Color.White, size = 20.dp)
                }
            } else {
                MicButton(phase = phase, level = level, gesture = gesture)
            }
        }
    }
}

/**
 * The hold-to-record mic (`micButton`, `ChatComposerView.swift:270-301`): grows to 1.25 and fills
 * with accent while recording; behind it a halo tracks the input level. The whole 44 dp box carries
 * the gesture; TalkBack's activation starts a hands-free take ([ComposerGesture.startLocked]).
 */
@Composable
private fun MicButton(phase: ComposerPhase, level: Float, gesture: ComposerGesture) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val active = phase.isActive
    val currentGesture by rememberUpdatedState(gesture)
    val coordinates = remember { WindowCoordinates() }
    // The live mic is 1.25× under Reduce Motion too (a state, not a movement); only the spring goes.
    val grow by animateFloatAsState(if (active) 1.25f else 1f, Motion.respecting(reduce, Motion.snappy()), label = "micGrow")
    val heard by animateFloatAsState(level, Motion.easeOut(LEVEL_EASE_MS), label = "micLevel")
    val haloAlpha by animateFloatAsState(if (active) 1f else 0f, Motion.respecting(reduce, Motion.snappy()), label = "micHaloAlpha")
    val haloBase by animateFloatAsState(if (active) 1.6f else 0.5f, Motion.respecting(reduce, Motion.snappy()), label = "micHaloBase")
    Box(
        Modifier
            .touchArea(ComposerMetrics.slotSize, ComposerMetrics.touchSize)
            .onGloballyPositioned { coordinates.layout = it }
            .pointerInput(Unit) { recordGesture(coordinates) { currentGesture } }
            .semantics {
                contentDescription = "Record voice message"
                role = Role.Button
                onClick(label = "Starts a hands-free recording. Send or discard it when you’re done.") {
                    currentGesture.startLocked()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Level-reactive halo — visible proof the mic hears something (`:284-291`).
        Box(
            Modifier
                .size(ComposerMetrics.controlSize)
                .graphicsLayer {
                    val scale = if (active) haloBase + heard * 1.1f else haloBase
                    scaleX = scale
                    scaleY = scale
                    alpha = haloAlpha
                }
                .background(colors.accent.copy(alpha = 0.18f), CircleShape),
        )
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = grow
                    scaleY = grow
                }
                .size(ComposerMetrics.controlSize)
                .glassSurface(CircleShape, if (active) GlassStyle.Prominent else GlassStyle.Regular, interactive = true),
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(ShroudIcons.Mic, if (active) Color.White else colors.accent, size = 20.dp)
        }
    }
}

/** The mic's place in the window, read on each pointer event (it moves under a still finger). */
private class WindowCoordinates {
    var layout: LayoutCoordinates? = null

    fun toWindow(local: Offset): Offset {
        val current = layout?.takeIf { it.isAttached } ?: return local
        return current.localToWindow(local)
    }
}

/**
 * The hold: touch-down reports (0, 0) at once (iOS `DragGesture(minimumDistance: 0)`), every move
 * the translation from the down point in **window** space, in dp; release or a cancelled touch
 * (the first microphone prompt cancels it) ends it (`ChatComposerView.swift:303-326`). The touch is
 * consumed so nothing under or around the mic scrolls.
 */
private suspend fun PointerInputScope.recordGesture(coordinates: WindowCoordinates, gesture: () -> ComposerGesture) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val origin = coordinates.toWindow(down.position)
        gesture().pointer(0f, 0f)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                val position = coordinates.toWindow(change.position)
                change.consume()
                gesture().pointer((position.x - origin.x).toDp().value, (position.y - origin.y).toDp().value)
            }
        } finally {
            gesture().pointerUp()
        }
    }
}

/**
 * Lays this out [visible] square but takes touches over [touch], centred — iOS
 * `.contentShape(Circle().inset(by: -2))`: a larger target around a control without moving it.
 * Put the click after it and the drawn control, centred, inside.
 */
internal fun Modifier.touchArea(visible: Dp, touch: Dp): Modifier = layout { measurable, _ ->
    val touchPx = touch.roundToPx()
    val visiblePx = visible.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(touchPx, touchPx))
    layout(visiblePx, visiblePx) { placeable.place((visiblePx - touchPx) / 2, (visiblePx - touchPx) / 2) }
}

/**
 * Grows the touch area by [horizontal] / [vertical] on each side without changing the layout:
 * `Modifier.outsetTouch(0.dp, 6.dp).pressable { … }.padding(vertical = 6.dp).glassSurface(…)` makes a
 * 36 dp capsule a 48 dp target.
 */
internal fun Modifier.outsetTouch(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) { placeable.place(-h, -v) }
}

/** The strip glass (`ComposerStripGlass`, `ChatComposerView.swift:399-406`): 2 dp of padding, radius 20. */
internal fun Modifier.composerStripGlass(): Modifier =
    this.glassSurface(RoundedCornerShape(ComposerMetrics.fieldRadius), GlassStyle.Bar).padding(vertical = 2.dp)

/** What the strip above the row shows. */
private enum class StripKind { Link, Reply }

/**
 * At most one strip above the row (`ChatComposerView.swift:87-104`): the link strip, unless a take is
 * running; else the reply strip, which stays up while recording ("a voice note can answer a message
 * too"). The strip pushes the thread up as it appears, sprung with [Motion.snappy].
 */
@Composable
private fun ComposerStrip(
    linkBar: ChatLinkBarState?,
    reply: ReplyQuoteContent?,
    linkShowsAboveText: Boolean,
    linkCanToggleImageSize: Boolean,
    linkUsesLargeImage: Boolean,
    onToggleLinkAboveText: () -> Unit,
    onToggleLinkImageSize: () -> Unit,
    onRemoveLinkPreview: () -> Unit,
    onTapReply: (() -> Unit)?,
    onCancelReply: () -> Unit,
) {
    val reduce = ShroudTheme.reduceMotion
    val kind = when {
        linkBar != null -> StripKind.Link
        reply != null -> StripKind.Reply
        else -> null
    }
    // What a leaving strip keeps showing while it animates out.
    var lastLink by remember { mutableStateOf(linkBar) }
    var lastReply by remember { mutableStateOf(reply) }
    if (linkBar != null) lastLink = linkBar
    if (reply != null) lastReply = reply
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            // `.transition(.move(edge: .bottom).combined(with: .opacity))` sprung with `Motion.snappy` (`:98, 103, 139-140`).
            val fade = Motion.respecting(reduce, Motion.snappy<Float>())
            val slide = Motion.snappy<IntOffset>()
            val enter: EnterTransition = when {
                targetState == null -> EnterTransition.None
                reduce -> fadeIn(fade)
                else -> slideInVertically(slide) { it } + fadeIn(fade)
            }
            val exit: ExitTransition = when {
                initialState == null -> ExitTransition.None
                reduce -> fadeOut(fade)
                else -> slideOutVertically(slide) { it } + fadeOut(fade)
            }
            (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> Motion.respecting(reduce, Motion.snappy()) }
        },
        label = "composerStrip",
    ) { shown ->
        when (shown) {
            StripKind.Link -> lastLink?.let { state ->
                ChatLinkBar(
                    state = state,
                    showsAboveText = linkShowsAboveText,
                    canToggleImageSize = linkCanToggleImageSize,
                    usesLargeImage = linkUsesLargeImage,
                    onToggleAboveText = onToggleLinkAboveText,
                    onToggleImageSize = onToggleLinkImageSize,
                    onRemove = onRemoveLinkPreview,
                    modifier = Modifier.padding(bottom = STRIP_GAP).composerStripGlass(),
                )
            }
            StripKind.Reply -> lastReply?.let { content ->
                ChatReplyBar(
                    content = content,
                    onTapPreview = onTapReply,
                    onCancel = onCancelReply,
                    modifier = Modifier.padding(bottom = STRIP_GAP).composerStripGlass(),
                )
            }
            null -> Box(Modifier.fillMaxWidth())
        }
    }
}

/** Between the strip and the row (`VStack(spacing: 8)`, `ChatComposerView.swift:86`). */
private val STRIP_GAP = 8.dp

/** The field's placeholder (`ChatComposerView.swift:206`). */
internal const val MESSAGE_PLACEHOLDER = "Message"

/** The halo follows the level with `easeOut(0.12)` (`ChatComposerView.swift:290`). */
private const val LEVEL_EASE_MS = 120

/** Send's drop as it swaps in (`ChatComposerView.swift:258`). */
private val SEND_DROP = 6.dp

/** The lock pill's top above the composer's top: its 60 dp end 14 dp above the row or strip (`ChatComposerView.swift:124-128`). */
private val LOCK_PILL_RISE = 74.dp

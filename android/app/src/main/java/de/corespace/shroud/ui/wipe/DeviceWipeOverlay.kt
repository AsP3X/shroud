package de.corespace.shroud.ui.wipe

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.DeviceWipeController
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlin.math.pow

/**
 * The full-screen "Clearing this phone" overlay over everything while `DeviceWipeController` runs
 * the Log Out / forced sign-out / removal wipe (`ShroudUI/Components/DeviceWipeOverlay.swift`;
 * settings-lock §14.5; design `Log Out — Clearing this phone` `qaRQA`, `Log Out — Phone is clear`
 * `ZFJ1m`; plan §1.7.13 entry point).
 *
 * Human: Covers everything — including the switch to Welcome underneath it — so the last thing the
 * user sees of their account is each piece of it being removed. The ring fills as steps finish,
 * dots drift out of the emblem while data is deleted, and each row ticks off with what it removed.
 * Done turns the ring green; the overlay then fades to Welcome. When the check finds something,
 * it says what and offers Try Again / Continue.
 *
 * Agent: READS `DeviceWipeController`; CALLS `retry` / `continueAfterFailure`. The shell shows it at
 * the top of the root (z 200) while `isPresented` and fades it out after. While presented it takes
 * every touch and Back and is a modal pane for TalkBack; once the controller lets go it draws the
 * **last non-idle phase** and starts nothing repeating, so a leaving overlay never redraws as
 * running inside its exit fade and never eats a touch meant for Welcome (`:17-30`, `:64-68`;
 * memory `stuck-removal-transition-eats-touches`). Each step's TalkBack announcement and the end's
 * haptic come from `DeviceWipeController.feedback`.
 */
@Composable
fun DeviceWipeOverlay() {
    val wipe = LocalAppContainer.current.auth.deviceWipe
    val phase by wipe.phase.collectAsState()
    val active by wipe.active.collectAsState()
    val details by wipe.details.collectAsState()
    val leftovers by wipe.leftovers.collectAsState()
    val retrying by wipe.retrying.collectAsState()
    val reason by wipe.reason.collectAsState()
    val handle by wipe.handle.collectAsState()
    val presented by wipe.isPresented.collectAsState()
    val view = LocalView.current
    // TalkBack hears each step and the end; the end plays its haptic (`DeviceWipeController.feedback`).
    LaunchedEffect(wipe, view) {
        wipe.feedback.collect { feedback ->
            view.speak(feedback.announcement)
            view.perform(feedback.haptic)
        }
    }
    DeviceWipeOverlayContent(
        state = WipeOverlayState(phase, reason, active, details, leftovers, retrying, handle),
        presented = presented,
        noun = DeviceNoun.current(LocalContext.current),
        onRetry = wipe::retry,
        onContinue = wipe::continueAfterFailure,
    )
}

/** What the overlay draws, as `DeviceWipeController` publishes it. */
@Immutable
data class WipeOverlayState(
    val phase: WipePhase,
    val reason: WipeReason = WipeReason.Logout,
    val active: WipeStep? = null,
    val details: Map<WipeStep, String> = emptyMap(),
    val leftovers: List<DeviceDataWipe.Leftover> = emptyList(),
    val retrying: Set<WipeStep> = emptySet(),
    val handle: String = "",
)

/**
 * [DeviceWipeOverlay] on explicit state, for screen tests. [presented] false is the leaving
 * overlay: no touches, no Back, hidden from TalkBack, nothing repeating, the last phase kept.
 */
@Composable
internal fun DeviceWipeOverlayContent(
    state: WipeOverlayState,
    presented: Boolean,
    noun: String,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    // The controller is back at Idle before the fade ends; Idle drawn as Running restarted the
    // emblem's loops inside the removal transition, which then never finished (`:17-30`).
    var lastShown by remember { mutableStateOf(WipePhase.Running) }
    val phase = WipeOverlayText.shownPhase(state.phase, lastShown)
    SideEffect { if (state.phase != WipePhase.Idle) lastShown = state.phase }
    val rows = WipeStep.entries.associateWith { WipeOverlayText.rowState(it, phase, state.active, state.retrying, state.details, state.leftovers) }
    val progress = WipeOverlayText.progress(phase, rows.values)
    val title = WipeOverlayText.title(phase, noun)
    // Loops run only while the wipe runs on a presented overlay and motion is on.
    val animating = presented && phase == WipePhase.Running && !reduce

    BackHandler(enabled = presented) {}
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundGrouped)
            .then(if (presented) Modifier.pointerInput(Unit) { swallowEveryTouch() } else Modifier)
            .then(
                if (presented) {
                    Modifier.semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        // Scrolls when it does not fit (a small phone, landscape, a long failed subtitle); the
        // footer stays pinned so Try Again and Continue are always on screen (`:37-61`).
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WipeEmblem(phase, progress, noun, animating)
                Heading(title, WipeOverlayText.subtitle(phase, state.reason, state.handle, state.leftovers, noun))
                StepsCard(rows, state.details, spinning = presented)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.backgroundGrouped)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Footer(phase, presented, onRetry, onContinue, Modifier.widthIn(max = 520.dp).fillMaxWidth())
        }
    }
}

/** Takes every pointer event so nothing under the overlay reacts. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.swallowEveryTouch() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

/** Title 26 Bold and the subtitle, cross-fading with `Motion.standard` (`:76-92`). */
@Composable
private fun Heading(title: String, subtitle: String) {
    val colors = ShroudTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crossfade(title, animationSpec = Motion.fade(), label = "wipeTitle") { text ->
            ShroudText(text, inter(26f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() }, textAlign = TextAlign.Center)
        }
        Crossfade(subtitle, animationSpec = Motion.fade(), label = "wipeSubtitle") { text ->
            ShroudText(text, inter(15f, lineSpacing = 2f), colors.textSecondary, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

/** The six rows on `background`, r14, 1 dp separators (`steps`, `:122-136`); the running row's arc turns only while [spinning]. */
@Composable
private fun StepsCard(rows: Map<WipeStep, WipeOverlayText.RowState>, details: Map<WipeStep, String>, spinning: Boolean) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(horizontal = 16.dp),
    ) {
        WipeStep.entries.forEachIndexed { index, step ->
            if (index > 0) Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(colors.separator))
            val state = rows.getValue(step)
            StepRow(step, state, WipeOverlayText.rowDetail(step, state, details), WipeOverlayText.rowStatus(step, state, details), spinning)
        }
    }
}

/**
 * One step (`row(_:)`, `:138-163`): status 24, title 16 (pending: Regular secondary; else Medium
 * primary) that may wrap, the detail 14 secondary on one line that keeps its width. TalkBack: the
 * title, with "Waiting" / "In progress" / the detail as its state.
 */
@Composable
private fun StepRow(step: WipeStep, state: WipeOverlayText.RowState, detail: String?, spoken: String, spinning: Boolean) {
    val colors = ShroudTheme.colors
    val pending = state == WipeOverlayText.RowState.Pending
    val titleColor by animateColorAsState(if (pending) colors.textSecondary else colors.textPrimary, Motion.snappy(), label = "stepTitle")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clearAndSetSemantics {
                contentDescription = step.title
                stateDescription = spoken
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepStatus(state, spinning)
        ShroudText(step.title, inter(16f, if (pending) FontWeight.Normal else FontWeight.Medium), titleColor, Modifier.weight(1f).padding(vertical = 4.dp))
        Crossfade(detail, animationSpec = Motion.fade(), label = "stepDetail") { text ->
            if (text != null) ShroudText(text, inter(14f), colors.textSecondary, maxLines = 1)
        }
    }
}

/**
 * Hollow while waiting, a turning arc while running, a filled tick when done, "!" when it failed
 * (`WipeStepStatus`, `:329-368`); a finished state pops in from 0.5 with a fade (`Motion.snappy`).
 */
@Composable
private fun StepStatus(state: WipeOverlayText.RowState, spinning: Boolean) {
    val colors = ShroudTheme.colors
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            val pops = targetState == WipeOverlayText.RowState.Done || targetState == WipeOverlayText.RowState.Failed
            (if (pops) scaleIn(Motion.snappy(), 0.5f) + fadeIn(Motion.snappy()) else fadeIn(Motion.snappy())) togetherWith fadeOut(Motion.fade())
        },
        label = "stepStatus",
    ) { shown ->
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (shown) {
                WipeOverlayText.RowState.Pending -> Box(Modifier.size(24.dp).border(1.5.dp, colors.separator, CircleShape))
                WipeOverlayText.RowState.Active -> WipeSpinner(spinning)
                WipeOverlayText.RowState.Done -> Box(Modifier.size(24.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    ShroudIcon(ShroudIcons.CheckBold, Color.White, size = 11.dp)
                }
                WipeOverlayText.RowState.Failed -> Box(Modifier.size(24.dp).clip(CircleShape).background(colors.danger.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    ShroudText("!", inter(12f, FontWeight.Bold), colors.danger)
                }
            }
        }
    }
}

/**
 * The running row's arc (`WipeSpinner`, `:370-387`): a 2.2 dp `separator` track and a 0.28-turn
 * accent arc with round caps, inset 1.1, one turn per 0.8 s; still under Reduce Motion and on a
 * leaving overlay ([spinning] false — nothing repeats inside the exit fade).
 */
@Composable
private fun WipeSpinner(spinning: Boolean) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    var turns by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduce, spinning) {
        if (reduce || !spinning) return@LaunchedEffect
        // An infinite animation: tests and the system's animation policy may pause it.
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { now -> turns = ((now - start) / 1e9f / SPINNER_TURN_S) % 1f }
    }
    Canvas(Modifier.size(24.dp).padding(1.1.dp)) {
        val stroke = 2.2.dp.toPx()
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawCircle(colors.separator, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
        drawArc(colors.accent, turns * 360f - 90f, 0.28f * 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private const val SPINNER_TURN_S = 0.8f

/**
 * The device in a ring that fills as the wipe goes, dots drifting out of it while data is removed
 * (`WipeEmblem`, `:229-285`): track 4 dp `separator`, progress arc 4 dp in the tint from 12 o'clock
 * (`Motion.gentle`, hidden at 0), an 88 dp disc, the glyph cross-fading (iOS `.replace`) and
 * breathing 1 ↔ 1.06 over 1.6 s while running. Tint: running `accent`, done `online`, failed
 * `danger` (`Motion.standard`). Decorative.
 */
@Composable
private fun WipeEmblem(phase: WipePhase, progress: Float, noun: String, animating: Boolean) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val running = phase != WipePhase.Done && phase != WipePhase.Failed
    val tintTarget = when (phase) {
        WipePhase.Done -> colors.online
        WipePhase.Failed -> colors.danger
        else -> colors.accent
    }
    val tint by animateColorAsState(tintTarget, Motion.respecting(reduce, Motion.standard()), label = "emblemTint")
    val disc by animateColorAsState(if (running) colors.accentSoft else tintTarget.copy(alpha = 0.14f), Motion.respecting(reduce, Motion.standard()), label = "emblemDisc")
    val shownProgress by animateFloatAsState(progress, Motion.respecting(reduce, Motion.gentle()), label = "emblemProgress")
    val breath = remember { Animatable(1f) }
    LaunchedEffect(animating) {
        if (animating) {
            while (true) {
                // Each breath starts on an infinite-animation frame, so the policy (tests) can hold the loop.
                withInfiniteAnimationFrameNanos { }
                breath.animateTo(1.06f, tween(BREATH_HALF_MS))
                breath.animateTo(1f, tween(BREATH_HALF_MS))
            }
        } else {
            breath.snapTo(1f)
        }
    }
    Box(Modifier.size(128.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (animating) WipeParticles(colors.accent)
        Canvas(Modifier.size(128.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawCircle(colors.separator, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (shownProgress > 0f) {
                drawArc(tint, -90f, shownProgress.coerceIn(0f, 1f) * 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(Modifier.size(88.dp).clip(CircleShape).background(disc))
        Crossfade(phase.emblemGlyph(noun), animationSpec = Motion.respecting(reduce, Motion.standard()), label = "emblemGlyph") { glyph ->
            ShroudIcon(
                glyph.icon,
                tint,
                Modifier.graphicsLayer {
                    scaleX = breath.value
                    scaleY = breath.value
                },
                size = glyph.size.dp,
            )
        }
    }
}

private const val BREATH_HALF_MS = 800

private class EmblemGlyph(val icon: ImageVector, val size: Int)

/** Running: the device (Lucide `smartphone`; a tablet's `device-tablet-fill`) 38; done: check 36; failed: `warning-fill` 36. */
private fun WipePhase.emblemGlyph(noun: String): EmblemGlyph = when (this) {
    WipePhase.Done -> EmblemGlyph(ShroudIcons.Check, 36)
    WipePhase.Failed -> EmblemGlyph(ShroudIcons.WarningFill, 36)
    else -> EmblemGlyph(if (noun == DeviceNoun.TABLET) ShroudIcons.DeviceTabletFill else ShroudIcons.Smartphone, 38)
}

/**
 * Six dots leaving the emblem on a loop, staggered so one is always in flight (`WipeParticles`,
 * `:287-325`; [WipeOverlayText.particle] holds the curve). Composed only while the wipe runs.
 */
@Composable
private fun WipeParticles(color: Color) {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { now -> seconds = (now - start) / 1e9f }
    }
    Canvas(Modifier.size(128.dp)) {
        val centre = Offset(size.width / 2, size.height / 2)
        WipeOverlayText.PARTICLES.forEach { p ->
            val frame = WipeOverlayText.particle(p, seconds.toDouble())
            val radius = p.size.dp.toPx() / 2 * frame.scale
            drawCircle(
                color,
                radius = radius,
                center = centre + Offset((p.dx * frame.eased).dp.toPx(), (p.dy * frame.eased).dp.toPx()),
                alpha = frame.alpha.coerceIn(0f, 1f),
            )
        }
    }
}

/** TalkBack announcement. `View.announceForAccessibility` and `TYPE_ANNOUNCEMENT` are deprecated. */
private fun View.speak(text: CharSequence) {
    val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
        this.text.add(text)
        contentDescription = text
        className = this@speak.javaClass.name
        packageName = context.packageName
    }
    if (parent?.requestSendAccessibilityEvent(this, event) == true) return
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return
    if (manager.isEnabled) manager.sendAccessibilityEvent(event)
}

/**
 * Try Again / Continue when it failed; otherwise the reassurance — a small spinner and "Taking you
 * to the welcome screen…" once done (`footer`, `:197-226`). Failed slides up from the bottom with a
 * fade (Reduce Motion: fade).
 */
@Composable
private fun Footer(phase: WipePhase, presented: Boolean, onRetry: () -> Unit, onContinue: () -> Unit, modifier: Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    AnimatedContent(
        targetState = phase == WipePhase.Failed,
        modifier = modifier,
        transitionSpec = {
            val enter = if (targetState && !reduce) fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it / 2 } else fadeIn(Motion.respecting(reduce, Motion.standard()))
            enter togetherWith fadeOut(Motion.fade())
        },
        contentAlignment = Alignment.BottomCenter,
        label = "wipeFooter",
    ) { failed ->
        if (failed) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Try Again", onRetry, showsArrow = false, enabled = presented)
                SecondaryButton("Continue", onContinue, enabled = presented)
            }
        } else {
            val done = phase == WipePhase.Done
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Crossfade(done, animationSpec = Motion.fade(), label = "footerGlyph") { isDone ->
                    if (isDone) {
                        // Only while the overlay is up: nothing repeats in its exit fade.
                        if (presented && !reduce) Spinner(colors.textSecondary, size = 15.dp) else Box(Modifier.size(15.dp))
                    } else {
                        ShroudIcon(ShroudIcons.ShieldCheck, colors.textSecondary, size = 15.dp)
                    }
                }
                Crossfade(WipeOverlayText.footer(done), animationSpec = Motion.fade(), label = "footerText") { text ->
                    ShroudText(text, inter(13f), colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * The overlay's words, row states and curves as iOS draws them (`DeviceWipeOverlay.swift:94-193,
 * 287-325`). Pure, so the copy and the math are tested on the JVM.
 */
internal object WipeOverlayText {
    /** `WipeStepStatus.State`. */
    enum class RowState { Pending, Active, Done, Failed }

    /** The phase drawn: the controller's, except that Idle keeps the last one while the overlay fades out (`:24-30`). */
    fun shownPhase(phase: WipePhase, lastShown: WipePhase): WipePhase = if (phase == WipePhase.Idle) lastShown else phase

    /** `title` (`:94-100`), [A] "phone" / "tablet". */
    fun title(phase: WipePhase, noun: String): String = when (phase) {
        WipePhase.Failed -> "Couldn’t clear everything"
        WipePhase.Done -> "This $noun is clear"
        else -> "Clearing this $noun"
    }

    /**
     * The line under the title (`subtitle`, `:102-118`): why and for whom while running ("Your
     * session ended. ", "This phone was removed from your account. ", nothing for Log Out), what is
     * left when the check failed, and that nothing is left once done.
     */
    fun subtitle(phase: WipePhase, reason: WipeReason, handle: String, leftovers: List<DeviceDataWipe.Leftover>, noun: String): String = when (phase) {
        WipePhase.Failed ->
            "Still here: ${DeviceWipeController.labels(leftovers)}. Try again — if it keeps failing, restart your $noun and open Shroud; it finishes on its own."
        WipePhase.Done ->
            if (handle.isEmpty()) "Nothing from your account is left on this $noun." else "Nothing from $handle is left on this device."
        else -> {
            val whose = if (handle.isEmpty()) "on this $noun" else "for $handle"
            "${reason.lead(noun)}Removing everything Shroud stored $whose."
        }
    }

    /** `rowState(_:)` (`:165-171`): failed rows first, then the running or retried one, then finished ones. */
    fun rowState(
        step: WipeStep,
        phase: WipePhase,
        active: WipeStep?,
        retrying: Set<WipeStep>,
        details: Map<WipeStep, String>,
        leftovers: List<DeviceDataWipe.Leftover>,
    ): RowState = when {
        phase == WipePhase.Failed && (step == WipeStep.Verify || leftovers.any { it.step == step }) -> RowState.Failed
        active == step || step in retrying -> RowState.Active
        details[step] != null -> RowState.Done
        else -> RowState.Pending
    }

    /** The detail a row shows (`:140`): a finished row's detail, "Still here" on a failed data row, else nothing. */
    fun rowDetail(step: WipeStep, state: RowState, details: Map<WipeStep, String>): String? = when (state) {
        RowState.Done -> details[step]
        RowState.Failed -> if (step == WipeStep.Verify) null else "Still here"
        else -> null
    }

    /**
     * What TalkBack says of a row's state (`accessibilityValue`, `:173-179`): "Waiting", "In
     * progress", else the detail, falling back to "Failed" / "Done".
     */
    fun rowStatus(step: WipeStep, state: RowState, details: Map<WipeStep, String>): String = when (state) {
        RowState.Done -> details[step] ?: "Done"
        RowState.Failed -> if (step == WipeStep.Verify) "Failed" else "Still here"
        RowState.Active -> "In progress"
        RowState.Pending -> "Waiting"
    }

    /** The ring (`progress`, `:181-185`): full once done, else the share of finished rows. */
    fun progress(phase: WipePhase, rows: Collection<RowState>): Float =
        if (phase == WipePhase.Done) 1f else rows.count { it == RowState.Done }.toFloat() / WipeStep.entries.size

    /** The footer line (`:215-219`). */
    fun footer(done: Boolean): String =
        if (done) "Taking you to the welcome screen…" else "Your account and chats on other devices stay as they are."

    /** One dot of the emblem (`Particle`, `:289-303`): where it flies (dp), its size (dp), its delay (s). */
    class Particle(val dx: Float, val dy: Float, val size: Float, val delay: Double)

    /** `WipeParticles.particles` (`:296-303`). */
    val PARTICLES = listOf(
        Particle(62f, -40f, 8f, 0.0),
        Particle(72f, 12f, 5f, 0.5),
        Particle(-64f, -30f, 6f, 0.25),
        Particle(-70f, 24f, 5f, 0.9),
        Particle(40f, 60f, 6f, 1.2),
        Particle(-18f, -70f, 4f, 0.7),
    )

    /** `WipeParticles.period` (`:304`). */
    const val PARTICLE_PERIOD_S = 1.8

    /** A dot at [seconds]: how far along it is ([eased]), its scale and its opacity (`:312-319`). */
    class ParticleFrame(val phase: Double, val eased: Float, val scale: Float, val alpha: Float)

    fun particle(p: Particle, seconds: Double): ParticleFrame {
        val phase = ((seconds + PARTICLE_PERIOD_S - p.delay) % PARTICLE_PERIOD_S) / PARTICLE_PERIOD_S
        val eased = 1 - (1 - phase).pow(2)
        val alpha = if (phase < 0.2) phase / 0.2 * 0.9 else 0.9 * (1 - (phase - 0.2) / 0.8)
        return ParticleFrame(phase, eased.toFloat(), (1 - 0.65 * eased).toFloat(), alpha.toFloat())
    }
}

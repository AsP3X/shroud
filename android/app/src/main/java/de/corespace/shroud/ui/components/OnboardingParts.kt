package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.auth.PasswordStrength
import de.corespace.shroud.core.auth.PasswordStrengthLevel
import de.corespace.shroud.ui.theme.BrandColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay

/** A 1 dp separator with an optional leading inset (a card's `Divider().padding(.leading, 42)`). */
@Composable
fun Separator(modifier: Modifier = Modifier, startInset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(ShroudTheme.colors.separator),
    )
}

/** Caption over a group ("CONNECTION", "ENCRYPTION PHRASE"): 11 semibold, 0.8 tracking. */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    ShroudText(
        text,
        inter(11f, FontWeight.SemiBold, letterSpacing = 0.8.sp),
        ShroudTheme.colors.textSecondary,
        modifier.semantics { heading() },
    )
}

/**
 * Grey rounded bar standing in for a phrase word that is still arriving
 * (`ShroudUI/Components/ShimmerPlaceholder.swift:4-36`; design-inventory addendum Shimmer SH.1):
 * radius 6, `backgroundGrouped`, overlaid with a horizontal gradient grouped → background →
 * grouped 1.8 × the bar's width, offset by width × phase while the phase runs −1 → 1 linearly every
 * [PHRASE_SHIMMER_MS] (`:31-34`). The highlight is the page background: white in light mode,
 * black in dark mode. Reduce Motion is not consulted (iOS doesn't). Not the list skeleton sweep
 * ([shimmering]).
 */
@Composable
fun ShimmerPlaceholder(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val phase by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PHRASE_SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase",
    )
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                val w = size.width
                val start = phraseShimmerStart(phase, w)
                // Clamped ends are the grouped fill, so the bar outside the gradient stays grey.
                drawRect(
                    Brush.horizontalGradient(
                        listOf(colors.backgroundGrouped, colors.background, colors.backgroundGrouped),
                        startX = start,
                        endX = start + PHRASE_SHIMMER_SPAN * w,
                    ),
                )
            },
    )
}

/** One sweep of [ShimmerPlaceholder]: `.linear(duration: 0.85)` (`ShimmerPlaceholder.swift:32`). */
const val PHRASE_SHIMMER_MS = 850

/** The gradient spans 1.8 × the bar's width (`ShimmerPlaceholder.swift:25`). */
const val PHRASE_SHIMMER_SPAN = 1.8f

/** Left edge of [ShimmerPlaceholder]'s gradient: `geometry.size.width * phase` (`ShimmerPlaceholder.swift:26`). */
fun phraseShimmerStart(phase: Float, width: Float): Float = width * phase

/** Two-step stepper of the Log In and Sign Up flows (`FlowStepper.swift`). */
@Composable
fun FlowStepper(activeStep: Int, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
    val colors = ShroudTheme.colors
    val connector by animateColorAsState(if (activeStep > 1) colors.accent else colors.separator, Motion.standard(), label = "stepConnector")
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Step $activeStep of 2, ${if (activeStep == 1) "Account" else "Phrase"}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepChip(1, "Account", isActive = activeStep == 1, isComplete = activeStep > 1)
        Box(Modifier.size(24.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(connector))
        StepChip(2, "Phrase", isActive = activeStep == 2, isComplete = false)
    }
}

@Composable
private fun StepChip(number: Int, label: String, isActive: Boolean, isComplete: Boolean) {
    val colors = ShroudTheme.colors
    val chip by animateColorAsState(if (isActive) colors.accentSoft else Color.Transparent, Motion.standard(), label = "chip")
    val circle by animateColorAsState(if (isActive || isComplete) colors.accent else colors.background, Motion.standard(), label = "chipCircle")
    val labelColor by animateColorAsState(if (isActive) colors.accentText else colors.textSecondary, Motion.standard(), label = "chipLabel")
    Row(
        Modifier
            .clip(CircleShape)
            .background(chip)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            if (isComplete) {
                ShroudIcon(ShroudIcons.Check, Color.White, size = 11.dp)
            } else {
                ShroudText("$number", inter(11f, FontWeight.Bold), if (isActive) Color.White else colors.textSecondary)
            }
        }
        ShroudText(label, inter(12f, FontWeight.SemiBold), labelColor)
    }
}

/** Password strength panel of the Sign Up identity card (`PasswordStrengthMeter.swift`). */
@Composable
fun PasswordStrengthMeter(evaluation: PasswordStrength, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val level = evaluation.level
    val score by animateFloatAsState(evaluation.score.toFloat(), Motion.respecting(reduce, Motion.snappy()), label = "strengthScore")
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.strengthPanel)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(17.dp)
                .clearAndSetSemantics {
                    contentDescription = "Password strength"
                    stateDescription = if (level == PasswordStrengthLevel.Empty) "None" else level.label
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudText("PASSWORD STRENGTH", inter(11f, FontWeight.SemiBold, letterSpacing = 0.8.sp), colors.textSecondary)
            Spacer(Modifier.weight(1f))
            // The badge pops in and out (iconSwap) only when it appears or disappears; between
            // levels it stays and eases its colours, glyph and text (`PasswordStrengthMeter.swift:32-35`,
            // settings-lock addendum PasswordStrengthMeter M1). It keeps the last level while it leaves.
            var badgeLevel by remember { mutableStateOf(level) }
            if (level != PasswordStrengthLevel.Empty && badgeLevel != level) badgeLevel = level
            val badgeTransition = Motion.iconSwap.respecting(reduce)
            Appear(visible = level != PasswordStrengthLevel.Empty, enter = badgeTransition.enter, exit = badgeTransition.exit) {
                StrengthBadge(badgeLevel)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.strengthTrack)) {
            val minWidth = if (level == PasswordStrengthLevel.Empty) 0.dp else 8.dp
            val fill = maxOf(maxWidth * score, minWidth)
            val brush = when (level) {
                PasswordStrengthLevel.Strong, PasswordStrengthLevel.Good ->
                    Brush.verticalGradient(listOf(BrandColors.strengthStrongTop, BrandColors.strengthStrongBottom))
                PasswordStrengthLevel.Fair -> Brush.linearGradient(listOf(colors.warningIcon, colors.warningIcon))
                PasswordStrengthLevel.Weak -> Brush.linearGradient(listOf(colors.danger, colors.danger))
                PasswordStrengthLevel.Empty -> Brush.linearGradient(listOf(colors.separator.copy(alpha = 0.6f), colors.separator.copy(alpha = 0.6f)))
            }
            Box(Modifier.width(fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(brush))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Requirement("12+ characters", evaluation.hasMinimumLength)
            Requirement("Symbol & number", evaluation.hasSymbolAndNumber)
        }
    }
}

/**
 * The strength badge (`PasswordStrengthMeter.swift:43-55`): glyph 11 + level 11 Bold, padding 7 / 2,
 * capsule. Colours ease with `Motion.snappy` (Reduce Motion: the short fade), glyph and text
 * cross-fade, the capsule's width follows the text.
 */
@Composable
private fun StrengthBadge(level: PasswordStrengthLevel) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val (fgTarget, bgTarget) = when (level) {
        PasswordStrengthLevel.Strong, PasswordStrengthLevel.Good -> colors.successText to colors.successBackground
        PasswordStrengthLevel.Fair -> colors.warningText to colors.warningBackground
        PasswordStrengthLevel.Weak -> colors.dangerText to colors.danger.copy(alpha = 0.12f)
        PasswordStrengthLevel.Empty -> colors.textSecondary to colors.backgroundGrouped
    }
    val fg by animateColorAsState(fgTarget, Motion.respecting(reduce, Motion.snappy()), label = "badgeForeground")
    val bg by animateColorAsState(bgTarget, Motion.respecting(reduce, Motion.snappy()), label = "badgeBackground")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .animateContentSize(Motion.respecting(reduce, Motion.snappy()))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = strengthBadgeIcon(level), animationSpec = Motion.fade(), label = "badgeGlyph") { icon ->
            ShroudIcon(icon, fg, size = 11.dp)
        }
        Crossfade(targetState = level.label, animationSpec = Motion.fade(), label = "badgeText") { text ->
            ShroudText(text, inter(11f, FontWeight.Bold), fg)
        }
    }
}

/** strong / good: shield-check; fair: shield-half; weak: shield-alert (settings-lock addendum M4: design icons). */
private fun strengthBadgeIcon(level: PasswordStrengthLevel) = when (level) {
    PasswordStrengthLevel.Strong, PasswordStrengthLevel.Good -> ShroudIcons.ShieldCheck
    PasswordStrengthLevel.Fair -> ShroudIcons.ShieldHalf
    else -> ShroudIcons.ShieldAlert
}

@Composable
private fun Requirement(label: String, met: Boolean) {
    val colors = ShroudTheme.colors
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = if (met) "Met" else "Not met"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = met,
            transitionSpec = { (scaleIn(Motion.snappy(), 0.45f) + fadeIn()) togetherWith (scaleOut(Motion.snappy(), 0.45f) + fadeOut()) },
            label = "requirement",
        ) { isMet ->
            ShroudIcon(
                if (isMet) ShroudIcons.CircleCheck else ShroudIcons.Circle,
                if (isMet) colors.online else colors.textSecondary.copy(alpha = 0.45f),
                size = 12.dp,
            )
        }
        ShroudText(label, inter(11f, FontWeight.Medium), colors.textSecondary)
    }
}

/**
 * Word-number badge that flashes accent once when its word arrives
 * (`ShroudUI/Components/EncryptionPhraseCard.swift:74-113`): 20 × 20, radius 6, `accentSoft` with
 * the number 10 SemiBold monospaced `accentText`. Only a change **to** revealed pulses (iOS
 * `onChange` never sees the initial value, `:93`; settings-lock addendum EncryptionPhraseCard E1):
 * fill `accent`, text white, scale 1.12 on the word-reveal spring, back after
 * [Motion.BADGE_PULSE_MS] with `Motion.fade` (`:100-110`, E2). A change to hidden ends the pulse at
 * once (`:94-96`). Reduce Motion keeps the colour flash and drops the scale (`:91-92`).
 */
@Composable
fun PhraseWordNumberBadge(number: Int, isRevealed: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    var pulsing by remember { mutableStateOf(false) }
    // The value the last effect saw: a badge composed already revealed (Log In's phrase step, Sign
    // Up returning to the phrase) must not flash.
    val last = remember { booleanArrayOf(isRevealed) }
    LaunchedEffect(isRevealed) {
        val was = last[0]
        last[0] = isRevealed
        if (!isRevealed) {
            pulsing = false
            return@LaunchedEffect
        }
        if (!badgePulses(was, isRevealed)) return@LaunchedEffect
        pulsing = true
        delay(Motion.BADGE_PULSE_MS)
        pulsing = false
    }
    val spec: FiniteAnimationSpec<Color> = if (pulsing) Motion.wordReveal() else Motion.fade()
    val fill by animateColorAsState(if (pulsing) colors.accent else colors.accentSoft, spec, label = "badgeFill")
    val text by animateColorAsState(if (pulsing) Color.White else colors.accentText, spec, label = "badgeText")
    val scale by animateFloatAsState(
        if (pulsing && !reduce) 1.12f else 1f,
        if (pulsing) Motion.wordReveal() else Motion.fade(),
        label = "badgeScale",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText("$number", inter(10f, FontWeight.SemiBold, monospaced = true), text)
    }
}

/** Whether a badge pulses when its word goes from [was] to [now] revealed: only on false → true (E1). */
fun badgePulses(was: Boolean, now: Boolean): Boolean = !was && now

/**
 * The generated phrase in six rows of two (`EncryptionPhraseCard.swift`); words past
 * [revealedCount] show a shimmer.
 */
@Composable
fun EncryptionPhraseCard(words: List<String>, revealedCount: Int, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(vertical = 6.dp),
    ) {
        for (row in 0 until 6) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 0 until 2) {
                    val index = row * 2 + column
                    PhraseCell(index + 1, words.getOrElse(index) { "" }, index < revealedCount, Modifier.weight(1f))
                }
            }
            if (row < 5) Separator()
        }
    }
}

@Composable
private fun PhraseCell(number: Int, word: String, isRevealed: Boolean, modifier: Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = if (isRevealed) "Word $number: $word" else "Word $number, loading" },
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhraseWordNumberBadge(number, isRevealed)
        Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
            Appear(
                visible = isRevealed,
                enter = if (reduce) fadeIn(Motion.fade()) else fadeIn(Motion.wordReveal()) + scaleIn(Motion.wordReveal(), 0.86f, TransformOrigin(0f, 0.5f)),
                exit = fadeOut(Motion.fade()),
            ) {
                ShroudText(word, inter(14f, FontWeight.Medium, monospaced = true), colors.textPrimary, maxLines = 1)
            }
            Appear(visible = !isRevealed, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                ShimmerPlaceholder(92.dp, 14.dp)
            }
        }
    }
}

/**
 * "I wrote down my encryption phrase" (`confirmRow`, `SignUpView.swift:237-270`; the same row on Log
 * In's new phrase, `LogInFlowView.swift:359-393`): the box and the line toggle together; the row is
 * at least 48 dp tall without moving its neighbours (iOS reaches ≈ 44 pt with a −11 hit inset;
 * addendum SignUp S4); press 0.97. TalkBack: a check box, "Checked" / "Not checked".
 */
@Composable
fun WroteDownRow(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(enabled = enabled, scale = 0.97f, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "Checked" else "Not checked" },
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBoxMark(checked)
        ShroudText("I wrote down my encryption phrase", inter(13f, FontWeight.Medium), colors.textPrimary)
    }
}

/**
 * The amber warning under a phrase (`warningCard`, `SignUpView.swift:221-235`): triangle 15
 * `warningIcon` + 12 sp `warningText`, line spacing 3, padding 12 / 10, r12, one TalkBack stop.
 */
@Composable
fun PhraseWarningCard(text: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.warningBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ShroudIcon(ShroudIcons.WarningFill, colors.warningIcon, size = 15.dp)
        ShroudText(text, inter(12f, lineSpacing = 3f), colors.warningText)
    }
}

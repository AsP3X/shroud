package de.corespace.shroud.ui.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.onboarding.onboardingHeroSource
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The animation of one phase change (`LockScreenView.swift:219-220` plus the phase's own
 * `withAnimation`): leaving or reaching *released* rides `Motion.gentle`, *verified* `Motion.bouncy`,
 * everything else (`Idle` ↔ `Checking`) `Motion.snappy`; Reduce Motion the short fade.
 */
fun <T> lockPhaseSpec(from: UnlockPhase, to: UnlockPhase, reduceMotion: Boolean): FiniteAnimationSpec<T> = when {
    reduceMotion -> Motion.reduced()
    from.isReleased != to.isReleased -> Motion.gentle()
    from.isVerified != to.isVerified -> Motion.bouncy()
    to == UnlockPhase.Idle && from == UnlockPhase.Revealing -> Motion.gentle()
    else -> Motion.snappy()
}

/**
 * The lock screen's hero (`LockScreenView.swift:170-297`; settings-lock §11.4; design `yGDcx`,
 * storyboard `dRyqM`): a 300 × 300 stage scaled by [scale] on short screens — two rings, the soft
 * disc, the "Sealed" and "•••• ••••" chips, the brand mark and the lock badge. Every value follows
 * [phase] through one transition, so each change rides the token of its step; the badge breathes
 * only while the prompt is up (never in *Releasing* / *Revealing*, memory
 * `stuck-removal-transition-eats-touches`). [arrival] is the first appearance (0 → 1). Decorative:
 * hidden from TalkBack and not hit-testable.
 */
@Composable
internal fun LockHero(phase: Transition<UnlockPhase>, scale: Float, arrival: Float, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    fun <T> spec(s: Transition.Segment<UnlockPhase>): FiniteAnimationSpec<T> = lockPhaseSpec(s.initialState, s.targetState, reduce)

    val ringScale by phase.animateFloat({ spec(this) }, label = "ringScale") { if (it.isReleased) 1.3f else 1f }
    val releasedAlpha by phase.animateFloat({ spec(this) }, label = "releasedAlpha") { if (it.isReleased) 0f else 1f }
    val discScale by phase.animateFloat({ spec(this) }, label = "discScale") { LockHeroMath.discScale(it) }
    val chipScale by phase.animateFloat({ spec(this) }, label = "chipScale") { if (it.isReleased) 0.9f else 1f }
    val markScale by phase.animateFloat({ spec(this) }, label = "markScale") { LockHeroMath.markScale(it) }
    val markOffset by phase.animateFloat({ spec(this) }, label = "markOffset") { LockHeroMath.markOffsetY(it) }
    val markAlpha by phase.animateFloat({ spec(this) }, label = "markAlpha") { if (it == UnlockPhase.Revealing) 0f else 1f }
    val markShadowAlpha by phase.animateFloat({ spec(this) }, label = "markShadowAlpha") { if (it.isReleased) 0.4f else 0.3f }
    val markShadowRadius by phase.animateFloat({ spec(this) }, label = "markShadowRadius") { if (it.isReleased) 22f else 18f }
    val markShadowY by phase.animateFloat({ spec(this) }, label = "markShadowY") { if (it.isReleased) 22f else 16f }
    val verified = phase.targetState.isVerified
    val badgeFill by phase.animateColor({ spec(this) }, label = "badgeFill") { if (it.isVerified) colors.online else colors.bubbleIncoming }

    // The badge (`:121-130`, `:242-245`): it breathes 1 ↔ 1.12 over 0.9 s while the system prompt
    // is up, springs to 1.1 once verified, and settles back to 1 otherwise — one value, so a breath
    // in progress flows into the verified spring without a jump.
    val badge = remember { Animatable(1f) }
    val badgeTarget = phase.targetState
    LaunchedEffect(badgeTarget, reduce) {
        when {
            badgeTarget.isVerified -> badge.animateTo(LockHeroMath.badgeScale(badgeTarget, 1f), lockPhaseSpec(UnlockPhase.Checking, UnlockPhase.Verified, reduce))
            badgeTarget == UnlockPhase.Checking && !reduce -> while (true) {
                badge.animateTo(1.12f, tween(LockHeroMath.BREATH_MS, easing = FastOutSlowInEasing))
                badge.animateTo(1f, tween(LockHeroMath.BREATH_MS, easing = FastOutSlowInEasing))
            }
            else -> badge.animateTo(1f, Motion.respecting(reduce, Motion.snappy()))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height((LockHeroMath.STAGE_DP * scale).dp)
            .graphicsLayer {
                val s = if (reduce) 1f else 0.9f + 0.1f * arrival
                scaleX = s
                scaleY = s
                alpha = arrival.coerceIn(0f, 1f)
            }
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        // The stage keeps its 300 dp layout; [scale] shrinks it (and every offset) on short screens.
        Box(
            Modifier
                .requiredSize(LockHeroMath.STAGE_DP.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            Ring(300f, colors.accent.copy(alpha = 0.06f), ringScale, releasedAlpha)
            Ring(240f, colors.accent.copy(alpha = 0.12f), ringScale, releasedAlpha)
            Box(
                Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = discScale
                        scaleY = discScale
                        alpha = releasedAlpha
                    }
                    .clip(CircleShape)
                    .background(colors.accentSoft),
            )
            SealedChip(
                verified,
                Modifier.graphicsLayer {
                    translationX = (-98).dp.toPx()
                    translationY = 68.dp.toPx()
                    rotationZ = 3f
                    alpha = releasedAlpha
                    scaleX = chipScale
                    scaleY = chipScale
                },
            )
            LockedChip(
                verified,
                Modifier.graphicsLayer {
                    translationX = 97.dp.toPx()
                    translationY = (-83).dp.toPx()
                    rotationZ = -5f
                    alpha = releasedAlpha
                    scaleX = chipScale
                    scaleY = chipScale
                },
            )
            BrandLogoMark(
                100.dp,
                Modifier
                    .graphicsLayer {
                        translationY = markOffset.dp.toPx()
                        scaleX = markScale
                        scaleY = markScale
                        alpha = markAlpha
                    }
                    .dropShadow(
                        brandTileShape(100.dp),
                        Shadow(radius = markShadowRadius.dp, color = colors.accent, offset = DpOffset(0.dp, markShadowY.dp), alpha = markShadowAlpha),
                    )
                    // Zoom source for the phrase push, as Welcome's mark is for Sign Up / Log In (`:202-203`).
                    .onboardingHeroSource(100.dp),
            )
            LockBadge(
                verified,
                badgeFill,
                Modifier.graphicsLayer {
                    translationX = 47.dp.toPx()
                    translationY = 48.dp.toPx()
                    scaleX = badge.value
                    scaleY = badge.value
                    alpha = releasedAlpha
                },
            )
        }
    }
}

@Composable
private fun Ring(diameter: Float, color: Color, scale: Float, alpha: Float) {
    Canvas(
        Modifier
            .size(diameter.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
    ) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
    }
}

/** The incoming bubble that "decrypts" on verify (`sealedChip`, `:281-297`). */
@Composable
private fun SealedChip(verified: Boolean, modifier: Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .dropShadow(CircleShape, Shadow(radius = 9.dp, color = Color.Black, offset = DpOffset(0.dp, 6.dp), alpha = 0.12f))
            .clip(CircleShape)
            .background(colors.bubbleIncoming)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(verified, animationSpec = Motion.fade(), label = "sealedGlyph") { open ->
            ShroudIcon(if (open) ShroudIcons.CircleCheck else ShroudIcons.ShieldCheck, colors.online, size = 12.dp)
        }
        Crossfade(verified, animationSpec = Motion.fade(), label = "sealedText") { open ->
            ShroudText(if (open) "Unlocked" else "Sealed", inter(12f, FontWeight.Medium), if (open) colors.textPrimary else colors.textSecondary, maxLines = 1)
        }
    }
}

/** The outgoing bubble: dots become the message (`lockedChip`, `:261-279`). */
@Composable
private fun LockedChip(verified: Boolean, modifier: Modifier) {
    val colors = ShroudTheme.colors
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    Row(
        modifier
            .dropShadow(shape, Shadow(radius = 9.dp, color = colors.accent, offset = DpOffset(0.dp, 6.dp), alpha = 0.3f))
            .clip(shape)
            .background(colors.accent)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(verified, animationSpec = Motion.fade(), label = "lockedGlyph") { open ->
            ShroudIcon(if (open) ShroudIcons.LockOpen else ShroudIcons.Lock, Color.White.copy(alpha = 0.8f), size = 11.dp)
        }
        AnimatedContent(
            verified,
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            label = "lockedText",
        ) { open ->
            ShroudText(
                if (open) "Hey! 👋" else "•••• ••••",
                inter(14f, FontWeight.SemiBold, letterSpacing = if (open) 0.sp else 2.sp),
                Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The lock that springs open and turns green (`lockBadge`, `:247-259`). */
@Composable
private fun LockBadge(verified: Boolean, fill: Color, modifier: Modifier) {
    val colors = ShroudTheme.colors
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .dropShadow(CircleShape, Shadow(radius = 8.dp, color = Color.Black, offset = DpOffset(0.dp, 6.dp), alpha = 0.16f))
                .clip(CircleShape)
                .background(fill),
        )
        Crossfade(verified, animationSpec = Motion.fade(), label = "badgeGlyph") { open ->
            ShroudIcon(if (open) ShroudIcons.LockOpen else ShroudIcons.Lock, if (open) Color.White else colors.accent, size = 16.dp)
        }
    }
}

package de.corespace.shroud.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Motion tokens from `ios/shroud/ShroudUI/Theme/Motion.swift`. Pick by intent, never invent
 * spring numbers in a screen (`Motion.swift:4-17`).
 *
 * SwiftUI springs are given as (response, dampingFraction); Compose takes (dampingRatio,
 * stiffness). For unit mass, stiffness = (2π / response)² and dampingRatio = dampingFraction
 * ([swiftSpring]). SwiftUI's `.easeOut` / `.easeInOut` are the cubic Béziers (0, 0, 0.58, 1) and
 * (0.42, 0, 0.58, 1) ([IosEaseOut], [IosEaseInOut]).
 */
object Motion {
    /** SwiftUI `UnitCurve.easeOut`: cubic Bézier (0, 0, 0.58, 1). */
    val IosEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** SwiftUI `UnitCurve.easeInOut`: cubic Bézier (0.42, 0, 0.58, 1). */
    val IosEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Press-down: near-instant, `.easeOut(duration: 0.09)` (`Motion.swift:20`). */
    fun <T> press(): FiniteAnimationSpec<T> = easeOut(90)

    /** Press-release: springs back with a touch of life, spring(0.34, 0.62) (`Motion.swift:22`). */
    fun <T> release(): FiniteAnimationSpec<T> = swiftSpring(0.34f, 0.62f)

    /** Quick, no overshoot: icon swaps, toggles, badges, receipts — spring(0.26, 0.86) (`Motion.swift:24`). */
    fun <T> snappy(): FiniteAnimationSpec<T> = swiftSpring(0.26f, 0.86f)

    /** The default: list/layout changes, tab content — spring(0.38, 0.9) (`Motion.swift:25`). */
    fun <T> standard(): FiniteAnimationSpec<T> = swiftSpring(0.38f, 0.9f)

    /** Slow settle: large surfaces, sheets, hero chrome — spring(0.5, 0.92) (`Motion.swift:26`). */
    fun <T> gentle(): FiniteAnimationSpec<T> = swiftSpring(0.5f, 0.92f)

    /** Visible overshoot for "it happened" moments — spring(0.32, 0.66) (`Motion.swift:27`). */
    fun <T> bouncy(): FiniteAnimationSpec<T> = swiftSpring(0.32f, 0.66f)

    /** A reaction flying from the bar (or a double tap) into its chip — spring(0.46, 0.82) (`Motion.swift:28-29`). */
    fun <T> reactionFlight(): FiniteAnimationSpec<T> = swiftSpring(0.46f, 0.82f)

    /** Opacity cross-dissolves, `.easeOut(duration: 0.18)` (`Motion.swift:31`). */
    fun <T> fade(): FiniteAnimationSpec<T> = easeOut(180)

    /** Scrims, `.easeOut(duration: 0.22)` (`Motion.swift:32`). */
    fun <T> scrim(): FiniteAnimationSpec<T> = easeOut(220)

    /**
     * A message lifting into its long-press menu: Telegram's spring, mass 5, stiffness 900,
     * damping 104 (`Motion.swift:34-36`) — normalised to unit mass: stiffness 900 / 5 = 180,
     * dampingRatio 104 / (2·√(900·5)) = 0.7752.
     */
    fun <T> menuLift(): FiniteAnimationSpec<T> = spring(dampingRatio = MENU_LIFT_DAMPING_RATIO, stiffness = MENU_LIFT_STIFFNESS)

    /** Putting it back: Telegram's 0.2 s ease-in-out (`Motion.swift:37-39`). */
    fun <T> menuDrop(): FiniteAnimationSpec<T> = easeInOut(MENU_DROP_MS.toInt())

    /** The chat push / pop: `ChatOpenAnimation.push` = `.easeInOut(duration: 0.35)` (`ChatOpenTransition.swift:4-5`). */
    fun <T> chatOpenPush(): FiniteAnimationSpec<T> = tween(350, easing = IosEaseInOut)

    /** The thread following new content to the bottom: `followAnimation` = `.easeOut(duration: 0.25)` (`ConversationView.swift:469`). */
    fun <T> follow(): FiniteAnimationSpec<T> = easeOut(250)

    /** Reduce Motion substitute: movement collapses into a short fade, `.easeOut(duration: 0.15)` (`Motion.swift:41-43`). */
    fun <T> reduced(): FiniteAnimationSpec<T> = easeOut(REDUCED_MS.toInt())

    /** Phrase words pop in with this spring (`EncryptionPhraseReveal.wordRevealSpring`). */
    fun <T> wordReveal(): FiniteAnimationSpec<T> = swiftSpring(0.28f, 0.72f)

    /** SwiftUI `.easeOut(duration:)` literals (conversation-compose-media §2.1). */
    fun <T> easeOut(durationMillis: Int): FiniteAnimationSpec<T> = tween(durationMillis, easing = IosEaseOut)

    /** SwiftUI `.easeInOut(duration:)` literals (conversation-compose-media §2.1). */
    fun <T> easeInOut(durationMillis: Int): FiniteAnimationSpec<T> = tween(durationMillis, easing = IosEaseInOut)

    /** 45 ms between pairs of phrase words (`EncryptionPhraseReveal`). */
    const val PHRASE_PAIR_DELAY_MS = 45L
    const val BADGE_PULSE_MS = 320L

    /** `Motion.menuDropDuration` (`Motion.swift:38`). */
    const val MENU_DROP_MS = 200L

    /** `Motion.reducedDuration` (`Motion.swift:42`). */
    const val REDUCED_MS = 150L

    /** Unit-mass stiffness of [menuLift]: 900 / 5. */
    const val MENU_LIFT_STIFFNESS = 180f

    /** Damping ratio of [menuLift]: 104 / (2·√(900·5)). */
    val MENU_LIFT_DAMPING_RATIO: Float = (104.0 / (2.0 * sqrt(900.0 * 5.0))).toFloat()

    /** Picks [spec] normally, or a flat fade when the user asked for less motion (`Motion.swift:45-48`). */
    fun <T> respecting(reduceMotion: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spec

    /** A SwiftUI spring(response, dampingFraction) as a Compose spring (unit mass). */
    fun <T> swiftSpring(response: Float, dampingFraction: Float): FiniteAnimationSpec<T> =
        spring(dampingRatio = dampingFraction, stiffness = swiftStiffness(response))

    /** (2π / response)²: the unit-mass stiffness of a SwiftUI spring with this response. */
    fun swiftStiffness(response: Float): Float = (2 * PI / response).pow(2).toFloat()

    // ---- Shared transitions (`Motion.swift:50-67`) ----

    /**
     * A control that swaps in place (send ⇄ mic, clear button, receipts): scale 0.45 + opacity
     * (`Motion.swift:61-62`), on [snappy].
     */
    val iconSwap: MotionTransition
        get() = MotionTransition(
            enter = scaleIn(snappy(), initialScale = 0.45f) + fadeIn(snappy()),
            exit = scaleOut(snappy(), targetScale = 0.45f) + fadeOut(snappy()),
        )

    /**
     * A transient surface rising from the bottom edge (toasts): move from the bottom + scale 0.9
     * anchored at the bottom + opacity (`Motion.swift:64-67`), on [bouncy] ("a toast should feel
     * like it landed", `ToastBanner.swift:103-104`).
     */
    val riseFromBottom: MotionTransition
        get() {
            val bottom = TransformOrigin(0.5f, 1f)
            return MotionTransition(
                enter = slideInVertically(bouncy<IntOffset>()) { it } + scaleIn(bouncy(), 0.9f, bottom) + fadeIn(bouncy()),
                exit = slideOutVertically(bouncy<IntOffset>()) { it } + scaleOut(bouncy(), 0.9f, bottom) + fadeOut(bouncy()),
            )
        }

    /**
     * A new chat bubble grows out of the corner it was "spoken" from: insertion scale 0.82 from the
     * bottom-trailing (mine) or bottom-leading corner + opacity; removal scale 0.9 + opacity
     * (`Motion.swift:52-59`; not used by an iOS view today, kept for the thread). The insertion rides
     * [bouncy] ("it happened"), the removal [standard]. The anchor is the physical corner: right
     * for mine, left for theirs.
     */
    fun bubbleIn(isMine: Boolean): MotionTransition =
        MotionTransition(
            enter = scaleIn(bouncy(), 0.82f, TransformOrigin(if (isMine) 1f else 0f, 1f)) + fadeIn(bouncy()),
            exit = scaleOut(standard(), 0.9f) + fadeOut(standard()),
        )

    /** The opacity-only stand-in every transition collapses to under Reduce Motion. */
    val reducedTransition: MotionTransition
        get() = MotionTransition(enter = fadeIn(reduced()), exit = fadeOut(reduced()))
}

/**
 * A SwiftUI `AnyTransition` split into its Compose halves: [enter] for insertion, [exit] for
 * removal, [content] for `AnimatedContent`. Use [respecting] for the Reduce Motion fade.
 */
@Immutable
class MotionTransition(val enter: EnterTransition, val exit: ExitTransition) {
    val content: ContentTransform get() = enter togetherWith exit

    /** This transition, or the Reduce Motion fade ([Motion.reducedTransition]). */
    fun respecting(reduceMotion: Boolean): MotionTransition = if (reduceMotion) Motion.reducedTransition else this
}

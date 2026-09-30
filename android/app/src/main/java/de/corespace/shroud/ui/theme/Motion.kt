package de.corespace.shroud.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI
import kotlin.math.pow

/**
 * Motion tokens from `ios/shroud/ShroudUI/Theme/Motion.swift`. Pick by intent, never invent
 * spring numbers in a screen.
 *
 * SwiftUI springs are given as (response, dampingFraction); Compose takes
 * (dampingRatio, stiffness). For unit mass, stiffness = (2π / response)².
 */
object Motion {
    fun <T> press(): FiniteAnimationSpec<T> = tween(90, easing = LinearOutSlowInEasing)
    fun <T> release(): FiniteAnimationSpec<T> = swiftSpring(0.34f, 0.62f)
    fun <T> snappy(): FiniteAnimationSpec<T> = swiftSpring(0.26f, 0.86f)
    fun <T> standard(): FiniteAnimationSpec<T> = swiftSpring(0.38f, 0.9f)
    fun <T> gentle(): FiniteAnimationSpec<T> = swiftSpring(0.5f, 0.92f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = swiftSpring(0.32f, 0.66f)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(180, easing = LinearOutSlowInEasing)
    fun <T> scrim(): FiniteAnimationSpec<T> = tween(220, easing = LinearOutSlowInEasing)

    /** Reduce-motion substitute: movement collapses into a short fade. */
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(150, easing = FastOutSlowInEasing)

    /** Phrase words pop in with this spring (`EncryptionPhraseReveal.wordRevealSpring`). */
    fun <T> wordReveal(): FiniteAnimationSpec<T> = swiftSpring(0.28f, 0.72f)

    /** 45 ms between pairs of phrase words (`EncryptionPhraseReveal`). */
    const val PHRASE_PAIR_DELAY_MS = 45L
    const val BADGE_PULSE_MS = 320L

    fun <T> respecting(reduceMotion: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spec

    fun <T> swiftSpring(response: Float, dampingFraction: Float): FiniteAnimationSpec<T> =
        spring(dampingRatio = dampingFraction, stiffness = (2 * PI / response).pow(2).toFloat())
}


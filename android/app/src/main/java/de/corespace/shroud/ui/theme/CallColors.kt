package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The call stage's fixed, always-dark colours: iOS's navy stage (design family A, P13a decided;
 * design-inventory §6.3, §9).
 */
object CallColors {
    /** Stage gradient top (`InCallOverlay.swift:146-150`). */
    val stageTop = Color(0xFF141A29)

    /** Stage gradient bottom (`InCallOverlay.swift:146-150`). */
    val stageBottom = Color(0xFF0D0F1A)

    /** The "Not verified" badge tint (`InCallOverlay.swift:563`). */
    val unverified = Color(0xFFFFD9A8)

    /** Top → bottom stage background. */
    val stageGradient: Brush = Brush.verticalGradient(listOf(stageTop, stageBottom))
}

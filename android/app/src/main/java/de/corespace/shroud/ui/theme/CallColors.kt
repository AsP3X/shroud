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

    /**
     * The call screen's clear glass (controls, Share, the speaking meter, the safety badge): iOS
     * draws `.glassEffect(.regular)` over the dark stage or a picture; Android a light veil
     * (design call controls `#FFFFFF2E`), no blur — a video surface cannot be blurred behind.
     */
    val controlGlass = Color(0x2EFFFFFF)

    /** The glass's 1 dp rim (design badge stroke `#FFFFFF2E`, a step softer on the circles). */
    val controlRim = Color(0x24FFFFFF)

    /** The safety-number popover (design MTNhH `#2C2C2EEB`, r26). */
    val popover = Color(0xEB2C2C2E)

    /** The popover's secondary text (design MTNhH `#EBEBF599`, iOS dark `secondaryLabel`). */
    val popoverSecondary = Color(0x99EBEBF5)

    /** Behind the safety number's groups (iOS `.primary.opacity(0.06)` in the dark popover). */
    val numberWell = Color(0x0FFFFFFF)

    /** The border of the picture tiles (`InCallOverlay.swift:309`, white 35 %). */
    val tileBorder = Color(0x59FFFFFF)
}

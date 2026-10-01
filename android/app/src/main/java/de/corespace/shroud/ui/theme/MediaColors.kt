package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fixed colours of the media surfaces (photo / video compose, editors, viewers, permission toasts):
 * dark in both appearances, so not themed (conversation-compose-media §2.3).
 */
object MediaColors {
    /** Editor and compose chrome, `Color(44/255, 44/255, 46/255)` (`MediaComposeOverlay.swift:124`). */
    val chrome = Color(0xFF2C2C2E)

    /** The compose screen's send / selection blue, `telegramBlue` (51, 144, 236) (`MediaComposeOverlay.swift:125`). */
    val blue = Color(0xFF3390EC)

    /** Warning text on the dark video compose screen, `Color(1, 0.62, 0.55)` (`VideoComposeOverlay.swift:421, 442`). */
    val warningText = Color(0xFFFF9E8C)

    /** Scrim behind the attach sheet (design Attach Open `Scrim` #0B0B1259), lighter than `ShroudColors.scrim`. */
    val sheetScrim = Color(0x590B0B12)

    /** The permission toast's dark pill (design u3il8T `#1F1F24F0`). */
    val toastDark = Color(0xF01F1F24)
}

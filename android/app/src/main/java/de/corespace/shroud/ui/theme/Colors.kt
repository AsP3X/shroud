package de.corespace.shroud.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens. Names and values match the variables in `design/Android-App.pen` and the iOS
 * colour assets (`ios/shroud/Assets.xcassets`), light and dark.
 */
@Immutable
data class ShroudColors(
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val background: Color,
    val backgroundGrouped: Color,
    val backgroundChat: Color,
    val bubbleIncoming: Color,
    val bubbleOutgoing: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val separator: Color,
    val online: Color,
    val danger: Color,
    val dangerText: Color,
    val warningBackground: Color,
    val warningText: Color,
    val warningIcon: Color,
    val successBackground: Color,
    val successText: Color,
    val successFill: Color,
    val strengthPanel: Color,
    val strengthTrack: Color,
    val glass: Color,
    val glassSoft: Color,
    val glassStroke: Color,
    val toggleOff: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightColors = ShroudColors(
    accent = Color(0xFF5E5CE6),
    accentSoft = Color(0xFFECECFC),
    accentText = Color(0xFF5E5CE6),
    background = Color(0xFFFFFFFF),
    backgroundGrouped = Color(0xFFF2F2F7),
    backgroundChat = Color(0xFFF5F4FA),
    bubbleIncoming = Color(0xFFFFFFFF),
    bubbleOutgoing = Color(0xFF5E5CE6),
    textPrimary = Color(0xFF0B0B12),
    textSecondary = Color(0xFF8E8E93),
    separator = Color(0xFFE5E5EA),
    online = Color(0xFF34C759),
    danger = Color(0xFFFF3B30),
    dangerText = Color(0xFFC4261D),
    warningBackground = Color(0xFFFDF1DC),
    warningText = Color(0xFF8A5E0C),
    warningIcon = Color(0xFFB97D10),
    successBackground = Color(0xFFE6F7EC),
    successText = Color(0xFF1D7A3E),
    successFill = Color(0xFF1D7A3E),
    strengthPanel = Color(0xFFFAFAFC),
    strengthTrack = Color(0xFFECECEF),
    glass = Color(0xB8FFFFFF),
    glassSoft = Color(0x99FFFFFF),
    glassStroke = Color(0x80FFFFFF),
    toggleOff = Color(0xFFE9E9EA),
    scrim = Color(0x990B0B12),
    isDark = false,
)

val DarkColors = ShroudColors(
    accent = Color(0xFF6B6BF2),
    accentSoft = Color(0xFF2A2A46),
    accentText = Color(0xFFA7A7FA),
    background = Color(0xFF000000),
    backgroundGrouped = Color(0xFF1C1C1E),
    backgroundChat = Color(0xFF0B0B12),
    bubbleIncoming = Color(0xFF2C2C2E),
    bubbleOutgoing = Color(0xFF5E5CE6),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E93),
    separator = Color(0xFF38383A),
    online = Color(0xFF34C759),
    danger = Color(0xFFFF453A),
    dangerText = Color(0xFFFF6961),
    warningBackground = Color(0xFF3A2A12),
    warningText = Color(0xFFF5D9A6),
    warningIcon = Color(0xFFF5B032),
    successBackground = Color(0xFF12301D),
    successText = Color(0xFF7ED69A),
    successFill = Color(0xFF1D7A3E),
    strengthPanel = Color(0xFF1C1C1E),
    strengthTrack = Color(0xFF38383A),
    glass = Color(0xB82C2C2E),
    glassSoft = Color(0x992C2C2E),
    glassStroke = Color(0x1FFFFFFF),
    toggleOff = Color(0xFF39393D),
    scrim = Color(0x990B0B12),
    isDark = true,
)

/** The brand tile behind the veil: `design/icon/shroud-icon.svg`. */
object BrandColors {
    val backdropTop = Color(0xFF7D7BFA)
    val backdropMid = Color(0xFF5E5CE6)
    val backdropBottom = Color(0xFF3432B8)
    val fold = Color(0xFF4B49D8)
    val clothBottom = Color(0xFFE4E3FF)
    val veilShadow = Color(0xFF0A0930)
    val strengthStrongTop = Color(0xFF5AD97C)
    val strengthStrongBottom = Color(0xFF2FA85B)
}

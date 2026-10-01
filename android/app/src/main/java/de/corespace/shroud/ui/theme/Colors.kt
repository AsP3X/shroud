package de.corespace.shroud.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens. Names and values match the variables in `design/Android-App.pen` and the iOS
 * colour assets (`ios/shroud/Assets.xcassets`, `ShroudUI/Theme/Theme.swift`), light and dark.
 * Feature code never uses raw hex (`Theme.swift:3`); fixed, always-dark surfaces use
 * [MediaColors] and [CallColors].
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
    /** Glass of the bar controls and conversation chrome (design `$glass`), with blur. */
    val glass: Color,
    /** Glass of floating controls: tab bar, Glass Circle, nav capsules (design `$glass-soft`), with blur. */
    val glassSoft: Color,
    /** Rim of [glassSoft] surfaces (design `#FFFFFF80`; dark `#FFFFFF1F`). */
    val glassStroke: Color,
    val toggleOff: Color,
    val scrim: Color,
    /** Unread count of a muted chat; solid so the white count keeps 4.5:1 (`Theme.swift:33-34`, asset `MutedBadge`). */
    val mutedBadge: Color,
    /** Row disclosure chevrons, iOS `systemGray3` (`Theme.swift:23-25`). */
    val chevron: Color,
    /** Pressed list row, iOS `systemGray5` (`NewChatSheet.swift:77-81`). */
    val rowPressed: Color,
    /** The tab bar's selected-tab lens (design variable `tab-selected`). */
    val tabSelected: Color,
    /** The lens while lifted under the finger (`FloatingTabBar.swift:234-238`, 10 % / 20 %). */
    val tabSelectedLifted: Color,
    /** Rim of [glass] bar controls (design `New Chat` stroke `#FFFFFFCC`; dark = [glassStroke]). */
    val glassBarStroke: Color,
    /** Every glass surface without blur: API 30, or a backdrop that cannot blur (design `Glass — Without Blur`, Gwp1b). */
    val glassOpaque: Color,
    /** Card glass: context menus, the in-app banner (design `#FFFFFFD1`; dark from the message menu `#1F1F24F0`). */
    val cardGlass: Color,
    /** Rim of [cardGlass]. */
    val cardStroke: Color,
    /** [cardGlass] without blur. */
    val cardOpaque: Color,
    /** Scrim behind a context menu over a blurred list (design `Blur Scrim`). */
    val menuScrim: Color,
    /** [menuScrim] when the list behind cannot blur (design Gwp1b: plain `#0B0B1259`). */
    val menuScrimOpaque: Color,
    /** Scrim behind floating sheets and action sheets (design `New Chat` `Scrim`). */
    val sheetScrim: Color,
    /** Dim over the screen underneath during predictive back (design Predictive Back `Dim`). */
    val dimPredictiveBack: Color,
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
    mutedBadge = Color(0xFF6D6D72),
    chevron = Color(0xFFC7C7CC),
    rowPressed = Color(0xFFE5E5EA),
    tabSelected = Color(0x120B0B12),
    tabSelectedLifted = Color(0x1A0B0B12),
    glassBarStroke = Color(0xCCFFFFFF),
    glassOpaque = Color(0xF5FFFFFF),
    cardGlass = Color(0xD1FFFFFF),
    cardStroke = Color(0x99FFFFFF),
    cardOpaque = Color(0xF5FFFFFF),
    menuScrim = Color(0x59F2F2F7),
    menuScrimOpaque = Color(0x590B0B12),
    sheetScrim = Color(0x470B0B12),
    dimPredictiveBack = Color(0x330B0B12),
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
    mutedBadge = Color(0xFF4E4E51),
    chevron = Color(0xFF48484A),
    rowPressed = Color(0xFF2C2C2E),
    tabSelected = Color(0x1FFFFFFF),
    tabSelectedLifted = Color(0x33FFFFFF),
    glassBarStroke = Color(0x1FFFFFFF),
    glassOpaque = Color(0xF52C2C2E),
    cardGlass = Color(0xF01F1F24),
    cardStroke = Color(0x14FFFFFF),
    cardOpaque = Color(0xF51F1F24),
    menuScrim = Color(0x470F0F14),
    menuScrimOpaque = Color(0x590B0B12),
    sheetScrim = Color(0x470B0B12),
    dimPredictiveBack = Color(0x330B0B12),
    isDark = true,
)

/**
 * Brand colours. The logo tile behind the veil comes from `design/icon/shroud-icon.svg`
 * ([backdropTop]…[veilShadow], drawn by `BrandLogoMark`); [brandGradient] is iOS
 * `Theme.brandGradient` (`Theme.swift:49-56`): the default avatar, the Notes avatar and the
 * Settings hero avatar — never the logo tile.
 */
object BrandColors {
    val backdropTop = Color(0xFF7D7BFA)
    val backdropMid = Color(0xFF5E5CE6)
    val backdropBottom = Color(0xFF3432B8)
    val fold = Color(0xFF4B49D8)
    val clothBottom = Color(0xFFE4E3FF)
    val veilShadow = Color(0xFF0A0930)
    val strengthStrongTop = Color(0xFF5AD97C)
    val strengthStrongBottom = Color(0xFF2FA85B)

    /** `Color(red: 124/255, green: 122/255, blue: 255/255)` (`Theme.swift:51`). */
    val gradientTop = Color(0xFF7C7AFF)

    /** `Color(red: 94/255, green: 92/255, blue: 230/255)` (`Theme.swift:52`). */
    val gradientBottom = Color(0xFF5E5CE6)

    /** Top → bottom, both appearances (`Theme.swift:49-56`). */
    val brandGradient: Brush = Brush.verticalGradient(listOf(gradientTop, gradientBottom))
}

/**
 * Icon-tile and attach-option tints, the same in both appearances (design-inventory §2; iOS
 * `SettingsView.swift:382-488`, `ChatAttachSheet.swift:375-381`, `ColorThemePreference.swift:31-33`).
 * Grey and accent tiles use `textSecondary` / `accent`.
 */
object TilePalette {
    /** Saved Messages, Transcription (`SettingsView.swift:415`, `:488`), File (`ChatAttachSheet.swift:376`). */
    val blue = Color(0xFF2E8FE0)

    /** Recent Calls, Data and Storage (`SettingsView.swift:424`, `:467`), Photos (`ChatAttachSheet.swift:375`). */
    val green = Color(0xFF2FA85B)

    /** Devices (`SettingsView.swift:431`), Location (`ChatAttachSheet.swift:377`). */
    val orange = Color(0xFFF76B1C)

    /** Chat Folders (`SettingsView.swift:440`). */
    val cyan = Color(0xFF4AC7FA)

    /** Notifications and Sounds (`SettingsView.swift:450`), Contact (`ChatAttachSheet.swift:378`). */
    val pink = Color(0xFFE64A72)

    /** Language (`SettingsView.swift:482`), Music (`ChatAttachSheet.swift:379`). */
    val purple = Color(0xFF9B4AE6)

    /** Destructive tiles (Lock chats now). */
    val red = Color(0xFFFF3B30)

    /** The Signed-in card (`SettingsView.swift:382`). */
    val coral = Color(0xFFFF6B6B)

    /** Light theme row (`ColorThemePreference.swift:31`). */
    val amber = Color(0xFFFF9F0A)

    /** Dark theme row (`ColorThemePreference.swift:33`). */
    val indigo = Color(0xFF3432B8)

    /** Gift (`ChatAttachSheet.swift:380`). */
    val gold = Color(0xFFE69A1C)

    /** Stickers (`ChatAttachSheet.swift:381`). */
    val teal = Color(0xFF0FA3A3)

    val all: List<Color> get() = listOf(blue, green, orange, cyan, pink, purple, red, coral, amber, indigo, gold, teal)
}

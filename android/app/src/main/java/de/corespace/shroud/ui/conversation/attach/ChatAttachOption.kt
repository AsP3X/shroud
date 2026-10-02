package de.corespace.shroud.ui.conversation.attach

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import de.corespace.shroud.ui.theme.ShroudColors
import de.corespace.shroud.ui.theme.ShroudIcons

/**
 * The eight buttons of the attach sheet (iOS `ChatAttachOption`, `ChatAttachSheet.swift:328-384`;
 * design w4lZ1; conversation-compose-media §7.3). Camera and Photos work; the rest say "coming soon".
 * Colours are iOS's floats; Camera follows the app's accent.
 */
enum class ChatAttachOption(val title: String) {
    Camera("Camera"),
    Photos("Photos"),
    File("File"),
    Location("Location"),
    Contact("Contact"),
    Music("Music"),
    Gift("Gift"),
    Stickers("Stickers"),
    ;

    /** Phosphor fills of the design (conversation-compose-media §2.5). */
    val icon: ImageVector
        get() = when (this) {
            Camera -> ShroudIcons.CameraFill
            Photos -> ShroudIcons.ImageFill
            File -> ShroudIcons.FileFill
            Location -> ShroudIcons.MapPinFill
            Contact -> ShroudIcons.UserFill
            Music -> ShroudIcons.MusicNoteFill
            Gift -> ShroudIcons.GiftFill
            Stickers -> ShroudIcons.SmileyFill
        }

    /** The glyph's colour (`ChatAttachSheet.swift:372-383`). */
    fun iconColor(colors: ShroudColors): Color = when (this) {
        Camera -> colors.accent
        Photos -> Color(red = 0.184f, green = 0.659f, blue = 0.357f)
        File -> Color(red = 0.180f, green = 0.561f, blue = 0.878f)
        Location -> Color(red = 0.969f, green = 0.420f, blue = 0.110f)
        Contact -> Color(red = 0.902f, green = 0.290f, blue = 0.447f)
        Music -> Color(red = 0.608f, green = 0.290f, blue = 0.902f)
        Gift -> Color(red = 0.902f, green = 0.604f, blue = 0.110f)
        Stickers -> Color(red = 0.059f, green = 0.639f, blue = 0.639f)
    }

    /**
     * The 52 dp disc behind the glyph: the light pastel, or in dark mode a 20 % wash of the glyph's
     * own colour — the pastels would be bright discs on the black sheet (`ChatAttachSheet.swift:200-204`).
     */
    fun circleFill(colors: ShroudColors): Color = if (colors.isDark) {
        iconColor(colors).copy(alpha = 0.2f)
    } else {
        when (this) {
            Camera -> colors.accentSoft
            Photos -> Color(red = 0.902f, green = 0.969f, blue = 0.925f)
            File -> Color(red = 0.894f, green = 0.945f, blue = 0.988f)
            Location -> Color(red = 0.996f, green = 0.937f, blue = 0.890f)
            Contact -> Color(red = 0.988f, green = 0.906f, blue = 0.929f)
            Music -> Color(red = 0.953f, green = 0.910f, blue = 0.992f)
            Gift -> Color(red = 0.992f, green = 0.945f, blue = 0.863f)
            Stickers -> Color(red = 0.886f, green = 0.965f, blue = 0.965f)
        }
    }

    companion object {
        /** The sheet's two rows of four (`ChatAttachSheet.swift:33-34`). */
        val firstRow: List<ChatAttachOption> = listOf(Camera, Photos, File, Location)
        val secondRow: List<ChatAttachOption> = listOf(Contact, Music, Gift, Stickers)
    }
}

package de.corespace.shroud.ui.settings.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * A device's square tile (iOS `DeviceIconTile`, `DevicesView.swift:859-875`; settings-lock §4.5):
 * [size] square, radius `size × 0.27`, the kind's tint (`textSecondary` for an unknown device), the
 * kind's glyph `size × 0.47` in white. Decorative: the row says which device it is.
 *
 * An Android device (sealed kind 4, P4) is the green `#2FA85B` tile with the phone glyph, like an
 * iPhone's shape in the Android colour (`DevicesView.swift:840, 851`).
 */
@Composable
internal fun DeviceTile(kind: DeviceKind, size: Dp, modifier: Modifier = Modifier) {
    val tint = kind.tintArgb?.let(::Color) ?: ShroudTheme.colors.textSecondary
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(size)
            .clip(RoundedCornerShape(size * TILE_RADIUS))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(DeviceGlyphs.of(kind.glyph), Color.White, size = size * TILE_GLYPH)
    }
}

/** `DevicesView.swift:866-870`: corner and glyph as shares of the tile. */
private const val TILE_RADIUS = 0.27f
private const val TILE_GLYPH = 0.47f

/**
 * The tile glyphs of settings-lock §4.5, the design's equivalents of iOS's SF Symbols
 * (`DevicesView.swift:836-846`): Phosphor `device-mobile-fill` (iPhone, Android, a phone by name),
 * `device-tablet-fill` (iPad), `laptop-fill` (Mac), `desktop-fill` (computer and unknown) and
 * Lucide `globe` (web browser).
 */
internal object DeviceGlyphs {
    fun of(glyph: DeviceKind.Glyph): ImageVector = when (glyph) {
        DeviceKind.Glyph.Phone -> ShroudIcons.DeviceMobileFill
        DeviceKind.Glyph.Tablet -> ShroudIcons.DeviceTabletFill
        DeviceKind.Glyph.Globe -> ShroudIcons.Globe
        DeviceKind.Glyph.Laptop -> ShroudIcons.LaptopFill
        DeviceKind.Glyph.Desktop -> ShroudIcons.DesktopFill
    }
}

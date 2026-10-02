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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * A device's square tile (iOS `DeviceIconTile`, `DevicesView.swift:859-875`; settings-lock §4.5):
 * [size] square, radius `size × 0.27`, the kind's tint (`textSecondary` for an unknown device), the
 * kind's glyph `size × 0.47` in white. Decorative: the row says which device it is.
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
 * The tile glyphs of settings-lock §4.5: Phosphor `device-mobile-fill` / `device-tablet-fill` and
 * Lucide `globe` from [ShroudIcons]. The kit has no Phosphor `laptop-fill` / `desktop-fill` yet, so
 * [Laptop] and [Desktop] are plain filled stand-ins on Phosphor's 256-unit grid until W3-INT adds
 * the two to `gen_shroud_icons.py` (contract change request of W3-SETTINGS-B); then swap them for
 * `ShroudIcons.LaptopFill` / `ShroudIcons.DesktopFill`.
 */
internal object DeviceGlyphs {
    fun of(glyph: DeviceKind.Glyph): ImageVector = when (glyph) {
        DeviceKind.Glyph.Phone -> ShroudIcons.DeviceMobileFill
        DeviceKind.Glyph.Tablet -> ShroudIcons.DeviceTabletFill
        DeviceKind.Glyph.Globe -> ShroudIcons.Globe
        DeviceKind.Glyph.Laptop -> Laptop
        DeviceKind.Glyph.Desktop -> Desktop
    }

    /** A laptop: a rounded screen over a wider base. */
    val Laptop: ImageVector by lazy {
        filled(
            "laptop",
            "M56,48H200a16,16 0 0 1 16,16V168H40V64A16,16 0 0 1 56,48Z",
            "M16,184H240v8a16,16 0 0 1 -16,16H32a16,16 0 0 1 -16,-16Z",
        )
    }

    /** A desktop computer: a monitor on a stand. */
    val Desktop: ImageVector by lazy {
        filled(
            "desktop",
            "M40,40H216a16,16 0 0 1 16,16V160a16,16 0 0 1 -16,16H40a16,16 0 0 1 -16,-16V56A16,16 0 0 1 40,40Z",
            "M112,176h32v24H112Z",
            "M80,200h96a8,8 0 0 1 0,16H80a8,8 0 0 1 0,-16Z",
        )
    }

    private fun filled(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 256f,
            viewportHeight = 256f,
        )
        for (path in paths) builder.addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        return builder.build()
    }
}

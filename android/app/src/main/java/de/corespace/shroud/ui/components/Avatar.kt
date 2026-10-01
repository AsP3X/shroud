package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import de.corespace.shroud.ui.theme.BrandColors
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlin.math.max

/**
 * Circular initials avatar (`AvatarView.swift:4-23`; design `Avatar` `gTywD`): a [size] circle in
 * a top-to-bottom gradient, white SemiBold initials at `fontSize ?: max(12, size × 0.36)`
 * (52 → 18.7; callers pass 15 for 40 dp, the design's value). Decorative: the row it sits in says
 * who it is.
 *
 * The initials keep their size at any font scale, as iOS's fixed `.system(size:)` does: they live
 * inside a fixed circle and would clip. [fontSize] is read as a design size (its `value`).
 */
@Composable
fun Avatar(
    initials: String,
    size: Dp = 52.dp,
    brush: Brush = BrandColors.brandGradient,
    fontSize: TextUnit? = null,
    modifier: Modifier = Modifier,
) {
    val design = if (fontSize != null && fontSize.isSpecified) fontSize.value else max(12f, size.value * 0.36f)
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(size)
            .clip(CircleShape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = initials,
            style = inter(design / fontScale, FontWeight.SemiBold).copy(color = Color.White),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A gradient circle with a white glyph instead of initials (`ChatRowView.swift:27-36`): the Notes
 * row's bookmark on the brand gradient — not `accentSoft`, so the white glyph keeps its contrast
 * (`ChatsView.swift:341-345`). Design `Chat Notes to me`: Phosphor `bookmark-simple-fill` 22 dp.
 * Decorative.
 */
@Composable
fun SymbolAvatar(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 22.dp,
    brush: Brush = BrandColors.brandGradient,
) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(size)
            .clip(CircleShape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, Color.White, size = iconSize)
    }
}

/** The usual list avatar: initials of [name] on the gradient of [seed] (`ChatRowView.swift:20-23, 38`). */
@Composable
fun NameAvatar(name: String, modifier: Modifier = Modifier, seed: String = name, size: Dp = 52.dp, fontSize: TextUnit? = null) {
    Avatar(AvatarPalette.initials(name), size, AvatarPalette.brush(seed), fontSize, modifier)
}

@Preview(name = "Avatars", widthDp = 412)
@Composable
private fun AvatarPreview() {
    ShroudTheme(dark = false) {
        Row(
            Modifier.background(ShroudTheme.colors.background).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar("JC")
            NameAvatar("Design Team")
            NameAvatar("jane_cooper", size = 40.dp, fontSize = 15.sp)
            Avatar("NV", size = 88.dp, fontSize = 32.sp)
            SymbolAvatar(ShroudIcons.BookmarkSimpleFill)
        }
    }
}

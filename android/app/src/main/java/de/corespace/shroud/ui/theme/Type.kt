package de.corespace.shroud.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import de.corespace.shroud.R

/** Inter, bundled (the design's `font` variable). Sizes are the design's, in sp. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * A text style in Inter. Line height defaults to the font's own; `lineSpacing` adds the iOS
 * `.lineSpacing(_:)` gap between lines.
 */
fun inter(
    size: Float,
    weight: FontWeight = FontWeight.Normal,
    lineSpacing: Float = 0f,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    monospaced: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = if (monospaced) FontFamily.Monospace else Inter,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    lineHeight = if (lineSpacing > 0f) (size * 1.21f + lineSpacing).sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

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
 * JetBrains Mono 2.304, bundled (P13d decided; design-inventory D3): the design's monospace for
 * phrase words and numbers, the server URL preview, ids, share links and codes, safety numbers.
 * iOS uses SF Mono (`.monospaced`). Bold requests resolve to SemiBold, the heaviest weight shipped.
 */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

/** OpenType feature that switches Inter's figures to tabular (equal-width) digits. */
const val TABULAR_DIGITS = "tnum"

/**
 * A text style in Inter. Line height defaults to the font's own; `lineSpacing` adds the iOS
 * `.lineSpacing(_:)` gap between lines.
 *
 * - [monospaced]: the whole text in [Mono] (iOS `design: .monospaced`): phrases, ids, codes.
 * - [tabularDigits]: Inter with `tnum` (iOS `.monospacedDigit()`): timers, durations and counters
 *   whose width must not jump while digits change (conversation-thread §23.4,
 *   conversation-compose-media "monospaced digits").
 */
fun inter(
    size: Float,
    weight: FontWeight = FontWeight.Normal,
    lineSpacing: Float = 0f,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    monospaced: Boolean = false,
    tabularDigits: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = if (monospaced) Mono else Inter,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing,
    fontFeatureSettings = if (tabularDigits) TABULAR_DIGITS else null,
    lineHeight = if (lineSpacing > 0f) (size * 1.21f + lineSpacing).sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** A text style in [Mono]; shorthand for `inter(size, weight, monospaced = true)`. */
fun mono(size: Float, weight: FontWeight = FontWeight.Normal, letterSpacing: TextUnit = TextUnit.Unspecified): TextStyle =
    inter(size, weight, letterSpacing = letterSpacing, monospaced = true)

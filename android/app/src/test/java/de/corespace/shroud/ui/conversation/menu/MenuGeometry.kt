package de.corespace.shroud.ui.conversation.menu

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals

/** `CGRect(x:y:width:height:)`. */
internal fun rect(x: Float, y: Float, width: Float, height: Float): Rect = Rect(x, y, x + width, y + height)

/** The iOS tests compare CGFloats exactly; float sums here may differ by an ulp. */
internal const val EPS = 0.001f

internal fun assertRect(expected: Rect, actual: Rect) {
    assertEquals("left of $actual", expected.left, actual.left, EPS)
    assertEquals("top of $actual", expected.top, actual.top, EPS)
    assertEquals("width of $actual", expected.width, actual.width, EPS)
    assertEquals("height of $actual", expected.height, actual.height, EPS)
}

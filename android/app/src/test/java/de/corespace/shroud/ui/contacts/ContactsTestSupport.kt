package de.corespace.shroud.ui.contacts

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.corespace.shroud.ui.components.ComposeHarness

/** Nodes whose (merged) text or field text is exactly [text]. */
internal fun ComposeHarness.exactly(text: String): List<SemanticsNode> = nodes().filter { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
        node.config.getOrNull(SemanticsProperties.EditableText)?.text == text
}

/** Whether a node reads exactly [text]. */
internal fun ComposeHarness.shows(text: String): Boolean = exactly(text).isNotEmpty()

/** Taps the one clickable node whose text is exactly [text]. */
internal fun ComposeHarness.tapText(text: String) {
    val node = exactly(text).singleOrNull { SemanticsActions.OnClick in it.config }
        ?: error("no single clickable \"$text\": ${describe()}")
    node.config[SemanticsActions.OnClick].action!!.invoke()
    settle()
}

/** Taps the one node described [description]. */
internal fun ComposeHarness.tapLabel(description: String) {
    node(description).config[SemanticsActions.OnClick].action!!.invoke()
    settle()
}

/** Types [text] into the one text field on screen. */
internal fun ComposeHarness.typeInField(text: String) {
    val field = nodes().singleOrNull { SemanticsActions.SetText in it.config } ?: error("no single text field: ${describe()}")
    field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    settle()
}

/** Whether the one node with exactly [text] is disabled. */
internal fun ComposeHarness.isDisabled(text: String): Boolean = SemanticsProperties.Disabled in exactly(text).single().config

/**
 * Idles twice and draws once: Robolectric runs no view traversal, and a draw makes the Compose
 * view measure and lay out, so bounds read next (a menu's anchor) are current.
 */
internal fun ComposeHarness.settle() {
    idle()
    idle()
    root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
    idle()
}

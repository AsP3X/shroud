package de.corespace.shroud.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.ui.theme.ShroudTheme
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Hosts composables in a Robolectric `ComponentActivity` (`ui-test-manifest` declares it in the
 * debug manifest) and reads what TalkBack would get from the merged semantics tree — the JVM
 * stand-in for a Compose UI test, which this module only runs on devices.
 */
internal class ComposeHarness(dark: Boolean = false, content: @Composable () -> Unit) {
    val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    init {
        activity.setContent { ShroudTheme(dark = dark, content = content) }
        idle()
    }

    /**
     * Runs recompositions, effects and a second of frames (the main looper's clock moves, so
     * Choreographer frames fire and finite animations settle).
     */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    /** The Compose root view. */
    val root: View get() = findRoot(activity.window.decorView) ?: error("no Compose root in the activity")

    /** Every merged semantics node, as accessibility services see them. */
    fun nodes(): List<SemanticsNode> {
        val owner = (root as RootForTest).semanticsOwner
        return owner.getAllSemanticsNodes(mergingEnabled = true)
    }

    /** The one node whose content description is [description]. */
    fun node(description: String): SemanticsNode {
        val matches = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        check(matches.size == 1) { "expected one node described \"$description\", found ${matches.size}: ${describe()}" }
        return matches.single()
    }

    /** Nodes whose text (merged) contains [text]. */
    fun nodesWithText(text: String): List<SemanticsNode> =
        nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true }

    /** A readable dump for failure messages. */
    fun describe(): String = nodes().joinToString("; ") { node ->
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        "#${node.id} cd=$description text=$text"
    }

    private fun findRoot(view: View): View? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

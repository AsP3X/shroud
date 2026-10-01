package de.corespace.shroud.ui.components

import android.os.Looper
import android.provider.Settings
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * Hosts composables in a Robolectric `ComponentActivity` (`ui-test-manifest` declares it in the
 * debug manifest) and reads what TalkBack would get from the merged semantics tree — the JVM
 * stand-in for a Compose UI test, which this module only runs on devices.
 */
internal class ComposeHarness(
    dark: Boolean = false,
    reduceMotion: Boolean = true,
    content: @Composable () -> Unit,
) {
    val activity: ComponentActivity

    init {
        // 60 Hz frames: Robolectric's 1 ms default makes every running animation draw a thousand
        // frames per idle second. Reduce motion (animator scale 0, read by ShroudTheme and by
        // Compose's own clock) stops the endless loops — halo, shimmer, typing, spinner — and
        // settles every other animation at once; semantics never depend on motion. With motion on,
        // a running infinite animation (the retry spinner) starves Robolectric's frame clock and
        // recompositions after an idle never land, so a harness with motion only suits screens
        // without endless loops.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (reduceMotion) 0f else 1f)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ShroudTheme(dark = dark, content = content) }
        idle()
    }

    /**
     * Runs recompositions, effects and half a second of frames (the main looper's clock moves, so
     * Choreographer frames fire and finite animations settle).
     */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
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
        val disabled = SemanticsProperties.Disabled in node.config
        "#${node.id} cd=$description text=$text disabled=$disabled"
    }

    private fun findRoot(view: View): View? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

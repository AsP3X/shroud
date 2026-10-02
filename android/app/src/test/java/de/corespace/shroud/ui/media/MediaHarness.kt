package de.corespace.shroud.ui.media

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.theme.ShroudTheme
import java.time.Duration
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

/**
 * The media screens' Robolectric host: `ui.components.ComposeHarness`'s set-up (60 Hz frames,
 * Reduce Motion, the app theme, semantics as TalkBack reads them) inside an [OverlayHost], plus a
 * [close] that destroys the activity.
 *
 * Closing matters. An activity left alive keeps its window recomposer running, and Compose keeps
 * every running recomposer in a static list, so each screen a test leaves behind stays in memory
 * for the rest of the JVM; the full unit-test run then ran out of heap. Closing also lets the
 * cursor blink, banner timers and debounced renders end before Robolectric resets the main looper
 * (a post dropped by that reset stalls Compose's shared main-thread dispatcher for every later
 * test).
 *
 * Use one per test and [close] it in `@After`. [settle] is compose-ui-test's idling: it hands
 * state written outside composition (semantics actions) to the recomposer, then runs half a
 * second of frames.
 */
internal class MediaHarness(content: @Composable () -> Unit) : AutoCloseable {
    private val controller = run {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    val activity: ComponentActivity = controller.get()

    init {
        activity.setContent { ShroudTheme(dark = false) { OverlayHost(content) } }
        settle()
    }

    fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    /** Every merged semantics node, as accessibility services see them. */
    fun nodes(): List<SemanticsNode> {
        val root = findRoot(activity.window.decorView) as? RootForTest ?: return emptyList()
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
    }

    fun has(description: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

    /** The one node whose content description is [description]. */
    fun node(description: String): SemanticsNode {
        val matches = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        check(matches.size == 1) { "expected one node described \"$description\", found ${matches.size}: ${describe()}" }
        return matches.single()
    }

    /** Nodes whose (merged) text contains [text]. */
    fun nodesWithText(text: String): List<SemanticsNode> =
        nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true }

    fun click(node: SemanticsNode) {
        node.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    fun click(description: String) = click(node(description))

    fun clickText(text: String) = click(nodesWithText(text).single())

    fun selected(description: String): Boolean = node(description).config.getOrNull(SemanticsProperties.Selected) == true

    fun disabled(description: String): Boolean = SemanticsProperties.Disabled in node(description).config

    fun describe(): String = nodes().joinToString("; ") { node ->
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        "#${node.id} cd=$description text=$text disabled=${SemanticsProperties.Disabled in node.config}"
    }

    /** Ends the composition and destroys the activity, so nothing of this test outlives it. */
    override fun close() {
        activity.setContent {}
        settle()
        controller.pause().stop().destroy()
        settle()
    }

    private fun findRoot(view: View): View? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

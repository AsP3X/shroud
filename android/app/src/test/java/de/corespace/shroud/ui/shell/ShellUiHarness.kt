package de.corespace.shroud.ui.shell

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import de.corespace.shroud.core.notifications.NotificationOpenRequest
import de.corespace.shroud.ui.theme.ShroudTheme
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import java.util.UUID

/**
 * Fake [ShellScreens] for the shell's Compose tests: each tab root and pushed screen is one
 * full-size node described `root:<Tab>` / `route:<name>`, the badges are fixed, and the opens run
 * the production [OpenPendingEffect] over plain state the test drives.
 */
class FakeShellScreens : ShellScreens {
    /** `notifications.pendingOpen`. */
    var pending by mutableStateOf<NotificationOpenRequest?>(null)

    /** `messaging.hasLoadedServerChats`. */
    var hasLoadedServerChats by mutableStateOf(true)

    /** Usernames the chat list or the contacts know. */
    val known = mutableMapOf<UUID, String>()

    /** How often a tab root was built (a `remember` ran), per tab. */
    val rootBuilds = mutableMapOf<MainTab, Int>()

    /** The [LocalTabBarClearance] the last composed tab root saw. */
    var clearance: Dp? = null

    var badgeCounts: Map<MainTab, Int> = mapOf(MainTab.Chats to 3)

    /** The shell's navigator, as the tab roots receive it. */
    var navigator: ShellNavigator? = null

    @Composable
    override fun TabRoot(tab: MainTab, navigator: ShellNavigator) {
        remember(tab) { rootBuilds[tab] = (rootBuilds[tab] ?: 0) + 1 }
        val seen = LocalTabBarClearance.current
        SideEffect {
            clearance = seen
            this.navigator = navigator
        }
        Box(Modifier.fillMaxSize().semantics { contentDescription = "root:${tab.title}" })
    }

    @Composable
    override fun Route(route: Any, navigator: ShellNavigator) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = "route:${routeName(route)}" })
    }

    @Composable
    override fun badges(): Map<MainTab, Int> = badgeCounts

    @Composable
    override fun Opens(navigator: ShellNavigator) {
        OpenPendingEffect(
            navigator = navigator,
            pending = pending,
            hasLoadedServerChats = hasLoadedServerChats,
            knownUsername = { known[it] },
            onHandled = { if (pending == it) pending = null },
        )
    }

    companion object {
        fun routeName(route: Any): String = when (route) {
            is ChatRoute.Conversation -> "chat:${route.username}"
            is ChatRoute.ContactProfile -> "profile:${route.username}"
            else -> route.toString()
        }
    }
}

/**
 * Hosts [content] in a Robolectric `ComponentActivity` (the window size comes from the test's
 * `@Config(qualifiers = …)`) and reads the merged semantics tree with bounds in dp — the JVM
 * stand-in for a Compose UI test, as `ui/components/ComposeHarness` does for the kit.
 */
class ShellUiHarness(reduceMotion: Boolean = true, content: @Composable () -> Unit) {
    val activity: ComponentActivity

    init {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (reduceMotion) 0f else 1f)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ShroudTheme(dark = false, content = content) }
        idle()
    }

    val density: Float get() = activity.resources.displayMetrics.density

    /** Recompositions, effects and [millis] of frames. */
    fun idle(millis: Long = 500) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    fun nodes(): List<SemanticsNode> = ((findRoot(activity.window.decorView) as RootForTest).semanticsOwner).getAllSemanticsNodes(mergingEnabled = true)

    /** Nodes whose content description is exactly [description]. */
    fun described(description: String): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

    /** The one node described exactly [description]. */
    fun node(description: String): SemanticsNode {
        val matches = described(description)
        check(matches.size == 1) { "expected one node \"$description\", found ${matches.size}: ${describe()}" }
        return matches.single()
    }

    fun has(description: String): Boolean = described(description).isNotEmpty()

    /** Some node's (merged) text is exactly [text]. */
    fun hasText(text: String): Boolean =
        nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    /** [node]'s bounds in the root, dp. */
    fun boundsDp(node: SemanticsNode): Rect {
        val px = node.boundsInRoot
        return Rect(px.left / density, px.top / density, px.right / density, px.bottom / density)
    }

    fun rootHeightDp(): Float = findRoot(activity.window.decorView)!!.height / density

    fun rootWidthDp(): Float = findRoot(activity.window.decorView)!!.width / density

    fun describe(): String = nodes().joinToString("; ") { node ->
        "#${node.id} cd=${node.config.getOrNull(SemanticsProperties.ContentDescription)} text=${node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }}"
    }

    private fun findRoot(view: View): View? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

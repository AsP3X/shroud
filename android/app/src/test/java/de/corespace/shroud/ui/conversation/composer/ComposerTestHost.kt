package de.corespace.shroud.ui.conversation.composer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.theme.ShroudTheme
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration

/**
 * The composer's Robolectric host: 60 Hz frames, Reduce Motion (every animation settles, no blink
 * or shimmer loop), the app theme inside an [OverlayHost] (the attach sheet, menus and toasts draw
 * there), semantics as TalkBack reads them, window insets dispatched like the system's, touches
 * sent as real `MotionEvent`s, and PNG renders.
 *
 * Close it in `@After`: an activity left alive keeps its recomposer in Compose's static list for the
 * rest of the one test JVM (the heap the full run once lost, `MediaHarness`), and a focused field's
 * cursor blink would outlive the test.
 */
internal class ComposerTestHost(dark: Boolean = false, content: @Composable () -> Unit) : AutoCloseable {
    private val controller = run {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    val activity: ComponentActivity = controller.get()

    init {
        activity.setContent { ShroudTheme(dark = dark) { OverlayHost(content) } }
        settle()
    }

    /**
     * Hands state written outside composition to the recomposer, runs half a second of frames, then
     * lays the window out: a change that only remeasures (an inset, a moved bar) asks the view for a
     * draw, which Robolectric never performs, so it is laid out here as compose-ui-test does.
     */
    fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        (root as RootForTest).measureAndLayoutForTest()
    }

    /**
     * [settle], after the staggered entrance's waits (`entranceRow`: up to 8 × 30 ms) passed in real
     * time: Compose's UI dispatcher has no `Delay`, so an effect's `delay` runs on kotlinx's own
     * timer, which Robolectric's clock does not move. Without it a render catches rows still hidden.
     */
    fun settleEntrances() {
        settle()
        Thread.sleep(ENTRANCE_WAIT_MS)
        settle()
    }

    /** The Compose root view. */
    val root: View get() = findRoot(activity.window.decorView) ?: error("no Compose root in the activity")

    /** dp → px at the activity's density. */
    fun px(dp: Float): Float = dp * activity.resources.displayMetrics.density

    /** The insets of an edge-to-edge window: the keyboard ([imeDp], from the window's bottom), the navigation and status bars. */
    fun windowInsets(imeDp: Float = 0f, navigationDp: Float = 0f, statusDp: Float = 0f): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px(imeDp).toInt()))
            .setVisible(WindowInsetsCompat.Type.ime(), imeDp > 0f)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(navigationDp).toInt()))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(statusDp).toInt(), 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .build()

    /**
     * Applies window insets at rest, the way the system does after a change. Compose ignores a
     * plain apply while it believes an inset animation runs, and Robolectric never ends the one it
     * starts as the window attaches, so that one is ended first.
     */
    fun insets(imeDp: Float = 0f, navigationDp: Float = 0f, statusDp: Float = 0f) {
        root.dispatchWindowInsetsAnimationEnd(WindowInsetsAnimation(WindowInsets.Type.ime(), null, 0))
        ViewCompat.dispatchApplyWindowInsets(root, windowInsets(imeDp, navigationDp, statusDp))
        settle()
    }

    /**
     * The keyboard animating as the system drives it on API 30+: prepare, the target applied (which
     * Compose holds back while prepared), start, then one progress per frame with that frame's
     * keyboard height from [framesDp], each followed by one 16 ms frame and [onFrame], then the end.
     */
    fun imeAnimation(framesDp: List<Float>, navigationDp: Float, onFrame: (imeDp: Float) -> Unit) {
        val view = root
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), null, IME_ANIMATION_MS)
        val target = framesDp.last()
        view.dispatchWindowInsetsAnimationPrepare(animation)
        ViewCompat.dispatchApplyWindowInsets(view, windowInsets(target, navigationDp))
        view.dispatchWindowInsetsAnimationStart(
            animation,
            WindowInsetsAnimation.Bounds(android.graphics.Insets.NONE, android.graphics.Insets.of(0, 0, 0, px(maxOf(target, framesDp.first())).toInt())),
        )
        for (frame in framesDp) {
            view.dispatchWindowInsetsAnimationProgress(windowInsets(frame, navigationDp).toWindowInsets()!!, listOf(animation))
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            onFrame(frame)
        }
        view.dispatchWindowInsetsAnimationEnd(animation)
        settle()
    }

    private var downTime = 0L

    /** A finger event at [point] (root px), delivered to the Compose view. */
    fun touch(action: Int, point: Offset) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, point.x, point.y, 0)
        root.dispatchTouchEvent(event)
        event.recycle()
        settle()
    }

    /** Every merged semantics node, as accessibility services see them. */
    fun nodes(): List<SemanticsNode> {
        val owner = (root as RootForTest).semanticsOwner
        return owner.getAllSemanticsNodes(mergingEnabled = true)
    }

    fun has(description: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains(description) } == true }

    /** The one node described exactly [description], else the one whose description contains it. */
    fun node(description: String): SemanticsNode {
        val all = nodes()
        val exact = all.filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        if (exact.size == 1) return exact.single()
        val matches = all.filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.contains(description) } == true }
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

    fun clickText(text: String) = click(nodesWithText(text).first { SemanticsActions.OnClick in it.config })

    fun describe(): String = nodes().joinToString("; ") { node ->
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        "#${node.id} cd=$description text=$text bounds=${node.boundsInRoot}"
    }

    /**
     * Draws the window into a PNG under `android/app/build/outputs/c11-screens/` (not committed),
     * for the design pass to lay next to the `.pen` frames. Software rendering: glass shows its
     * translucent fill without the backdrop blur.
     */
    fun render(name: String): Bitmap {
        settle()
        val view = root
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/c11-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /**
     * Ends the composition and destroys the activity, so nothing of this test outlives it, and
     * drains what that leaves for Compose's UI dispatcher. Cancel the test's own scope **before**
     * this: a state write after the last drain (a send's `finally`) posts a dispatch that the reset
     * between Robolectric tests drops, and the dispatcher then believes one is still scheduled —
     * every later composition in this test JVM silently stops recomposing (`HarnessRule`).
     */
    override fun close() {
        activity.setContent {}
        settle()
        controller.pause().stop().destroy()
        settle()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val IME_ANIMATION_MS = 250L

        /** Past the longest entrance wait (8 steps of 30 ms) with room for a busy test machine. */
        const val ENTRANCE_WAIT_MS = 400L
    }

    private fun findRoot(view: View): View? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

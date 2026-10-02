package de.corespace.shroud.ui.media

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import de.corespace.shroud.ui.components.ComposeHarness
import org.junit.rules.ExternalResource

/**
 * Opens [ComposeHarness]es for one test and empties them afterwards, so nothing a screen left
 * running (a banner's timer, the camera's bind wait) resumes into the next test. Compose's UI
 * dispatcher has no `Delay`: a `delay` in an effect waits on kotlinx's background timer, and a
 * resumption landing between two Robolectric tests leaves that dispatcher believing a dispatch is
 * already scheduled — every later effect then silently never runs.
 */
class HarnessRule : ExternalResource() {
    private val open = ArrayList<ComposeHarness>()

    internal fun compose(dark: Boolean = true, content: @Composable () -> Unit): ComposeHarness =
        ComposeHarness(dark = dark, content = content).also { open += it }

    override fun after() {
        open.forEach { harness ->
            harness.activity.setContent { }
            harness.idle()
        }
        open.clear()
    }
}

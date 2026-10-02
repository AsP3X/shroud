package de.corespace.shroud.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.components.toastBottomPadding
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ToastHost` reads the shell's tab-bar clearance (`ToastBanner.swift:97`, shell-chats §10.15): over
 * a tab root a toast floats 20 dp above the floating tab bar; on a pushed screen or a sheet (clearance
 * 0) it floats 20 dp above the navigation bar or the keyboard, plus the host's own `bottomInset`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class ToastOverTabBarTest {
    @After
    fun tearDown() = ShellUiHarness.disposeAll()

    @Test
    fun thePaddingRule() {
        assertEquals(104.dp, toastBottomPadding(tabBarClearance = 84.dp, systemBottom = 24.dp, bottomInset = 0.dp))
        assertEquals(44.dp, toastBottomPadding(tabBarClearance = 0.dp, systemBottom = 24.dp, bottomInset = 0.dp))
        assertEquals(100.dp, toastBottomPadding(tabBarClearance = 0.dp, systemBottom = 24.dp, bottomInset = 56.dp))
    }

    @Test
    fun overATabRootTheToastFloatsAboveTheBar() {
        // Gesture navigation: the bar's top is 20 + 64 = 84 dp off the screen bottom.
        assertEquals(84f + 20f, capsuleBottomGap(clearance = 84.dp), 1f)
    }

    @Test
    fun onAPushedScreenItFloatsAboveTheSystemBar() {
        assertEquals(20f, capsuleBottomGap(clearance = null), 1f)
    }

    /** Screen bottom → the toast capsule's bottom, dp (the message sits 12 dp inside the capsule). */
    private fun capsuleBottomGap(clearance: Dp?): Float {
        val state = ToastState()
        val ui = ShellUiHarness {
            Box(Modifier.fillMaxSize()) {
                if (clearance != null) {
                    CompositionLocalProvider(LocalTabBarClearance provides clearance) { ToastHost(state) }
                } else {
                    ToastHost(state)
                }
            }
        }
        state.show(Toast.success("Copied"))
        ui.idle(200)
        val message = ui.nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Copied" } == true }
        val textBottom = ui.boundsDp(message).bottom
        // The 14 sp line is centred in the row with the 16 dp icon; the row has 12 dp under it.
        return ui.rootHeightDp() - textBottom - 12f
    }
}

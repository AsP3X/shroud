package de.corespace.shroud.ui.chats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.theme.DarkColors
import de.corespace.shroud.ui.theme.LightColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Chats header's spacing (`ChatsView.swift:65-79`): SwiftUI's `VStack(spacing: 8)` puts the gap
 * between the offline banner and the header search only while both show — with the tab bar's search
 * open the banner stands alone and the list starts right under it. And New Chat's sheet colour
 * (`NewChatSheet.swift:77-81`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatsHeaderTest {
    private val hosts = ArrayList<ComposeHarness>()

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    /** Lays the view out (Robolectric runs no traversal on its own) and runs what that posted. */
    private fun ComposeHarness.layOut() {
        idle()
        root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        idle()
    }

    /** The distance in dp (mdpi: 1 px = 1 dp) from the banner's bottom to what follows the header. */
    private fun ComposeHarness.gapUnderBanner(): Float {
        layOut()
        val banner = node(ChatsCopy.OFFLINE_SPOKEN).boundsInRoot
        val below = node(BELOW).boundsInRoot
        return below.top - banner.bottom
    }

    @Test
    fun theGapStandsBetweenTheBannerAndTheField() {
        assertEquals(8.dp, ChatsHeaderLayout.gapUnderBanner(fieldShown = true))
        assertEquals(0.dp, ChatsHeaderLayout.gapUnderBanner(fieldShown = false))

        var searching by mutableStateOf(false)
        val ui = ComposeHarness {
            Column(Modifier.fillMaxWidth()) {
                ChatsHeader(isOffline = true, tabBarSearchActive = searching, query = "", onQueryChange = {})
                Box(Modifier.fillMaxWidth().height(1.dp).semantics { contentDescription = BELOW })
            }
        }.also { hosts += it }
        // Banner, 8 dp, the 36 dp field and its 10 dp bottom padding.
        assertEquals(54f, ui.gapUnderBanner(), 0.5f)
        assertTrue(ui.describe(), ui.nodes().any { SemanticsActions.SetText in it.config })

        // The tab bar's search opens: the field goes and takes the gap with it.
        searching = true
        assertEquals(0f, ui.gapUnderBanner(), 0.5f)
        assertTrue(ui.describe(), ui.nodes().none { SemanticsActions.SetText in it.config })

        searching = false
        assertEquals(54f, ui.gapUnderBanner(), 0.5f)
    }

    @Test
    fun newChatIsAnElevatedSheetInTheDark() {
        assertEquals(LightColors.background, NewChatLook.sheetFill(LightColors))
        // iOS `systemBackground` in a sheet, dark: #1C1C1E — not the app's black, which would melt
        // into the dimmed screen behind it.
        assertEquals(Color(0xFF1C1C1E), NewChatLook.sheetFill(DarkColors))
    }

    private companion object {
        const val BELOW = "below the header"
    }
}

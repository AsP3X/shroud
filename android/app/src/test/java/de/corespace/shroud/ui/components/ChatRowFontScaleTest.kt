package de.corespace.shroud.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Row height at large text (shell-chats §18: "iOS Dynamic Type does not scale these fixed sizes;
 * Android sp does — rows must grow (min height, not fixed height), avatars stay 52 dp"). At the
 * default scale a [ChatRow] is the design's 72 dp (52 dp avatar + 10 dp above and below, design
 * `Chat Row` R22zHn; `SkeletonRows.swift:28-29`); at font scale 2.0 it grows with its two text
 * lines instead of clipping them, and the skeleton keeps the 72 dp shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRowFontScaleTest {
    @Test
    fun rowIsSeventyTwoDpAtTheDefaultScale() {
        assertEquals(72f, measureHeightDp(fontScale = 1f) { Row() }, 0.5f)
    }

    @Test
    fun rowGrowsWithItsTextAtFontScaleTwo() {
        val height = measureHeightDp(fontScale = 2f) { Row() }
        // Name 16 sp + preview 14 sp at 2× need ~76 dp of text plus 20 dp of padding.
        assertTrue("row is $height dp at font scale 2", height > 90f)
    }

    @Test
    fun rowWithLiveActivityGrowsLikeThePreview() {
        val preview = measureHeightDp(fontScale = 2f) { Row() }
        val typing = measureHeightDp(fontScale = 2f) { Row(activity = ChatPeerActivity.Typing) }
        assertEquals(preview, typing, 2f)
    }

    @Test
    fun skeletonRowKeepsTheChatRowShape() {
        assertEquals(72f, measureHeightDp(fontScale = 2f) { SkeletonChatRow() }, 0.5f)
    }

    @Composable
    private fun Row(activity: ChatPeerActivity? = null) {
        ChatRow(
            title = "Design Team",
            subtitle = "Nina: Final icons are ready",
            time = "12:45",
            avatar = { NameAvatar("Design Team") },
            unreadCount = 3,
            activity = activity,
            onClick = {},
        )
    }

    private fun measureHeightDp(fontScale: Float, content: @Composable () -> Unit): Float {
        var heightPx = -1
        var density = 1f
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                ShroudTheme(dark = false) {
                    Column(Modifier.width(412.dp).onGloballyPositioned { heightPx = it.size.height }) { content() }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("content was laid out", heightPx >= 0)
        return heightPx / density
    }
}

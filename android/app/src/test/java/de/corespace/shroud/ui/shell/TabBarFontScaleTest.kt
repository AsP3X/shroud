package de.corespace.shroud.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.inter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tab bar at font scale 2.0 (P12c decided: clamp the bar, rows scale fully; shell-chats §4.9,
 * D9): iOS sets the labels in a fixed 10 pt that ignores Dynamic Type (`FloatingTabBar.swift:331`),
 * so the bar clamps its text to 1.3× and keeps its 64 dp capsule, 60 × 56 dp items at 360 dp, and
 * "Contacts" inside its item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabBarFontScaleTest {
    @After
    fun tearDown() = ShellUiHarness.disposeAll()

    @Test
    fun theBarsTextNeverGrowsPast1Point3() {
        val large = FloatingTabBarMetrics.clamp(Density(2.625f, 2f))
        assertEquals(1.3f, large.fontScale, 0f)
        assertEquals(2.625f, large.density, 0f)
        val small = Density(2.625f, 0.85f)
        assertSame("smaller text is left alone", small, FloatingTabBarMetrics.clamp(small))
        assertEquals(1.3f, FloatingTabBarMetrics.clamp(Density(2.625f, 1.3f)).fontScale, 0f)
    }

    @Test
    fun atFontScaleTwoTheBarKeepsItsFrameAndContactsFits() {
        var clampedLabel = 0f
        var unclampedLabel = 0f
        val ui = ShellUiHarness {
            val base = LocalDensity.current
            val large = Density(base.density, 2f)
            val measurer = rememberTextMeasurer()
            val style = inter(10f, FontWeight.SemiBold)
            SideEffect {
                clampedLabel = measurer.measure("Contacts", style, density = FloatingTabBarMetrics.clamp(large)).size.width / base.density
                unclampedLabel = measurer.measure("Contacts", style, density = large).size.width / base.density
            }
            CompositionLocalProvider(LocalDensity provides large) {
                Box(Modifier.width(320.dp)) {
                    FloatingTabBar(
                        selection = MainTab.Chats,
                        onSelect = {},
                        isSearching = false,
                        onSearchingChange = {},
                        query = "",
                        onQueryChange = {},
                        searchFocus = remember { FocusRequester() },
                        badges = mapOf(MainTab.Chats to 120),
                    )
                }
            }
        }
        val contacts = ui.boundsDp(ui.node("Contacts"))
        assertEquals(60f, contacts.width, 0.5f)
        assertEquals(56f, contacts.height, 0.5f)
        val search = ui.boundsDp(ui.node("Search"))
        assertEquals(64f, search.height, 0.5f)
        assertEquals(64f, search.width, 0.5f)
        assertTrue("\"Contacts\" at 1.3× is $clampedLabel dp, its item 60", clampedLabel <= 60f)
        assertTrue("unclamped it would not fit ($unclampedLabel dp)", unclampedLabel > 60f)
        // TalkBack still reads the count, capped like the badge: "Chats, tab, 1 of 4, selected, 99+ unread".
        assertEquals("99+ unread", ui.node("Chats").config.getOrNull(SemanticsProperties.StateDescription))
    }
}

package de.corespace.shroud.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.max

/**
 * A screen pushed from a tab root — every Settings sub-screen (settings-lock §2.1; design
 * `Nav Bar / Pushed` `Koeh8`; iOS pushes them with the inline system bar, e.g.
 * `DevicesView.swift:80-82`).
 *
 * - Bar under the status bar: 44 dp high, side inset 16, gap 8. Leading: the 44 dp glass back
 *   circle with Phosphor `caret-left-bold` in `textPrimary` ("Back"; design: "Back uses chevron
 *   in text-primary"). Centre: [title] as a [GlassBarTitle], centred on the screen — the side
 *   reserve is the wider of the back circle and [trailing], so the title never runs under either.
 *   Trailing: a 44 dp slot (empty, or a glass capsule such as Server's "Save").
 * - [content] is one scrolling column on [scrollState] that scrolls under the bar and the status
 *   bar, faded by [ScrollEdgeEffect] once it has moved. A drag hides the keyboard.
 * - Background `backgroundGrouped` (iOS `GroupedScreen`, `ScreenContent.swift:24-36`).
 * - Bottom: [bottomBar], then the navigation bar or keyboard inset (the tab bar is hidden while a
 *   Settings route is pushed, `SettingsView.swift:26-27`).
 * - Back: the back circle calls [onBack]. System back belongs to the shell's stack (it runs the
 *   predictive-back pop, shell-chats §4.8, which is the same pop); while [backEnabled] is false
 *   this screen swallows it and dims the circle (Server while saving, settings-lock §10.4).
 */
@Composable
fun PushedScreen(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ShroudTheme.colors
    val backdrop = rememberHazeState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Derived, so scrolling only recomposes when content starts or stops passing under the bar.
    val scrolledUnder by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    BackHandler(enabled = !backEnabled) {
        // Back is off while the screen finishes something it must not abandon.
    }
    Column(modifier.fillMaxSize().background(colors.backgroundGrouped)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .edgeEffectSource(backdrop)
                    .nestedScroll(rememberKeyboardDismissOnDrag())
                    .verticalScroll(scrollState)
                    .padding(top = statusTop + PushedBarMetrics.height),
                content = content,
            )
            ScrollEdgeEffect(
                visible = scrolledUnder,
                extent = statusTop + PushedBarMetrics.height,
                backdrop = backdrop,
            )
            // The back circle's glass blurs the scrolled content too (API 31+; opaque on API 30).
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    PushedBar(
                        leading = {
                            GlassCircleButton(
                                ShroudIcons.CaretLeftBold,
                                "Back",
                                onBack,
                                tint = colors.textPrimary,
                                enabled = backEnabled,
                            )
                        },
                        title = { GlassBarTitle(title) },
                        trailing = trailing,
                    )
                }
            }
        }
        bottomBar()
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }
}

/** Metrics of the pushed bar (design `Nav Bar / Pushed` `Koeh8`, 412 × 44). */
object PushedBarMetrics {
    val height: Dp = 44.dp
    val horizontalInset: Dp = 16.dp
    val spacing: Dp = 8.dp

    /**
     * Width left for a title centred on a bar [barWidth] wide whose sides hold [leadingWidth] and
     * [trailingWidth] (px): the wider side plus the gap is kept free on both sides, so the title
     * stays centred on the screen. At 412 dp with two 44 dp sides: 412 − 32 − 2 × 52 = 276, the
     * design's title width.
     */
    fun titleWidth(barWidth: Int, leadingWidth: Int, trailingWidth: Int, spacing: Int): Int =
        (barWidth - 2 * (max(leadingWidth, trailingWidth) + spacing)).coerceAtLeast(0)
}

/** The 44 dp bar row: leading, a screen-centred title, trailing. */
@Composable
private fun PushedBar(
    leading: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Row(Modifier.layoutId("leading"), verticalAlignment = Alignment.CenterVertically, content = leading)
            Box(Modifier.layoutId("title"), contentAlignment = Alignment.Center) { title() }
            Row(
                Modifier.layoutId("trailing").widthIn(min = GlassBarMetrics.controlSize),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = PushedBarMetrics.horizontalInset),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables.first { it.layoutId == "leading" }.measure(loose)
        val trail = measurables.first { it.layoutId == "trailing" }.measure(loose)
        val gap = PushedBarMetrics.spacing.roundToPx()
        val width = constraints.maxWidth
        val titleMax = PushedBarMetrics.titleWidth(width, lead.width, trail.width, gap)
        val head = measurables.first { it.layoutId == "title" }.measure(Constraints(maxWidth = titleMax, maxHeight = loose.maxHeight))
        val height = maxOf(PushedBarMetrics.height.roundToPx(), lead.height, trail.height, head.height)
        layout(width, height) {
            lead.place(0, (height - lead.height) / 2)
            head.place((width - head.width) / 2, (height - head.height) / 2)
            trail.place(width - trail.width, (height - trail.height) / 2)
        }
    }
}

@Preview(name = "Pushed screen · 412", widthDp = 412, heightDp = 500)
@Composable
private fun PushedScreenPreview() {
    ShroudTheme(dark = false) {
        PushedScreen("Privacy and Security", onBack = {}) {
            Column(Modifier.padding(16.dp)) {
                SettingsCard {
                    ToggleRow("Hide chats during screen recording", "While the screen is recorded, Shroud shows only its logo.", checked = true, onCheckedChange = {})
                }
            }
        }
    }
}

@Preview(name = "Pushed screen · 360 · dark", widthDp = 360, heightDp = 500)
@Composable
private fun PushedScreenDarkPreview() {
    ShroudTheme(dark = true) {
        PushedScreen("Server", onBack = {}, trailing = { GlassBarButton(null, "Save", {}, GlassBarButtonStyle.Capsule, label = "Save") }) {
            Column(Modifier.padding(16.dp)) {
                SettingsCard { SettingsRow("Devices", ShroudIcons.LockFill, ShroudTheme.colors.accent, value = "3", onClick = {}) }
            }
        }
    }
}

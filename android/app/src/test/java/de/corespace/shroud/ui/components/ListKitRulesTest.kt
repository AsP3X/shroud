package de.corespace.shroud.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the list kit: skeleton, entrance, bars, settings rows, search keyboard, copy. */
class ListKitRulesTest {
    /** `SkeletonRows.swift:44-48`. */
    @Test
    fun skeletonWidthsCycleThroughTheIosTable() {
        assertEquals(
            listOf(132 to 214, 96 to 168, 148 to 190, 110 to 232, 124 to 152, 88 to 205, 140 to 176),
            SkeletonWidths.widths,
        )
        assertEquals(132 to 214, SkeletonWidths.at(0))
        assertEquals(140 to 176, SkeletonWidths.at(6))
        assertEquals(132 to 214, SkeletonWidths.at(7))
        assertEquals(96 to 168, SkeletonWidths.at(8))
    }

    /** Entrance stagger timing, `Motion.swift:168-202` (shell-chats §10.12). */
    @Test
    fun entranceRowsStaggerByThirtyMillisecondsUpToTheEighth() {
        assertEquals(0L, ListEntranceTiming.delayMillis(0))
        assertEquals(30L, ListEntranceTiming.delayMillis(1))
        assertEquals(90L, ListEntranceTiming.delayMillis(3))
        assertEquals(240L, ListEntranceTiming.delayMillis(8))
        assertEquals(240L, ListEntranceTiming.delayMillis(9))
        assertEquals(240L, ListEntranceTiming.delayMillis(500))
        assertEquals(0L, ListEntranceTiming.delayMillis(-1))
    }

    @Test
    fun entranceOnlyAnimatesInsideTheSixHundredMillisecondWindow() {
        val start = 10_000L
        assertTrue(ListEntranceTiming.animates(start, start))
        assertTrue(ListEntranceTiming.animates(start + 599, start))
        assertFalse(ListEntranceTiming.animates(start + 600, start))
        assertFalse(ListEntranceTiming.animates(start + 5_000, start))
        assertEquals(10f, ListEntranceTiming.LIFT_DP)
    }

    /** `GlassBar.swift:13-32`. */
    @Test
    fun glassBarMetricsMatchIos() {
        assertEquals(44.dp, GlassBarMetrics.controlSize)
        assertEquals(96.dp, GlassBarMetrics.sideReserve(2))
        assertEquals(52.dp, GlassBarMetrics.sideReserve(1))
        assertEquals(52.dp, GlassBarMetrics.sideReserve(0))
        assertEquals(140.dp, GlassBarMetrics.sideReserve(3))
        assertEquals(54.dp, GlassBarMetrics.mainBarHeight)
        assertEquals(12.dp, GlassBarMetrics.headerGap)
    }

    /** Design `Nav Bar / Pushed` (Koeh8): the 276 dp title of a 412 dp bar. */
    @Test
    fun pushedBarTitleKeepsTheWiderSideFreeOnBothSides() {
        assertEquals(276, PushedBarMetrics.titleWidth(barWidth = 380, leadingWidth = 44, trailingWidth = 44, spacing = 8))
        assertEquals(224, PushedBarMetrics.titleWidth(barWidth = 380, leadingWidth = 44, trailingWidth = 70, spacing = 8))
        assertEquals(0, PushedBarMetrics.titleWidth(barWidth = 100, leadingWidth = 44, trailingWidth = 70, spacing = 8))
    }

    @Test
    fun contentIsUnderTheBarOnceTheListMoved() {
        assertFalse(MainScrollBackdrop.isUnderBar(0, 0))
        assertTrue(MainScrollBackdrop.isUnderBar(0, 1))
        assertTrue(MainScrollBackdrop.isUnderBar(3, 0))
    }

    /** `SettingsRowView.swift:31-54` and settings-lock §2.3. */
    @Test
    fun settingsRowKindFollowsItsParameters() {
        assertEquals(SettingsRowKind.Navigation, SettingsRowKind.of(hasAction = true, soon = false, destructive = false))
        assertEquals(SettingsRowKind.Action, SettingsRowKind.of(hasAction = true, soon = false, destructive = true))
        assertEquals(SettingsRowKind.Soon, SettingsRowKind.of(hasAction = false, soon = true, destructive = false))
        assertEquals(SettingsRowKind.Soon, SettingsRowKind.of(hasAction = true, soon = true, destructive = false))
        assertEquals(SettingsRowKind.Static, SettingsRowKind.of(hasAction = false, soon = false, destructive = false))
        assertEquals(0.72f, SettingsRowKind.SOON_ALPHA)
        assertFalse(SettingsRowKind.Soon.pressable)
        assertTrue(SettingsRowKind.Navigation.pressable)
    }

    /** `NotificationsSettingsView.swift:422`, `PrivacySecurityView.swift:331`. */
    @Test
    fun toggleRowPaddingFollowsTheScreen() {
        assertEquals(11.dp, SettingsMetrics.toggleRowVerticalPadding(ToggleRowSpacing.Notifications, hasSubtitle = false))
        assertEquals(10.dp, SettingsMetrics.toggleRowVerticalPadding(ToggleRowSpacing.Notifications, hasSubtitle = true))
        assertEquals(12.dp, SettingsMetrics.toggleRowVerticalPadding(ToggleRowSpacing.Privacy, hasSubtitle = true))
        assertEquals(12.dp, SettingsMetrics.toggleRowVerticalPadding(ToggleRowSpacing.Privacy, hasSubtitle = false))
    }

    /** Search keyboard (shell-chats §4.3, plan P5): no capitals, no autocorrect, Search action. */
    @Test
    fun searchKeyboardAsksForNoCorrections() {
        val options = SearchKeyboard.options
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertEquals(false, options.autoCorrectEnabled)
        assertEquals(ImeAction.Search, options.imeAction)
    }

    @Test
    fun searchFieldsTurnOffPersonalisedLearning() {
        val info = EditorInfo()
        info.imeOptions = EditorInfo.IME_ACTION_SEARCH
        SearchKeyboard.disablePersonalizedLearning(info)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    /** `ListLoadErrorView.swift:49, 59`. */
    @Test
    fun retryButtonWording() {
        assertEquals("Try Again", ListLoadErrorCopy.buttonTitle(retrying = false))
        assertEquals("Retrying…", ListLoadErrorCopy.buttonTitle(retrying = true))
        assertEquals("Try again", ListLoadErrorCopy.buttonLabel(retrying = false))
        assertEquals("Retrying", ListLoadErrorCopy.buttonLabel(retrying = true))
    }

    /** Notifications' large icon (notifications-push §6.1): 40 dp, initials 15. */
    @Test
    fun avatarBitmapScalesWithTheDisplay() {
        assertEquals(15f, AvatarBitmap.initialsSizeDp(40))
        assertEquals(19.5f, AvatarBitmap.initialsSizeDp(52))
        val metrics = AvatarBitmap.layout(40, 2.625f)
        assertEquals(105, metrics.sizePx)
        assertEquals(39.375f, metrics.textSizePx, 1e-4f)
        assertEquals(1, AvatarBitmap.layout(0, 3f).sizePx)
    }
}

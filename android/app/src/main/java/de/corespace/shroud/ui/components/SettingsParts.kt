package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.Locale

/**
 * A grouped card of settings rows (settings-lock §2.2; iOS `settingsCard`,
 * `SettingsView.swift:568-574`, `DevicesView.swift:426-432`): a column on `background`, clipped
 * to a 14 dp rounded rectangle, on the screen's `backgroundGrouped`.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SettingsMetrics.cardShape)
            .background(ShroudTheme.colors.background),
        content = content,
    )
}

/**
 * A section header over a card (settings-lock §2.2; `NotificationsSettingsView.swift:432-438`):
 * [text] upper-cased (Swift `uppercased()`, locale-free), 13 sp `textSecondary`, padding h 14, a
 * heading for TalkBack. Devices adds 6 dp above and below through [modifier]
 * (`DevicesView.swift:399-407`).
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val shown = text.uppercase(Locale.ROOT)
    ShroudText(
        shown,
        inter(13f),
        ShroudTheme.colors.textSecondary,
        modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsMetrics.textInset)
            .semantics { heading() },
    )
}

/**
 * A section footer under a card (settings-lock §2.2; `DevicesView.swift:409-417`,
 * `NotificationsSettingsView.swift:440-448`): 13 sp `textSecondary`, wrapping, padding h 14.
 * [pullUp] is iOS's negative top padding: Notifications and Appearance pull their footers up by
 * 6 dp so a 14-spaced column shows an 8 dp gap; Devices passes top 6 / bottom 8 through
 * [modifier] instead.
 */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier, pullUp: Dp = 0.dp) {
    ShroudText(
        text,
        inter(13f),
        ShroudTheme.colors.textSecondary,
        modifier
            .then(if (pullUp > 0.dp) Modifier.pulledUp(pullUp) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = SettingsMetrics.textInset),
    )
}

/** Negative top padding: lays the content out [by] higher and gives that much height back. */
private fun Modifier.pulledUp(by: Dp): Modifier = layout { measurable, constraints ->
    val shift = by.roundToPx()
    val placeable = measurable.measure(constraints)
    val height = (placeable.height - shift).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -shift) }
}

/**
 * The 1 dp `separator` line between rows of a card, inset from the leading edge by [start]
 * (settings-lock §2.2): 54 in Settings, 56 in Devices and the Appearance themes, 70 for the
 * Appearance logos, 14 in toggle lists and details (`SettingsView.swift:561-566`). Decorative.
 */
@Composable
fun InsetDivider(start: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(ShroudTheme.colors.separator),
    )
}

/**
 * The tinted square behind a settings glyph (`SettingsRowView.swift:17-26`): 30 × 30, radius 8,
 * [tint] fill, [icon] 14 dp white. Decorative: the row's title says what it is.
 */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(SettingsMetrics.tileSize)
            .clip(SettingsMetrics.tileShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, Color.White, size = SettingsMetrics.tileGlyph)
    }
}

/**
 * Vertical rhythm of a [ToggleRow]: [Notifications] is `NotificationsSettingsView.swift:406-423`
 * (padding v 11 without a subtitle, 10 with; title–subtitle gap 3), [Privacy] is
 * `PrivacySecurityView.swift:318-336` (padding v 12; gap 4).
 */
enum class ToggleRowSpacing { Notifications, Privacy }

/**
 * A settings switch row (settings-lock §2.2): [title] 16 sp `textPrimary` and an optional
 * wrapping [subtitle] 13 sp `textSecondary`, the app's [ShroudToggle] trailing, padding h 14.
 * The whole row toggles; TalkBack hears one switch named [title] with "On" / "Off". Disabled: 50 %
 * and no touches, the switch included.
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    spacing: ToggleRowSpacing = ToggleRowSpacing.Notifications,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = ShroudTheme.colors
    val vertical = SettingsMetrics.toggleRowVerticalPadding(spacing, hasSubtitle = subtitle != null)
    val gap = if (spacing == ToggleRowSpacing.Privacy) 4.dp else 3.dp
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else SettingsMetrics.DISABLED_TOGGLE_ALPHA)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(horizontal = SettingsMetrics.textInset, vertical = vertical),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
            ShroudText(title, inter(16f), colors.textPrimary)
            if (subtitle != null) ShroudText(subtitle, inter(13f), colors.textSecondary)
        }
        ShroudToggle(checked = checked, onCheckedChange = onCheckedChange, label = title, enabled = enabled)
    }
}

/** Shared numbers of the settings parts (settings-lock §2.2-2.3). */
object SettingsMetrics {
    val cardShape = RoundedCornerShape(14.dp)
    val textInset: Dp = 14.dp
    val tileSize: Dp = 30.dp
    val tileShape = RoundedCornerShape(8.dp)
    val tileGlyph: Dp = 14.dp

    /** A switch row that cannot change right now (settings-lock §2.2). */
    const val DISABLED_TOGGLE_ALPHA = 0.5f

    /** Vertical padding of a [ToggleRow] (`NotificationsSettingsView.swift:422`, `PrivacySecurityView.swift:331`). */
    fun toggleRowVerticalPadding(spacing: ToggleRowSpacing, hasSubtitle: Boolean): Dp = when (spacing) {
        ToggleRowSpacing.Privacy -> 12.dp
        ToggleRowSpacing.Notifications -> if (hasSubtitle) 10.dp else 11.dp
    }
}

@Preview(name = "Settings parts · 412", widthDp = 412)
@Composable
private fun SettingsPartsPreview() = SettingsPartsSamples(dark = false)

@Preview(name = "Settings parts · 360 · dark", widthDp = 360)
@Composable
private fun SettingsPartsDarkPreview() = SettingsPartsSamples(dark = true)

@Composable
private fun SettingsPartsSamples(dark: Boolean) {
    ShroudTheme(dark = dark) {
        Column(
            Modifier.background(ShroudTheme.colors.backgroundGrouped).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeader("In-app notifications")
            SettingsCard {
                ToggleRow("In-App Banners", checked = true, onCheckedChange = {})
                InsetDivider(14.dp)
                ToggleRow("In-App Sounds", "Play the notification sound while Shroud is open.", checked = false, onCheckedChange = {})
                InsetDivider(14.dp)
                ToggleRow("Show Sender", checked = true, enabled = false, onCheckedChange = {})
            }
            SectionFooter("Shown while Shroud is open.", pullUp = 6.dp)
            SettingsCard {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(ShroudIcons.LockFill, ShroudTheme.colors.textSecondary)
                    IconTile(ShroudIcons.GearSixFill, ShroudTheme.colors.accent)
                }
            }
        }
    }
}

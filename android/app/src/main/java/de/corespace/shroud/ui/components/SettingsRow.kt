package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * A settings list row (`SettingsRowView.swift:4-56`; shell-chats §10.17; settings-lock §2.3):
 * `Row(spacing 12)`, padding h 14 v 10. An optional [IconTile] ([icon] on [tile], `accent` when
 * no tint is given), [title] 16 sp filling the row, then by kind:
 *
 * - **Navigation** ([onClick] set, not [destructive]): optional [value] 16 sp `textSecondary`
 *   (only shown with an action, `SettingsRowView.swift:31-35`) and the Phosphor `caret-right`
 *   13 dp chevron in the `chevron` token. With a [subtitle] the title and a one-line 13 sp
 *   `textSecondary` subtitle stack with spacing 2 (the Server row, `SettingsView.swift:497-528`;
 *   TalkBack "Server, <subtitle>").
 * - **Action** ([destructive]): title in `danger`, no chevron; a subtitle wraps ("Reset QR code",
 *   `PrivacySecurityView.swift:515-533`; "Remove All Other Devices", `DevicesView.swift:327-355`).
 *   [busy] swaps the trailing side for a small spinner and disables the row.
 * - **Soon** ([soon]): "Soon" 13 sp Medium `textSecondary`, disabled at 72 % — the iOS row
 *   without an action (`SettingsRowView.swift:41-54`).
 * - **Static** (no [onClick], not [soon]): the title and value as text, nothing to press.
 *
 * Rows with an action press with [highlightRow] (`backgroundGrouped` on press-down, light haptic,
 * no scale). The tile and chevron are decorative; TalkBack reads one merged row.
 */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    tile: Color? = null,
    value: String? = null,
    subtitle: String? = null,
    soon: Boolean = false,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val colors = ShroudTheme.colors
    val kind = SettingsRowKind.of(hasAction = onClick != null, soon = soon, destructive = destructive)
    val active = enabled && !busy && kind.pressable
    val press = if (onClick != null && kind.pressable) {
        Modifier.highlightRow(onClick = onClick, enabled = active)
    } else {
        Modifier.semantics(mergeDescendants = true) { if (kind == SettingsRowKind.Soon) disabled() }
    }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (kind == SettingsRowKind.Soon) SettingsRowKind.SOON_ALPHA else 1f)
            .then(press)
            .padding(horizontal = SettingsMetrics.textInset, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) IconTile(icon, tile ?: colors.accent)
        val titleColor = if (destructive) colors.danger else colors.textPrimary
        if (subtitle != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText(title, inter(16f), titleColor)
                val oneLine = kind == SettingsRowKind.Navigation
                ShroudText(
                    subtitle,
                    inter(13f),
                    colors.textSecondary,
                    maxLines = if (oneLine) 1 else Int.MAX_VALUE,
                    overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        } else {
            ShroudText(title, inter(16f), titleColor, Modifier.weight(1f))
        }
        when {
            busy -> Spinner(colors.textSecondary, size = 18.dp)
            kind == SettingsRowKind.Soon -> ShroudText("Soon", inter(13f, FontWeight.Medium), colors.textSecondary, maxLines = 1)
            kind == SettingsRowKind.Navigation -> {
                if (value != null) ShroudText(value, inter(16f), colors.textSecondary, maxLines = 1)
                ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
            }
            kind == SettingsRowKind.Static && value != null -> ShroudText(value, inter(16f), colors.textSecondary, maxLines = 1)
            else -> Unit
        }
    }
}

/** What a [SettingsRow] is, from its parameters (pure, so the rules are tested). */
enum class SettingsRowKind(val pressable: Boolean) {
    Navigation(true),
    Action(true),
    Soon(false),
    Static(false),
    ;

    companion object {
        /** A row without an action dims to this (`SettingsRowView.swift:54`). */
        const val SOON_ALPHA = 0.72f

        fun of(hasAction: Boolean, soon: Boolean, destructive: Boolean): SettingsRowKind = when {
            soon -> Soon
            !hasAction -> Static
            destructive -> Action
            else -> Navigation
        }
    }
}

@Preview(name = "Settings rows · 412", widthDp = 412)
@Composable
private fun SettingsRowPreview() = SettingsRowSamples(dark = false)

@Preview(name = "Settings rows · 360 · dark", widthDp = 360)
@Composable
private fun SettingsRowDarkPreview() = SettingsRowSamples(dark = true)

@Composable
private fun SettingsRowSamples(dark: Boolean) {
    ShroudTheme(dark = dark) {
        val colors = ShroudTheme.colors
        Column(
            Modifier.background(colors.backgroundGrouped).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard {
                SettingsRow("Saved Messages", ShroudIcons.LockFill, Color(0xFF2E8FE0), onClick = {})
                InsetDivider(54.dp)
                SettingsRow("Devices", ShroudIcons.PhoneFill, Color(0xFFF76B1C), value = "3", onClick = {})
                InsetDivider(54.dp)
                SettingsRow("Chat Folders", ShroudIcons.Folder, Color(0xFF4AC7FA), soon = true, onClick = null)
                InsetDivider(54.dp)
                SettingsRow("Server", ShroudIcons.HardDrivesFill, colors.accent, subtitle = "shroud.corespace.de", onClick = {})
            }
            SettingsCard {
                SettingsRow("Remove All Other Devices", ShroudIcons.WarningFill, colors.danger, destructive = true, busy = true, onClick = {})
                InsetDivider(14.dp)
                SettingsRow(
                    "Reset QR code",
                    subtitle = "Makes a new QR code and invite link. The old ones stop working.",
                    destructive = true,
                    onClick = {},
                )
            }
        }
    }
}

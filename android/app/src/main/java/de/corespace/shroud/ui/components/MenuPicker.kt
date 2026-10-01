package de.corespace.shroud.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * A value that opens a menu of [options] — iOS `Picker(…).pickerStyle(.menu)` (the Auto-lock
 * delay, `PrivacySecurityView.swift:285-292`; settings-lock §2.5).
 *
 * Human: Shows the current value in accent with an up-down caret. A tap opens a small card next to
 * it (no dim), the current value ticked; choosing closes it. Tapping outside or back closes it
 * without a change.
 *
 * Agent: [onSelect] runs only when the choice differs from [value] (a SwiftUI picker's `onChange`
 * does not fire for the same value); the caller plays its own haptic, as iOS does
 * (`PrivacySecurityView.swift:306-309`). [contentDescription] names the setting for TalkBack
 * ("Auto-lock"); the value is its state description, role drop-down list. The visual row is 44 dp
 * high inside a 48 dp touch target.
 */
@Composable
fun <T> MenuPicker(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val colors = ShroudTheme.colors
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var open by remember { mutableStateOf(false) }
    val current = label(value)
    Row(
        modifier
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.DropdownList,
                onClick = { open = true },
            )
            .clearAndSetSemantics {
                this.contentDescription = contentDescription ?: current
                stateDescription = current
                role = Role.DropdownList
                if (enabled) {
                    onClick {
                        open = true
                        true
                    }
                } else {
                    disabled()
                }
            }
            .heightIn(min = MenuPickerMetrics.TouchHeight)
            .padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(current, inter(16f), if (enabled) colors.accent else colors.textSecondary, maxLines = 1)
        ShroudIcon(OverlayIcons.CaretUpDown, if (enabled) colors.accent else colors.textSecondary, size = 13.dp)
    }

    if (open && enabled) {
        ContextMenuOverlay(
            anchor = anchor,
            actions = options.map { option ->
                MenuAction(
                    title = label(option),
                    checked = option == value,
                    onClick = { if (option != value) onSelect(option) },
                )
            },
            style = MenuStyle.Light,
            onDismiss = { open = false },
            paneTitle = contentDescription ?: "Options",
            dims = false,
            metrics = MenuCardMetrics.Picker,
            header = null,
        )
    }
}

/** settings-lock §2.5: 44 dp row (iOS), 48 dp touch target (00-plan §2.0 rule 8). */
internal object MenuPickerMetrics {
    val TouchHeight = 48.dp
}

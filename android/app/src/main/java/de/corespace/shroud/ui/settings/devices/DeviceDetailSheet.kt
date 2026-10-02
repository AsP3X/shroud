package de.corespace.shroud.ui.settings.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.ui.components.AlertButton
import de.corespace.shroud.ui.components.AlertField
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudAlertDialog
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.launch
import java.time.Instant

/** What the details sheet shows of one device, kept while the sheet animates out. */
private data class DeviceDetail(
    val device: DeviceRow,
    val isRevoking: Boolean,
) {
    val label: DeviceNameSeal.Label? get() = device.label
    val isCurrent: Boolean get() = device.isThisDevice
}

/** The last shown detail (not state: written while the sheet is up only). */
private class ShownDetail {
    var value: DeviceDetail? = null
}

/**
 * Everything the server knows about one device, plus its actions (iOS `DeviceDetailSheet`,
 * `DevicesView.swift:590-781`; settings-lock §4.6–4.7; design `Device Details` `nUbf0`): the floating
 * inset sheet (S13) with the hero, the facts card (Name → Rename, Type, Linked, Last active,
 * Device ID → copy), the rename error, and Remove Device — or, for this phone, the note that Log
 * Out removes it.
 *
 * [device] null hides the sheet. [onRename] returns the error to show, null once saved. A copy
 * confirms on the sheet's own toast, which floats above the sheet (the screen's toast would sit
 * under it, `DevicesView.swift:724-726`).
 */
@Composable
internal fun DeviceDetailSheet(
    device: DeviceRow?,
    isRevoking: Boolean,
    noun: String,
    lastActive: (DeviceRow) -> String,
    formatDate: (Instant) -> String,
    onRename: suspend (DeviceRow, String) -> String?,
    onCopyId: (DeviceRow) -> Unit,
    onRevoke: (DeviceRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = remember { ShownDetail() }
    if (device != null) shown.value = DeviceDetail(device, isRevoking)
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    val deviceId = shown.value?.device?.id
    var isRenaming by remember(deviceId) { mutableStateOf(false) }
    var isSavingName by remember(deviceId) { mutableStateOf(false) }
    var renameError by remember(deviceId) { mutableStateOf<String?>(null) }
    // The draft name lives in memory only (never saved state: names are sealed everywhere else).
    var draft by remember(deviceId) { mutableStateOf("") }

    // Core normalises and seals the name (K3 `rename`); an empty result comes back as "Enter a name.".
    fun saveName(target: DeviceRow) {
        val name = draft.trim()
        if (name.isEmpty()) return
        isSavingName = true
        renameError = null
        scope.launch {
            try {
                renameError = onRename(target, name)
            } finally {
                isSavingName = false
            }
        }
    }

    ShroudSheet(
        visible = device != null,
        onDismiss = onDismiss,
        style = SheetStyle.Inset,
        paneTitle = DevicesCopy.DETAILS_PANE,
    ) {
        val detail = shown.value ?: return@ShroudSheet
        DeviceDetailContent(
            detail = detail,
            noun = noun,
            lastActive = lastActive,
            formatDate = formatDate,
            isSavingName = isSavingName,
            renameError = renameError,
            onStartRename = {
                draft = detail.label?.name ?: ""
                isRenaming = true
            },
            onCopyId = {
                onCopyId(detail.device)
                toasts.show(Toast.success(DevicesCopy.DEVICE_ID_COPIED))
            },
            onRevoke = { onRevoke(detail.device) },
        )
    }

    // The sheet's own toast, drawn above it (a later layer); not modal, so taps go through.
    OverlayLayer(active = device != null || toasts.current != null, modal = false) {
        ToastHost(toasts)
    }

    val renaming = shown.value?.device
    ShroudAlertDialog(
        visible = isRenaming && device != null,
        title = DevicesCopy.RENAME_TITLE,
        message = DevicesCopy.RENAME_MESSAGE,
        field = AlertField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = DevicesCopy.NAME,
            capitalization = KeyboardCapitalization.Words,
        ),
        primary = AlertButton(DevicesCopy.SAVE, enabled = draft.isNotBlank()) {
            renaming?.let(::saveName)
        },
        onDismiss = { isRenaming = false },
    )
}

/** The sheet's column (`DevicesView.swift:607-722`); the inset sheet already pads [8,16,24,16] with 14 between. */
@Composable
private fun ColumnScope.DeviceDetailContent(
    detail: DeviceDetail,
    noun: String,
    lastActive: (DeviceRow) -> String,
    formatDate: (Instant) -> String,
    isSavingName: Boolean,
    renameError: String?,
    onStartRename: () -> Unit,
    onCopyId: () -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val device = detail.device
    val name = DevicesCopy.displayName(device)
    val kind = device.kind

    // Hero: tile 64, the name, the status line (`:610-621`; top 24 less the sheet's own 8).
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeviceTile(kind, 64.dp)
        ShroudText(name, inter(22f, FontWeight.Bold), colors.textPrimary, textAlign = TextAlign.Center)
        ShroudText(
            if (detail.isCurrent) DevicesCopy.detailsActiveNow(noun) else lastActive(device),
            inter(14f),
            if (detail.isCurrent) colors.accent else colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }

    SettingsCard {
        // Name → Rename (`:624-647`).
        Row(
            Modifier
                .fillMaxWidth()
                .highlightRow(onClick = onStartRename, enabled = !isSavingName)
                .clearAndSetSemantics { contentDescription = DevicesCopy.renameLabel(name) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudText(DevicesCopy.NAME, inter(16f), colors.textPrimary, Modifier.weight(1f))
            if (isSavingName) {
                Spinner(colors.textSecondary, size = 16.dp)
            } else {
                ShroudText(DevicesCopy.RENAME, inter(15f, FontWeight.Medium), colors.accent)
            }
        }
        InsetDivider(14.dp)
        InfoRow(DevicesCopy.TYPE, kind.label)
        InsetDivider(14.dp)
        InfoRow(DevicesCopy.LINKED, formatDate(device.createdAt))
        InsetDivider(14.dp)
        InfoRow(
            DevicesCopy.LAST_ACTIVE,
            when {
                detail.isCurrent -> DevicesCopy.NOW
                else -> device.lastSeenAt?.let(formatDate) ?: DevicesCopy.NEVER
            },
        )
        InsetDivider(14.dp)
        // Device ID → copy (`:660-682`).
        Row(
            Modifier
                .fillMaxWidth()
                .highlightRow(onClick = onCopyId)
                .clearAndSetSemantics { contentDescription = DevicesCopy.COPY_DEVICE_ID }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShroudText(DevicesCopy.DEVICE_ID, inter(16f), colors.textPrimary, Modifier.alignByBaseline())
            ShroudText(
                Ids.wire(device.id),
                inter(12f, monospaced = true),
                colors.textSecondary,
                Modifier
                    .weight(1f)
                    .alignByBaseline(),
                textAlign = TextAlign.End,
            )
            ShroudIcon(ShroudIcons.CopyRegular, colors.accent, Modifier.align(Alignment.CenterVertically), size = 13.dp)
        }
    }

    if (renameError != null) {
        ShroudText(
            renameError,
            inter(13f),
            colors.danger,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        )
    }

    if (detail.isCurrent) {
        ShroudText(
            DevicesCopy.currentDeviceNote(noun),
            inter(13f),
            colors.textSecondary,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        )
    } else {
        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = onRevoke, enabled = !detail.isRevoking)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (detail.isRevoking) Spinner(colors.danger, size = 16.dp)
                ShroudText(
                    if (detail.isRevoking) DevicesCopy.REMOVING else DevicesCopy.REMOVE_DEVICE,
                    inter(16f, FontWeight.SemiBold),
                    colors.danger,
                )
            }
        }
    }
}

/** A fact: title 16, value 15 end-aligned and wrapping, one TalkBack node (`infoRow`, `DevicesView.swift:751-765`). */
@Composable
private fun InfoRow(title: String, value: String) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShroudText(title, inter(16f), colors.textPrimary, Modifier.alignByBaseline())
        ShroudText(
            value,
            inter(15f),
            colors.textSecondary,
            Modifier
                .weight(1f)
                .alignByBaseline(),
            textAlign = TextAlign.End,
        )
    }
}

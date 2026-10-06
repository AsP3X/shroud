package de.corespace.shroud.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.ui.components.AlertButton
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.ShroudAlertDialog
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.settings.SelectionCheck
import de.corespace.shroud.ui.settings.devices.DeviceTile
import de.corespace.shroud.ui.settings.devices.DevicesCopy
import de.corespace.shroud.ui.settings.devices.rememberDeviceTimeLabel
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * "Log out your oldest device?" — Log In's question once a full account's phrase checked out
 * ([PendingDeviceLimit]), and "Choose a device to log out", the picker it opens.
 *
 * Human: Every device slot is signed in, so logging in here means logging one out. The question
 * shows which, like a row of Settings › Devices: its own tile, its name (opened with the phrase just
 * typed) and "Last active … · Linked …". It offers the least recently used device; "Choose Another
 * Device" opens a list of all of them, oldest first, to pick a different one — a tap picks it and
 * returns to the question, now "Log out this device?". "Log Out and Continue" logs it out and logs
 * in here; Cancel leaves the phrase as typed. It is asked over the phrase step, after the phrase
 * matched the account's published key, so a full account never costs a device unless both
 * password and phrase were right.
 *
 * Agent: [limit] non-null shows the question, or the picker while `choosing`; the last one stays on
 * screen while it animates out, and one swaps for the other in place. [busy] turns the red button
 * into "Logging Out…" with a spinner and holds the question (no Back, dim, Cancel or Choose Another
 * Device) until the retry ends. Buttons are stacked: "Log Out and Continue" doesn't fit half a 272 dp row.
 */
@Composable
internal fun DeviceLimitDialog(
    limit: PendingDeviceLimit?,
    busy: Boolean,
    clock: AppClock,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
    onPick: (UUID) -> Unit,
    onPickerCancel: () -> Unit,
) {
    val timeLabel = rememberDeviceTimeLabel(clock)
    val selected = limit?.selected
    ShroudAlertDialog(
        visible = limit != null && !limit.choosing,
        title = if (limit?.selectedIsOldest != false) DeviceLimitCopy.TITLE else DeviceLimitCopy.TITLE_OTHER,
        message = DeviceLimitCopy.MESSAGE,
        primary = AlertButton(
            title = if (busy) DeviceLimitCopy.CONFIRMING else DeviceLimitCopy.CONFIRM,
            destructive = true,
            closesAlert = false,
            isLoading = busy,
            onClick = onConfirm,
        ),
        onDismiss = onCancel,
        cancelTitle = DeviceLimitCopy.CANCEL,
        stackedButtons = true,
        body = selected?.let { device ->
            {
                DeviceLimitBody(
                    device = device,
                    status = DeviceLimitCopy.status(device.lastSeenAt, device.createdAt, timeLabel, ::longDate),
                    canChoose = limit.canChoose,
                    busy = busy,
                    onChooseAnother = onChooseAnother,
                )
            }
        },
    )
    ShroudAlertDialog(
        visible = limit != null && limit.choosing,
        title = DeviceLimitCopy.PICKER_TITLE,
        message = DeviceLimitCopy.PICKER_MESSAGE,
        primary = null,
        onDismiss = onPickerCancel,
        cancelTitle = DeviceLimitCopy.CANCEL,
        body = limit?.let { shown ->
            {
                DevicePickerList(
                    rows = shown.rows,
                    selectedId = shown.selectedId,
                    lastActive = { DeviceLimitCopy.lastActive(it.lastSeenAt, timeLabel) },
                    onPick = onPick,
                )
            }
        },
    )
}

/** The inset card's fill: the dialog's text field's (`backgroundGrouped`, `background` in dark). */
@Composable
private fun cardFill() = if (ShroudTheme.colors.isDark) ShroudTheme.colors.background else ShroudTheme.colors.backgroundGrouped

/**
 * The device card, "Choose Another Device" and the note. The card is a Devices row (`DeviceListRow`):
 * padding 14 start / end, 10 top / bottom, the device's 30 dp tile 12 before two lines 2 apart — the
 * name 16 `textPrimary`, the status 13 `textSecondary` (up to two lines); radius 14 like a settings
 * card. "Choose Another Device" is 15 SemiBold `accentText`, centred, a 44 dp target 2 under the
 * card, 0.45 while busy. The note is 12 `textSecondary`, centred, 10 under that.
 */
@Composable
private fun DeviceLimitBody(device: DeviceRow, status: String, canChoose: Boolean, busy: Boolean, onChooseAnother: () -> Unit) {
    val colors = ShroudTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cardFill())
                    .semantics(mergeDescendants = true) {}
                    .testTag("login.limitDevice")
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceTile(device.kind, 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShroudText(DevicesCopy.displayName(device), inter(16f), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ShroudText(status, inter(13f), colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (canChoose) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (busy) DISABLED_ALPHA else 1f)
                        .heightIn(min = 44.dp)
                        .pressable(enabled = !busy, onClick = onChooseAnother, role = Role.Button),
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudText(DeviceLimitCopy.CHOOSE_ANOTHER, inter(15f, FontWeight.SemiBold), colors.accentText, textAlign = TextAlign.Center)
                }
            }
        }
        ShroudText(DeviceLimitCopy.NOTE, inter(12f), colors.textSecondary, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

/**
 * The picker's list: one inset card (fill and radius as the question's), a Devices row per device in
 * the server's order — padding 14 / 10, 30 dp tile 12 before the lines (2 apart): the name 16
 * `textPrimary` with "Oldest" 13 `textSecondary` 6 after it on the first row, "Last active …" 13
 * `textSecondary`; the selected row's check (`check-bold` 14 `accent`, as Appearance) at the end.
 * Separators inset 56 like the Devices list. Each row is a button that says the name, its last
 * activity and "selected"; the press lands `rowPressed`.
 */
@Composable
private fun DevicePickerList(rows: List<DeviceRow>, selectedId: UUID, lastActive: (DeviceRow) -> String, onPick: (UUID) -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardFill()),
    ) {
        rows.forEachIndexed { index, device ->
            val name = DevicesCopy.displayName(device)
            val status = lastActive(device)
            val oldest = index == 0
            val isSelected = device.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = { onPick(device.id) }, fill = colors.rowPressed)
                    .semantics(mergeDescendants = true) {
                        contentDescription = DeviceLimitCopy.pickerLabel(name, oldest, status)
                        selected = isSelected
                    }
                    .testTag("login.pickDevice$index")
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceTile(device.kind, 30.dp)
                Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShroudText(name, inter(16f), colors.textPrimary, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (oldest) ShroudText(DeviceLimitCopy.OLDEST_TAG, inter(13f), colors.textSecondary, maxLines = 1)
                    }
                    ShroudText(status, inter(13f), colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SelectionCheck(isSelected)
            }
            if (index < rows.lastIndex) InsetDivider(56.dp)
        }
    }
}

/** The dialogs' words; "Last active …" is the Devices list's own ([DevicesCopy.lastActiveAt]). */
internal object DeviceLimitCopy {
    const val TITLE = "Log out your oldest device?"
    const val TITLE_OTHER = "Log out this device?"
    const val MESSAGE =
        "Your account is logged in on $DEVICE_LIMIT devices, the most it can have. To log in here, Shroud logs out:"
    const val NOTE =
        "It’s logged out right away and erases everything of your account on it: messages, keys and files. What it already sent stays in your chats."
    const val CHOOSE_ANOTHER = "Choose Another Device"
    const val CONFIRM = "Log Out and Continue"
    const val CONFIRMING = "Logging Out…"
    const val CANCEL = "Cancel"
    const val NEVER_ACTIVE = "Never active"
    const val PICKER_TITLE = "Choose a device to log out"
    const val PICKER_MESSAGE = "Least recently used first."
    const val OLDEST_TAG = "Oldest"

    /** "Last active 9:37" as in Settings › Devices, or "Never active" for a device that never was. */
    fun lastActive(lastSeenAt: Instant?, timeLabel: (Instant) -> String): String =
        lastSeenAt?.let { DevicesCopy.lastActiveAt(it, timeLabel) } ?: NEVER_ACTIVE

    /** "Linked 12 March 2025": the date alone. */
    fun linked(createdAt: Instant, formatDate: (Instant) -> String): String = "${DevicesCopy.LINKED} ${formatDate(createdAt)}"

    /** The card's second line: "Last active 9:37 · Linked 12 March 2025". */
    fun status(lastSeenAt: Instant?, createdAt: Instant, timeLabel: (Instant) -> String, formatDate: (Instant) -> String): String =
        "${lastActive(lastSeenAt, timeLabel)} · ${linked(createdAt, formatDate)}"

    /** TalkBack on a picker row: "Pixel 9, Oldest, Last active Yesterday". */
    fun pickerLabel(name: String, oldest: Boolean, lastActive: String): String =
        listOfNotNull(name, OLDEST_TAG.takeIf { oldest }, lastActive).joinToString(", ")
}

/** "Choose Another Device" while the retry runs (settings-lock §2.4's disabled alpha). */
private const val DISABLED_ALPHA = 0.45f

/** ICU long date, no time ("12 March 2025", "March 12, 2025"), in the default locale. */
private fun longDate(instant: Instant): String =
    android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.LONG).format(Date.from(instant))

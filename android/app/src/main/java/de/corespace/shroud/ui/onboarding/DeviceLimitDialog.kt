package de.corespace.shroud.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.core.net.OldestDeviceDto
import de.corespace.shroud.ui.components.AlertButton
import de.corespace.shroud.ui.components.ShroudAlertDialog
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.settings.devices.DeviceTile
import de.corespace.shroud.ui.settings.devices.DevicesCopy
import de.corespace.shroud.ui.settings.devices.rememberDeviceTimeLabel
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.time.Instant
import java.util.Date

/**
 * "Log out your oldest device?" — Log In's answer to a `409 DEVICE_LIMIT` that named the account's
 * least recently active device ([OldestDeviceDto], server `error.rs`).
 *
 * Human: Every device slot is signed in, so logging in here means logging one out. The dialog
 * shows which one by its dates — its name is sealed with the 12-word phrase this phone doesn't
 * have yet — as one row like Settings › Devices: the grey device tile, "Last active …" (or "Never
 * active") and "Linked <date>". "Log Out and Continue" logs it out and logs in here.
 *
 * It is asked over the phrase step, once the phrase matched the account's published key, so a full
 * account never costs a device unless both password and phrase were right. Cancel leaves the phrase
 * as typed.
 *
 * Agent: [device] non-null shows the dialog; the last one stays on screen while it animates out.
 * [busy] turns the red button into "Logging Out…" with a spinner and holds the dialog (no Back, dim
 * or Cancel) until the retry ends; the caller swaps in a new [device] when the server answers with
 * another one. Buttons are stacked: "Log Out and Continue" doesn't fit half a 272 dp row.
 */
@Composable
internal fun DeviceLimitDialog(
    device: OldestDeviceDto?,
    busy: Boolean,
    clock: AppClock,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val timeLabel = rememberDeviceTimeLabel(clock)
    ShroudAlertDialog(
        visible = device != null,
        title = DeviceLimitCopy.TITLE,
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
        body = device?.let { shown ->
            {
                DeviceLimitBody(
                    lastActive = DeviceLimitCopy.lastActive(shown.lastSeenAt, timeLabel),
                    linked = DeviceLimitCopy.linked(shown.createdAt, ::longDate),
                )
            }
        },
    )
}

/**
 * The device card and the note under it. The card is a Devices row (`DeviceListRow`): padding 14
 * start / end, 10 top / bottom, the 30 dp tile 12 before two lines 2 apart — 16 `textPrimary`, 13
 * `textSecondary`; radius 14 like a settings card, filled like the dialog's text field
 * (`backgroundGrouped`, `background` in dark). The note is 12 `textSecondary`, centred, 10 under it.
 */
@Composable
private fun DeviceLimitBody(lastActive: String, linked: String) {
    val colors = ShroudTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (colors.isDark) colors.background else colors.backgroundGrouped)
                .semantics(mergeDescendants = true) {}
                .testTag("login.oldestDevice")
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceTile(DeviceKind.Unknown, 30.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText(lastActive, inter(16f), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ShroudText(linked, inter(13f), colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ShroudText(DeviceLimitCopy.NOTE, inter(12f), colors.textSecondary, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

/** The dialog's words; "Last active …" is the Devices list's own ([DevicesCopy.lastActiveAt]). */
internal object DeviceLimitCopy {
    const val TITLE = "Log out your oldest device?"
    const val MESSAGE =
        "Your account is logged in on $DEVICE_LIMIT devices, the most it can have. To log in here, Shroud logs out the one you used least recently:"
    const val NOTE =
        "It’s logged out right away and erases everything of your account on it: messages, keys and files. What it already sent stays in your chats."
    const val CONFIRM = "Log Out and Continue"
    const val CONFIRMING = "Logging Out…"
    const val CANCEL = "Cancel"
    const val NEVER_ACTIVE = "Never active"

    /** "Last active 9:37" as in Settings › Devices, or "Never active" for a device that never was. */
    fun lastActive(lastSeenAt: Instant?, timeLabel: (Instant) -> String): String =
        lastSeenAt?.let { DevicesCopy.lastActiveAt(it, timeLabel) } ?: NEVER_ACTIVE

    /** "Linked 12 March 2025": the date alone. */
    fun linked(createdAt: Instant, formatDate: (Instant) -> String): String = "${DevicesCopy.LINKED} ${formatDate(createdAt)}"
}

/** ICU long date, no time ("12 March 2025", "March 12, 2025"), in the default locale. */
private fun longDate(instant: Instant): String =
    android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.LONG).format(Date.from(instant))

package de.corespace.shroud.ui.settings.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.settings.privacy.DeleteAccountActions
import de.corespace.shroud.ui.settings.privacy.PasswordCard
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** The words a confirmation shows, kept while the sheet animates out. */
internal data class RemoveDeviceSheetText(
    val title: String,
    val message: String,
    val actionTitle: String,
)

/** The words for [target] ("Remove Pixel 9a?" and the one-device consequences, or "Remove all other devices?"). */
internal fun removeDeviceSheetText(target: RemovalTarget, state: DevicesUiState, noun: String): RemoveDeviceSheetText =
    when (target) {
        is RemovalTarget.One -> RemoveDeviceSheetText(
            title = DevicesCopy.confirmTitle(state.displayName(target.row)),
            message = DevicesCopy.CONFIRM_MESSAGE_ONE,
            actionTitle = DevicesCopy.REMOVE,
        )
        RemovalTarget.AllOthers -> RemoveDeviceSheetText(
            title = DevicesCopy.CONFIRM_ALL_TITLE,
            message = DevicesCopy.confirmAllMessage(noun),
            actionTitle = DevicesCopy.confirmAllButton(state.others.size),
        )
    }

/** The last shown words (not state: written while the sheet is up only). */
private class ShownSheetText {
    var value: RemoveDeviceSheetText? = null
}

/**
 * Asks for the account password before removing one device or all the others (security audit L4).
 * Was an action sheet, which closes before its action runs, so a wrong password could not stay on
 * screen. The floating inset sheet of Device Details, holding Delete Account's parts: the title,
 * the consequences, "Enter your password to confirm.", the password card ([PasswordCard]) with the
 * red line under it, then the danger capsule ("Remove" / "Remove 3", "Removing…" with a spinner
 * while it runs; pale and disabled until a password is typed) over Cancel ([DeleteAccountActions]).
 *
 * [text] null hides the sheet. While [submitting], Cancel, Back, the scrim and a drag do nothing
 * ([onDismiss] is the caller's, which ignores them then).
 */
@Composable
internal fun RemoveDeviceSheet(
    text: RemoveDeviceSheetText?,
    password: TextFieldValue,
    onPasswordChange: (TextFieldValue) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    error: String?,
    submitting: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = remember { ShownSheetText() }
    if (text != null) shown.value = text
    ShroudSheet(
        visible = text != null,
        onDismiss = onDismiss,
        style = SheetStyle.Inset,
        paneTitle = shown.value?.title ?: DevicesCopy.REMOVE_DEVICE,
    ) {
        val words = shown.value ?: return@ShroudSheet
        val colors = ShroudTheme.colors
        ShroudText(
            words.title,
            inter(22f, FontWeight.Bold),
            colors.textPrimary,
            Modifier
                .padding(top = 16.dp)
                .semantics { heading() },
        )
        ShroudText(words.message, inter(15f), colors.textSecondary)
        ShroudText(DevicesCopy.CONFIRM_PASSWORD, inter(15f), colors.textSecondary)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PasswordCard(password, onPasswordChange, revealed, onToggleReveal, submitting)
            if (error != null) {
                ShroudText(error, inter(13f), colors.danger, Modifier.padding(horizontal = 4.dp))
            }
        }
        DeleteAccountActions(
            submitting = submitting,
            canSubmit = canSubmit,
            onDelete = onSubmit,
            onCancel = onDismiss,
            modifier = Modifier.padding(top = 4.dp),
            actionTitle = words.actionTitle,
            busyTitle = DevicesCopy.REMOVING,
        )
    }
}

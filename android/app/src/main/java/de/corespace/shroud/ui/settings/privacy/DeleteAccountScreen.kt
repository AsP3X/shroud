package de.corespace.shroud.ui.settings.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.Haptic
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SecretKeyboard
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.shell.LocalPushedBackGate
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** Account-deletion plan §3.3, byte for byte. */
internal object DeleteAccountCopy {
    const val ROW_TITLE = "Delete Account"
    const val ROW_SUBTITLE = "Deletes your account and erases it from every device."
    const val NAV_TITLE = "Delete Account"
    const val TITLE = "Delete your account?"
    val CONSEQUENCES = listOf(
        "Your messages are replaced with “Message deleted” for everyone.",
        "Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages.",
        "Your contacts, Saved Messages, photos, files and call history are deleted.",
        "Your username and share code are released, so someone else can take them.",
        "Every device signed in to this account is signed out and erased.",
    )
    const val CONFIRM = "This can't be undone. Enter your password to confirm."
    const val PASSWORD_LABEL = "Password"
    const val PASSWORD_PLACEHOLDER = "Your account password"
    const val DELETE = "Delete Account"
    const val DELETING = "Deleting…"
    const val CANCEL = "Cancel"
    const val WRONG_PASSWORD = "That password isn't right."
    const val RATE_LIMITED = "Too many tries. Try again later."
    const val UNREACHABLE = "Couldn't reach the server. Your account wasn't deleted."
}

private val consequenceIcons = listOf(
    ShroudIcons.MessageSquareX,
    ShroudIcons.Users,
    ShroudIcons.Trash,
    ShroudIcons.AtSign,
    ShroudIcons.Smartphone,
)

/**
 * Settings › Privacy and Security › Delete Account. While the request runs, system back, the
 * predictive-back pop, the back circle and Cancel do nothing.
 */
@Composable
fun DeleteAccountScreen(onBack: () -> Unit) {
    val deleter = LocalAppContainer.current.auth.accountDeletion
    val scope = rememberCoroutineScope()
    val model = remember(deleter) { DeleteAccountModel(deleter, scope) }
    val submitting = model.submitting
    val backGate = LocalPushedBackGate.current
    SideEffect { backGate.setEnabled(!submitting) }
    DisposableEffect(backGate, model) {
        onDispose {
            backGate.setEnabled(true)
            model.clear()
        }
    }
    PushedScreen(
        title = DeleteAccountCopy.NAV_TITLE,
        onBack = { if (!model.submitting) onBack() },
        backEnabled = !submitting,
        bottomBar = {
            DeleteAccountActions(
                submitting = submitting,
                canSubmit = model.canSubmit,
                onDelete = model::submit,
                onCancel = { if (!model.submitting) onBack() },
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp),
            )
        },
    ) {
        DeleteAccountForm(
            password = model.password,
            onPasswordChange = model::onPasswordChange,
            revealed = model.revealed,
            onToggleReveal = model::toggleRevealed,
            error = model.error?.text,
            submitting = submitting,
        )
    }
}

/** The scrollable warning, for the screen and for tests that drive a state without a server. */
@Composable
internal fun DeleteAccountForm(
    password: TextFieldValue,
    onPasswordChange: (TextFieldValue) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    error: String?,
    submitting: Boolean,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShroudText(
            DeleteAccountCopy.TITLE,
            inter(26f, FontWeight.Bold),
            colors.textPrimary,
            Modifier.semantics { heading() },
        )
        SettingsCard {
            DeleteAccountCopy.CONSEQUENCES.forEachIndexed { index, line ->
                if (index > 0) InsetDivider(44.dp)
                ConsequenceRow(consequenceIcons[index], line)
            }
        }
        ShroudText(DeleteAccountCopy.CONFIRM, inter(15f), colors.textSecondary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PasswordCard(password, onPasswordChange, revealed, onToggleReveal, submitting)
            if (error != null) {
                ShroudText(error, inter(13f), colors.danger, Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/** Form plus the pinned actions, so a test can show every §3.2 state in one column. */
@Composable
internal fun DeleteAccountContent(
    password: TextFieldValue,
    onPasswordChange: (TextFieldValue) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    error: String?,
    submitting: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        DeleteAccountForm(password, onPasswordChange, revealed, onToggleReveal, error, submitting)
        DeleteAccountActions(
            submitting = submitting,
            canSubmit = password.text.isNotEmpty() && !submitting,
            onDelete = onDelete,
            onCancel = onCancel,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ConsequenceRow(icon: ImageVector, line: String) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ShroudIcon(icon, colors.danger, Modifier.padding(top = 2.dp), size = 18.dp)
        ShroudText(line, inter(16f), colors.textPrimary, Modifier.weight(1f))
    }
}

@Composable
private fun PasswordCard(
    password: TextFieldValue,
    onPasswordChange: (TextFieldValue) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    submitting: Boolean,
) {
    val colors = ShroudTheme.colors
    val focus = remember { FocusRequester() }
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .alpha(if (submitting) 0.45f else 1f)
                .pointerInput(focus, submitting) {
                    detectTapGestures { if (!submitting) focus.requestFocus() }
                }
                .padding(start = 14.dp, end = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudText(DeleteAccountCopy.PASSWORD_LABEL, inter(16f), colors.textPrimary)
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                NoLearningTextInput {
                    BasicTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .contentType(ContentType.Password)
                            .semantics { contentDescription = DeleteAccountCopy.PASSWORD_PLACEHOLDER },
                        enabled = !submitting,
                        singleLine = true,
                        textStyle = inter(16f).copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = SecretKeyboard,
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation('•'),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (password.text.isEmpty()) {
                                    ShroudText(
                                        DeleteAccountCopy.PASSWORD_PLACEHOLDER,
                                        inter(16f),
                                        colors.textSecondary.copy(alpha = 0.8f),
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            Box(
                Modifier
                    .pressable(
                        enabled = !submitting,
                        scale = 0.88f,
                        onClick = { if (!submitting) onToggleReveal() },
                        onClickLabel = if (revealed) "Hide password" else "Show password",
                    )
                    .size(44.dp)
                    .semantics { contentDescription = if (revealed) "Hide password" else "Show password" },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(
                    if (revealed) ShroudIcons.Eye else ShroudIcons.EyeOff,
                    colors.textSecondary,
                    size = 18.dp,
                )
            }
        }
    }
}

@Composable
internal fun DeleteAccountActions(
    submitting: Boolean,
    canSubmit: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val title = if (submitting) DeleteAccountCopy.DELETING else DeleteAccountCopy.DELETE
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (canSubmit || submitting) 1f else 0.45f)
                .pressable(
                    enabled = canSubmit,
                    scale = 0.975f,
                    dimming = 0.05f,
                    haptic = Haptic.Medium,
                    onClick = { if (canSubmit) onDelete() },
                )
                .dropShadow(
                    CircleShape,
                    Shadow(radius = 20.dp, color = colors.danger, offset = DpOffset(0.dp, 8.dp), alpha = 0.25f),
                )
                .height(54.dp)
                .clip(CircleShape)
                .background(colors.danger)
                .semantics(mergeDescendants = true) { contentDescription = title },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (submitting) Spinner(Color.White, size = 18.dp)
            ShroudText(title, inter(17f, FontWeight.SemiBold), Color.White, maxLines = 1)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .alpha(if (submitting) 0.4f else 1f)
                .pressable(enabled = !submitting, onClick = { if (!submitting) onCancel() })
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            ShroudText(DeleteAccountCopy.CANCEL, inter(17f), colors.accent)
        }
    }
}

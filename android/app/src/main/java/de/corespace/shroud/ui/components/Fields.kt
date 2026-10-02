package de.corespace.shroud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** Keyboard for secrets: no suggestions and no learning (Gboard treats it as incognito). */
val SecretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/**
 * A plain single-line field with a grey placeholder.
 *
 * [contentType] is its autofill hint (iOS `textContentType`: `.username`, `.password`,
 * `.newPassword`; settings-lock addendum SignUp S3, LogIn L7) — the password manager fills it and
 * offers to save after the screen commits. [noPersonalizedLearning] keeps what is typed out of the
 * keyboard's learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`, P5 decided: phrase fields).
 */
@Composable
fun ShroudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = inter(16f),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    contentType: ContentType? = null,
    noPersonalizedLearning: Boolean = false,
) {
    val colors = ShroudTheme.colors
    val field: @Composable () -> Unit = {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .let { if (contentType != null) it.contentType(contentType) else it }
                .semantics { contentDescription = placeholder },
            enabled = enabled,
            singleLine = true,
            textStyle = style.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) ShroudText(placeholder, style, colors.textSecondary.copy(alpha = 0.8f), maxLines = 1)
                    inner()
                }
            },
        )
    }
    if (noPersonalizedLearning) NoLearningTextInput(field) else field()
}

/**
 * A row of a credentials card: 50 high, 18 dp icon, 16 sp field (`credentialRow`,
 * `SignUpView.swift:272-297`, `LogInFlowView.swift:620-666`). With [isSecret] it hides the text and
 * shows the eye toggle when [onToggleVisible] is given. A tap anywhere on the row focuses its field
 * (`LogInFlowView.swift:664-665`, addendum LogIn L4); the eye keeps its own tap. [contentType] is the
 * field's autofill hint (S3, L7).
 */
@Composable
fun CredentialRow(
    icon: ImageVector,
    iconTint: Color,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
    revealed: Boolean = false,
    onToggleVisible: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    contentType: ContentType? = null,
) {
    val colors = ShroudTheme.colors
    val focus = focusRequester ?: remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            // A plain tap detector, not `clickable`: no extra TalkBack node over the field.
            .pointerInput(focus) { detectTapGestures { focus.requestFocus() } }
            .padding(start = 14.dp, end = if (onToggleVisible != null) 3.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(icon, iconTint, size = 18.dp)
        ShroudTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            visualTransformation = if (isSecret && !revealed) PasswordVisualTransformation('•') else VisualTransformation.None,
            focusRequester = focus,
            contentType = contentType,
        )
        if (onToggleVisible != null) {
            Box(
                Modifier
                    .pressable(scale = 0.88f, onClick = onToggleVisible, onClickLabel = if (revealed) "Hide password" else "Show password")
                    .size(44.dp)
                    .semantics { contentDescription = if (revealed) "Hide password" else "Show password" },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(if (revealed) ShroudIcons.Eye else ShroudIcons.EyeOff, colors.textSecondary, size = 18.dp)
            }
        }
    }
}

/**
 * A labelled field of the Server sheet (`field(title:…)`, `ServerSettingsSheet.swift:370-399`):
 * 13 medium label over a 48 high grouped box. [onImeAction] runs on the keyboard's action key
 * (the last field's Done drops the keyboard, addendum ServerSheet V6).
 */
@Composable
fun LabeledField(
    title: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    onImeAction: (() -> Unit)? = null,
) {
    val colors = ShroudTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShroudText(title, inter(13f, FontWeight.Medium), colors.textPrimary)
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.backgroundGrouped)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(icon, colors.accent, size = 16.dp)
            ShroudTextField(
                value,
                onValueChange,
                title,
                Modifier.weight(1f),
                style = inter(15f, FontWeight.Medium),
                keyboardOptions = keyboardOptions,
                keyboardActions = if (onImeAction != null) KeyboardActions(onAny = { onImeAction() }) else KeyboardActions.Default,
            )
        }
    }
}

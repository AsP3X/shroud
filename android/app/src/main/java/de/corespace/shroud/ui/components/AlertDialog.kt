package de.corespace.shroud.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The text field of a [ShroudAlertDialog] (iOS `.alert` with a `TextField`, e.g. "Rename Device",
 * `DevicesView.swift:728-736`). The field starts from [value] when the dialog opens (cursor at the
 * end) and reports each edit through [onValueChange]. [capitalization] follows the iOS field
 * (`.textInputAutocapitalization(.words)` for names). [noPersonalizedLearning] keeps what is typed
 * out of the keyboard's learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`, plan workstream G) — on by
 * default: names typed here are as private as messages.
 */
data class AlertField(
    val value: String,
    val onValueChange: (String) -> Unit,
    val placeholder: String,
    val capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val noPersonalizedLearning: Boolean = true,
)

/**
 * The dialog's confirming button; [destructive] draws it in `danger`, [enabled] false dims it to
 * 0.45. [closesAlert] false leaves closing to [onClick] (the update offer stays up when its link
 * cannot open). [isLoading] puts a spinner before the title and holds the dialog: Back, the dim
 * and Cancel do nothing until it ends (the caller sets the busy title, "Logging Out…").
 */
data class AlertButton(
    val title: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val closesAlert: Boolean = true,
    val isLoading: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A centred dialog drawn by the app (iOS `.alert`; settings-lock §2.4). By decision C22 / P13c it
 * is only for input — destructive confirmations use [ActionSheet] — so its uses are "Rename Device"
 * (`DevicesView.swift:728-748`, settings-lock §4.6) and the root's "Update available" offer. The one
 * destructive exception is Log In's "Log out your oldest device?": it shows a device card ([body])
 * and stays up, busy, while its request runs, which an action sheet (closed before its action) can't.
 *
 * Human: A rounded card fades and grows in (0.94 → 1) over a dim: bold [title], grey [message],
 * the [field] (focused, keyboard up), then Cancel and the [primary] button side by side. Back, a
 * tap on the dim or Cancel close it; the keyboard's Done does what [primary] does while it is
 * enabled. A null [cancelTitle] leaves [primary] alone, full width (an "OK" notice). [body] sits
 * under the message; [stackedButtons] puts [primary] full width over Cancel, for titles too long to
 * share a row. A null [primary] leaves Cancel alone, full width (a list to pick from, whose rows act).
 *
 * Agent: [visible] is the caller's state; [onDismiss] asks to hide it. [primary] calls [onDismiss]
 * first, then its own `onClick` (an iOS alert button always closes the alert), unless its
 * `closesAlert` is false. The card is `min(320, width − 80)` wide and stays above the keyboard.
 * Drawn in the [OverlayHost] layer.
 */
@Composable
fun ShroudAlertDialog(
    visible: Boolean,
    title: String,
    message: String? = null,
    field: AlertField? = null,
    primary: AlertButton?,
    onDismiss: () -> Unit,
    cancelTitle: String? = "Cancel",
    stackedButtons: Boolean = false,
    body: (@Composable () -> Unit)? = null,
) {
    val visibility = rememberOverlayTransition(visible)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val shown = remember { ShownAlert() }
    if (visible) shown.content = AlertContent(title, message, field, primary, cancelTitle, stackedButtons, body)
    // Busy: every way out waits for the request (read when asked, so the newest state counts).
    val dismissIfIdle: () -> Unit = { if (visibility.targetState && shown.content?.primary?.isLoading != true) currentOnDismiss() }

    OverlayLayer(active = visibility.isOverlayUp, onDismissRequest = dismissIfIdle) {
        val content = shown.content ?: return@OverlayLayer
        val colors = ShroudTheme.colors
        val palette = ShroudTheme.colors
        val reduceMotion = ShroudTheme.reduceMotion
        val transition = rememberTransition(visibility, label = "alert")
        val back by rememberOverlayBack(enabled = visible, onBack = dismissIfIdle)
        val primary = content.primary
        val busy = primary?.isLoading == true
        val confirm: () -> Unit = {
            if (visibility.targetState && primary != null && primary.enabled && !busy) {
                if (primary.closesAlert) currentOnDismiss()
                primary.onClick()
            }
        }

        Box(Modifier.fillMaxSize().overlayPane(content.title, onDismiss = dismissIfIdle)) {
            transition.AnimatedVisibility(visible = { it }, enter = fadeIn(Motion.scrim()), exit = fadeOut(Motion.scrim())) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.sheetScrim)
                        .dismissOnTap(openedAt = 0L, onDismiss = dismissIfIdle),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = AlertMetrics.SideMargin, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                transition.AnimatedVisibility(visible = { it }, enter = alertEnter(reduceMotion), exit = alertExit(reduceMotion)) {
                    Column(
                        Modifier
                            .widthIn(max = AlertMetrics.MaxWidth)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val lean = backLeanScale(back)
                                scaleX = lean
                                scaleY = lean
                            }
                            .clip(RoundedCornerShape(AlertMetrics.Radius))
                            .background(if (colors.isDark) colors.bubbleIncoming else colors.background)
                            .verticalScroll(rememberScrollState())
                            .padding(AlertMetrics.Padding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ShroudText(
                            text = content.title,
                            style = inter(17f, FontWeight.SemiBold),
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (content.message != null) {
                            ShroudText(text = content.message, style = inter(13f), color = colors.textSecondary, textAlign = TextAlign.Center)
                        }
                        if (content.field != null) {
                            AlertTextField(content.field, onDone = confirm)
                        }
                        content.body?.invoke()
                        val primaryButton: @Composable (Modifier) -> Unit = { modifier ->
                            if (primary != null) {
                                AlertCapsule(
                                    title = primary.title,
                                    fill = if (primary.destructive) colors.danger else colors.accent,
                                    textColor = Color.White,
                                    enabled = primary.enabled,
                                    onClick = confirm,
                                    modifier = modifier,
                                    isLoading = busy,
                                )
                            }
                        }
                        val cancelButton: @Composable (Modifier) -> Unit = { modifier ->
                            if (content.cancelTitle != null) {
                                AlertCapsule(
                                    title = content.cancelTitle,
                                    fill = colors.accentSoft,
                                    textColor = colors.accentText,
                                    enabled = !busy,
                                    onClick = dismissIfIdle,
                                    modifier = modifier,
                                )
                            }
                        }
                        if (content.stackedButtons) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                primaryButton(Modifier.fillMaxWidth())
                                cancelButton(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cancelButton(Modifier.weight(1f))
                                primaryButton(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sizes of settings-lock §2.4. */
internal object AlertMetrics {
    val MaxWidth = 320.dp
    val SideMargin = 40.dp
    val Radius = 28.dp
    val Padding = 24.dp
    val ButtonHeight = 48.dp
    const val DISABLED_ALPHA = 0.45f

    /** The card's width in a window [windowWidth] wide: `min(320, width − 2·40)`. */
    fun cardWidth(windowWidth: Dp): Dp = min(MaxWidth, windowWidth - SideMargin * 2)
}

@Immutable
private data class AlertContent(
    val title: String,
    val message: String?,
    val field: AlertField?,
    val primary: AlertButton?,
    val cancelTitle: String?,
    val stackedButtons: Boolean,
    val body: (@Composable () -> Unit)?,
)

private class ShownAlert {
    var content: AlertContent? = null
}

private fun alertEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(Motion.reduced())
    } else {
        fadeIn(Motion.fade()) + scaleIn(Motion.snappy(), initialScale = 0.94f, transformOrigin = TransformOrigin.Center)
    }

private fun alertExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(Motion.reduced())
    } else {
        fadeOut(Motion.fade()) + scaleOut(Motion.snappy(), targetScale = 0.94f, transformOrigin = TransformOrigin.Center)
    }

/**
 * 48 high capsule, 17 SemiBold; disabled at 0.45 (settings-lock §2.4). Loading: an 18 dp spinner in
 * the text colour 8 before the title, full strength, not pressable.
 */
@Composable
private fun AlertCapsule(
    title: String,
    fill: Color,
    textColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    isLoading: Boolean = false,
) {
    Row(
        modifier
            .alpha(if (enabled || isLoading) 1f else AlertMetrics.DISABLED_ALPHA)
            .pressable(enabled = enabled && !isLoading, scale = 0.97f, onClick = onClick, role = Role.Button)
            .heightIn(min = AlertMetrics.ButtonHeight)
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) Spinner(textColor, size = 18.dp)
        ShroudText(text = title, style = inter(17f, FontWeight.SemiBold), color = textColor, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * The dialog's field: grouped fill, radius 12, 16 sp, focused when the dialog opens with the cursor
 * after the current text (iOS alert fields open that way), Done confirms.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AlertTextField(field: AlertField, onDone: () -> Unit) {
    val colors = ShroudTheme.colors
    val focus = remember { FocusRequester() }
    // The field owns the text while the dialog is open: it starts from `value` and reports every
    // edit. (Following `value` back in would fight the typing: the layer is redrawn one frame
    // after the caller, with the previous `value`.)
    var editing by remember { mutableStateOf(TextFieldValue(field.value, TextRange(field.value.length))) }
    val onValueChange by rememberUpdatedState(field.onValueChange)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val textField: @Composable () -> Unit = {
        BasicTextField(
            value = editing,
            onValueChange = {
                val changed = it.text != editing.text
                editing = it
                if (changed) onValueChange(it.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .semantics { contentDescription = field.placeholder },
            singleLine = true,
            textStyle = inter(16f).copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = field.capitalization,
                keyboardType = field.keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (colors.isDark) colors.background else colors.backgroundGrouped)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (editing.text.isEmpty()) {
                        ShroudText(field.placeholder, inter(16f), colors.textSecondary.copy(alpha = 0.8f), maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
    if (field.noPersonalizedLearning) {
        InterceptPlatformTextInput(
            interceptor = { request, next ->
                next.startInputMethod(
                    object : PlatformTextInputMethodRequest by request {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                            val connection = request.createInputConnection(outAttributes)
                            outAttributes.imeOptions = withoutPersonalizedLearning(outAttributes.imeOptions)
                            return connection
                        }
                    },
                )
            },
            content = textField,
        )
    } else {
        textField()
    }
}

/** Adds `IME_FLAG_NO_PERSONALIZED_LEARNING` to an `EditorInfo.imeOptions` value. */
internal fun withoutPersonalizedLearning(imeOptions: Int): Int = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

@Preview(name = "Rename dialog", widthDp = 412, heightDp = 915)
@Composable
private fun RenameDialogPreview() {
    ShroudTheme(dark = false) {
        Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundGrouped)) {
            ShroudAlertDialog(
                visible = true,
                title = "Rename Device",
                message = "The name is encrypted — only your devices can read it.",
                field = AlertField("Pixel 9", {}, "Name", capitalization = KeyboardCapitalization.Words),
                primary = AlertButton("Save") {},
                onDismiss = {},
            )
        }
    }
}

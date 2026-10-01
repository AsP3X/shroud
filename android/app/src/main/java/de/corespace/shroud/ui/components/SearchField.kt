package de.corespace.shroud.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The rounded header search of Chats and Contacts (`SearchField.swift:4-51`; shell-chats §10.7,
 * §4.3; design `Search` in `Header`): 36 dp (grows with the font scale), `backgroundGrouped`,
 * radius 10, padding h 10, spacing 6. Lucide `search` 16 dp turns `accent` while focused; text 15
 * `textPrimary`, [placeholder] in `textSecondary`; a clear button (Phosphor `x-circle-fill` 14 dp in
 * a 24 dp box, 44 dp to the finger, pressable 0.8, "Clear search", `Motion.iconSwap`) while there
 * is text; a decorative 1 dp `accent @ 0.35` focus ring. Focus and emptiness animate with
 * `Motion.snappy`.
 *
 * Keyboard: no capitalisation, no autocorrect, Search action hides the keyboard, and **no
 * personalised learning** ([NoLearningTextInput]): what someone searches for in their chats
 * must not train the keyboard (plan workstream G).
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val glyph by animateColorAsState(if (focused) colors.accent else colors.textSecondary, Motion.snappy(), label = "searchGlyph")
    val ring by animateColorAsState(colors.accent.copy(alpha = if (focused) 0.35f else 0f), Motion.snappy(), label = "searchRing")
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(colors.backgroundGrouped, shape)
            .border(1.dp, ring, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.Search, glyph, size = 16.dp)
        Box(Modifier.weight(1f)) {
            NoLearningTextInput {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textStyle = inter(15f).copy(color = colors.textPrimary),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    interactionSource = interaction,
                    keyboardOptions = SearchKeyboard.options,
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                ShroudText(placeholder, inter(15f), colors.textSecondary, maxLines = 1)
                            }
                            field()
                        }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = kitIconSwapIn(Motion.snappy()),
            exit = kitIconSwapOut(Motion.snappy()),
        ) {
            Box(
                Modifier
                    .hitArea(24, 44)
                    .pressable(scale = 0.8f, onClick = { onQueryChange("") })
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.XCircleFill, colors.textSecondary, size = 14.dp)
            }
        }
    }
}

/**
 * Lays out [visibleDp] square but takes touches over [touchDp], centred — iOS
 * `.contentShape(Rectangle().inset(by: -10))` (`SearchField.swift:31-32`): 44 dp to the finger
 * without moving the 24 dp glyph box.
 */
private fun Modifier.hitArea(visibleDp: Int, touchDp: Int): Modifier = layout { measurable, _ ->
    val touch = touchDp.dp.roundToPx()
    val visible = visibleDp.dp.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(touch, touch))
    layout(visible, visible) { placeable.place((visible - touch) / 2, (visible - touch) / 2) }
}

/** The keyboard both search fields ask for (shell-chats §4.3). */
object SearchKeyboard {
    val options = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Search,
    )

    /** Adds `IME_FLAG_NO_PERSONALIZED_LEARNING` to what the text field asked the keyboard for. */
    fun disablePersonalizedLearning(info: EditorInfo) {
        info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
}

/**
 * Wraps text fields whose input must not teach the keyboard (`IME_FLAG_NO_PERSONALIZED_LEARNING`),
 * through Compose's documented hook for editing `EditorInfo` (shell-chats §4.3). Used by
 * [SearchField] and the tab bar's search field (W3-SHELL).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoLearningTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val noLearning = object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    SearchKeyboard.disablePersonalizedLearning(outAttributes)
                    return connection
                }
            }
            nextHandler.startInputMethod(noLearning)
        },
        content = content,
    )
}

@Preview(name = "Search field", widthDp = 412)
@Composable
private fun SearchFieldPreview() {
    ShroudTheme(dark = false) {
        Column(
            Modifier.background(ShroudTheme.colors.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchField("", {})
            SearchField("jane", {})
        }
    }
}

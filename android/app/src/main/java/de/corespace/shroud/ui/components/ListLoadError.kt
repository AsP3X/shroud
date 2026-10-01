package de.corespace.shroud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.launch

/**
 * Stands in for a list's empty state when the **load** failed rather than the list being empty
 * (`ListLoadErrorView.swift:8-66`; shell-chats §10.8; design `Calls — Error` `w4INX`): "No chats
 * yet" is a lie when the server is unreachable — say what went wrong and offer a way to try again.
 *
 * `Column(spacing 10)` centred, padding top 48, h 24: Phosphor `cell-signal-slash-bold` 32 dp
 * `danger @ 0.85` (decorative, 6 below); [title] 16 SemiBold, a heading; [message] 14
 * `textSecondary` centred (another 24 each side); a 44 dp "Try Again" (16 SemiBold `accent`,
 * pressable 0.94) that runs [onRetry] in this composable's scope, showing a small spinner and
 * "Retrying…" meanwhile and refusing a second tap. TalkBack: "Try again" / "Retrying".
 * Callers show and hide it with [ListStateTransitions] (fade + 8 dp rise).
 */
@Composable
fun ListLoadError(title: String, message: String, onRetry: suspend () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 48.dp)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShroudIcon(
            ShroudIcons.CellSignalSlashBold,
            colors.danger.copy(alpha = 0.85f),
            Modifier.padding(bottom = 6.dp).clearAndSetSemantics {},
            size = 32.dp,
        )
        ShroudText(title, inter(16f, FontWeight.SemiBold), colors.textPrimary, Modifier.semantics { heading() }, textAlign = TextAlign.Center)
        ShroudText(message, inter(14f), colors.textSecondary, Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .pressable(
                    enabled = !retrying,
                    scale = 0.94f,
                    onClick = {
                        if (!retrying) {
                            retrying = true
                            scope.launch {
                                try {
                                    onRetry()
                                } finally {
                                    retrying = false
                                }
                            }
                        }
                    },
                )
                .clearAndSetSemantics { contentDescription = ListLoadErrorCopy.buttonLabel(retrying) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(retrying) { Spinner(colors.accent, size = 16.dp) }
            ShroudText(ListLoadErrorCopy.buttonTitle(retrying), inter(16f, FontWeight.SemiBold), colors.accent, maxLines = 1)
        }
    }
}

/** The retry button's words (`ListLoadErrorView.swift:49, 59`). */
object ListLoadErrorCopy {
    fun buttonTitle(retrying: Boolean): String = if (retrying) "Retrying…" else "Try Again"

    fun buttonLabel(retrying: Boolean): String = if (retrying) "Retrying" else "Try again"
}

/**
 * How list states (load error, empty state) come and go: fade plus an 8 dp offset, iOS
 * `.transition(.opacity.combined(with: .offset(y: 8)))` (`ListLoadErrorView.swift:64`,
 * `ChatsView.swift:405`), `Motion.fade()`.
 */
object ListStateTransitions {
    @Composable
    fun enter(): EnterTransition {
        val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
        return fadeIn(Motion.fade()) + slideInVertically(Motion.fade()) { offset }
    }

    @Composable
    fun exit(): ExitTransition {
        val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
        return fadeOut(Motion.fade()) + slideOutVertically(Motion.fade()) { offset }
    }
}

@Preview(name = "List load error", widthDp = 412)
@Composable
private fun ListLoadErrorPreview() {
    ShroudTheme(dark = false) {
        ListLoadError("Can't load calls", "Could not connect to the server.", {}, Modifier.background(ShroudTheme.colors.background))
    }
}

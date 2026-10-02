package de.corespace.shroud.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.theme.ShroudTheme
import java.util.UUID

/**
 * The conversation screen's bottom as `ConversationContent` lays it out (C9): the chat background,
 * [ConversationComposeHost] at the bottom outside any inset padding, and the chat's toasts
 * [composerToastInset] above it. [keyboard] paints the keyboard's area (renders of tBB5Y only).
 */
@Composable
internal fun ComposerStage(
    controller: ComposeController,
    toasts: ToastState,
    onHeight: (Dp) -> Unit = {},
    keyboard: Boolean = false,
) {
    var height by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundChat)) {
        if (keyboard) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.ime)
                    .background(if (ShroudTheme.colors.isDark) Color(0xFF202124) else Color(0xFFE8EAED)),
            )
        }
        ConversationComposeHost(
            controller = controller,
            onComposerHeightChanged = {
                height = it
                onHeight(it)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        val systemBottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
        ToastHost(toasts, bottomInset = composerToastInset(height, systemBottom, controller.coversComposer))
    }
}

/** [ComposeHost] that shows the composer's toasts on the stage and records the rest. */
internal class StageComposeHost(val toasts: ToastState = ToastState()) : ComposeHost {
    var pins = 0
    val jumps = ArrayList<UUID>()
    override var isShowingMessageMenu: Boolean = false

    override fun pinToBottom() {
        pins++
    }

    override fun jumpToQuoted(messageId: UUID) {
        jumps += messageId
    }

    override fun showToast(toast: Toast) = toasts.show(toast)

    override fun requestDelete(message: ChatMessage) = Unit
}

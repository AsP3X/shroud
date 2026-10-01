package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.MessageRowModel

/**
 * One message bubble: text, links, reply quote, media, voice, to-do, reactions and its footer
 * (conversation-thread §3–§14; iOS `MessageBubble`).
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-THREAD-BUBBLES**, which replaces the
 * body. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MessageBubble(row: MessageRowModel, context: BubbleContext, modifier: Modifier = Modifier) {
}

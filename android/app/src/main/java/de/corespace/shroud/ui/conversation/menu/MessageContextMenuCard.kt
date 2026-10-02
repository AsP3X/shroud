package de.corespace.shroud.ui.conversation.menu

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.ui.components.ContextMenuCardSurface
import de.corespace.shroud.ui.components.ContextMenuItem
import de.corespace.shroud.ui.components.ContextMenuSeparator
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.theme.ShroudIcons

/**
 * Sizes and words of the action card under the lifted bubble (`MessageContextMenuCard`,
 * `MessageActionMenu.swift:406-447`; conversation-thread §16.8). Pure; dp.
 */
object MessageContextMenuCardMetrics {
    const val WIDTH = 250f
    const val ROW_HEIGHT = 44f

    /**
     * The card's height: the muted receipt row when there is one, the actions, "Select", and a 1 dp
     * hairline between rows (`MessageActionMenu.swift:433-436`).
     */
    fun height(receipt: ReceiptStatus?, actions: List<MessageMenuAction>): Float {
        val rows = (if (receiptTitle(receipt) == null) 0 else 1) + actions.size + 1
        return rows * ROW_HEIGHT + (rows - 1)
    }

    /**
     * The muted row's words, lower case as in the design; null draws no row — still sending or
     * failed says so on the bubble itself, and "read" there would be a false receipt
     * (`MessageActionMenu.swift:438-447`).
     */
    fun receiptTitle(receipt: ReceiptStatus?): String? = when (receipt) {
        null, ReceiptStatus.Sending, ReceiptStatus.Failed -> null
        ReceiptStatus.Sent -> "sent"
        ReceiptStatus.Delivered -> "delivered"
        ReceiptStatus.Read -> "read"
    }
}

/**
 * The action card under the lifted bubble (`MessageContextMenuCard`, `MessageActionMenu.swift:408-528`;
 * design `Conversation — * Message Menu`).
 *
 * Human: Only what the message can do is offered, and your own message's muted top row says what its
 * ticks say ("sent", "delivered", "read"), never more. Dark in both appearances: `#1F1F24` @ 0.94,
 * radius 14, 0.5 dp white @ 0.08 rim, 44 dp rows with 1 dp hairlines, icon 15 in a 22 dp slot, title
 * 16, padding h 14; Delete in the dark `danger` (#FF453A). A pressed row lights up at once and fades
 * out with a light haptic (the shared [ContextMenuItem]).
 *
 * Agent: the host decides [receipt] (null for someone else's message or a note) and [actions]
 * ([MessageMenuAction.primary]) and sizes the slot with [MessageContextMenuCardMetrics.height] from the
 * same two values; "Select" is always appended. [onAction] runs on the tap; the host closes the menu
 * first, then acts.
 */
@Composable
fun MessageContextMenuCard(
    receipt: ReceiptStatus?,
    actions: List<MessageMenuAction>,
    onAction: (MessageMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ContextMenuCardSurface(MenuStyle.Dark, modifier.width(MessageContextMenuCardMetrics.WIDTH.dp)) {
        val receiptTitle = MessageContextMenuCardMetrics.receiptTitle(receipt)
        if (receiptTitle != null) {
            // Information, not an action: a muted row TalkBack reads as text (MAM:451-454).
            ContextMenuItem(
                action = MenuAction(receiptTitle, ShroudIcons.Check),
                style = MenuStyle.Dark,
                onClick = {},
                muted = true,
                showsIconSlot = true,
            )
            ContextMenuSeparator(MenuStyle.Dark)
        }
        (actions + MessageMenuAction.Select).forEachIndexed { index, action ->
            if (index > 0) ContextMenuSeparator(MenuStyle.Dark)
            ContextMenuItem(
                action = MenuAction(action.title, action.icon, destructive = action.isDestructive),
                style = MenuStyle.Dark,
                onClick = { onAction(action) },
                showsIconSlot = true,
            )
        }
    }
}

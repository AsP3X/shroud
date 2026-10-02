package de.corespace.shroud.ui.settings.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.push.UnifiedPushState
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.SectionFooter
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The Delivery section at the top of Notifications and Sounds: how notifications reach this phone
 * while Shroud is closed (UnifiedPush distributor, background connection), with a row to [onOpen]
 * the Delivery screen (decision record 1; plan §2.4 W3-PUSH "Delivery UI copy"; notifications-push
 * §5.14, §5.14.2 "Notifications can't reach this phone").
 *
 * Human: One row, "Delivery", whose value names the path — the distributor ("ntfy"), "Background
 * connection", or "Off while Shroud is closed" in red. While nothing delivers, the reason sits
 * under it, the same sentence the test notification gives.
 *
 * Agent: reads K6 `push.registration.delivery` (main-confined, R1). The installed distributors are
 * read only while one registers, for its label. Signature from the W2-INT seam (plan §1.7.13);
 * W3-SETTINGS-B places it.
 */
@Composable
fun PushDeliverySection(onOpen: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val registration = container.push.registration
    val delivery by registration.delivery.collectAsState()
    val registering = (delivery.unifiedPush as? UnifiedPushState.Registering)?.distributorPackage
    val distributors = remember(registration, registering) {
        if (registering != null) registration.distributors() else emptyList()
    }
    val noun = remember(context) { DeviceNoun.current(context) }
    DeliverySectionContent(DeliveryState(delivery, distributors), noun, onOpen)
}

/**
 * The section's drawing: a card with the row — "Delivery" 16 `textPrimary`, the value 16 (one
 * line, end-aligned; `textSecondary`, or `dangerText` while nothing delivers), the Phosphor
 * `caret-right` 13 in `chevron`; padding h 14, v 13, like the Sound row
 * (`NotificationsSettingsView.swift:159-179`) — and, while nothing delivers, the reason as a
 * footer pulled up 6 dp (an 8 dp gap in the screen's 14-spaced column).
 *
 * Emits into the caller's column (the card and the footer are its children).
 */
@Composable
internal fun DeliverySectionContent(state: DeliveryState, noun: String, onOpen: () -> Unit) {
    val colors = ShroudTheme.colors
    val value = state.summary
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .highlightRow(onClick = onOpen)
                .clearAndSetSemantics { contentDescription = DeliveryCopy.rowLabel(value) }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudText(DeliveryCopy.ROW, inter(16f), colors.textPrimary, maxLines = 1)
            ShroudText(
                value,
                inter(16f),
                if (state.isOff) colors.dangerText else colors.textSecondary,
                Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
        }
    }
    state.sectionFooter(noun)?.let { SectionFooter(it, pullUp = 6.dp) }
}

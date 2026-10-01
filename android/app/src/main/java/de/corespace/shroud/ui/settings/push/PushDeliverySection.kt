package de.corespace.shroud.ui.settings.push

import androidx.compose.runtime.Composable

/**
 * The Delivery section at the top of Notifications and Sounds: how notifications reach this phone while Shroud is closed (UnifiedPush distributor, background connection) with a row to [onOpen] the Delivery screen (decision record 1; notifications-push §5.14). W3-SETTINGS-B places it.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-PUSH**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PushDeliverySection(onOpen: () -> Unit) {
}

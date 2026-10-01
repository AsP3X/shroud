package de.corespace.shroud.ui.settings.privacy

import androidx.compose.runtime.Composable

/**
 * Settings › Privacy and Security: auto-lock, read receipts, typing, presence, link previews, relay calls, blocked contacts (settings-lock §6, §7; iOS `PrivacySecurityView`).
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-SETTINGS-B**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PrivacySecurityScreen(onBack: () -> Unit) {
}

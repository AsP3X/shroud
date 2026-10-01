package de.corespace.shroud.ui.settings

import androidx.compose.runtime.Composable

/**
 * The Settings tab root (settings-lock §2; iOS `SettingsView`). [onOpenCalls] is set in the two-pane layout, where Calls lives under Settings.
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-SETTINGS-A**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(onOpenCalls: (() -> Unit)?) {
}

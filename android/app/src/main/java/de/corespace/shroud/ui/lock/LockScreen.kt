package de.corespace.shroud.ui.lock

import androidx.compose.runtime.Composable
import de.corespace.shroud.ui.shell.LockScreenRouter

/**
 * The chat lock screen: biometric / device-credential unlock, phrase entry, Log Out (settings-lock §11; iOS `LockScreenView`).
 *
 * **Entry-point stub (W2-INT seam, plan §1.7.13), owner W3-LOCK-ONBOARD**, which replaces the body; the
 * signature is the contract the shell and the other screens compile against. Draws nothing until then.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LockScreen(router: LockScreenRouter) {
}

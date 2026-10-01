package de.corespace.shroud.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * `BOOT_COMPLETED`: restarts the opt-in background connection after a reboot (00-plan §1.7.10;
 * allowed for a `specialUse` foreground service on API 35+). Not direct-boot aware: it runs after
 * the first unlock, when [ShroudApplication][de.corespace.shroud.ShroudApplication] has already
 * built the container and run `onProcessStart` (which restores push state).
 *
 * Manifest stub created by W0-A; W3-PUSH replaces the body. Until then the broadcast only starts
 * the process.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the system's broadcast counts (lint UnsafeProtectedBroadcastReceiver).
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    }
}

package de.corespace.shroud.ui.calls

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The thin lock-screen host of the call screen (calls §6.9): `showWhenLocked`, `turnScreenOn`,
 * `excludeFromRecents`, its own task affinity, `singleInstance`. It hosts `CallScreen` for the
 * full-screen intent and the notification's Answer; in the unlocked app the same screen is an
 * overlay in `MainActivity` (iOS `RootView.swift:93-102`). Counted by the app's
 * `AppPhaseMonitor` like `MainActivity`.
 *
 * Manifest stub created by W0-A; W3-CALLS-SYSTEM replaces the body. Nothing launches it before
 * then; if anything does, it closes at once.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

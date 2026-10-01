package de.corespace.shroud.core.push.unifiedpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Distributor → app half of the UnifiedPush Android protocol (AND_3; actions
 * `org.unifiedpush.android.connector.{NEW_ENDPOINT,REGISTRATION_FAILED,UNREGISTERED,MESSAGE}`,
 * 00-plan §1.7.10). Exported because distributors must reach it — so anyone can: every intent
 * whose `token` is not the stored connection token is dropped, and a `MESSAGE` counts only if it
 * decrypts (RFC 8291) under the subscription key and auth secret only this phone and the Shroud
 * server hold. No connector library (it pulls Google Tink; decision record 2026-10-01).
 *
 * Manifest stub created by W0-A; W3-PUSH replaces the body. Until then nothing is registered
 * with a distributor and every intent is ignored.
 */
class UnifiedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

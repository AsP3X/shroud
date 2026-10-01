package de.corespace.shroud.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/** Whether the app may notify (iOS `UNAuthorizationStatus`, the three cases the app reads). */
enum class NotificationAuthorization { Authorized, Denied, NotDetermined }

/**
 * The notification permission (iOS `NotificationsController.refreshAuthorization` /
 * `requestAuthorizationIfNeeded`, `ios/shroud/Services/Notifications/NotificationsController.swift:63-78`;
 * notifications-push §5.11, settings-lock §6.1).
 *
 * Android 13+ asks for `POST_NOTIFICATIONS`; before 13 notifications are on unless the user turned
 * them off. The system shows its dialog at most twice, so the app remembers that it asked in
 * `shroud.device` / `notifications.permissionAsked` ([KEY_ASKED]), a device fact the Log Out wipe
 * keeps (N16, P6: Android remembers a double denial across a logout, so the flag must too).
 *
 * P6a (decided 2026-10-01): asked once after the first unlock with notifications on (iOS
 * `PushNotificationService.start`, `:61-67`), then only from the Settings cards.
 */
class NotificationPermission(
    private val context: Context,
    private val devicePrefs: SharedPreferences,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    /** What the app may do now; [activity] (ours, resumed) tells a first denial from a final one. */
    @SuppressLint("InlinedApi") // A string constant, only asked about from API 33 on ([sdk] checks).
    fun current(activity: Activity?): NotificationAuthorization {
        val granted = sdk < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val rationale = sdk >= Build.VERSION_CODES.TIRAMISU &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        return authorization(
            sdk = sdk,
            granted = granted,
            enabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            asked = wasRequested,
            rationale = rationale,
        )
    }

    /** The system dialog was shown once (set right before launching it). */
    val wasRequested: Boolean get() = devicePrefs.getBoolean(KEY_ASKED, false)

    /** Records that the system dialog is about to show (settings-lock §6.1). Kept across Log Out. */
    fun markRequested() {
        if (!wasRequested) devicePrefs.edit { putBoolean(KEY_ASKED, true) }
    }

    companion object {
        /** In `shroud.device` (00-plan §1.5); the same key name settings-lock §6.1 uses. */
        const val KEY_ASKED = "notifications.permissionAsked"

        /**
         * The rule of notifications-push §5.11, pure:
         * - before 13: [enabled] → Authorized, else Denied (no "not determined" before 13);
         * - 13+ granted: [enabled] → Authorized, else Denied (blocked in Settings after granting);
         * - 13+ not granted, never [asked] or the system still wants a [rationale] (one "Don't
         *   allow" so far) → NotDetermined: the dialog will show;
         * - else Denied: "Don't allow" twice, only Settings can change it. (A dialog dismissed
         *   without an answer reads as Denied too; the Denied card's "Open Settings" reaches the
         *   same switch — no timing heuristics, §5.11.)
         */
        fun authorization(sdk: Int, granted: Boolean, enabled: Boolean, asked: Boolean, rationale: Boolean): NotificationAuthorization = when {
            sdk < Build.VERSION_CODES.TIRAMISU || granted -> if (enabled) NotificationAuthorization.Authorized else NotificationAuthorization.Denied
            !asked || rationale -> NotificationAuthorization.NotDetermined
            else -> NotificationAuthorization.Denied
        }
    }
}

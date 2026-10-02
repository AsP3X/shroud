package de.corespace.shroud.ui.shell

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the window does to keep unlocked chats out of the Recents thumbnail and out of screen
 * captures — P5 (decided: the per-API-level scheme, one switch "Hide chats during screen recording",
 * default on), shell-chats §3.8–3.9 (D2, D3), settings-lock §7.4:
 *
 * | API | While the chats are unlocked |
 * | --- | --- |
 * | 30–32 | `FLAG_SECURE`: the only way to keep the Recents thumbnail blank there; it also blacks out screenshots, recordings and casts of the chats, whatever the switch says |
 * | 33–34 | Recents screenshot off (`setRecentsScreenshotEnabled(false)`); `FLAG_SECURE` only while the switch is on (no recording callback before 35) |
 * | 35+ | Recents screenshot off; captures are covered by the in-app capture cover (`ScreenCaptureMonitor`), screenshots stay allowed, as on iOS |
 *
 * Locked or signed out: nothing (the lock screen shows nothing to hide). iOS draws its
 * `AppSwitcherPrivacyCover` before the switcher snapshot (`RootView.swift:97-104, 347-358`);
 * Android snapshots around `onPause`/`onStop`, too early to rely on a late draw (shell-chats §3.8).
 */
@Immutable
data class WindowProtection(val flagSecure: Boolean, val hideFromRecents: Boolean) {
    companion object {
        val None = WindowProtection(flagSecure = false, hideFromRecents = false)

        /**
         * What a phrase screen needs on [sdk] (`HidePhraseFromRecents`, `OnboardingSupport.kt`): out
         * of Recents, and `FLAG_SECURE` where Recents cannot skip the thumbnail (API 30–32).
         */
        fun phrase(sdk: Int = Build.VERSION.SDK_INT): WindowProtection =
            WindowProtection(flagSecure = sdk < Build.VERSION_CODES.TIRAMISU, hideFromRecents = true)

        /** The protection for [sdk] (P5 table above). */
        fun decide(sdk: Int, unlocked: Boolean, hidesDuringScreenCapture: Boolean): WindowProtection {
            if (!unlocked) return None
            return when {
                sdk < Build.VERSION_CODES.TIRAMISU -> WindowProtection(flagSecure = true, hideFromRecents = true)
                sdk < Build.VERSION_CODES.VANILLA_ICE_CREAM -> WindowProtection(flagSecure = hidesDuringScreenCapture, hideFromRecents = true)
                else -> WindowProtection(flagSecure = false, hideFromRecents = true)
            }
        }
    }
}

/**
 * Applies [WindowProtection] to an activity's window, reference-counted per reason so one release
 * does not undo another (shell-chats §3.8: the unlocked shell and the phrase screens both ask; the
 * phrase screens' own `HidePhraseFromRecents` predates this guard — see the package report).
 *
 * [MainActivity][de.corespace.shroud.MainActivity] owns one and feeds it the shell's protection;
 * [reapply] puts the window back in the shell's state after a helper that wrote the flags directly
 * let go of them.
 */
class WindowProtectionGuard(
    private val window: WindowControls,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private val holds = LinkedHashMap<String, WindowProtection>()

    /** Sets (or replaces) what [reason] needs; [WindowProtection.None] releases it. */
    fun hold(reason: String, protection: WindowProtection) {
        if (protection == WindowProtection.None) holds.remove(reason) else holds[reason] = protection
        reapply()
    }

    /** The union of every hold. */
    val combined: WindowProtection
        get() = WindowProtection(
            flagSecure = holds.values.any { it.flagSecure },
            hideFromRecents = holds.values.any { it.hideFromRecents },
        )

    /** Writes [combined] to the window again. */
    fun reapply() {
        val now = combined
        window.setSecure(now.flagSecure)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) window.setRecentsScreenshotEnabled(!now.hideFromRecents)
    }

    companion object {
        /** The unlocked shell's hold. */
        const val SHELL = "shell"
    }
}

/** The two window switches [WindowProtectionGuard] needs (an activity in production, a fake in tests). */
interface WindowControls {
    fun setSecure(secure: Boolean)

    /** API 33+ only. */
    fun setRecentsScreenshotEnabled(enabled: Boolean)

    companion object {
        /** [activity]'s window. */
        fun of(activity: Activity): WindowControls = object : WindowControls {
            override fun setSecure(secure: Boolean) {
                if (secure) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun setRecentsScreenshotEnabled(enabled: Boolean) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(enabled)
            }
        }
    }
}

/**
 * The activity's [WindowProtectionGuard] (`MainActivity` provides it); null outside an activity
 * (previews, screen tests).
 */
val LocalWindowProtectionGuard: ProvidableCompositionLocal<WindowProtectionGuard?> = staticCompositionLocalOf { null }

/**
 * Holds [protection] on the window for as long as the caller is composed, under [reason] — the
 * reference-counted way for a screen to keep itself out of Recents (shell-chats §3.8: "reference-count
 * requests (phrase screens + unlocked shell) so one release does not undo the other"). The phrase
 * screens' `HidePhraseFromRecents` (W3-LOCK-ONBOARD) should become
 * `HoldWindowProtection("phrase", WindowProtection.phrase())` (contract change request); until then
 * the root re-applies the guard once the onboarding stack has left (`RootScreen`).
 */
@Composable
fun HoldWindowProtection(reason: String, protection: WindowProtection) {
    val guard = LocalWindowProtectionGuard.current ?: return
    DisposableEffect(guard, reason, protection) {
        guard.hold(reason, protection)
        onDispose { guard.hold(reason, WindowProtection.None) }
    }
}

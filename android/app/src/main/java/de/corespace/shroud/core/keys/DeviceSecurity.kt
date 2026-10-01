package de.corespace.shroud.core.keys

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build

/**
 * The enrolled strong biometric, for the lock screen's copy ("Unlock with fingerprint",
 * "Fingerprint not working?"; settings-lock §11.2, S6). [word] is the lower-case noun the copy uses.
 */
enum class BiometricLabel(val word: String) {
    Fingerprint("fingerprint"),
    Face("face"),
    Generic("biometrics"),
}

/**
 * What the phone's own security offers the vault (crypto spec §10.2, §10.4; settings-lock §11.1).
 * The Android side of iOS `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)`
 * (`ios/shroud/Services/Crypto/HistoryKeyVault.swift:470-475`) and of the Face ID / Touch ID label.
 *
 * Every property is read live: the user can add or remove a screen lock or a fingerprint while
 * the app is in the background, and the lock screen re-probes on every resume.
 */
class DeviceSecurity(context: Context) {
    private val app = context.applicationContext
    private val keyguard: KeyguardManager = app.getSystemService(KeyguardManager::class.java)

    /**
     * A PIN, pattern or password is set — the vault's precondition (`canProtectWrapKey`,
     * crypto spec §10.2). Without it the history key cannot be protected and chats stay shut.
     */
    val isDeviceSecure: Boolean get() = keyguard.isDeviceSecure

    /** The keyguard is up right now: WhenUnlocked records (`keys/…`) cannot be opened. */
    val isDeviceLocked: Boolean get() = keyguard.isDeviceLocked

    /** The phone has a StrongBox Keymaster; the vault wrap key prefers it (crypto §10.2). */
    val hasStrongBox: Boolean by lazy { app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) }

    /**
     * A class 3 (strong) biometric is enrolled and usable, so the lock screen offers "Unlock with
     * <label>". False with weak face unlock only: the prompt then goes straight to the screen lock
     * (crypto §10.4, settings-lock §11.2 *ScreenLockOnly*).
     */
    fun strongBiometricAvailable(): Boolean {
        val manager = app.getSystemService(BiometricManager::class.java) ?: return false
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * The strong biometric's noun (crypto §10.4): API 31+ from the system's own button label for
     * `BIOMETRIC_STRONG`, API 30 from `FEATURE_FINGERPRINT`; anything unclear is [BiometricLabel.Generic].
     */
    fun biometricLabel(): BiometricLabel {
        val buttonLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                app.getSystemService(BiometricManager::class.java)
                    ?.getStrings(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    ?.buttonLabel
                    ?.toString()
            } catch (_: RuntimeException) {
                null
            }
        } else {
            null
        }
        return labelFor(buttonLabel, app.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
    }

    companion object {
        /**
         * Pure mapping behind [biometricLabel]: a system label naming exactly one of face or
         * fingerprint wins, one naming both is [BiometricLabel.Generic]; otherwise (no label, or
         * one in another language) a fingerprint sensor means [BiometricLabel.Fingerprint], and
         * anything else is [BiometricLabel.Generic] (crypto §10.4).
         */
        fun labelFor(systemButtonLabel: String?, hasFingerprintFeature: Boolean): BiometricLabel {
            val label = systemButtonLabel?.lowercase().orEmpty()
            val face = "face" in label
            val finger = "finger" in label
            return when {
                face && finger -> BiometricLabel.Generic
                face -> BiometricLabel.Face
                finger -> BiometricLabel.Fingerprint
                hasFingerprintFeature -> BiometricLabel.Fingerprint
                else -> BiometricLabel.Generic
            }
        }
    }
}

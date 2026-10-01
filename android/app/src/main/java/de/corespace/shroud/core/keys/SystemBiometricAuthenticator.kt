package de.corespace.shroud.core.keys

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The vault's presence check on the framework `android.hardware.biometrics.BiometricPrompt`
 * (crypto spec §10.4; plan C20 — no `androidx.biometric`, which needs a `FragmentActivity`). The
 * Android side of iOS's `LAContext` sheets (`ios/shroud/Services/Crypto/HistoryKeyVault.swift:262-342`).
 *
 * - [UnlockMethod.BiometryPreferred] → `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`: the fingerprint
 *   first, the system offers the screen lock itself (and goes straight to it without a strong
 *   biometric, e.g. weak face unlock only).
 * - [UnlockMethod.PasscodeOnly] → `DEVICE_CREDENTIAL`: the screen lock only.
 * - No negative button: not allowed together with `DEVICE_CREDENTIAL`.
 * - Copy (design frame *Locked — System Biometric Prompt*, `Android-App.pen` `p5OtXk`): title
 *   [TITLE], subtitle [SUBTITLE]; the sensor hint, app name and "Use PIN" are system-owned. iOS's
 *   reason string "Unlock your encrypted chats" (`:179`) is not used.
 *
 * The prompt is bound to the vault cipher (`CryptoObject`), so the key opens only for this one
 * operation. Cancelling the coroutine (the vault's 90 s limit) cancels the prompt, like
 * `LAContext.invalidate()` (`:228-243`). A non-matching finger is not terminal (the system shows
 * "Not recognized"); any terminal error is a [BiometricPromptError] for
 * [HistoryKeyVault.classifyPromptError]. Without a host activity the attempt counts as cancelled.
 */
class SystemBiometricAuthenticator(private val activity: () -> Activity?) : VaultAuthenticator {
    /**
     * Needs the normal permission `USE_BIOMETRIC`, which the manifest declares. A
     * `SecurityException` is still caught below and reported as `VaultError.Keystore`, so the
     * unlock fails cleanly instead of crashing.
     */
    override suspend fun authenticate(cipher: Cipher, method: UnlockMethod): Cipher = withContext(Dispatchers.Main.immediate) {
        val host = activity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: throw VaultError.UserCancelled
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.cipher ?: cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (cont.isActive) cont.resumeWithException(BiometricPromptError(errorCode))
                }

                override fun onAuthenticationFailed() = Unit
            }
            try {
                BiometricPrompt.Builder(host)
                    .setTitle(TITLE)
                    .setSubtitle(SUBTITLE)
                    .setAllowedAuthenticators(authenticators(method))
                    .setConfirmationRequired(false)
                    .build()
                    .authenticate(BiometricPrompt.CryptoObject(cipher), signal, host.mainExecutor, callback)
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resumeWithException(VaultError.Keystore(e.javaClass.simpleName, e))
            }
        }
    }

    companion object {
        const val TITLE = "Unlock Shroud"
        const val SUBTITLE = "Confirm it’s you to open your chats"

        /** The allowed authenticators per [method] (crypto spec §10.4). */
        fun authenticators(method: UnlockMethod): Int = when (method) {
            UnlockMethod.BiometryPreferred -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            UnlockMethod.PasscodeOnly -> Authenticators.DEVICE_CREDENTIAL
        }
    }
}

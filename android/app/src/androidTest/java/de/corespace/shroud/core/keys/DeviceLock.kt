package de.corespace.shroud.core.keys

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The test phone's screen lock, driven from the instrumentation (00-plan §6.3: the emulator has
 * PIN [PIN], set with `adb shell locksettings set-pin 1234`). Shell commands run as the shell user
 * through `UiAutomation`, which may type into SystemUI's keyguard and credential views.
 */
object DeviceLock {
    const val PIN = "1234"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val keyguard: KeyguardManager
        get() = instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /** Runs a shell command as the shell user and returns its output. */
    fun shell(command: String): String = device.executeShellCommand(command)

    val isSecure: Boolean get() = keyguard.isDeviceSecure

    val isLocked: Boolean get() = keyguard.isDeviceLocked

    /** Screen off: with a PIN and "power button instantly locks" the keyguard is up at once. */
    fun lockNow() {
        device.sleep()
        waitFor(10_000) { keyguard.isDeviceLocked }
    }

    /** Screen on and keyguard gone, typing [PIN] into the bouncer when the phone is locked. */
    fun ensureUnlocked() {
        device.wakeUp()
        shell("svc power stayon true")
        if (!keyguard.isDeviceLocked) return
        repeat(3) {
            shell("wm dismiss-keyguard")
            SystemClock.sleep(800)
            shell("input text $PIN")
            shell("input keyevent 66")
            if (waitFor(5_000) { !keyguard.isDeviceLocked }) return
            device.wakeUp()
        }
        check(!keyguard.isDeviceLocked) { "could not unlock the test phone with its PIN" }
    }

    /**
     * Types [PIN] into the system's credential view of a vault prompt (`BiometricPrompt` with
     * `DEVICE_CREDENTIAL`, crypto spec §10.4) once its title is on screen. Returns false when no
     * prompt showed up within [timeoutMs].
     */
    fun answerPromptWithPin(timeoutMs: Long = 20_000): Boolean {
        if (!waitForPrompt(timeoutMs)) return false
        // The PIN pad takes focus by itself; give its window a moment to accept input.
        SystemClock.sleep(1_000)
        device.wait(Until.findObject(By.pkg("com.android.systemui").clazz("android.widget.EditText")), 3_000)?.click()
        shell("input text $PIN")
        shell("input keyevent 66")
        return true
    }

    /** Waits until the vault prompt (title [SystemBiometricAuthenticator.TITLE]) is on screen. */
    fun waitForPrompt(timeoutMs: Long = 20_000): Boolean =
        device.wait(Until.findObject(By.text(SystemBiometricAuthenticator.TITLE)), timeoutMs) != null

    /**
     * Cancels the vault prompt (back). On API 30 the credential view opens the soft keyboard for the
     * PIN, and the first back only hides it; back is pressed until the prompt's title is gone.
     */
    fun dismissPrompt() {
        repeat(3) {
            device.pressBack()
            if (device.wait(Until.gone(By.text(SystemBiometricAuthenticator.TITLE)), 2_000) == true) return
        }
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }
}

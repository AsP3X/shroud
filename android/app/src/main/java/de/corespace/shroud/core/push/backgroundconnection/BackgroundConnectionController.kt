package de.corespace.shroud.core.push.backgroundconnection

import de.corespace.shroud.core.push.PushCopy
import de.corespace.shroud.core.push.PushSettings

/**
 * Starts and stops [BackgroundConnectionService] and the socket hold (`Holder.Background`).
 * [enable] twice keeps one service. The preference stays set across [stopKeepPreference] (signed out)
 * so the next sign-in can bring it back. [forget] stops it as part of wiping `shroud.push`.
 */
class BackgroundConnectionController(
    private val host: Host,
    private val prefs: PushSettings,
    private val sessionToken: () -> String?,
    private val hold: (String) -> Unit,
    private val releaseHold: () -> Unit,
    private val onEnded: () -> Unit,
    private val battery: BatteryGate,
    private val reconnect: ReconnectSchedule,
    private val onReconnect: () -> Unit,
) {
    interface Host {
        fun start()
        fun stop()
    }

    interface BatteryGate {
        fun unrestricted(): Boolean
        fun askOnceIfNeeded()
    }

    interface ReconnectSchedule {
        fun arm()
        fun cancel()
        fun pulse(reconnect: () -> Unit)
    }

    var onChanged: () -> Unit = {}

    /** A start has been asked for and not yet stopped. The second [enable] does not start again. */
    var requested: Boolean = false
        private set

    private var held = false

    fun enable() {
        prefs.backgroundConnection = true
        if (requested) return
        requested = true
        onChanged()
        battery.askOnceIfNeeded()
        try {
            host.start()
        } catch (_: Exception) {
            requested = false
            onChanged()
        }
    }

    fun disable() {
        prefs.backgroundConnection = false
        stopService()
    }

    /** Signed out: the service stops and the preference is left as the user set it. */
    fun stopKeepPreference() = stopService()

    /** Log Out / removal, before the preference file is cleared. */
    fun forget() = stopService()

    fun onServiceStarted() {
        // A sticky restart after stop or forget must not bring the socket back.
        if (!requested) {
            host.stop()
            return
        }
        val token = sessionToken()
        if (token.isNullOrEmpty()) {
            stopService()
            return
        }
        if (!held) {
            hold(token)
            held = true
        }
        reconnect.arm()
        onChanged()
    }

    fun onReconnectWake() {
        if (!requested) {
            host.stop()
            reconnect.cancel()
            return
        }
        onServiceStarted()
        if (requested) reconnect.pulse(onReconnect)
    }

    /** The service is gone. A stop we asked for also tells the foreground coordinator. */
    fun onServiceDestroyed() {
        val stopping = !requested
        releaseNow(notify = stopping)
        if (stopping) reconnect.cancel()
        onChanged()
    }

    fun restartAfterBoot(userUnlocked: Boolean) {
        if (shouldRestart(prefs.backgroundConnection, sessionToken() != null, userUnlocked)) enable()
    }

    fun batteryUnrestricted(): Boolean = battery.unrestricted()

    private fun stopService() {
        requested = false
        host.stop()
        releaseNow(notify = true)
        reconnect.cancel()
        onChanged()
    }

    private fun releaseNow(notify: Boolean) {
        if (!held) return
        held = false
        releaseHold()
        if (notify) onEnded()
    }

    companion object {
        const val NOTIFICATION_TEXT = PushCopy.CONNECTED

        /** Boot restarts the service only when the user asked for it, is signed in, and the phone is unlocked. */
        fun shouldRestart(prefOn: Boolean, signedIn: Boolean, userUnlocked: Boolean): Boolean =
            prefOn && signedIn && userUnlocked
    }
}

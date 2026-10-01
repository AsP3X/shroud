package de.corespace.shroud

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.lifecycle.AppPhaseMonitor
import de.corespace.shroud.ui.calls.CallActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The process entry point, iOS `ShroudApp` + the launch half of `AppDelegate`
 * (`ShroudApp.swift:4-12`, `AppDelegate.swift:13-21`; shell-chats addendum *ShroudApp.swift*).
 * There is no delegate object: what iOS runs in `didFinishLaunching` runs in
 * [AppContainer.onProcessStart], in the order the wave's INT package owns (00-plan §1.3).
 *
 * The [container] is built lazily and only once the user has unlocked the phone since boot: it
 * reads credential-encrypted storage, which throws in direct boot (00-plan §1.3,
 * notifications-push §6.3). The app has no direct-boot-aware component, so in practice the
 * process starts unlocked; should it ever start earlier, nothing is built until
 * `ACTION_USER_UNLOCKED` (there is no direct-boot push path, decision record 2026-10-01). The
 * UnifiedPush receiver, the background connection, Telecom and the removal wake reach the
 * container only through this property — they never build their own objects.
 *
 * [appPhase] needs no storage, so it is installed before the first unlock and sees every
 * activity from the first one (00-plan §1.4).
 *
 * WorkManager initialises on demand through [Configuration.Provider] (its startup initializer
 * is removed in the manifest): nothing opens its database before the first job is scheduled.
 *
 * Shared file: W0-A creates it, then the wave's INT package owns it (00-plan §2.6).
 */
class ShroudApplication : Application(), Configuration.Provider {
    /** The phase of *our* activities (`MainActivity`, `CallActivity`), never another app's. */
    val appPhase = AppPhaseMonitor(tracks = { it is MainActivity || it is CallActivity })

    /**
     * The process's one [AppContainer]. Touching it before the first unlock is a programming
     * error (every caller runs in a component that cannot start before it) and throws.
     */
    val container: AppContainer by lazy {
        check(UserManagerCompat.isUserUnlocked(this)) { "AppContainer needs the user unlocked (direct boot)" }
        AppContainer(this, appPhase)
    }

    private var started = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        appPhase.install(this)
        if (UserManagerCompat.isUserUnlocked(this)) {
            startProcess()
        } else {
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        unregisterReceiver(this)
                        startProcess()
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Once per process, after the first unlock. */
    private fun startProcess() {
        if (started) return
        started = true
        container.onProcessStart()
        // Invariant 5 (interim until W3-SHELL's auto-lock, shell-chats §3.7): going to the
        // background drops decrypted chats and the messaging keys from memory.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    lockWhenBackgrounded()
                }
            },
        )
    }

    /**
     * Locks the chats ([AppContainer.lockChatsInMemory], `RootView.swift:315-320`: memory, keys, the
     * stale temp-file sweep) — unless the vault's own
     * system prompt is up: on some skins (Samsung One UI) the biometric prompt stops our activity,
     * and locking under it would undo the unlock the user is in the middle of (crypto §10.7,
     * settings-lock §11.5). The lock then waits for the prompt to end and still happens if the app
     * is in the background by then.
     */
    private fun lockWhenBackgrounded() {
        val keys = container.keys.cryptoController
        pendingPromptLock?.cancel()
        pendingPromptLock = container.appScope.launch {
            if (keys.vaultPromptInFlight.value) {
                keys.vaultPromptInFlight.first { !it }
                if (appPhase.phase.value != AppPhase.Background) return@launch
            }
            container.lockChatsInMemory()
        }
    }

    private var pendingPromptLock: Job? = null
}

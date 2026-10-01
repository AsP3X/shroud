package de.corespace.shroud.core.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * The app's phase, iOS `ScenePhase` on Android (shell-chats §3.6; 00-plan §1.4, C13):
 * - [Active] — at least one of our activities is resumed (iOS `.active`, `RootView.swift:275`);
 * - [Inactive] — one is started but none is resumed: a system dialog, BiometricPrompt or the
 *   permission sheet is on top, or the user is on the way out / back (iOS `.inactive`,
 *   `RootView.swift:295-298`);
 * - [Background] — none is started (iOS `.background`, `RootView.swift:257-274`).
 */
enum class AppPhase { Active, Inactive, Background }

/**
 * One [StateFlow] of [AppPhase] for the whole process, derived from the activity lifecycle of
 * *our* activities (`MainActivity`, `CallActivity`; [tracks] decides). It replaces
 * `ProcessLifecycleOwner`, whose `ON_STOP` comes 700 ms late — long enough for the app switcher
 * to snapshot unlocked chats and to delay the "away" focus frame (shell-chats §3.6). iOS reacts
 * to the scene phase at once (`RootView.swift:254-298`, `.onChange(of: scenePhase)`); so does this.
 *
 * Consumers: AppShellController (auto-lock, covers, session probe), AppForegroundCoordinator
 * (focus frame, `leaveForeground`), RealtimeClient (first focus frame), NotificationsController
 * ([isResumed]), CallController (`onAppVisible`), BiometricPrompt and permission flows
 * ([topActivity]).
 *
 * An activity recreated by a configuration change the manifest does not handle (font scale,
 * locale) stops with [Activity.isChangingConfigurations] set. The phase then stays [Inactive]
 * until the new instance starts instead of flickering to [Background] (iOS never recreates
 * `RootView`, shell-chats addendum *ShroudApp.swift*) — which would lock the chats and send the
 * "away" focus frame. The relaunch runs inside one main-thread message, so the bridge also ends
 * when a message posted through [postToMain] runs: a recreation that never starts cannot hold the
 * phase out of [Background] (and the auto-lock with it).
 *
 * Needs no storage, so [ShroudApplication][de.corespace.shroud.ShroudApplication] installs it in
 * `onCreate`, before the first unlock too. Lifecycle callbacks arrive on the main thread; [phase]
 * may be read anywhere, [topActivity] only on the main thread.
 *
 * @param tracks which activities count; the app passes its own two.
 * @param postToMain runs a block after the current main-thread message (tests drive it by hand).
 */
class AppPhaseMonitor(
    private val tracks: (Activity) -> Boolean = { true },
    private val postToMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : Application.ActivityLifecycleCallbacks {
    private val state = MutableStateFlow(AppPhase.Background)
    private val started = IdentityRefs()
    private val resumed = IdentityRefs()

    /** One token per activity stopped for a recreation whose new instance has not started yet. */
    private val recreations = ArrayList<Any>()

    val phase: StateFlow<AppPhase> = state.asStateFlow()

    /** iOS `applicationState == .active`: one of our activities is in front and resumed. */
    val isResumed: Boolean get() = phase.value == AppPhase.Active

    /** Started (visible, perhaps behind a dialog): anything but [AppPhase.Background]. */
    val isStarted: Boolean get() = phase.value != AppPhase.Background

    /**
     * The activity to host BiometricPrompt and permission requests: the most recently resumed
     * one still resumed, else the most recently started one still started. Held weakly.
     */
    val topActivity: Activity? get() = (resumed.last() ?: started.last()) as? Activity

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (tracks(activity)) onStarted(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (tracks(activity)) onResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (tracks(activity)) onPaused(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (tracks(activity)) onStopped(activity, changingConfigurations = activity.isChangingConfigurations)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (tracks(activity)) onDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    // The state machine, keyed by the activity instance (any object in JVM tests).

    internal fun onStarted(key: Any) {
        if (recreations.isNotEmpty()) recreations.removeAt(0)
        started.add(key)
        publish()
    }

    internal fun onResumed(key: Any) {
        started.add(key)
        resumed.remove(key)
        resumed.add(key)
        publish()
    }

    internal fun onPaused(key: Any) {
        resumed.remove(key)
        publish()
    }

    internal fun onStopped(key: Any, changingConfigurations: Boolean) {
        resumed.remove(key)
        if (started.remove(key) && changingConfigurations) {
            val token = Any()
            recreations += token
            postToMain(Runnable { if (recreations.remove(token)) publish() })
        }
        publish()
    }

    internal fun onDestroyed(key: Any) {
        resumed.remove(key)
        started.remove(key)
        publish()
    }

    private fun publish() {
        val next = when {
            !resumed.isEmpty() -> AppPhase.Active
            !started.isEmpty() || recreations.isNotEmpty() -> AppPhase.Inactive
            else -> AppPhase.Background
        }
        if (state.value != next) state.value = next
    }

    /** Insertion-ordered set of weakly held objects, compared by identity; cleared refs are dropped. */
    private class IdentityRefs {
        private val refs = ArrayList<WeakReference<Any>>()

        fun add(key: Any) {
            prune()
            if (refs.none { it.get() === key }) refs += WeakReference(key)
        }

        fun remove(key: Any): Boolean {
            prune()
            return refs.removeAll { it.get() === key }
        }

        fun last(): Any? {
            prune()
            return refs.lastOrNull()?.get()
        }

        fun isEmpty(): Boolean {
            prune()
            return refs.isEmpty()
        }

        private fun prune() {
            refs.removeAll { it.get() == null }
        }
    }
}

package de.corespace.shroud.ui.shell

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.function.Consumer

/**
 * Whether the chats are being recorded, cast or mirrored — iOS `UITraitCollection.sceneCaptureState`
 * (`RootView.swift:447-487`; shell-chats §3.9, D3; P5):
 *
 * - API 35+: `WindowManager.addScreenRecordingCallback` — this app's windows are visible in a
 *   MediaProjection recording or cast (needs `DETECT_SCREEN_RECORDING`, in the manifest). Registered
 *   while `MainActivity` is started ([attach]).
 * - Every API: a presentation display (`DisplayManager.DISPLAY_CATEGORY_PRESENTATION`) counts as
 *   mirroring ([startDisplays]).
 * - API 30–34 have no recording signal: `FLAG_SECURE` is the protection there ([WindowProtection]).
 *
 * Screenshots are not "capture" (iOS neither); `Activity.registerScreenCaptureCallback` is not used.
 * [captured] feeds `AppShellController.setScreenCaptured`. Main thread.
 */
class ScreenCaptureMonitor {
    private var recording = false
    private var presenting = false
    private val state = MutableStateFlow(false)
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Recorded, cast or mirrored right now. */
    val captured: StateFlow<Boolean> = state.asStateFlow()

    /** Follows presentation displays for the process's lifetime (idempotent). */
    fun startDisplays(context: Context) {
        if (displayListener != null) return
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refreshDisplays()
            override fun onDisplayRemoved(displayId: Int) = refreshDisplays()
            override fun onDisplayChanged(displayId: Int) = refreshDisplays()
        }
        displayManager = manager
        displayListener = listener
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refreshDisplays()
    }

    /**
     * Listens to [activity]'s screen-recording state on API 35+ until the returned handle is closed
     * (`MainActivity.onStart` / `onStop`, as the platform recommends). No-op below 35.
     */
    fun attach(activity: Activity): AutoCloseable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return AutoCloseable {}
        return attachRecording(activity)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun attachRecording(activity: Activity): AutoCloseable {
        val windowManager = activity.windowManager
        val callback = Consumer<Int> { visible -> setRecording(visible == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        val initial = runCatching { windowManager.addScreenRecordingCallback(activity.mainExecutor, callback) }.getOrNull()
            ?: return AutoCloseable {}
        setRecording(initial == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        return AutoCloseable {
            runCatching { windowManager.removeScreenRecordingCallback(callback) }
            setRecording(false)
        }
    }

    /** The recording callback's state (tests drive it directly). */
    fun setRecording(visible: Boolean) {
        recording = visible
        publish()
    }

    /** Whether a presentation display is connected (tests drive it directly). */
    fun setPresenting(present: Boolean) {
        presenting = present
        publish()
    }

    private fun refreshDisplays() {
        val manager = displayManager ?: return
        setPresenting(runCatching { manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty() }.getOrDefault(false))
    }

    private fun publish() {
        val next = recording || presenting
        if (state.value != next) state.value = next
    }
}

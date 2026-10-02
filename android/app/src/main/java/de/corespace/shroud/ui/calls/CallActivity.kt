package de.corespace.shroud.ui.calls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.lifecycleScope
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.system.CallIntents
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.theme.ShroudTheme
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock-screen host of the call screen (K8; calls §6.9): the full-screen intent of a ring and
 * the notification's Answer open it, over the keyguard and with the screen turned on. In the
 * unlocked app the same `CallScreen` is [InCallOverlay] in `MainActivity` (iOS `RootView.swift:93-102`).
 *
 * - **Intent:** [CallIntents.parse] — extras `call_id` and `action` (`"show"` or `"answer"`).
 *   Without a valid intent it finishes at once and touches nothing: the system e2e starts it as
 *   root only to clear the package's stopped flag (`am start -n …/.ui.calls.CallActivity`), and it
 *   must never run the session glue, the shell or `finishInterruptedWipeIfNeeded` (Grok §4: a
 *   pending wipe would delete the session). It never builds the shell either way.
 * - **No call** for a valid intent (the ring ended, or a process restart lost it): finishes.
 * - **Answer** (once per intent): `CallController.acceptIncoming()` for the call that rings.
 * - **Hooks:** `callsSystem.screenHooks.onCallScreenShown(callId)` in [onResume] (it may start the
 *   phoneCall service an earlier start was refused), `onCallScreenHidden()` in [onPause].
 * - **Back** leaves the call screen (P17b): the task goes back, the call goes on.
 * - Finishes itself once the call screen is gone (after "Call ended").
 *
 * Show-when-locked and turn-screen-on are set in code as well as in the manifest.
 */
class CallActivity : ComponentActivity() {
    private var ports: CallPorts? = null
    private var callId: UUID? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val parsed = CallIntents.parse(intent)
        if (parsed == null) {
            // The stopped-flag clear of the system e2e, or a stray start: nothing else happens.
            finish()
            return
        }
        // Over the keyguard and with the screen on (the manifest says so too).
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val ports = portsFor()
        val active = ports.ui.value.active
        if (active == null) {
            finish()
            return
        }
        this.ports = ports
        callId = active.id
        // A recreation (rotation is handled in place, process death is not) must not answer twice.
        if (savedInstanceState == null) handleAction(ports, parsed)
        setContent {
            CallActivityContent(ports, onGone = ::finish, onBack = { moveTaskToBack(true) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val ports = ports ?: return
        val parsed = CallIntents.parse(intent) ?: return
        ports.ui.value.active?.let { callId = it.id }
        handleAction(ports, parsed)
    }

    override fun onResume() {
        super.onResume()
        val id = ports?.ui?.value?.active?.id ?: callId ?: return
        ports?.onCallScreenShown(id)
    }

    override fun onPause() {
        ports?.onCallScreenHidden()
        super.onPause()
    }

    /** "answer" accepts the call that rings here; "show" only shows it. */
    private fun handleAction(ports: CallPorts, parsed: Pair<UUID, String>) {
        val (id, action) = parsed
        if (action != ACTION_ANSWER) return
        val active = ports.ui.value.active ?: return
        if (active.id != id || active.phase != CallPhase.IncomingRinging) return
        lifecycleScope.launch { ports.acceptIncoming() }
    }

    private fun portsFor(): CallPorts {
        portsOverride?.let { return it(this) }
        return ContainerCallPorts((application as ShroudApplication).container)
    }

    companion object {
        /** Screen tests stand in for the container (Robolectric runs a plain `Application`). */
        @VisibleForTesting
        internal var portsOverride: ((CallActivity) -> CallPorts)? = null
    }
}

/** The `CallIntents.EXTRA_ACTION` value of the notification's Answer. */
private const val ACTION_ANSWER = "answer"

/**
 * The call screen in [CallActivity]: the call while there is one, TalkBack on it from the start,
 * the permission prompt for answering over the keyguard, light status bar icons; [onGone] once
 * the call (and its "Call ended") is over.
 */
@Composable
private fun CallActivityContent(ports: CallPorts, onGone: () -> Unit, onBack: () -> Unit) {
    CompositionLocalProvider(LocalCallPorts provides ports) {
        ShroudTheme(dark = true) {
            OverlayHost {
                CallPermissionPromptHost(ports)
                val state by ports.ui.collectAsState()
                val call = state.active
                val focus = remember { FocusRequester() }
                LaunchedEffect(call == null) {
                    if (call == null) onGone()
                }
                LaunchedEffect(call?.id) {
                    if (call == null) return@LaunchedEffect
                    // Once the screen is laid out (a frame), so the focus target is attached.
                    withFrameNanos { }
                    runCatching { focus.requestFocus() }
                }
                BackHandler(onBack = onBack)
                Box(Modifier.fillMaxSize()) {
                    if (call != null) CallScreen(ports = ports, state = state, call = call, focusRequester = focus)
                }
                LightStatusBarWhileShown(call != null)
            }
        }
    }
}


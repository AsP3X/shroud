package de.corespace.shroud.ui.wipe

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.DeviceWipeController
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.auth.WipeStep
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform

/**
 * The full-screen "Clearing this phone" overlay over everything while `DeviceWipeController` runs
 * the Log Out / forced sign-out / removal wipe (iOS `DeviceWipeOverlay.swift`; settings-lock §14.4;
 * plan §1.7.13 entry point).
 *
 * **Seam (W2-INT), owner W3-LOCK-ONBOARD** — which replaces this body with the designed overlay
 * (rows, ticks, motion). The interim body is deliberately plain but complete in behaviour: it
 * swallows every touch and Back, says why and for whom the phone is being cleared
 * ([WipeOverlayText.subtitle]), shows each row in its iOS state ([WipeOverlayText.rowState]),
 * announces each step and plays the haptics of `DeviceWipeController.feedback`, and offers Try
 * Again / Continue when the check found something (`DeviceWipeOverlay.swift:96-218` copy). Not in
 * the design file on purpose: W3-LOCK-ONBOARD implements the designed frames.
 */
@Composable
fun DeviceWipeOverlay() {
    val wipe = LocalAppContainer.current.auth.deviceWipe
    val noun = DeviceNoun.current(LocalContext.current)
    val phase by wipe.phase.collectAsState()
    val active by wipe.active.collectAsState()
    val details by wipe.details.collectAsState()
    val leftovers by wipe.leftovers.collectAsState()
    val retrying by wipe.retrying.collectAsState()
    val reason by wipe.reason.collectAsState()
    val handle by wipe.handle.collectAsState()
    val colors = ShroudTheme.colors
    val view = LocalView.current
    // TalkBack hears each step and the end; the end plays its haptic (`DeviceWipeController.feedback`).
    LaunchedEffect(wipe, view) {
        wipe.feedback.collect { feedback ->
            view.speak(feedback.announcement)
            view.perform(feedback.haptic)
        }
    }
    BackHandler(enabled = true) {}
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = ScreenInset, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        val title = when (phase) {
            WipePhase.Failed -> "Couldn’t clear everything"
            WipePhase.Done -> "This $noun is clear"
            else -> "Clearing this $noun"
        }
        ShroudText(title, inter(22f, FontWeight.Bold), colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        ShroudText(
            WipeOverlayText.subtitle(phase, reason, handle, leftovers, noun),
            inter(15f),
            colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            for (step in WipeStep.entries) {
                val state = WipeOverlayText.rowState(step, phase, active, retrying, details, leftovers)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ShroudText(step.title, inter(15f, FontWeight.Medium), colors.textPrimary, modifier = Modifier.weight(1f))
                    if (state == WipeOverlayText.RowState.Active) Spinner(colors.textSecondary, Modifier.padding(end = 8.dp), size = 14.dp)
                    ShroudText(WipeOverlayText.rowStatus(step, state, details), inter(13f), colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (phase == WipePhase.Failed) {
            PrimaryButton("Try Again", { wipe.retry() }, showsArrow = false)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Continue", { wipe.continueAfterFailure() }, Modifier.fillMaxWidth())
        } else {
            ShroudText(
                if (phase == WipePhase.Done) "Taking you to the welcome screen…" else "Your account and chats on other devices stay as they are.",
                inter(14f),
                colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** TalkBack announcement. `View.announceForAccessibility` and `TYPE_ANNOUNCEMENT` are deprecated. */
private fun View.speak(text: CharSequence) {
    val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
        this.text.add(text)
        contentDescription = text
        className = this@speak.javaClass.name
        packageName = context.packageName
    }
    if (parent?.requestSendAccessibilityEvent(this, event) == true) return
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return
    if (manager.isEnabled) manager.sendAccessibilityEvent(event)
}

/**
 * The overlay's words and row states as iOS draws them (`DeviceWipeOverlay.swift:101-117, 141-180`).
 * Pure, so the copy is tested on the JVM.
 */
internal object WipeOverlayText {
    /** `WipeStepStatus.State`. */
    enum class RowState { Pending, Active, Done, Failed }

    /**
     * The line under the title (`subtitle`, `:101-117`): why and for whom while running ("Your
     * session ended. ", "This phone was removed from your account. ", nothing for Log Out), what is
     * left when the check failed, and that nothing is left once done.
     */
    fun subtitle(phase: WipePhase, reason: WipeReason, handle: String, leftovers: List<DeviceDataWipe.Leftover>, noun: String): String = when (phase) {
        WipePhase.Failed ->
            "Still here: ${DeviceWipeController.labels(leftovers)}. Try again — if it keeps failing, restart your $noun and open Shroud; it finishes on its own."
        WipePhase.Done ->
            if (handle.isEmpty()) "Nothing from your account is left on this $noun." else "Nothing from $handle is left on this device."
        else -> {
            val whose = if (handle.isEmpty()) "on this $noun" else "for $handle"
            "${reason.lead(noun)}Removing everything Shroud stored $whose."
        }
    }

    /** `rowState(_:)` (`:167-172`): failed rows first, then the running or retried one, then finished ones. */
    fun rowState(
        step: WipeStep,
        phase: WipePhase,
        active: WipeStep?,
        retrying: Set<WipeStep>,
        details: Map<WipeStep, String>,
        leftovers: List<DeviceDataWipe.Leftover>,
    ): RowState = when {
        phase == WipePhase.Failed && (step == WipeStep.Verify || leftovers.any { it.step == step }) -> RowState.Failed
        active == step || step in retrying -> RowState.Active
        details[step] != null -> RowState.Done
        else -> RowState.Pending
    }

    /**
     * The row's trailing text: a finished row's detail, "Still here" on a failed data row (`:144`),
     * else the iOS accessibility values ("Failed", "In progress", "Waiting", `:174-180`).
     */
    fun rowStatus(step: WipeStep, state: RowState, details: Map<WipeStep, String>): String = when (state) {
        RowState.Done -> details[step] ?: "Done"
        RowState.Failed -> if (step == WipeStep.Verify) "Failed" else "Still here"
        RowState.Active -> "In progress"
        RowState.Pending -> "Waiting"
    }
}

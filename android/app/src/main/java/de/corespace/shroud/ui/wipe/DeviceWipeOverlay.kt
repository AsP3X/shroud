package de.corespace.shroud.ui.wipe

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.auth.DeviceWipeController
import de.corespace.shroud.core.auth.WipePhase
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

/**
 * The full-screen "Clearing this phone" overlay over everything while `DeviceWipeController` runs
 * the Log Out / forced sign-out / removal wipe (iOS `DeviceWipeOverlay.swift`; settings-lock §14.4;
 * plan §1.7.13 entry point).
 *
 * **Seam (W2-INT), owner W3-LOCK-ONBOARD** — which replaces this body with the designed overlay
 * (rows, ticks, motion, TalkBack announcements from `DeviceWipeController.feedback`, haptics). The
 * interim body is deliberately plain but complete in behaviour: it swallows every touch and Back,
 * names the step in progress, lists each finished step's detail, and offers Try Again / Continue
 * when the check found something (`DeviceWipeOverlay.swift:96-218` copy). Not in the design file on
 * purpose: W3-LOCK-ONBOARD implements the designed frames.
 */
@Composable
fun DeviceWipeOverlay() {
    val wipe = LocalAppContainer.current.auth.deviceWipe
    val noun = DeviceNoun.current(LocalContext.current)
    val phase by wipe.phase.collectAsState()
    val active by wipe.active.collectAsState()
    val details by wipe.details.collectAsState()
    val leftovers by wipe.leftovers.collectAsState()
    val colors = ShroudTheme.colors
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
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            for (step in WipeStep.entries) {
                val status = when {
                    details[step] != null -> details[step]!!
                    step == active -> "In progress"
                    phase == WipePhase.Failed && leftovers.any { it.step == step } -> "Still here"
                    else -> "Waiting"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ShroudText(step.title, inter(15f, FontWeight.Medium), colors.textPrimary, modifier = Modifier.weight(1f))
                    if (step == active) Spinner(colors.textSecondary, Modifier.padding(end = 8.dp), size = 14.dp)
                    ShroudText(status, inter(13f), colors.textSecondary)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (phase == WipePhase.Failed) {
            ShroudText(
                "Still here: ${DeviceWipeController.labels(leftovers)}. Try again — if it keeps failing, restart your $noun and open Shroud; it finishes on its own.",
                inter(14f),
                colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
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

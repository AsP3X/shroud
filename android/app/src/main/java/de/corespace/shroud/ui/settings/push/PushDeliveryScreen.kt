package de.corespace.shroud.ui.settings.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.MenuPicker
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsMetrics
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ToggleRow
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform

/**
 * Settings › Notifications and Sounds › Delivery: the UnifiedPush distributor and its state, the
 * "Background connection" switch and, while it is on, the battery state (decision record 1; plan
 * §1.7.10, §2.4 W3-PUSH "Delivery UI copy"; W3-DESIGN's Delivery frames: one distributor, several
 * to choose, none installed, the server refused the host, background connection on/off with the
 * battery state).
 *
 * Human: While Shroud is closed, Google Play is the default when it is installed. A UnifiedPush
 * distributor the user installs (ntfy, for one) replaces it, or a background connection keeps the
 * server's socket open. Either, both
 * or neither may be on; the screen shows each with what it is doing and, when it cannot deliver,
 * why. Android may pause the background connection to save battery, so its battery state shows
 * while it is on, with a way to Android Settings.
 *
 * Agent: K6 `push.registration` only: `delivery`, `distributors()`, `chooseDistributor`,
 * `setBackgroundConnection` and, on every resume, `onSystemSettingsMaybeChanged` (back from
 * Android Settings or the battery dialog: core re-reads the battery and notification state).
 * K6 has no member to ask for the battery exemption again (core asks once when the switch first
 * turns on), so the battery row opens this app's page in Android Settings (contract gap, see the
 * report). Never registers or forgets on its own.
 */
@Composable
fun PushDeliveryScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val view = LocalView.current
    val model = remember(container) { PushDeliveryModel(container.push.registration, haptic = { view.perform(it) }) }
    // Also the first show: the distributors are read and the system state refreshed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onResume() }
    val delivery by model.delivery.collectAsState()
    val distributors by model.distributors.collectAsState()
    val noun = remember(context) { DeviceNoun.current(context) }
    PushedScreen(DeliveryCopy.TITLE, onBack) {
        PushDeliveryContent(
            state = DeliveryState(delivery, distributors),
            noun = noun,
            onChoose = model::choose,
            onBackgroundConnection = model::setBackgroundConnection,
            onOpenBatterySettings = { openAppSettings(context) },
        )
    }
}

/**
 * The screen's column, in the Notifications screen's rhythm (`NotificationsSettingsView.swift:31-52`):
 * padding h 16, top 8, 14 between groups, each footer 8 under its card, a 24 dp spacer at the end.
 *
 * 1. The intro, 14 `textSecondary`, inset 14.
 * 2. The distributor card: "Push distributor" with the menu of installed distributors and None,
 *    or a plain "None" when none is installed; under it the state line (connected, connecting, or
 *    the reason: `dangerText` for a failure, `textSecondary` for the empty state and None).
 * 3. The background card: the "Background connection" switch and, while it is on, "Battery use"
 *    (Unrestricted, or Optimized in `warningText` with a chevron to Android Settings); the footer,
 *    then — optimized only — the battery warning in `warningText`.
 */
@Composable
internal fun PushDeliveryContent(
    state: DeliveryState,
    noun: String,
    onChoose: (DistributorChoice) -> Unit,
    onBackgroundConnection: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShroudText(
            DeliveryCopy.intro(noun),
            inter(14f),
            colors.textSecondary,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsMetrics.textInset)
                .padding(top = 4.dp, bottom = 4.dp),
        )

        Group {
            SettingsCard { DistributorRow(state, onChoose) }
            state.distributorFooter(noun)?.let { line ->
                FooterLine(line.text, if (line.problem) colors.dangerText else colors.textSecondary)
            }
        }

        Group {
            SettingsCard {
                ToggleRow(
                    title = DeliveryCopy.BACKGROUND_CONNECTION,
                    checked = state.backgroundConnection,
                    onCheckedChange = onBackgroundConnection,
                )
                if (state.showsBattery) {
                    InsetDivider(14.dp)
                    BatteryRow(state.batteryUnrestricted, onOpenBatterySettings)
                }
            }
            FooterLine(DeliveryCopy.BACKGROUND_FOOTER, colors.textSecondary)
            if (state.showsBattery && !state.batteryUnrestricted) FooterLine(DeliveryCopy.BATTERY_WARNING, colors.warningText)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A card and its footers, 8 dp apart (iOS footers pulled up 6 in a 14-spaced stack). */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A footer: 13 sp, wrapping, inset 14 (settings-lock §2.2), in [color]. */
@Composable
private fun FooterLine(text: String, color: Color) {
    ShroudText(
        text,
        inter(13f),
        color,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsMetrics.textInset),
    )
}

/**
 * "Push distributor" (16 `textPrimary`) with its value: the [MenuPicker] (accent value and the
 * up-down caret; a menu of the installed distributors, then None, the current one ticked) when a
 * distributor is installed, else "None" 16 `textSecondary`. 48 dp tall, padding h 14 (the Auto-lock
 * row's picker, `PrivacySecurityView.swift:280-293`).
 */
@Composable
private fun DistributorRow(state: DeliveryState, onChoose: (DistributorChoice) -> Unit) {
    val colors = ShroudTheme.colors
    val choice = state.choice
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .then(if (state.canChoose) Modifier else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = SettingsMetrics.textInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(
            DeliveryCopy.PUSH_DISTRIBUTOR,
            inter(16f),
            colors.textPrimary,
            Modifier
                .weight(1f)
                .then(if (state.canChoose) Modifier.clearAndSetSemantics {} else Modifier),
        )
        if (state.canChoose) {
            MenuPicker(
                value = choice,
                options = state.options,
                label = { it.label },
                onSelect = onChoose,
                contentDescription = DeliveryCopy.PUSH_DISTRIBUTOR,
            )
        } else {
            ShroudText(choice.label, inter(16f), colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * "Battery use": "Unrestricted" (`textSecondary`, nothing to press) or "Optimized" in
 * `warningText` with the chevron, opening this app's page in Android Settings (where its battery
 * use is set). Padding h 14, v 13, like the Sound row; TalkBack reads "Battery use, Optimized".
 */
@Composable
private fun BatteryRow(unrestricted: Boolean, onOpenSettings: () -> Unit) {
    val colors = ShroudTheme.colors
    val value = if (unrestricted) DeliveryCopy.BATTERY_UNRESTRICTED else DeliveryCopy.BATTERY_OPTIMIZED
    val press = if (unrestricted) {
        Modifier.semantics(mergeDescendants = true) {}
    } else {
        Modifier
            .highlightRow(onClick = onOpenSettings)
            .clearAndSetSemantics { contentDescription = "${DeliveryCopy.BATTERY}, $value" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(press)
            .padding(horizontal = SettingsMetrics.textInset, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(DeliveryCopy.BATTERY, inter(16f), colors.textPrimary, Modifier.weight(1f))
        ShroudText(value, inter(16f), if (unrestricted) colors.textSecondary else colors.warningText, maxLines = 1)
        if (!unrestricted) ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
    }
}

/** The picker row's height: the menu picker's 48 dp touch target (settings-lock §2.5). */
private val ROW_HEIGHT = 48.dp

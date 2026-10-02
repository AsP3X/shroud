package de.corespace.shroud.ui.settings.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.SectionFooter
import de.corespace.shroud.ui.components.SectionHeader
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsRow
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * Settings › Devices: this device, the other devices, the device limit, the details sheet with
 * rename and copy, removing one or all of the others (iOS `DevicesView`,
 * `ios/shroud/Features/Main/DevicesView.swift:12-586`; settings-lock §4; design `Devices` `Gchkq`,
 * `Device Details` `nUbf0`).
 *
 * Human: Same data and rules as the iPhone and the web: this phone cannot be removed here (that is
 * Log Out); every other device can, one at a time or all at once, after a confirmation sheet. The
 * account holds at most five devices, so this is also where you make room for a new one. Names are
 * sealed with the phrase, so only your devices read them. Pull down to reload.
 *
 * Agent: [onCount] reports the device count after every load and removal (iOS `onCount`,
 * `:13-14`; the Settings row's value). Not in the published seam (plan §1.7.13) — a defaulted
 * parameter, so `DevicesScreen(onBack)` still compiles; W3-SETTINGS-A passes it (change request).
 */
@Composable
fun DevicesScreen(onBack: () -> Unit, onCount: ((Int) -> Unit)? = null) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val view = LocalView.current
    val toasts = rememberToastState()
    val currentOnCount by rememberUpdatedState(onCount)
    val model = remember(container) {
        DevicesViewModel(
            devices = container.auth.devices,
            actionScope = container.appScope,
            haptic = { view.perform(it) },
            toast = toasts::show,
        )
    }
    LaunchedEffect(model) { model.refresh() }
    val devicesState by model.devicesState.collectAsState()
    val removals by model.removals.collectAsState()
    val state = DevicesUiState(devicesState, removals)
    // The Settings row's value after every load and removal (`onCount`, `DevicesView.swift:13-14`).
    val count = state.rows?.size
    LaunchedEffect(count) { count?.let { currentOnCount?.invoke(it) } }
    val noun = remember(context) { DeviceNoun.current(context) }
    val clock = container.clock
    val is24h = DateFormat.is24HourFormat(context)
    // The app's locale as Compose observes it: a language change re-renders the time labels.
    val locale = LocalConfiguration.current.locales[0]
    val timeLabel: (Instant) -> String = { ChatListFormatting.timeLabel(it, clock.now(), ZoneId.systemDefault(), locale, is24h) }

    var pendingRevoke by remember { mutableStateOf<DeviceRow?>(null) }
    var showRevokeAllConfirm by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DeviceRow?>(null) }
    // Remove in the details: the confirmation waits until the sheet has gone (`DevicesView.swift:30-32, 110-113`).
    var revokeAfterSheet by remember { mutableStateOf<DeviceRow?>(null) }
    LaunchedEffect(revokeAfterSheet, detail) {
        val target = revokeAfterSheet ?: return@LaunchedEffect
        if (detail != null) return@LaunchedEffect
        delay(SHEET_EXIT_MS)
        pendingRevoke = target
        revokeAfterSheet = null
    }

    Box(Modifier.fillMaxSize()) {
        RefreshablePushedScreen(DevicesCopy.TITLE, onBack, onRefresh = { model.refresh() }) {
            DevicesContent(
                state = state,
                noun = noun,
                timeLabel = timeLabel,
                onRetry = { model.refresh() },
                onOpen = { detail = it },
                onRemove = { pendingRevoke = it },
                onRemoveAll = { showRevokeAllConfirm = true },
            )
        }
        ToastHost(toasts)
    }

    // The newest copy of the open device: a rename or reload shows at once (`DevicesView.swift:575-576`).
    val shownDevice = detail?.let(model::latest)
    DeviceDetailSheet(
        device = shownDevice,
        isRevoking = shownDevice?.let(state::isRevoking) ?: false,
        noun = noun,
        lastActive = { DevicesCopy.lastActive(it, timeLabel) },
        formatDate = ::longDateTime,
        onRename = model::rename,
        onCopyId = { device -> copyDeviceId(context, device) },
        onRevoke = { device ->
            revokeAfterSheet = device
            detail = null
        },
        onDismiss = { detail = null },
    )

    val confirming = pendingRevoke
    ActionSheet(
        visible = confirming != null,
        title = DevicesCopy.confirmTitle(confirming?.let(state::displayName)),
        message = DevicesCopy.CONFIRM_MESSAGE_ONE,
        items = listOf(ActionSheetItem(DevicesCopy.REMOVE, destructive = true) { confirming?.let(model::revoke) }),
        onDismiss = { pendingRevoke = null },
    )
    ActionSheet(
        visible = showRevokeAllConfirm,
        title = DevicesCopy.CONFIRM_ALL_TITLE,
        message = DevicesCopy.confirmAllMessage(noun),
        items = listOf(ActionSheetItem(DevicesCopy.confirmAllButton(state.others.size), destructive = true) { model.revokeAllOthers() }),
        onDismiss = { showRevokeAllConfirm = false },
    )
}

/**
 * The list under the bar (`DevicesView.swift:48-72, 151-251`): loading card, load error, or the
 * loaded sections. Padding h 16, top 8, a 24 dp spacer at the end. List changes animate with
 * `Motion.standard` (a removed row folds away), the error lines fade.
 */
@Composable
internal fun DevicesContent(
    state: DevicesUiState,
    noun: String,
    timeLabel: (Instant) -> String,
    onRetry: suspend () -> Unit,
    onOpen: (DeviceRow) -> Unit,
    onRemove: (DeviceRow) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .animateContentSize(Motion.respecting(reduceMotion, Motion.standard())),
    ) {
        val devices = state.rows
        val loadError = state.loadError
        when {
            devices != null -> LoadedDevices(state, devices, noun, timeLabel, onOpen, onRemove, onRemoveAll)
            loadError != null -> ListLoadError(DevicesCopy.LOAD_ERROR_TITLE, loadError, onRetry)
            else -> LoadingCard()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoadingCard() {
    val colors = ShroudTheme.colors
    SettingsCard(Modifier.padding(top = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spinner(colors.textSecondary)
            ShroudText(DevicesCopy.LOADING, inter(14f), colors.textSecondary)
        }
    }
}

@Composable
private fun LoadedDevices(
    state: DevicesUiState,
    devices: List<DeviceRow>,
    noun: String,
    timeLabel: (Instant) -> String,
    onOpen: (DeviceRow) -> Unit,
    onRemove: (DeviceRow) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val others = state.others
    val current = state.current

    DevicesHeader(DevicesCopy.THIS_DEVICE)
    SettingsCard {
        if (current != null) {
            DeviceListRow(current, state, noun, timeLabel, onOpen, onRemove)
        } else {
            // The server always lists the calling device; a miss means the list is stale (`:158-165`).
            ShroudText(
                DevicesCopy.missingFromList(noun),
                inter(14f),
                colors.textSecondary,
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            )
        }
    }

    if (others.isNotEmpty()) {
        SettingsCard(Modifier.padding(top = 14.dp)) {
            SettingsRow(
                title = if (state.isRevokingAll) DevicesCopy.REMOVING_ALL else DevicesCopy.REMOVE_ALL,
                icon = ShroudIcons.Hand,
                tile = colors.danger,
                destructive = true,
                busy = state.isRevokingAll,
                enabled = !state.isRemoving,
                onClick = onRemoveAll,
            )
        }
        DevicesFooter(DevicesCopy.removeAllFooter(noun))
    }

    DevicesHeader(DevicesCopy.otherDevicesHeader(others.size), Modifier.padding(top = if (others.isEmpty()) 14.dp else 8.dp))
    SettingsCard {
        if (others.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShroudText(DevicesCopy.NO_OTHER_DEVICES, inter(16f), colors.textPrimary)
                ShroudText(DevicesCopy.NO_OTHER_DEVICES_HINT, inter(13f), colors.textSecondary)
            }
        } else {
            others.forEachIndexed { index, device ->
                key(device.id) {
                    DeviceListRow(device, state, noun, timeLabel, onOpen, onRemove)
                    if (index < others.lastIndex) InsetDivider(56.dp)
                }
            }
        }
    }

    AnimatedVisibility(visible = state.actionError != null, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
        // Keeps the last text while it fades out.
        val text = remember { LastText() }
        state.actionError?.let { text.value = it }
        ShroudText(
            text.value,
            inter(13f),
            colors.danger,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp),
        )
    }

    DevicesHeader(DevicesCopy.DEVICE_LIMIT_HEADER, Modifier.padding(top = 8.dp))
    SettingsCard { CapacityRow(devices.size) }
    DevicesFooter(DevicesCopy.capacityFooter(devices.size))

    SettingsCard(Modifier.padding(top = 14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoTitle(DevicesCopy.INFO_TITLE)
            ShroudText(DevicesCopy.INFO_BODY, inter(13f), colors.textSecondary)
        }
    }
}

/** Holder of the last shown error line during its fade-out (not state: written while shown only). */
private class LastText {
    var value: String = ""
}

/** Devices' header: the kit's, with 6 dp above and below (`DevicesView.swift:399-407`). */
@Composable
private fun DevicesHeader(text: String, modifier: Modifier = Modifier) {
    SectionHeader(text, modifier.padding(top = 6.dp, bottom = 6.dp))
}

/** Devices' footer: 6 dp above, 8 below (`DevicesView.swift:409-417`). */
@Composable
private fun DevicesFooter(text: String) {
    SectionFooter(text, Modifier.padding(top = 6.dp, bottom = 8.dp))
}

/** A `Label` with Phosphor `shield-check-fill`: 15 SemiBold `accent` (`DevicesView.swift:222-224`). */
@Composable
internal fun InfoTitle(text: String) {
    val colors = ShroudTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ShroudIcon(ShroudIcons.ShieldCheckFill, colors.accent, size = 16.dp)
        ShroudText(text, inter(15f, FontWeight.SemiBold), colors.accent)
    }
}

/**
 * One device (`deviceRow`, `DevicesView.swift:253-325`): the tile, the name and its status line open
 * the details (the whole row height; plain press, light haptic on tap); this phone shows a chevron,
 * the others a Remove button (48 dp target) or a spinner while removing. Trailing padding 14.
 */
@Composable
private fun DeviceListRow(
    device: DeviceRow,
    state: DevicesUiState,
    noun: String,
    timeLabel: (Instant) -> String,
    onOpen: (DeviceRow) -> Unit,
    onRemove: (DeviceRow) -> Unit,
) {
    val colors = ShroudTheme.colors
    val view = LocalView.current
    val current = device.isThisDevice
    val name = state.displayName(device)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) {
                    view.perform(Haptic.Light)
                    onOpen(device)
                }
                .semantics(mergeDescendants = true) {}
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceTile(device.kind, 30.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText(name, inter(16f), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colors.online),
                        )
                        ShroudText(DevicesCopy.activeNow(noun), inter(13f), colors.accent)
                    }
                } else {
                    ShroudText(
                        DevicesCopy.lastActive(device, timeLabel),
                        inter(13f),
                        colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (current) ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
        }
        if (!current) {
            if (state.isRevoking(device)) {
                Box(Modifier.widthIn(min = 60.dp), contentAlignment = Alignment.Center) {
                    Spinner(colors.textSecondary, size = 16.dp)
                }
            } else {
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {
                            view.perform(Haptic.Light)
                            onRemove(device)
                        }
                        .semantics { contentDescription = DevicesCopy.removeLabel(name) },
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudText(
                        DevicesCopy.REMOVE,
                        inter(15f, FontWeight.Medium),
                        colors.danger,
                        Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/**
 * "Linked devices" with "<n> of 5" and a five-step meter (`capacityRow`, `DevicesView.swift:357-383`):
 * `warningText` / `warningIcon` at the limit; the meter is hidden from TalkBack, the row reads as one.
 */
@Composable
private fun CapacityRow(count: Int) {
    val colors = ShroudTheme.colors
    val full = DevicesCopy.isFull(count)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShroudText(DevicesCopy.LINKED_DEVICES, inter(16f), colors.textPrimary, Modifier.weight(1f))
            ShroudText(
                DevicesCopy.capacityValue(count),
                inter(16f, tabularDigits = true),
                if (full) colors.warningText else colors.textSecondary,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(DEVICE_LIMIT) { index ->
                val fill = when {
                    index >= count -> colors.separator
                    full -> colors.warningIcon
                    else -> colors.accent
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(fill),
                )
            }
        }
    }
}

/** `createdAt.formatted(date: .long, time: .shortened)` (`DevicesView.swift:651`): ICU long date, short time. */
private fun longDateTime(instant: Instant): String =
    android.icu.text.DateFormat.getDateTimeInstance(android.icu.text.DateFormat.LONG, android.icu.text.DateFormat.SHORT).format(Date.from(instant))

/** Copies the lower-case id; the sheet confirms it (`copyID`, `DevicesView.swift:581-585`). */
private fun copyDeviceId(context: Context, device: DeviceRow) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(DevicesCopy.DEVICE_ID, Ids.wire(device.id)))
}

/**
 * How long the details sheet takes to leave before the removal confirmation rises: the sheet's
 * `Motion.standard` exit settles in about this time (iOS presents the alert on the sheet's dismissal).
 */
private const val SHEET_EXIT_MS = 350L

package de.corespace.shroud.ui.settings.privacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.VaultKeyStore
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.IconTile
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.MenuPicker
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToggleRow
import de.corespace.shroud.ui.components.ToggleRowSpacing
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.shell.LocalAppActions
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.CancellationException

/**
 * Settings › Privacy and Security (iOS `PrivacySecurityView`,
 * `ios/shroud/Features/Main/PrivacySecurityView.swift:4-643`; settings-lock §7; P3c, P5, P13b —
 * built from iOS, the design's `YkjlJ` frame is stale and W3-DESIGN redraws it).
 *
 * Human: When the chats lock after leaving the app; hiding them while the screen is recorded, cast
 * or shared; what contacts see (read receipts, typing, online and last seen — both ways); who can
 * find you and retiring the QR code; whether a contact may clear your copy of a chat; link
 * previews; relaying calls; locking the chats now; the blocked list; and how the history is
 * sealed on this phone. The server switches wait for the server's values and say so meanwhile.
 *
 * Agent: server switches through `ContactsModule.privacy` (`PUT /privacy/settings`, one field each),
 * blocks through `ContactsModule.controller`, local switches in `SecurityPreferences`, the lock
 * through `AppActions.lockChatsNow` (W3-SHELL). The biometric word is re-read on every resume; the
 * software-Keystore notice (P3c) asks core where the vault's wrap key lives (core reads it off main).
 */
@Composable
fun PrivacySecurityScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val view = LocalView.current
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val privacy = container.contacts.privacy
    val contacts = container.contacts.controller
    val security = container.keys.securityPreferences
    val noun = remember(context) { DeviceNoun.current(context) }
    val model = remember(container, actions) {
        PrivacySecurityModel(
            privacy = privacy,
            security = security,
            refreshBlocks = contacts::refreshBlocks,
            unblockUser = contacts::unblock,
            lockChatsNow = actions::lockChatsNow,
            scope = scope,
            actionScope = container.appScope,
            haptic = { view.perform(it) },
            toast = toasts::show,
        )
    }
    LaunchedEffect(model) { model.loadServerSettings() }

    val state by model.state.collectAsState()
    val settings by privacy.settings.collectAsState()
    val hasLoaded by privacy.hasLoaded.collectAsState()
    val blocked by contacts.blocked.collectAsState()
    val autoLock by security.autoLockDelay.collectAsState()
    val hideCapture by security.hidesDuringScreenCapture.collectAsState()
    val linkPreviews by security.generatesLinkPreviews.collectAsState()
    val relayCalls by security.alwaysRelayCalls.collectAsState()

    // The enrolled biometric may change while Shroud is in the background (`:35-36`; settings-lock §7.3).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val deviceSecurity = container.keys.deviceSecurity
    val biometric = remember(resumes) {
        runCatching { if (deviceSecurity.strongBiometricAvailable()) deviceSecurity.biometricLabel() else null }.getOrNull()
    }
    // P3c: core reads the vault record on its own IO dispatcher (`CryptoController.vaultKeySecurity`).
    val softwareKeystore by produceState(false, container) {
        value = try {
            container.keys.cryptoController.vaultKeySecurity() == VaultKeyStore.Security.Software
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    var confirmingShareCodeReset by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PushedScreen(PrivacyCopy.TITLE, onBack) {
            PrivacyContent(
                state = state,
                settings = settings,
                hasLoaded = hasLoaded,
                blocked = blocked,
                autoLock = autoLock,
                hideCapture = hideCapture,
                linkPreviews = linkPreviews,
                relayCalls = relayCalls,
                biometric = biometric,
                softwareKeystore = softwareKeystore,
                noun = noun,
                callbacks = PrivacyCallbacks(
                    onAutoLock = model::setAutoLockDelay,
                    onHideCapture = model::setHidesDuringScreenCapture,
                    onRetry = model::retry,
                    onFlip = model::flip,
                    onResetShareCode = { confirmingShareCodeReset = true },
                    onLinkPreviews = model::setGeneratesLinkPreviews,
                    onRelayCalls = model::setAlwaysRelayCalls,
                    onLockNow = model::lockNow,
                    onUnblock = model::unblock,
                ),
            )
        }
        ToastHost(toasts)
    }

    // An action sheet (C22, P13c) where iOS has an alert (`:146-152`).
    ActionSheet(
        visible = confirmingShareCodeReset,
        title = PrivacyCopy.RESET_QR_TITLE,
        message = PrivacyCopy.RESET_QR_MESSAGE,
        items = listOf(ActionSheetItem(PrivacyCopy.RESET, destructive = true) { model.resetShareCode() }),
        onDismiss = { confirmingShareCodeReset = false },
    )
}

/** What the rows of [PrivacyContent] do. */
internal class PrivacyCallbacks(
    val onAutoLock: (AutoLockDelay) -> Unit,
    val onHideCapture: (Boolean) -> Unit,
    val onRetry: () -> Unit,
    val onFlip: (PrivacySwitch, Boolean) -> Unit,
    val onResetShareCode: () -> Unit,
    val onLinkPreviews: (Boolean) -> Unit,
    val onRelayCalls: (Boolean) -> Unit,
    val onLockNow: () -> Unit,
    val onUnblock: (BlockItemDto) -> Unit,
)

/**
 * The screen's column (`PrivacySecurityView.swift:38-139`): padding h 16, top 8, 14 between the
 * cards, a 24 dp spacer at the end. The load status and the blocked card come and go with
 * `Motion.standard` (the cards below close the gap), the failed line fades.
 */
@Composable
internal fun PrivacyContent(
    state: PrivacyState,
    settings: PrivacySettingsDto,
    hasLoaded: Boolean,
    blocked: List<BlockItemDto>,
    autoLock: AutoLockDelay,
    hideCapture: Boolean,
    linkPreviews: Boolean,
    relayCalls: Boolean,
    biometric: BiometricLabel?,
    softwareKeystore: Boolean,
    noun: String,
    callbacks: PrivacyCallbacks,
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .animateContentSize(Motion.respecting(reduceMotion, Motion.standard())),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsCard { AutoLockRow(autoLock, biometric, callbacks.onAutoLock) }

        SettingsCard {
            ToggleRow(
                title = PrivacyCopy.HIDE_CAPTURE,
                subtitle = PrivacyCopy.hideCaptureDetail(),
                checked = hideCapture,
                spacing = ToggleRowSpacing.Privacy,
                onCheckedChange = callbacks.onHideCapture,
            )
        }

        // The switches below wait for the server's values; say so instead of showing the defaults (`:50-54`).
        AnimatedVisibility(visible = !hasLoaded, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            LoadStatus(state.loadFailed, callbacks.onRetry)
        }

        SettingsCard {
            ShroudText(
                PrivacyCopy.VISIBILITY,
                inter(15f, FontWeight.SemiBold),
                colors.textPrimary,
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .padding(horizontal = 14.dp)
                    .padding(top = 12.dp, bottom = 2.dp),
            )
            PrivacySwitch.visibility.forEachIndexed { index, switch ->
                if (index > 0) InsetDivider(14.dp)
                ServerToggle(switch, state, settings, hasLoaded, callbacks.onFlip)
            }
            ShroudText(
                PrivacyCopy.VISIBILITY_FOOTNOTE,
                inter(13f),
                colors.textSecondary,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
            )
        }

        SettingsCard {
            ShroudText(
                PrivacyCopy.FINDING_YOU,
                inter(13f),
                colors.textSecondary,
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            )
            InsetDivider(14.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = callbacks.onResetShareCode, enabled = !state.resettingShareCode)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ShroudText(PrivacyCopy.RESET_QR, inter(16f), colors.danger)
                ShroudText(PrivacyCopy.RESET_QR_DETAIL, inter(13f), colors.textSecondary)
            }
        }

        SettingsCard { ServerToggle(PrivacySwitch.ChatDelete, state, settings, hasLoaded, callbacks.onFlip) }

        SettingsCard {
            ToggleRow(
                title = PrivacyCopy.LINK_PREVIEWS,
                subtitle = PrivacyCopy.linkPreviewsDetail(noun),
                checked = linkPreviews,
                spacing = ToggleRowSpacing.Privacy,
                onCheckedChange = callbacks.onLinkPreviews,
            )
        }

        SettingsCard {
            ToggleRow(
                title = PrivacyCopy.RELAY_CALLS,
                subtitle = PrivacyCopy.relayCallsDetail(noun),
                checked = relayCalls,
                spacing = ToggleRowSpacing.Privacy,
                onCheckedChange = callbacks.onRelayCalls,
            )
        }

        SettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = callbacks.onLockNow)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(ShroudIcons.LockFill, colors.danger, Modifier.clearAndSetSemantics {})
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShroudText(PrivacyCopy.LOCK_NOW, inter(16f), colors.textPrimary)
                    ShroudText(PrivacyCopy.LOCK_NOW_DETAIL, inter(13f), colors.textSecondary)
                }
            }
        }

        // Blocking happens on the profile; this is the only place it can be undone (`:202-203`).
        val shownBlocked = remember { LastBlocked() }
        if (blocked.isNotEmpty()) shownBlocked.value = blocked
        AnimatedVisibility(visible = blocked.isNotEmpty(), enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            SettingsCard { BlockedSection(shownBlocked.value, state.unblocking, callbacks.onUnblock) }
        }

        SettingsCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShroudIcon(ShroudIcons.ShieldCheckFill, colors.accent, size = 16.dp)
                    ShroudText(PrivacyCopy.ENCRYPTED_TITLE, inter(15f, FontWeight.SemiBold), colors.accent)
                }
                ShroudText(PrivacyCopy.ENCRYPTED_BODY, inter(13f), colors.textSecondary)
                if (softwareKeystore) {
                    ShroudText(PrivacyCopy.softwareKeystoreNotice(noun), inter(13f), colors.warningText)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** The last non-empty blocked list, kept while the card fades out (not state: written while shown only). */
private class LastBlocked {
    var value: List<BlockItemDto> = emptyList()
}

/**
 * "Auto-lock" with the menu of delays and the footnote (`autoLockRow`, `:278-312`): padding h 14,
 * v 12, spacing 4.
 */
@Composable
private fun AutoLockRow(value: AutoLockDelay, biometric: BiometricLabel?, onSelect: (AutoLockDelay) -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShroudText(
                PrivacyCopy.AUTO_LOCK,
                inter(16f),
                colors.textPrimary,
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
            )
            MenuPicker(
                value = value,
                options = AutoLockDelay.entries,
                label = { it.label },
                onSelect = onSelect,
                contentDescription = PrivacyCopy.AUTO_LOCK,
            )
        }
        ShroudText(PrivacyCopy.autoLockFootnote(biometric), inter(13f), colors.textSecondary)
    }
}

/**
 * Stands above the server switches until their values arrive (`privacyLoadStatus`, `:170-200`): a
 * spinner while loading; "Couldn't load these settings." and Try Again when the load failed.
 */
@Composable
private fun LoadStatus(failed: Boolean, onRetry: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) {
            ShroudText(PrivacyCopy.LOAD_FAILED, inter(13f), colors.dangerText, Modifier.weight(1f))
            ShroudText(
                PrivacyCopy.TRY_AGAIN,
                inter(15f, FontWeight.SemiBold),
                colors.accent,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onRetry() },
            )
        } else {
            Spinner(colors.accent, size = 14.dp)
            ShroudText(PrivacyCopy.LOADING, inter(13f), colors.textSecondary, Modifier.weight(1f))
        }
    }
}

/** A server switch on the shared pattern ([PrivacyState.displayed], [PrivacyState.isEnabled]). */
@Composable
private fun ServerToggle(
    switch: PrivacySwitch,
    state: PrivacyState,
    settings: PrivacySettingsDto,
    hasLoaded: Boolean,
    onFlip: (PrivacySwitch, Boolean) -> Unit,
) {
    ToggleRow(
        title = switch.title,
        subtitle = switch.detail,
        checked = state.displayed(switch, settings),
        enabled = state.isEnabled(switch, hasLoaded),
        spacing = ToggleRowSpacing.Privacy,
    ) { onFlip(switch, it) }
}

/**
 * The blocked users with a way out (`blockedContactsSection`, `:202-255`): "Blocked" 15 SemiBold,
 * rows of avatar 32, username and Unblock (48 dp rows), dividers inset 58, a 6 dp spacer. Each row
 * is one TalkBack element, "Unblock <username>", whose action unblocks (`:242-244`).
 */
@Composable
private fun BlockedSection(blocked: List<BlockItemDto>, unblocking: Set<java.util.UUID>, onUnblock: (BlockItemDto) -> Unit) {
    val colors = ShroudTheme.colors
    ShroudText(
        PrivacyCopy.BLOCKED,
        inter(15f, FontWeight.SemiBold),
        colors.textPrimary,
        Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = 6.dp),
    )
    blocked.forEachIndexed { index, item ->
        key(item.userId) {
            val busy = item.userId in unblocking
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = PrivacyCopy.unblockLabel(item.username)
                        role = Role.Button
                        if (busy) disabled()
                        onClick(label = PrivacyCopy.UNBLOCK) {
                            if (!busy) onUnblock(item)
                            !busy
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NameAvatar(item.username, seed = AvatarPalette.seed(item.username, item.userId), size = 32.dp, fontSize = 13.sp)
                ShroudText(
                    item.username,
                    inter(16f),
                    colors.textPrimary,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !busy,
                            role = Role.Button,
                        ) { onUnblock(item) }
                        .padding(start = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudText(PrivacyCopy.UNBLOCK, inter(15f, FontWeight.Medium), colors.accent)
                }
            }
            if (index < blocked.lastIndex) InsetDivider(58.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
}

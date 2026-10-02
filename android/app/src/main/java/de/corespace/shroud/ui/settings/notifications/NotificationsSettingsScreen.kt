package de.corespace.shroud.ui.settings.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.ConversationPeerDto
import de.corespace.shroud.core.notifications.NotificationPrefsState
import de.corespace.shroud.core.push.UnifiedPushState
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SectionFooter
import de.corespace.shroud.ui.components.SectionHeader
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToggleRow
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.permissions.openNotificationSettings
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.settings.push.PushDeliverySection
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.SettingsRoute
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/**
 * Settings › Notifications and Sounds (iOS `NotificationsSettingsView`,
 * `ios/shroud/Features/Main/NotificationsSettingsView.swift:13-458`; settings-lock §6.1, §6.4;
 * notifications-push §5.14, N14; design `Notifications and Sounds` `j7NxDm`, `Notifications —
 * Denied` `jnjw6`).
 *
 * Human: Two kinds of notification, set apart on purpose. Notifications while Shroud is closed or
 * locked (UnifiedPush or the background connection, the Delivery section at the top) never hold
 * message text; in-app banners while it is open may show it. One card says when Android keeps
 * them away — not allowed yet, turned off for Shroud, the Messages channel off, or Shroud
 * restricted in the background. Mutes live on the chat and follow the account everywhere.
 *
 * Agent: preferences from W2-NOTIF's `NotificationPreferences` (switches write at once; the server's
 * fields are saved 500 ms after the last change); the permission and the system blocks are re-read
 * on every resume (back from Android Settings, `:58-62`); the muted chats come from messaging.
 */
@SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only asked for where it exists (the card shows from API 33).
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit, onOpenSound: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val view = LocalView.current
    val navigation = LocalShellNavigation.current
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val notifications = container.notifications
    val controller = notifications.controller
    val messaging = container.messaging.controller
    val session = container.auth.sessionController.session
    val noun = remember(context) { DeviceNoun.current(context) }
    val model = remember(container) {
        NotificationsSettingsModel(
            preferences = notifications.preferences,
            pushSettings = controller::pushSettings,
            sendTest = controller::sendTest,
            token = { session.value?.token },
            updateBadge = messaging::updateBadge,
            unmuteChat = messaging::unmuteChat,
            noun = noun,
            scope = scope,
            haptic = { view.perform(it) },
            toast = toasts::show,
        )
    }
    val prefs by notifications.preferences.state.collectAsState()
    val state by model.state.collectAsState()
    val authorization by controller.authorization.collectAsState()
    val delivery by container.push.registration.delivery.collectAsState()
    val conversations by messaging.conversations.collectAsState()

    // Back from Android Settings, the permission, the channel or the background rule may have changed.
    var systemTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemTick++
        scope.launch { controller.refreshAuthorization() }
    }
    val channelBlocked = remember(systemTick, prefs.sound, prefs.badge) {
        runCatching { notifications.channels.messagesChannelBlocked() }.getOrDefault(false)
    }
    val restricted = remember(systemTick) { BackgroundRestriction.isRestricted(context) }
    val notice = SystemNotice.of(authorization, channelBlocked, restricted)

    val requestPermission = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS) { _, permanentlyDenied ->
        scope.launch { controller.refreshAuthorization() }
        // A grant changes what the server may push (effective `enabled`, notifications-push §5.10.4).
        container.push.registration.onSystemSettingsMaybeChanged()
        if (permanentlyDenied) openNotificationSettings(context)
    }

    val clock = container.clock
    val is24h = DateFormat.is24HourFormat(context)
    // The app's locale as Compose observes it: a language change re-renders the mute labels.
    val locale = LocalConfiguration.current.locales[0]
    val muted = conversations.filter { messaging.isMuted(it.peer.id) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PushedScreen(NotificationsCopy.TITLE, onBack) {
            NotificationsContent(
                prefs = prefs,
                state = state,
                notice = notice,
                noun = noun,
                unifiedPush = delivery.unifiedPush is UnifiedPushState.Registered,
                muted = muted.map { conversation ->
                    val label = MuteDuration.label(messaging.mute(conversation.peer.id), clock.now(), ZoneId.systemDefault(), locale, is24h)
                    MutedChat(conversation.peer, label ?: NotificationsCopy.MUTED)
                },
                deliverySection = { PushDeliverySection(onOpen = { navigation.push(SettingsRoute.PushDelivery) }) },
                onSwitch = model::set,
                onOpenSound = onOpenSound,
                onNoticeAction = { action ->
                    when (action) {
                        SystemNotice.AllowNotifications -> {
                            controller.markPermissionRequested()
                            requestPermission()
                        }
                        SystemNotice.AppBlocked -> openNotificationSettings(context)
                        SystemNotice.MessagesChannelOff -> openNotificationSettings(context, notifications.channels.messages())
                        SystemNotice.BackgroundRestricted -> openAppSettings(context)
                    }
                },
                onUnmute = model::unmute,
                onSendTest = model::sendTestNotification,
                onReset = { showResetConfirm = true },
            )
        }
        ToastHost(toasts)
    }

    ActionSheet(
        visible = showResetConfirm,
        title = NotificationsCopy.RESET_TITLE,
        message = NotificationsCopy.RESET_MESSAGE,
        items = listOf(ActionSheetItem(NotificationsCopy.RESET_BUTTON, destructive = true) { model.reset() }),
        onDismiss = { showResetConfirm = false },
    )
}

/** A muted chat's row data: who, and "Muted until 14:30" / "Muted". */
internal data class MutedChat(val peer: ConversationPeerDto, val label: String)

/**
 * The screen's column (`NotificationsSettingsView.swift:31-52`): padding h 16, top 8, 14 between
 * the cards, a 24 dp spacer at the end. [deliverySection] is W3-PUSH's Delivery section, placed at
 * the top (plan §1.7.13).
 */
@Composable
internal fun NotificationsContent(
    prefs: NotificationPrefsState,
    state: NotificationsSettingsState,
    notice: SystemNotice?,
    noun: String,
    unifiedPush: Boolean,
    muted: List<MutedChat>,
    deliverySection: @Composable () -> Unit,
    onSwitch: (NotificationSwitch, Boolean) -> Unit,
    onOpenSound: () -> Unit,
    onNoticeAction: (SystemNotice) -> Unit,
    onUnmute: (UUID) -> Unit,
    onSendTest: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        deliverySection()

        if (notice != null) NoticeCard(notice, onNoticeAction)

        SettingsCard {
            Toggle(NotificationSwitch.Enabled, NotificationsCopy.SHOW_NOTIFICATIONS, NotificationsCopy.showNotificationsDetail(noun), prefs, onSwitch)
            InsetDivider(14.dp)
            // Not tied to Show Notifications: in-app banners name the sender by it too (`:152`).
            Toggle(NotificationSwitch.ShowSender, NotificationsCopy.SHOW_SENDER, NotificationsCopy.SHOW_SENDER_DETAIL, prefs, onSwitch)
            InsetDivider(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = onOpenSound)
                    .clearAndSetSemantics { contentDescription = NotificationsCopy.soundLabel(prefs.sound) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudText(NotificationsCopy.SOUND, inter(16f), colors.textPrimary, Modifier.weight(1f))
                ShroudText(prefs.sound.title, inter(16f), colors.textSecondary, maxLines = 1)
                ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
            }
        }
        SectionFooter(NotificationsCopy.pushFooter(noun, unifiedPush), pullUp = 6.dp)

        Section(NotificationsCopy.ALSO_NOTIFY) {
            Toggle(NotificationSwitch.Reactions, NotificationsCopy.REACTIONS, null, prefs, onSwitch)
            InsetDivider(14.dp)
            Toggle(NotificationSwitch.ContactRequests, NotificationsCopy.CONTACT_REQUESTS, null, prefs, onSwitch)
        }

        Section(NotificationsCopy.IN_APP) {
            Toggle(NotificationSwitch.InAppBanners, NotificationsCopy.BANNERS, null, prefs, onSwitch)
            InsetDivider(14.dp)
            Toggle(
                NotificationSwitch.ShowPreview,
                NotificationsCopy.MESSAGE_PREVIEW,
                NotificationsCopy.MESSAGE_PREVIEW_DETAIL,
                prefs,
                onSwitch,
                enabled = prefs.inAppBanners,
            )
            InsetDivider(14.dp)
            Toggle(NotificationSwitch.InAppSounds, NotificationsCopy.SOUNDS, null, prefs, onSwitch)
            InsetDivider(14.dp)
            Toggle(NotificationSwitch.InAppVibrate, NotificationsCopy.VIBRATE, null, prefs, onSwitch)
        }
        SectionFooter(NotificationsCopy.IN_APP_FOOTER, pullUp = 6.dp)

        Section(NotificationsCopy.BADGE_COUNTER) {
            Toggle(NotificationSwitch.Badge, NotificationsCopy.SHOW_BADGE, NotificationsCopy.SHOW_BADGE_DETAIL, prefs, onSwitch)
            InsetDivider(14.dp)
            // Also counts for the Chats tab, so not tied to Show Badge (`:224`).
            Toggle(NotificationSwitch.BadgeIncludesMuted, NotificationsCopy.INCLUDE_MUTED, null, prefs, onSwitch)
        }

        Section(NotificationsCopy.MUTED_CHATS) {
            if (muted.isEmpty()) {
                ShroudText(
                    NotificationsCopy.NO_MUTED_CHATS,
                    inter(14f),
                    colors.textSecondary,
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                )
            } else {
                muted.forEachIndexed { index, chat ->
                    key(chat.peer.id) {
                        MutedRow(chat, unmuting = chat.peer.id in state.unmuting, onUnmute = onUnmute)
                        if (index < muted.lastIndex) InsetDivider(58.dp)
                    }
                }
            }
        }

        TestRow(isTesting = state.isTesting, enabled = !state.isTesting && prefs.enabled, onSendTest = onSendTest)
        AnimatedVisibility(visible = state.testResult != null, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            val shown = remember { LastResult() }
            state.testResult?.let { shown.value = it }
            SectionFooter(shown.value, pullUp = 6.dp)
        }

        SettingsCard {
            ShroudText(
                NotificationsCopy.RESET,
                inter(16f),
                colors.danger,
                Modifier
                    .fillMaxWidth()
                    .highlightRow(onClick = onReset)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** The last result sentence, kept while it fades out (not state: written while shown only). */
private class LastResult {
    var value: String = ""
}

/** A header over a card, 6 dp apart (`NotificationsSettingsView.swift:184-191`). */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(title)
        SettingsCard { content() }
    }
}

/** A switch row bound to a preference (`toggleRow`, `:406-423`). */
@Composable
private fun Toggle(
    switch: NotificationSwitch,
    title: String,
    subtitle: String?,
    prefs: NotificationPrefsState,
    onSwitch: (NotificationSwitch, Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ToggleRow(title = title, subtitle = subtitle, checked = switch.value(prefs), enabled = enabled) { onSwitch(switch, it) }
}

/**
 * The card above the switches (iOS `permissionCard`, `:84-140`; notifications-push §5.14.2):
 * padding 14, spacing 10 — the titled glyph, the explanation 13 `textSecondary`, the action 15
 * SemiBold `accent`. Allow uses `accent`, the blocks `danger`, the background restriction
 * `textPrimary` (design N12: Phosphor `bell-slash-fill` in `danger` for a block).
 */
@Composable
private fun NoticeCard(notice: SystemNotice, onAction: (SystemNotice) -> Unit) {
    val colors = ShroudTheme.colors
    val (icon, tint, title, body, action) = when (notice) {
        SystemNotice.AllowNotifications -> NoticeLook(ShroudIcons.BellRingingFill, colors.accent, NotificationsCopy.ALLOW_TITLE, NotificationsCopy.ALLOW_BODY, NotificationsCopy.ALLOW_BUTTON)
        SystemNotice.AppBlocked -> NoticeLook(ShroudIcons.BellSlashFill, colors.danger, NotificationsCopy.BLOCKED_TITLE, NotificationsCopy.BLOCKED_BODY, NotificationsCopy.OPEN_SETTINGS)
        SystemNotice.MessagesChannelOff -> NoticeLook(ShroudIcons.BellSlashFill, colors.danger, NotificationsCopy.CHANNEL_OFF_TITLE, NotificationsCopy.CHANNEL_OFF_BODY, NotificationsCopy.OPEN_SETTINGS)
        // Phosphor `battery-warning-fill` is not in the kit yet (change request); the warning glyph stands in.
        SystemNotice.BackgroundRestricted -> NoticeLook(ShroudIcons.WarningFill, colors.textPrimary, NotificationsCopy.RESTRICTED_TITLE, NotificationsCopy.RESTRICTED_BODY, NotificationsCopy.OPEN_SETTINGS)
    }
    SettingsCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(icon, tint, size = 17.dp)
                ShroudText(title, inter(16f, FontWeight.SemiBold), tint)
            }
            ShroudText(body, inter(13f), colors.textSecondary)
            // A small target: Compose widens it to 48 dp for touch without moving the text.
            ShroudText(
                action,
                inter(15f, FontWeight.SemiBold),
                colors.accent,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onAction(notice) },
            )
        }
    }
}

private data class NoticeLook(val icon: ImageVector, val tint: Color, val title: String, val body: String, val action: String)

/**
 * A muted chat (`mutedRow`, `:256-289`): avatar 32 (username gradient, initials 13), the name and the
 * mute's end, and Unmute (disabled while it runs). Padding h 14, v 8.
 */
@Composable
private fun MutedRow(chat: MutedChat, unmuting: Boolean, onUnmute: (UUID) -> Unit) {
    val colors = ShroudTheme.colors
    val username = chat.peer.username
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAvatar(username, seed = AvatarPalette.seed(username, chat.peer.id), size = 32.dp, fontSize = 13.sp)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ShroudText(username, inter(16f), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ShroudText(chat.label, inter(13f), colors.textSecondary)
        }
        ShroudText(
            NotificationsCopy.UNMUTE,
            inter(15f, FontWeight.Medium),
            colors.accent,
            Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !unmuting,
                    role = Role.Button,
                ) { onUnmute(chat.peer.id) }
                .semantics { contentDescription = NotificationsCopy.unmuteLabel(username) },
        )
    }
}

/**
 * "Send a Test Notification" (`testCard`, `:291-328`): the blue paper-plane tile, "Sending…" and a
 * spinner while it runs; disabled while testing or with Show Notifications off.
 */
@Composable
private fun TestRow(isTesting: Boolean, enabled: Boolean, onSendTest: () -> Unit) {
    val colors = ShroudTheme.colors
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .highlightRow(onClick = onSendTest, enabled = enabled)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TEST_TILE),
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.PaperPlaneTiltFill, Color.White, size = 13.dp)
            }
            ShroudText(
                if (isTesting) NotificationsCopy.SENDING else NotificationsCopy.SEND_TEST,
                inter(16f),
                colors.textPrimary,
                Modifier.weight(1f),
            )
            if (isTesting) Spinner(colors.textSecondary, size = 16.dp)
        }
    }
}

/** `Color(red: 46/255, green: 143/255, blue: 224/255)` (`NotificationsSettingsView.swift:301`). */
private val TEST_TILE = Color(0xFF2E8FE0)

/**
 * Whether Android holds Shroud back in the background (notifications-push §5.21): the user
 * restricted it (`ActivityManager.isBackgroundRestricted`) or the system put it in the restricted
 * standby bucket (API 30+). Either delays work and background starts.
 */
internal object BackgroundRestriction {
    fun isRestricted(context: Context): Boolean = runCatching {
        val activity = context.getSystemService(ActivityManager::class.java)
        if (activity?.isBackgroundRestricted == true) return@runCatching true
        val usage = context.getSystemService(UsageStatsManager::class.java)
        usage?.appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
    }.getOrDefault(false)
}

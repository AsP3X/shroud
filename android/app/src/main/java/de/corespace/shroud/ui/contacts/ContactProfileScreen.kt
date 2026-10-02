package de.corespace.shroud.ui.contacts

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.GlassCapsuleButton
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuRows
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.ScrollEdgeEffect
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.components.TypingLabel
import de.corespace.shroud.ui.components.edgeEffectSource
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.mono
import de.corespace.shroud.ui.theme.rememberHaptics
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * A contact's profile, pushed from the chat header (iOS `ContactProfileView`,
 * `ios/shroud/Features/Main/ContactProfileView.swift:4-538`; contacts §5.8, design `Contact
 * Profile` ks0in / `Contact Profile · Dark` Hueuv).
 *
 * Human: Avatar, name and presence (or "typing…"); Call, Video, Mute and Search; while their key
 * changed, the warning card with "I verified this contact"; username, encryption and the safety
 * number with "Mark as Verified" (or "Verified"); the Notifications row (the mute menu) and
 * Encryption; Block / Unblock and Delete Chat, each confirmed in a bottom action sheet (P13c).
 *
 * Agent: Reads presence, blocks, peer activity, mutes and the peer identity state; on entry it
 * refreshes presence, blocks and the peer's identity key one after the other (`:70-74`). Actions run
 * in the app scope (they finish if the screen goes), the screen only waits for their answer. A
 * deleted chat calls [onChatDeleted] (the host leaves the thread, taking this screen with it) or
 * pops through [onBack]. While a key change is pending the safety number shown is the **new** key's
 * (P10b) and verifying waits until the new key is trusted (`:210-212`).
 */
@Composable
fun ContactProfileScreen(peerId: UUID, username: String, onBack: () -> Unit, onChatDeleted: (() -> Unit)?) {
    val container = LocalAppContainer.current
    val contacts = container.contacts.controller
    val identities = container.contacts.peerIdentities
    val messaging = container.messaging.controller
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val toast = rememberToastState()
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnChatDeleted by rememberUpdatedState(onChatDeleted)

    val presence by contacts.presence.collectAsState()
    val blockedUsers by contacts.blocked.collectAsState()
    val activities by messaging.peerActivities.collectAsState()
    val conversations by messaging.conversations.collectAsState()
    val changes by identities.identityChanges.collectAsState()
    val verifiedPeers by identities.verifiedPeers.collectAsState()

    // Re-reads of the pin (a first pin, a trusted key, a verification) and of the mute after a change.
    var identityRevision by remember { mutableIntStateOf(0) }
    var muteRevision by remember { mutableIntStateOf(0) }
    var isBlocking by remember { mutableStateOf(false) }
    var isDeletingChat by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ProfileConfirm?>(null) }
    var muteMenuAnchor by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(peerId) {
        identities.events.collect { event -> if (event.peer == peerId) identityRevision++ }
    }
    // `.task` (`:70-74`): presence, blocks, then the identity key, in that order.
    LaunchedEffect(peerId) {
        contacts.refreshPresence(listOf(peerId))
        contacts.refreshBlocks()
        identities.refresh(peerId)
        identityRevision++
    }

    val change = changes[peerId]
    val safetyNumber = remember(peerId, change, verifiedPeers, identityRevision) { identities.safetyNumber(peerId) }
    val verified = remember(peerId, change, verifiedPeers, identityRevision) { identities.isSafetyVerified(peerId) }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val mute = remember(peerId, conversations, muteRevision) { messaging.mute(peerId) }
    val canMute = remember(peerId, conversations) { messaging.canMute(peerId) }
    val isMuted = mute != null
    val isBlocked = blockedUsers.any { it.userId == peerId }

    fun changeMute(duration: MuteDuration?) {
        // `changeMute(to:)` (`:291-307`).
        scope.launch {
            val error = container.appScope.runDetached {
                if (duration != null) messaging.muteChat(peerId, duration) else messaging.unmuteChat(peerId)
            }
            muteRevision++
            if (error != null) {
                toast.show(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                val label = MuteDuration.label(messaging.mute(peerId), Instant.now(), zone, locale, is24h)
                toast.show(Toast.success(ContactsCopy.muteDone(unmuted = duration == null, label = label)))
                haptic(Haptic.Light)
            }
        }
    }

    fun onMuteTrigger(anchor: Rect) {
        // `muteMenu` (`:265-289`): muted → unmute at once; no chat yet → say so; else the durations.
        when {
            isMuted -> changeMute(null)
            !canMute -> toast.show(Toast.info(ContactsCopy.MUTE_NEEDS_CHAT))
            else -> muteMenuAnchor = anchor
        }
    }

    fun startCall(modality: CallModality) {
        // `:109-132`: place it, then show what went wrong, if anything.
        scope.launch {
            val calls = container.calls.controller
            container.appScope.runDetached { calls.startCall(peerId, username, modality) }
            calls.lastError.value?.let { toast.show(Toast.failure(it)) }
        }
    }

    fun performBlockChange(block: Boolean) {
        // `performBlockChange(block:)` (`:522-537`).
        isBlocking = true
        scope.launch {
            val error = container.appScope.runDetached {
                if (block) contacts.block(peerId, username) else contacts.unblock(peerId)
            }
            isBlocking = false
            if (error != null) {
                toast.show(Toast.failure(error))
                haptic(Haptic.Error)
            } else {
                toast.show(Toast.success(ContactsCopy.blockDone(blocked = block, name = username)))
                haptic(Haptic.Success)
            }
        }
    }

    fun performChatDelete(deleteScope: ConversationDeleteScope) {
        // `performChatDelete(scope:)` (`:503-520`): no toast on success, the thread goes.
        isDeletingChat = true
        scope.launch {
            val outcome = container.appScope.runDetached { messaging.deleteConversation(peerId, deleteScope) }
            isDeletingChat = false
            val failure = ContactsCopy.deleteFailure(outcome)
            if (failure != null) {
                toast.show(Toast.failure(failure))
                haptic(Haptic.Error)
                return@launch
            }
            haptic(Haptic.Success)
            val leave = currentOnChatDeleted
            if (leave != null) leave() else currentOnBack()
        }
    }

    ProfileScaffold(onBack = onBack, onEdit = { toast.show(Toast.info(ContactsCopy.EDIT_SOON)) }, toast = toast) {
        ContactProfileContent(
            username = username,
            seed = AvatarPalette.seed(username, peerId),
            status = ContactStatus.profile(presence[peerId], now, zone, locale, is24h),
            isOnline = presence[peerId]?.online == true,
            activity = activities[peerId],
            isMuted = isMuted,
            notificationsValue = MuteDuration.label(mute, now, zone, locale, is24h) ?: ContactsCopy.ON,
            hasIdentityChange = change != null,
            safetyNumber = safetyNumber,
            isVerified = verified,
            isBlocked = isBlocked,
            isBlocking = isBlocking,
            isDeletingChat = isDeletingChat,
            onCall = { startCall(CallModality.Voice) },
            onVideo = { startCall(CallModality.Video) },
            onMute = ::onMuteTrigger,
            onSearch = { toast.show(Toast.info(ContactsCopy.SEARCH_SOON)) },
            onVerifiedInPerson = { confirm = ProfileConfirm.TrustNewKey },
            onMarkVerified = {
                identities.confirmSafety(peerId)
                identityRevision++
            },
            onBlock = { confirm = ProfileConfirm.Block(blocked = isBlocked) },
            onDeleteChat = { confirm = ProfileConfirm.DeleteChat },
        )
    }

    muteMenuAnchor?.let { anchor ->
        ContextMenu(
            anchor = anchor,
            actions = MuteDuration.entries.map { duration -> MenuAction(duration.title) { changeMute(duration) } },
            onDismiss = { muteMenuAnchor = null },
            paneTitle = ContactsCopy.MUTE_MENU,
            rows = MenuRows.Trailing,
        )
    }

    val shown = confirm
    ActionSheet(
        visible = shown != null,
        title = shown?.title(username),
        message = shown?.message(username),
        items = when (shown) {
            ProfileConfirm.TrustNewKey -> listOf(
                ActionSheetItem(ContactsCopy.TRUST_ACTION, destructive = true) {
                    // `:398-401`.
                    identities.acceptNewIdentity(peerId)
                    identityRevision++
                    toast.show(Toast.success(ContactsCopy.NEW_KEY_SAVED))
                },
            )
            is ProfileConfirm.Block -> if (shown.blocked) {
                listOf(ActionSheetItem(ContactsCopy.UNBLOCK) { performBlockChange(block = false) })
            } else {
                listOf(ActionSheetItem(ContactsCopy.BLOCK, destructive = true) { performBlockChange(block = true) })
            }
            ProfileConfirm.DeleteChat -> listOf(
                ActionSheetItem(ContactsCopy.deleteForBoth(username), destructive = true) { performChatDelete(ConversationDeleteScope.Everyone) },
                ActionSheetItem(ContactsCopy.DELETE_FOR_ME, destructive = true) { performChatDelete(ConversationDeleteScope.Me) },
            )
            null -> emptyList()
        },
        onDismiss = { confirm = null },
    )
}

/** The confirmations of the profile (iOS `confirmationDialog`s → bottom action sheets, P13c / C22). */
internal sealed interface ProfileConfirm {
    fun title(name: String): String
    fun message(name: String): String

    /** `:393-405`. */
    data object TrustNewKey : ProfileConfirm {
        override fun title(name: String) = ContactsCopy.TRUST_TITLE
        override fun message(name: String) = ContactsCopy.TRUST_MESSAGE
    }

    /** `:431-448`; [blocked] as it was when the dialog opened. */
    data class Block(val blocked: Boolean) : ProfileConfirm {
        override fun title(name: String) = ContactsCopy.blockConfirmTitle(blocked, name)
        override fun message(name: String) = ContactsCopy.blockConfirmMessage(blocked)
    }

    /** `:481-500`. */
    data object DeleteChat : ProfileConfirm {
        override fun title(name: String) = ContactsCopy.deleteConfirmTitle(name)
        override fun message(name: String) = ContactsCopy.deleteConfirmMessage(name)
    }
}

/**
 * The pushed screen without a title (`:56-68`): `backgroundGrouped` edge to edge; the 44 dp glass
 * back circle (Phosphor `caret-left-bold` in `textPrimary`, "Back") and the glass "Edit" capsule
 * (17 sp `accent`) under the status bar; the content scrolls under them, faded by the edge effect.
 * System back is the shell's pop (as `PushedScreen`, whose title this screen has none of).
 */
@Composable
private fun ProfileScaffold(onBack: () -> Unit, onEdit: () -> Unit, toast: ToastState, content: @Composable ColumnScope.() -> Unit) {
    val colors = ShroudTheme.colors
    val backdrop = rememberHazeState()
    val scroll = rememberScrollState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrolledUnder by remember(scroll) { derivedStateOf { scroll.value > 0 } }
    Box(Modifier.fillMaxSize().background(colors.backgroundGrouped)) {
        Column(
            Modifier
                .fillMaxSize()
                .edgeEffectSource(backdrop)
                .verticalScroll(scroll)
                .padding(top = statusTop + BAR_HEIGHT),
        ) {
            content()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        ScrollEdgeEffect(visible = scrolledUnder, extent = statusTop + BAR_HEIGHT, backdrop = backdrop)
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassCircleButton(ShroudIcons.CaretLeftBold, ContactsCopy.BACK, onBack, tint = colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    GlassCapsuleButton(ContactsCopy.EDIT, onEdit)
                }
            }
        }
        ToastHost(toast)
    }
}

private val BAR_HEIGHT = 44.dp

/** The profile's body (`:40-55`) from plain state, so tests and previews draw it without the engines. */
@Composable
internal fun ContactProfileContent(
    username: String,
    seed: String,
    status: String,
    isOnline: Boolean,
    activity: ChatPeerActivity?,
    isMuted: Boolean,
    notificationsValue: String,
    hasIdentityChange: Boolean,
    safetyNumber: String?,
    isVerified: Boolean,
    isBlocked: Boolean,
    isBlocking: Boolean,
    isDeletingChat: Boolean,
    onCall: () -> Unit,
    onVideo: () -> Unit,
    onMute: (anchor: Rect) -> Unit,
    onSearch: () -> Unit,
    onVerifiedInPerson: () -> Unit,
    onMarkVerified: () -> Unit,
    onBlock: () -> Unit,
    onDeleteChat: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileHero(username, seed, status, isOnline, activity)
        ActionRow(isMuted, onCall, onVideo, onMute, onSearch)
        if (hasIdentityChange) IdentityWarningCard(onVerifiedInPerson)
        InfoCard(username, safetyNumber, showsVerification = !hasIdentityChange, isVerified = isVerified, onMarkVerified = onMarkVerified)
        OptionsCard(isMuted, notificationsValue, onMute)
        DangerRow(
            title = ContactsCopy.blockRowTitle(isBlocked, username),
            color = if (isBlocked) ShroudTheme.colors.accent else ShroudTheme.colors.danger,
            busy = isBlocking,
            onClick = onBlock,
        )
        DangerRow(title = ContactsCopy.DELETE_CHAT, color = ShroudTheme.colors.danger, busy = isDeletingChat, onClick = onDeleteChat)
    }
}

/**
 * Avatar 96 (initials 34 SemiBold) 8 dp down, then 8 dp lower the name (22 SemiBold, one line,
 * shrinking to 70 %) over the status line (14, `accent` when online) or the typing label
 * (`:77-105`).
 */
@Composable
private fun ProfileHero(username: String, seed: String, status: String, isOnline: Boolean, activity: ChatPeerActivity?) {
    val colors = ShroudTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NameAvatar(username, Modifier.padding(top = 8.dp), seed = seed, size = 96.dp, fontSize = 34.sp)
        Column(
            Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = username,
                style = inter(22f, FontWeight.SemiBold).copy(color = colors.textPrimary, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = (22f * 0.7f).sp, maxFontSize = 22.sp, stepSize = 0.25.sp),
            )
            if (activity != null) {
                TypingLabel(activity, style = inter(14f))
            } else {
                ShroudText(status, inter(14f), if (isOnline) colors.accent else colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** Call · Video · Mute · Search (`:107-176`): four equal 58 dp tiles on `background`, radius 12, pressable 0.93. */
@Composable
private fun ActionRow(isMuted: Boolean, onCall: () -> Unit, onVideo: () -> Unit, onMute: (Rect) -> Unit, onSearch: () -> Unit) {
    val muteBounds = remember { BoundsHolder() }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile(ContactsCopy.CALL, onCall) { ShroudIcon(ShroudIcons.PhoneFill, ShroudTheme.colors.accent, size = 18.dp) }
        ActionTile(ContactsCopy.VIDEO, onVideo) { ShroudIcon(ShroudIcons.VideoCameraFill, ShroudTheme.colors.accent, size = 18.dp) }
        ActionTile(
            ContactsCopy.muteTileTitle(isMuted),
            onClick = { onMute(muteBounds.bounds) },
            modifier = Modifier.onGloballyPositioned { muteBounds.bounds = it.boundsInRoot() },
        ) {
            // The glyph swaps with the state (`.contentTransition(.symbolEffect(.replace))`, `:138`).
            AnimatedContent(targetState = isMuted, transitionSpec = { Motion.iconSwap.content }, label = "muteGlyph") { muted ->
                ShroudIcon(if (muted) ShroudIcons.BellFill else ShroudIcons.BellSlashFill, ShroudTheme.colors.accent, size = 18.dp)
            }
        }
        ActionTile(ContactsCopy.SEARCH, onSearch) { ShroudIcon(ShroudIcons.MagnifyingGlassBold, ShroudTheme.colors.accent, size = 18.dp) }
    }
}

/** Where a menu trigger sits, written on layout (no recomposition) and read on tap. */
private class BoundsHolder {
    var bounds: Rect = Rect.Zero
}

@Composable
private fun RowScope.ActionTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: @Composable () -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        modifier
            .weight(1f)
            .pressable(scale = 0.93f, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background)
            .clearAndSetSemantics { contentDescription = title }
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        glyph()
        ShroudText(title, inter(12f, FontWeight.Medium), colors.accent, maxLines = 1)
    }
}

/**
 * Only while the peer's key changed (`:366-406`): warning glyph + "Encryption key changed" (16
 * SemiBold `danger`), the explanation (14 `textSecondary`), and "I verified this contact" (15
 * SemiBold `accent`, full width) which asks "Trust the new key?". Padding 16, radius 14.
 */
@Composable
private fun IdentityWarningCard(onVerifiedInPerson: () -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.WarningFill, colors.danger, size = 16.dp)
            ShroudText(ContactsCopy.KEY_CHANGED_TITLE, inter(16f, FontWeight.SemiBold), colors.danger)
        }
        ShroudText(ContactsCopy.KEY_CHANGED_BODY, inter(14f), colors.textSecondary)
        // Only static text above and it only opens a confirmation: the target may grow (`:380-386`).
        ShroudText(
            ContactsCopy.I_VERIFIED,
            inter(15f, FontWeight.SemiBold),
            colors.accent,
            Modifier
                .fillMaxWidth()
                .pressable(scale = 1f, haptic = Haptic.None, onClick = onVerifiedInPerson),
        )
    }
}

/**
 * username · encryption · safety number (`:178-259`), rows 14 / 9 with a 1 dp separator inset 14.
 * The number is 13 Medium monospace and selectable; under it "Verified", or "Mark as Verified",
 * whose target grows only 4 dp up into the gap — a tap on the number never verifies (`:237-248`).
 * Neither shows while a key change waits ([showsVerification] false).
 */
@Composable
private fun InfoCard(username: String, safetyNumber: String?, showsVerification: Boolean, isVerified: Boolean, onMarkVerified: () -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background),
    ) {
        InfoFact(ContactsCopy.USERNAME_LABEL, "@$username")
        Separator(start = 14.dp)
        InfoFact(ContactsCopy.ENCRYPTION_LABEL, ContactsCopy.END_TO_END)
        if (safetyNumber != null) {
            Separator(start = 14.dp)
            val button = showsVerification && !isVerified
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(SAFETY_NUMBER_TAG)
                    .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = if (button) 0.dp else 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShroudText(ContactsCopy.SAFETY_NUMBER_LABEL, inter(12f), colors.textSecondary)
                SelectionContainer {
                    ShroudText(safetyNumber, mono(13f, FontWeight.Medium), colors.textPrimary)
                }
                if (showsVerification && isVerified) {
                    ShroudText(ContactsCopy.VERIFIED, inter(13f), colors.textSecondary)
                }
            }
            if (button) {
                // No gap above (the 4 dp are inside the target), the card's 9 dp below are too.
                ShroudText(
                    ContactsCopy.MARK_AS_VERIFIED,
                    inter(15f, FontWeight.SemiBold),
                    colors.accent,
                    Modifier
                        .fillMaxWidth()
                        .pressable(scale = 1f, haptic = Haptic.None, onClick = onMarkVerified)
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 9.dp),
                )
            }
        }
    }
}

/** The iOS accessibility identifier of the safety number block (`:254`). */
internal const val SAFETY_NUMBER_TAG = "contact.safetyNumber"

/** One fact: label 12 `textSecondary` over value 16 `textPrimary`, read as one (`:180-191`). */
@Composable
private fun InfoFact(label: String, value: String) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ShroudText(label, inter(12f), colors.textSecondary)
        ShroudText(value, inter(16f), colors.textPrimary)
    }
}

@Composable
private fun Separator(start: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(1.dp)
            .background(ShroudTheme.colors.separator),
    )
}

/**
 * Notifications (the mute menu: value = the mute label or "On", with a chevron) and Encryption
 * ("On", a status without a chevron, one TalkBack stop) — `:309-364`.
 */
@Composable
private fun OptionsCard(isMuted: Boolean, notificationsValue: String, onMute: (Rect) -> Unit) {
    val colors = ShroudTheme.colors
    val bounds = remember { BoundsHolder() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background),
    ) {
        OptionRow(
            icon = if (isMuted) ShroudIcons.BellSlashFill else ShroudIcons.BellFill,
            title = ContactsCopy.NOTIFICATIONS,
            value = notificationsValue,
            showsChevron = true,
            modifier = Modifier
                .onGloballyPositioned { bounds.bounds = it.boundsInRoot() }
                .pressable(scale = 1f, haptic = Haptic.None) { onMute(bounds.bounds) },
        )
        Separator(start = 56.dp)
        OptionRow(
            icon = ShroudIcons.LockFill,
            title = ContactsCopy.ENCRYPTION,
            value = ContactsCopy.ON,
            showsChevron = false,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
    }
}

/** Tile 30 (`accentSoft`, radius 8, glyph 15 `accent`), title 16, value 15 shrinking to 75 %, chevron 12 — padding 14 / 10, gap 12. */
@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    showsChevron: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentSoft)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(icon, colors.accent, size = 15.dp)
        }
        ShroudText(title, inter(16f), colors.textPrimary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText(
            text = value,
            style = inter(15f).copy(color = colors.textSecondary, textAlign = TextAlign.End),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = (15f * 0.75f).sp, maxFontSize = 15.sp, stepSize = 0.25.sp),
        )
        if (showsChevron) {
            ShroudIcon(ShroudIcons.CaretRightBold, colors.chevron, Modifier.clearAndSetSemantics {}, size = 12.dp)
        }
    }
}

/** Block / Unblock and Delete Chat (`:409-449`, `:459-501`): a full-width row, padding 14 / 13, a small spinner while busy, disabled meanwhile. */
@Composable
private fun DangerRow(title: String, color: Color, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ShroudTheme.colors.background)
            .pressable(enabled = !busy, scale = 1f, haptic = Haptic.None, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(ShroudTheme.colors.textSecondary, size = 16.dp)
        ShroudText(title, inter(16f), color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

package de.corespace.shroud.ui.conversation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.GlassBarButton
import de.corespace.shroud.ui.components.GlassBarGroup
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.PresenceDot
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SymbolAvatar
import de.corespace.shroud.ui.components.TypingLabel
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The header's subtitle (`presenceLabel`, `ConversationView.swift:167-175`; conversation-thread
 * §1.5). Pure.
 */
object ConversationPresence {
    /** Notes are sealed to the account and sync to its devices: the line says who can read them (CV:168-169). */
    const val NOTES = "Only you · end-to-end encrypted"
    const val ONLINE = "online"
    const val OFFLINE_LOCAL_COPY = "offline · local copy"

    /** Nothing heard about the peer yet (CV:174). */
    const val UNKNOWN = "…"

    /**
     * Notes → [NOTES]; the peer typing or recording → "typing…" / "recording…"; online → "online";
     * this phone offline → "offline · local copy"; else "last seen …", "offline" or "…" from
     * [presence] (`ChatListFormatting.presenceLabel`).
     */
    fun label(
        isNotes: Boolean,
        activity: ChatPeerActivity?,
        presence: PresenceDto?,
        isOffline: Boolean,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
        is24h: Boolean,
    ): String = when {
        isNotes -> NOTES
        activity != null -> activity.label + "…"
        presence?.online == true -> ONLINE
        isOffline -> OFFLINE_LOCAL_COPY
        else -> ChatListFormatting.presenceLabel(presence, now, zone, locale, is24h) ?: UNKNOWN
    }

    /** The subtitle is accent while the peer is online or doing something, `textSecondary` otherwise (CV:198-200). */
    fun isAccent(isNotes: Boolean, activity: ChatPeerActivity?, presence: PresenceDto?): Boolean =
        !isNotes && (activity != null || presence?.online == true)
}

/** What the header draws (all of it the view model's). */
@Immutable
data class ConversationHeaderState(
    val title: String,
    /** The avatar's gradient seed (`AvatarPalette.seed`, the username as on iOS). */
    val avatarSeed: String,
    val isNotes: Boolean,
    val subtitle: String,
    val subtitleIsAccent: Boolean,
    val isOnline: Boolean,
    val activity: ChatPeerActivity?,
)

/** Copy of the header controls (CV:706-746). */
object ConversationHeaderCopy {
    const val BACK = "Back"
    const val VIDEO_CALL = "Video call"
    const val CALL = "Call"
    const val MORE = "More"
    const val DELETE_ALL_NOTES = "Delete All Notes"
}

/**
 * The chat's glass top bar (`topChrome` + `headerContact`, `ConversationView.swift:692-819`;
 * conversation-thread §1.5; design `Conversation` hc3Jf, `— Notes` nPQKZ, `— Typing` NOg29).
 *
 * Human: Back on the left; the contact — a 40 dp avatar, the name (16 SemiBold) and a presence line
 * (12) — centred on the screen like a title; Video and Call fused into one glass capsule on the
 * right. The presence line turns into "typing" / "recording" with its little glyph, shows a pulsing
 * green dot while the peer is online, and is accent while they are online or busy. Tapping the
 * contact opens their profile. In Notes the bookmark avatar and "Only you · end-to-end encrypted"
 * are one heading and the right side holds a More menu whose only row clears every note.
 *
 * Agent: draws from [state]; the bar row sits under the status bar and the thread scrolls under it
 * (the caller fades the thread under it, [ChatEdgeFade]). The centre is inset by
 * `GlassBarMetrics.sideReserve(controls)` on both sides: 52 dp in Notes, 96 dp in a peer chat
 * (CV:705). Call buttons play a medium haptic. Presence changes animate with `Motion.snappy`.
 */
@Composable
fun ConversationHeader(
    state: ConversationHeaderState,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onCall: (CallModality) -> Unit,
    onDeleteAllNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        GlassBarRow(
            leading = {
                GlassBarButton(icon = ShroudIcons.ChevronLeft, contentDescription = ConversationHeaderCopy.BACK, onClick = onBack)
            },
            title = {
                if (state.isNotes) NotesContact(state) else PeerContact(state, onOpenProfile)
            },
            trailing = {
                if (state.isNotes) {
                    NotesMoreMenu(onDeleteAllNotes)
                } else {
                    GlassBarGroup {
                        GlassBarButton(
                            icon = ShroudIcons.Video,
                            contentDescription = ConversationHeaderCopy.VIDEO_CALL,
                            onClick = { onCall(CallModality.Video) },
                            haptic = Haptic.Medium,
                        )
                        GlassBarButton(
                            icon = ShroudIcons.Phone,
                            contentDescription = ConversationHeaderCopy.CALL,
                            onClick = { onCall(CallModality.Voice) },
                            haptic = Haptic.Medium,
                        )
                    }
                }
            },
            sideReserve = GlassBarMetrics.sideReserve(if (state.isNotes) 1 else 2),
        )
    }
}

/** The peer: avatar, name and presence, pressable into the profile (CV:770-818). */
@Composable
private fun PeerContact(state: ConversationHeaderState, onOpenProfile: () -> Unit) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    Row(
        Modifier.pressable(scale = 0.98f, dimming = 0.12f, haptic = Haptic.Light, onClick = onOpenProfile),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAvatar(name = state.title, seed = state.avatarSeed, size = 40.dp, fontSize = AVATAR_FONT)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ShroudText(
                state.title,
                inter(16f, FontWeight.SemiBold),
                colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "online" → "typing" / "recording" swaps in place, its glyph moving in step with the bubble.
            var lastActivity by remember { mutableStateOf(state.activity ?: ChatPeerActivity.Typing) }
            if (state.activity != null) lastActivity = state.activity
            val spec = Motion.respecting(reduceMotion, Motion.snappy<Float>())
            AnimatedContent(
                targetState = state.activity != null,
                transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
                contentAlignment = Alignment.CenterStart,
                label = "headerActivity",
            ) { busy ->
                if (busy) {
                    TypingLabel(lastActivity)
                } else {
                    PresenceLine(state)
                }
            }
        }
    }
}

/** The dot (online only) and the label (CV:794-806). */
@Composable
private fun PresenceLine(state: ConversationHeaderState) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val color by animateColorAsState(
        if (state.subtitleIsAccent) colors.accent else colors.textSecondary,
        Motion.respecting(reduceMotion, Motion.snappy()),
        label = "presenceColor",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val swap = Motion.iconSwap(Motion.snappy()).respecting(reduceMotion)
        AnimatedVisibility(visible = state.isOnline, enter = swap.enter, exit = swap.exit) {
            PresenceDot()
        }
        val spec = Motion.respecting(reduceMotion, Motion.snappy<Float>())
        AnimatedContent(
            targetState = state.subtitle,
            transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
            contentAlignment = Alignment.CenterStart,
            label = "presenceLabel",
        ) { label ->
            ShroudText(label, inter(12f), color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Notes' bookmark and "Only you · end-to-end encrypted": one heading (CV:753-769). */
@Composable
private fun NotesContact(state: ConversationHeaderState) {
    val colors = ShroudTheme.colors
    Row(
        Modifier.semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The brand gradient, as on the chat list's row (`notesHeaderAvatar`, CV:1680-1692; design 18 dp glyph).
        SymbolAvatar(ShroudIcons.BookmarkSimpleFill, size = 40.dp, iconSize = 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ShroudText(state.title, inter(16f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ShroudText(state.subtitle, inter(12f), colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Notes' More (`ellipsis` in a 44 dp interactive glass capsule, CV:729-746): a menu with one
 * destructive row, "Delete All Notes", which asks "Delete all notes?" first. Drawn with the app's
 * dark menu card (conversation-thread §1.5, §16.8 visuals).
 */
@Composable
private fun NotesMoreMenu(onDeleteAllNotes: () -> Unit) {
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        GlassBarButton(
            icon = ShroudIcons.DotsThreeBold,
            contentDescription = ConversationHeaderCopy.MORE,
            onClick = { open = true },
        )
    }
    if (open) {
        ContextMenu(
            anchor = anchor,
            actions = listOf(
                MenuAction(ConversationHeaderCopy.DELETE_ALL_NOTES, ShroudIcons.Trash, destructive = true, onClick = onDeleteAllNotes),
            ),
            style = MenuStyle.Dark,
            onDismiss = { open = false },
            paneTitle = ConversationHeaderCopy.MORE,
            dimsBackground = false,
        )
    }
}

/**
 * The soft fade where the thread passes under a glass bar (iOS `scrollEdgeEffectStyle(.soft)` of
 * `glassTopBar` / `glassBottomBar`; conversation-thread §1.2): `backgroundChat` at 95 % at the
 * screen edge, clear at the bar's inner edge, faded in and out with `Motion.fade` while the thread
 * has content under the bar ([visible]). Decorative.
 */
@Composable
fun ChatEdgeFade(visible: Boolean, height: Dp, fromTop: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = ShroudTheme.reduceMotion
    val shown by animateFloatAsState(
        if (visible) 1f else 0f,
        Motion.respecting(reduceMotion, Motion.fade()),
        label = "chatEdgeFade",
    )
    if (shown <= 0f || height <= 0.dp) return
    val chat = ShroudTheme.colors.backgroundChat
    val stops = listOf(chat.copy(alpha = EDGE_PEAK), chat.copy(alpha = 0f))
    Box(
        modifier
            .clearAndSetSemantics {}
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = shown }
            .background(Brush.verticalGradient(if (fromTop) stops else stops.reversed())),
    )
}

/** The flat scroll-edge gradient's peak (`ScrollEdgeEffectSpec.FLAT_PEAK`). */
private const val EDGE_PEAK = 0.95f

/** iOS passes 14 for the header's 40 pt avatar (CV:780-785). */
private val AVATAR_FONT = 14.sp

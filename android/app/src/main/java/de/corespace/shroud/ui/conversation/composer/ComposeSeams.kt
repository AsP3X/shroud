package de.corespace.shroud.ui.conversation.composer

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.attach.ChatAttachSheet
import de.corespace.shroud.ui.conversation.pickers.rememberMediaPicker
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.permissions.rememberPermissionRequest
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.flow.drop
import java.util.UUID

/**
 * What the composer asks of the conversation screen (conversation-compose-media §2; plan §1.7.13).
 * Implemented by the conversation screen (W3-THREAD-LIST).
 *
 * **Seam (W2-INT), owner W3-COMPOSER in wave 3.** Members with a body were added by W3-COMPOSER
 * (source-compatible); the screen overrides them.
 */
interface ComposeHost {
    /** A send went out: the list scrolls to the newest message. */
    fun pinToBottom()

    /** The reply bar's quote was tapped: the list jumps to [messageId]. */
    fun jumpToQuoted(messageId: UUID)

    /** The long-press menu is up: the composer keeps the keyboard down and ignores taps. */
    val isShowingMessageMenu: Boolean
    fun showToast(toast: Toast)

    /**
     * The photo viewer's Delete: show the thread's own "Delete message?" sheet for [message] with its
     * scope choice (iOS `pendingDelete`, `ConversationView.swift:490-496`). A delete performed from it
     * closes the viewer first ([ComposeController.closeMediaViewer], `:2266-2285`); Cancel leaves the
     * viewer open. Added by W3-COMPOSER; the default does nothing (contract change request to
     * W3-THREAD-LIST, which owns `DeleteMessageSheet`).
     */
    fun requestDelete(message: ChatMessage) {}
}

/**
 * The composer bar and everything that hangs off it (iOS `ConversationView`'s bottom bar and its
 * compose layers; conversation-compose-media §3–§8, §19, §20): the Notes "Todo" bar, the reply and
 * link strips, the field, the hold-to-record mic and recording bars, the attach sheet with its
 * Recents strip, the system photo picker, the camera, and the full-screen photo compose, video
 * compose, photo viewer, video player and "Sending media…" card.
 *
 * Place it at the bottom of the conversation's root `Box` (`modifier = Modifier.align(BottomCenter)`),
 * outside any inset padding: it rides the keyboard frame by frame itself
 * (`WindowInsets.ime ∪ navigationBars`, §3.9) and reports its measured height — the bottom inset
 * (keyboard or navigation bar) **included** — through [onComposerHeightChanged]. The thread uses it as
 * its bottom content padding (+ 12) and to lift the jump-to-latest control `height + 8`
 * (CV:337-338, 612-628). `ToastHost` adds the system bottom inset itself, so its `bottomInset` is this
 * height minus that inset (`toastBottomInset`, CV:208-212; 0 while [ComposeController.coversComposer]).
 * The full-screen layers (viewer, player, photo and video compose, camera) and the "Sending media…"
 * card draw in the app's `OverlayHost` layer, wherever this is placed; toasts the composer raises
 * while one of them is up are drawn on that layer.
 *
 * **Seam (W2-INT, plan §1.7.13), owner W3-COMPOSER.** [modifier] is an optional addition.
 */
@Composable
fun ConversationComposeHost(controller: ComposeController, onComposerHeightChanged: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val reportHeight by rememberUpdatedState(onComposerHeightChanged)

    // A controller the screen kept after `onLeave` speaks up again once it is drawn again.
    LaunchedEffect(controller) { controller.onShown() }

    // Every draft change, typed or programmatic (CV:211-213, 309-318). The first value is the draft
    // as it already was (coming back from the profile), not a change.
    LaunchedEffect(controller) {
        snapshotFlow { controller.draft.text.toString() }.drop(1).collect(controller::onDraftChanged)
    }

    val askMicrophone = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted, _ ->
        // Granted: nothing else happens — the user holds again (the first hold after the prompt never records, §4.7).
        if (!granted) controller.showPermissionToast(MICROPHONE_OFF) { openAppSettings(context) }
    }
    val askCamera = rememberPermissionRequest(Manifest.permission.CAMERA) { granted, _ ->
        if (granted) controller.presentCamera() else controller.showPermissionToast(CAMERA_OFF) { openAppSettings(context) }
    }
    val picker = rememberMediaPicker { uris -> controller.onPicked(uris) }
    val currentAskMicrophone by rememberUpdatedState(askMicrophone)
    val currentAskCamera by rememberUpdatedState(askCamera)
    val currentPicker by rememberUpdatedState(picker)

    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is ComposeEffect.OpenPicker -> currentPicker.open(effect.request)
                ComposeEffect.RequestMicrophone -> currentAskMicrophone()
                ComposeEffect.RequestCamera -> currentAskCamera()
                is ComposeEffect.PlayHaptic -> view.perform(effect.haptic)
            }
        }
    }

    val recorder by controller.recorderState.collectAsState()
    val phase by controller.gesture.phase.collectAsState()
    val linkState by controller.linkComposer.state.collectAsState()

    Column(
        modifier
            .fillMaxWidth()
            // Measured outside the inset padding, so the height includes the keyboard or the
            // navigation bar under the bar: the thread pads by it and the toasts subtract it.
            .onSizeChanged { size -> reportHeight(with(density) { size.height.toDp() }) }
            .composerInsets(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.isNotes) NotesTodoBar(onTodo = controller::sendTodo)
        ChatComposer(
            draft = controller.draft,
            recorder = recorder,
            phase = phase,
            gesture = controller.gesture,
            onAttach = {
                // iOS sheets resign the first responder: the keyboard goes as the sheet comes (§3.3).
                focusManager.clearFocus()
                controller.openAttachSheet()
            },
            onSend = controller::sendDraft,
            reply = controller.replyContent,
            onTapReply = controller::jumpToReplyTarget,
            onCancelReply = controller::clearReply,
            focusToken = controller.focusToken,
            linkBar = ChatLinkBarState.from(linkState.phase),
            linkShowsAboveText = linkState.showsAboveText,
            linkCanToggleImageSize = linkState.canToggleImageSize,
            linkUsesLargeImage = linkState.usesLargeImage,
            onToggleLinkAboveText = controller.linkComposer::toggleShowsAboveText,
            onToggleLinkImageSize = controller.linkComposer::toggleImageSize,
            onRemoveLinkPreview = controller::removeLinkPreview,
        )
    }

    ChatAttachSheet(
        visible = controller.showsAttachSheet,
        onSelect = controller::handleAttach,
        onCancel = controller::closeAttachSheet,
        onPickImage = controller::pickRecentPhoto,
        decodePreview = controller::decodePreview,
        libraryAccess = controller::photoLibraryAccess,
        loadRecents = controller::loadRecentPhotos,
        accessRequested = controller::photoAccessRequested,
        markAccessRequested = controller::markPhotoAccessRequested,
    )

    ComposeMediaLayers(controller)
}

/**
 * The bar rides the keyboard frame by frame (conversation-compose-media §3.9; design tBB5Y): the
 * bottom inset is the keyboard while it is up, else the navigation bar. Compose animates
 * `WindowInsets.ime` from `WindowInsetsAnimation` on every frame of the keyboard's own animation
 * (API 30+, edge-to-edge with `adjustResize`), so nothing here springs or animates on its own —
 * an extra animation would lag the keyboard (iOS: the composer is a `safeAreaBar`, `GlassBar.swift:272-275`).
 */
internal fun Modifier.composerInsets(): Modifier =
    windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))

/**
 * The conversation's `ToastHost(bottomInset = …)` (`toastBottomInset`, `ConversationView.swift:208-212`):
 * the composer's reported height above the system bottom inset `ToastHost` adds itself, so a toast
 * lands 20 dp above the composer; 0 while a full-screen layer covers the composer. Added by
 * W3-COMPOSER for the conversation screen.
 */
fun composerToastInset(composerHeight: Dp, systemBottom: Dp, coversComposer: Boolean): Dp =
    if (coversComposer) 0.dp else (composerHeight - systemBottom).coerceAtLeast(0.dp)

/** The design's permission toasts (u3il8T; P14 Q12; conversation-compose-media §4.7, §8.3). */
internal const val MICROPHONE_OFF = "Microphone access is off"
internal const val CAMERA_OFF = "Camera access is off"
internal const val SETTINGS_ACTION = "Settings"

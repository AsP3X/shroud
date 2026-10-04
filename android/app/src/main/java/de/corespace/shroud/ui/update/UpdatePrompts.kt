package de.corespace.shroud.ui.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.update.ClientUpdateStatus
import de.corespace.shroud.core.update.UpdateCheckOutcome
import de.corespace.shroud.core.update.UpdatePrompt
import de.corespace.shroud.ui.components.AlertButton
import de.corespace.shroud.ui.components.BlockOverlays
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudAlertDialog
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The update prompts' copy. */
object UpdateCopy {
    const val AVAILABLE_TITLE = "Update available"
    const val REQUIRED_TITLE = "Update required"
    const val UPDATE = "Update"
    const val LATER = "Later"
    const val OK = "OK"
    const val CHECK_AGAIN = "Check again"
    const val CHECKING = "Checking…"
    const val LINK_FAILED = "No app on this phone can open the update link."
    const val STILL_REQUIRED = "This server still needs a newer version"
    const val CHECK_FAILED = "Couldn’t reach the server"

    /** "Shroud 1.3.0 is available. You have 1.2.0." */
    fun availableMessage(latest: String?, current: String): String =
        if (latest != null) "Shroud $latest is available. You have $current." else "A new version of Shroud is available. You have $current."

    /** The required screen's body; without a link it says where the update comes from. */
    fun requiredMessage(latest: String?, hasLink: Boolean): String {
        val update = if (latest != null) "Update to $latest to keep using it." else "Update to keep using it."
        val base = "This version of Shroud no longer works with this server. $update"
        return if (hasLink) base else "$base Get the new version where you installed Shroud."
    }
}

/**
 * The root's update prompts ([UpdatePrompt], `ClientUpdateChecker`), above the shell, onboarding
 * and the lock screen and below calls, the privacy cover and the wipe overlay (`RootLayers`).
 *
 * - [UpdatePrompt.Available]: the "Update available" dialog while [offersDialog] (not over a call,
 *   the privacy cover or a wipe; it waits for them to go). "Later" (also Back and a tap on the dim)
 *   puts the shown version off ([onLater]). "Update" opens the link and then puts it off too; when
 *   nothing can open the link the dialog stays and a toast says so. Without a link it is a notice
 *   with one "OK".
 * - [UpdatePrompt.Required]: [UpdateRequiredScreen], fading in and out. While it is up and
 *   [blocksOverlays] (false while a call screen covers it), open sheets, menus and dialogs are
 *   closed or hidden ([BlockOverlays]). "Check again" ([onCheckAgain]) keeps "Checking…" up for at
 *   least [MIN_CHECKING_MS], then a server that still asks for an update, or no answer at all,
 *   gets a toast and a haptic; any other answer lifts the screen.
 *
 * [onUpdate] opens the link and answers whether anything could; a false shows [UpdateCopy.LINK_FAILED].
 */
@Composable
fun UpdatePromptLayer(
    prompt: UpdatePrompt,
    checking: Boolean,
    offersDialog: Boolean,
    onUpdate: (String) -> Boolean,
    onLater: (UpdatePrompt.Available) -> Unit,
    onCheckAgain: suspend () -> UpdateCheckOutcome,
    modifier: Modifier = Modifier,
    blocksOverlays: Boolean = true,
) {
    val reduce = ShroudTheme.reduceMotion
    val toast = rememberToastState()
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentOnCheckAgain by rememberUpdatedState(onCheckAgain)
    var holdingCheck by remember { mutableStateOf(false) }
    val open: (String) -> Boolean = { url ->
        onUpdate(url).also { opened -> if (!opened) toast.show(Toast.failure(UpdateCopy.LINK_FAILED)) }
    }
    val checkAgain: () -> Unit = {
        if (!holdingCheck) {
            holdingCheck = true
            scope.launch {
                try {
                    val outcome = coroutineScope {
                        // "Checking…" stays long enough to be read, however fast the server is.
                        val floor = launch { delay(MIN_CHECKING_MS) }
                        currentOnCheckAgain().also { floor.join() }
                    }
                    when {
                        outcome == UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateRequired) -> {
                            toast.show(Toast.info(UpdateCopy.STILL_REQUIRED))
                            haptic(Haptic.Warning)
                        }
                        outcome == UpdateCheckOutcome.Failed -> {
                            toast.show(Toast.failure(UpdateCopy.CHECK_FAILED))
                            haptic(Haptic.Error)
                        }
                    }
                } finally {
                    holdingCheck = false
                }
            }
        }
    }
    // The last required prompt stays drawn while the screen fades out.
    var lastRequired by remember { mutableStateOf<UpdatePrompt.Required?>(null) }
    if (prompt is UpdatePrompt.Required) lastRequired = prompt
    val required = prompt is UpdatePrompt.Required

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = required,
            enter = fadeIn(Motion.respecting(reduce, Motion.fade())),
            exit = fadeOut(Motion.respecting(reduce, Motion.fade())),
        ) {
            lastRequired?.let { shown ->
                UpdateRequiredScreen(
                    prompt = shown,
                    checking = checking || holdingCheck,
                    presented = required,
                    onUpdate = { open(it) },
                    onCheckAgain = checkAgain,
                    blocksOverlays = blocksOverlays,
                )
            }
        }
        UpdateAvailableDialog(
            offer = (prompt as? UpdatePrompt.Available)?.takeIf { offersDialog },
            onUpdate = open,
            onLater = onLater,
        )
        // Over the required screen the overlays are blocked, so its toasts draw here; the dialog's
        // draw in a layer above it.
        if (required) {
            ToastHost(toast)
        } else {
            OverlayLayer(active = toast.current != null, modal = false) { ToastHost(toast) }
        }
    }
}

/** "Checking…" stays at least this long after "Check again" (ms). */
const val MIN_CHECKING_MS = 600L

/**
 * "Update available" on the app's one dialog ([ShroudAlertDialog]): "Shroud 1.3.0 is available.
 * You have 1.2.0.", then "Later" and "Update" side by side, or a lone "OK" when the operator set
 * no link. [offer] null hides it; the dialog keeps its last text while it fades out. [onLater]
 * gets the offer on screen; "Update" calls it only once [onUpdate] could open the link.
 */
@Composable
fun UpdateAvailableDialog(offer: UpdatePrompt.Available?, onUpdate: (String) -> Boolean, onLater: (UpdatePrompt.Available) -> Unit) {
    var last by remember { mutableStateOf(offer) }
    if (offer != null) last = offer
    val shown = last
    val url = shown?.updateUrl
    val current by rememberUpdatedState(offer)
    ShroudAlertDialog(
        visible = offer != null,
        title = UpdateCopy.AVAILABLE_TITLE,
        message = shown?.let { UpdateCopy.availableMessage(it.latestVersion, it.currentVersion) },
        primary = if (url != null) {
            AlertButton(UpdateCopy.UPDATE, closesAlert = false) {
                val showing = current
                if (onUpdate(url) && showing != null) onLater(showing)
            }
        } else {
            AlertButton(UpdateCopy.OK) {}
        },
        onDismiss = { current?.let(onLater) },
        cancelTitle = if (url != null) UpdateCopy.LATER else null,
    )
}

/**
 * "Update required": a full-screen block in the wipe overlay's layout (`DeviceWipeOverlay`) —
 * grouped background edge to edge, the brand mark (80, accent shadow as on Welcome), title 26 Bold,
 * body 15 secondary, centred and scrolling when it does not fit; the buttons pinned at the bottom
 * above the navigation bar. With a link: "Update" (primary) and "Check again" (secondary, "Checking…"
 * and dimmed while [checking]); without one, "Check again" is the primary button with its spinner.
 *
 * Nothing under it takes a touch, and Back does nothing while [presented]. TalkBack meets one pane
 * named "Update required"; a leaving screen ([presented] false) is gone for it already. When it
 * appears, focus and the keyboard leave whatever field was under it (a phrase, a draft), and focus
 * cannot move back out of it with a hardware keyboard or D-pad. With [blocksOverlays] open
 * overlays close or hide while it is presented ([BlockOverlays]).
 */
@Composable
fun UpdateRequiredScreen(
    prompt: UpdatePrompt.Required,
    checking: Boolean,
    presented: Boolean,
    onUpdate: (String) -> Unit,
    onCheckAgain: () -> Unit,
    blocksOverlays: Boolean = true,
) {
    val colors = ShroudTheme.colors
    val url = prompt.updateUrl
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    BackHandler(enabled = presented) {}
    BlockOverlays(active = presented && blocksOverlays)
    LaunchedEffect(presented) {
        if (!presented) return@LaunchedEffect
        // Typing must not reach a field under the screen, nor its IME action submit it.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        focus.requestFocus()
    }
    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            // Focus may enter the screen, never leave it for the hidden app below.
            .then(if (presented) Modifier.focusProperties { onExit = { cancelFocusChange() } } else Modifier)
            .focusGroup()
            .background(colors.backgroundGrouped)
            .then(if (presented) Modifier.pointerInput(Unit) { swallowEveryTouch() } else Modifier)
            .then(
                if (presented) {
                    Modifier.semantics {
                        paneTitle = UpdateCopy.REQUIRED_TITLE
                        isTraversalGroup = true
                    }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            )
            .testTag(REQUIRED_SCREEN_TAG)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandLogoMark(
                        80.dp,
                        // Welcome's: accent 22 %, blur 16, y 10.
                        Modifier.dropShadow(brandTileShape(80.dp), Shadow(radius = 16.dp, color = colors.accent, offset = DpOffset(0.dp, 10.dp), alpha = 0.22f)),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShroudText(
                            UpdateCopy.REQUIRED_TITLE,
                            inter(26f, FontWeight.Bold),
                            colors.textPrimary,
                            Modifier.semantics { heading() },
                            textAlign = TextAlign.Center,
                        )
                        ShroudText(
                            UpdateCopy.requiredMessage(prompt.latestVersion, hasLink = url != null),
                            inter(15f, lineSpacing = 2f),
                            colors.textSecondary,
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.backgroundGrouped)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (url != null) {
                    PrimaryButton(UpdateCopy.UPDATE, { onUpdate(url) }, showsArrow = false, enabled = presented)
                    SecondaryButton(
                        if (checking) UpdateCopy.CHECKING else UpdateCopy.CHECK_AGAIN,
                        onCheckAgain,
                        enabled = presented && !checking,
                    )
                } else {
                    PrimaryButton(
                        if (checking) UpdateCopy.CHECKING else UpdateCopy.CHECK_AGAIN,
                        onCheckAgain,
                        showsArrow = false,
                        isLoading = checking,
                        enabled = presented,
                    )
                }
            }
        }
    }
}

/** The required screen's test tag. */
internal const val REQUIRED_SCREEN_TAG = "update.required"

/** Takes every pointer event so nothing under the screen reacts. */
private suspend fun PointerInputScope.swallowEveryTouch() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

@Preview(name = "Update required", widthDp = 412, heightDp = 915)
@Composable
private fun UpdateRequiredPreview() {
    ShroudTheme(dark = false) {
        UpdateRequiredScreen(
            UpdatePrompt.Required("0.1.0", "0.2.0", "https://shroud.corespace.de/download"),
            checking = false,
            presented = true,
            onUpdate = {},
            onCheckAgain = {},
        )
    }
}

@Preview(name = "Update available", widthDp = 412, heightDp = 915)
@Composable
private fun UpdateAvailablePreview() {
    ShroudTheme(dark = false) {
        Box(Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundGrouped)) {
            UpdateAvailableDialog(UpdatePrompt.Available("0.1.0", "0.2.0", "https://shroud.corespace.de/download"), onUpdate = { true }, onLater = {})
        }
    }
}

package de.corespace.shroud.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.GlassBarButton
import de.corespace.shroud.ui.components.GlassBarButtonStyle
import de.corespace.shroud.ui.components.PushedBarMetrics
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SectionCaption
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ShroudTextField
import de.corespace.shroud.ui.components.ShroudToggle
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.shell.LocalAppActions
import de.corespace.shroud.ui.shell.LocalPushedBackGate
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings › Server, the signed-in page (iOS `ServerSettingsView`, `ios/shroud/Features/Main/ServerSettingsView.swift:10-539`;
 * settings-lock §10.1; design `Settings — Server` `N6l9Rb`): the official server or a self-hosted
 * one. Opens on the **saved** configuration, so nothing animates in on entry (`:52-57`).
 *
 * Saving the same endpoint stores it and pops after the Saving → Saved beat. A changed endpoint
 * while signed in first asks "Change server?" and then signs out **without saving**: the shell's
 * Log Out ([LocalAppActions] `logOut(switchingTo)`) revokes the session on the old server and
 * stores the new one after the wipe (`:492-538`, shell §3.5). Back is blocked while a save runs —
 * it may end in a sign-out (`:114-116`).
 *
 * The Android 17 local-network permission is not asked here: nothing reaches the new server
 * before the next sign-in, where onboarding asks (settings-lock §10.1).
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val store = container.serverConfiguration
    val saved by store.configuration.collectAsState()
    val session by container.auth.sessionController.session.collectAsState()
    val actions = LocalAppActions.current
    val initial = remember(store) { store.configuration.value }
    ServerSettingsPage(
        initial = initial,
        saved = saved,
        signedIn = session != null,
        save = { draft -> store.save(draft) },
        onSignOut = { draft -> actions.logOut(switchingTo = draft) },
        onBack = onBack,
    )
}

/** Where the bottom Save button is (`SavePhase`, `ServerSettingsView.swift:41-46`). */
enum class ServerSavePhase { Idle, Saving, Success }

/** What a tap on Save leads to (`attemptSave()`, `ServerSettingsView.swift:472-490`). */
sealed interface ServerSaveDecision {
    /** A save is already running. */
    data object Ignore : ServerSaveDecision

    /** The self-hosted fields do not make a usable address; [message] goes under them. */
    data class Invalid(val message: String) : ServerSaveDecision

    /** Signed in and the endpoint changes: "Change server?" first. */
    data object ConfirmSignOut : ServerSaveDecision

    /** Store it and pop. */
    data object Save : ServerSaveDecision
}

/** The Server page's rules and copy (settings-lock §10.1). Pure. */
object ServerSettingsLogic {
    const val TITLE = "Server"
    const val HEADER = "Use the official Shroud network or connect to your own self-hosted server."
    const val SIGNED_IN_WARNING =
        "Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint."
    const val OFFICIAL_INFO =
        "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."

    /** [A] the emulator and LAN hint (design; iOS "…on a simulator use 127.0.0.1…your Mac’s LAN IP", `:423`). */
    const val SELF_HOSTED_INFO = "For Docker Compose on an emulator use 10.0.2.2 and port 8080. On a physical device, use your computer’s LAN IP."
    const val CONFIRM_TITLE = "Change server?"
    const val CONFIRM_MESSAGE = "Switching servers signs you out of this account on this device. You can sign in again on the new server."
    const val CONFIRM_ACTION = "Save and sign out"

    /** The spinner shows at least this long, even though the write is instant (`:503-504`). */
    const val SAVING_MS = 480L

    /** "Saved" stays this long before the page pops or signs out (`:526-527`). */
    const val SAVED_HOLD_MS = 620L

    /** `endpointChanged` (`ServerSettingsView.swift:59-62`): another API root, or another mode. */
    fun endpointChanged(draft: ServerConfiguration, saved: ServerConfiguration): Boolean =
        draft.resolvedBaseUrl != saved.resolvedBaseUrl || draft.mode != saved.mode

    /** `attemptSave()` (`ServerSettingsView.swift:472-490`). */
    fun decide(draft: ServerConfiguration, saved: ServerConfiguration, signedIn: Boolean, busy: Boolean): ServerSaveDecision {
        if (busy) return ServerSaveDecision.Ignore
        draft.validationError()?.let { return ServerSaveDecision.Invalid(it) }
        if (signedIn && endpointChanged(draft, saved)) return ServerSaveDecision.ConfirmSignOut
        return ServerSaveDecision.Save
    }

    /** The info card's text for [mode] (`infoCopy`, `:418-425`). */
    fun infoCopy(mode: ServerConnectionMode): String = when (mode) {
        ServerConnectionMode.Official -> OFFICIAL_INFO
        ServerConnectionMode.SelfHosted -> SELF_HOSTED_INFO
    }
}

/** Keeps the draft across a configuration change; the server address is not a secret. */
private val ServerConfigurationSaver = listSaver<ServerConfiguration, Any>(
    save = { listOf(it.mode.name, it.host, it.port, it.apiPath, it.useHTTPS) },
    restore = {
        ServerConfiguration(ServerConnectionMode.valueOf(it[0] as String), it[1] as String, it[2] as String, it[3] as String, it[4] as Boolean)
    },
)

/**
 * The Server page's drawing and its save flow ([ServerSettingsScreen] wires it to the store, the
 * session and the shell). [save] stores a configuration; [onSignOut] runs the Log Out that
 * switches to the draft; [onBack] pops (also after a plain save). [pause] waits out the Saving and
 * Saved beats (tests skip them).
 */
@Composable
fun ServerSettingsPage(
    initial: ServerConfiguration,
    saved: ServerConfiguration,
    signedIn: Boolean,
    save: (ServerConfiguration) -> Unit,
    onSignOut: (ServerConfiguration) -> Unit,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(stateSaver = ServerConfigurationSaver) { mutableStateOf(initial) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var errorScrollToken by remember { mutableIntStateOf(0) }
    var savePhase by remember { mutableStateOf(ServerSavePhase.Idle) }
    var showSignOutConfirm by rememberSaveable { mutableStateOf(false) }
    val isBusy = savePhase != ServerSavePhase.Idle

    // No way back while a save runs — it may end in a sign-out (`:114-116`).
    val backGate = LocalPushedBackGate.current
    SideEffect { backGate.setEnabled(!isBusy) }
    DisposableEffect(backGate) { onDispose { backGate.setEnabled(true) } }

    fun performSave(signOutAfter: Boolean) {
        if (savePhase != ServerSavePhase.Idle) return
        val target = draft
        scope.launch {
            savePhase = ServerSavePhase.Saving
            haptic(Haptic.Soft)
            // Let the spinner register even though the write is synchronous (`:503-504`).
            pause(ServerSettingsLogic.SAVING_MS)
            if (!signOutAfter) {
                // Signing out, the Log Out stores it once the wipe is over (`:506-510`).
                val failure = runCatching { save(target) }.exceptionOrNull()
                if (failure != null) {
                    haptic(Haptic.Error)
                    savePhase = ServerSavePhase.Idle
                    // iOS throws only the validation message here (`ServerConfigurationController.swift:19-25`).
                    errorMessage = target.validationError() ?: failure.message.orEmpty()
                    errorScrollToken++
                    return@launch
                }
            }
            savePhase = ServerSavePhase.Success
            haptic(Haptic.Success)
            pause(ServerSettingsLogic.SAVED_HOLD_MS)
            if (signOutAfter) onSignOut(target) else onBack()
        }
    }

    fun attemptSave() {
        when (val decision = ServerSettingsLogic.decide(draft, saved, signedIn, isBusy)) {
            ServerSaveDecision.Ignore -> Unit
            is ServerSaveDecision.Invalid -> {
                haptic(Haptic.Error)
                errorMessage = decision.message
                errorScrollToken++
            }
            ServerSaveDecision.ConfirmSignOut -> {
                errorMessage = null
                showSignOutConfirm = true
            }
            ServerSaveDecision.Save -> {
                errorMessage = null
                performSave(signOutAfter = false)
            }
        }
    }

    fun selectMode(mode: ServerConnectionMode) {
        if (draft.mode == mode) return
        haptic(Haptic.Soft)
        draft = draft.copy(mode = mode)
        errorMessage = null
    }

    // A drag on the form puts the keyboard away (`.scrollDismissesKeyboard(.interactively)`,
    // `:88`). Only the user's drags count: the scroll that brings a focused field into view
    // above the keyboard is not a drag and must not close it again.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(scrollState) {
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) focusManager.clearFocus()
        }
    }

    // Every failed Save brings the error line into view, centred under the bar, a frame later
    // (`:96-104`), even when its text did not change.
    val positions = remember { ErrorLinePositions() }
    val density = LocalDensity.current
    val barBlock = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PushedBarMetrics.height
    LaunchedEffect(errorScrollToken) {
        if (errorScrollToken == 0 || errorMessage == null) return@LaunchedEffect
        withFrameNanos { }
        val target = positions.scrollTarget(with(density) { barBlock.toPx() }, scrollState.viewportSize) ?: return@LaunchedEffect
        scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue), Motion.respecting(reduceMotion, Motion.standard()))
    }

    PushedScreen(
        title = ServerSettingsLogic.TITLE,
        onBack = onBack,
        backEnabled = !isBusy,
        scrollState = scrollState,
        trailing = {
            GlassBarButton(null, "Save", { attemptSave() }, GlassBarButtonStyle.Capsule, label = "Save", enabled = !isBusy)
        },
        bottomBar = { BottomSave(savePhase, enabled = !isBusy, onClick = { attemptSave() }) },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { positions.content = it }
                    .alpha(if (isBusy) 0.55f else 1f)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ShroudText(ServerSettingsLogic.HEADER, inter(14f), colors.textSecondary, Modifier.padding(top = 4.dp))
                ModePicker(draft.mode, reduceMotion, ::selectMode)
                SelfHostedSection(
                    visible = draft.mode == ServerConnectionMode.SelfHosted,
                    draft = draft,
                    reduceMotion = reduceMotion,
                    onChange = { draft = it },
                )
                ErrorLine(errorMessage, reduceMotion, Modifier.onGloballyPositioned { positions.error = it })
                SignedInWarning()
                InfoCard(draft.mode)
            }
            // `.allowsHitTesting(!isBusy)` (`:93`): nothing in the form takes a touch while saving.
            if (isBusy) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(pass = PointerEventPass.Initial).consume()
                            }
                        }
                        .semantics { disabled() },
                )
            }
        }
    }

    ActionSheet(
        visible = showSignOutConfirm,
        title = ServerSettingsLogic.CONFIRM_TITLE,
        message = ServerSettingsLogic.CONFIRM_MESSAGE,
        items = listOf(ActionSheetItem(ServerSettingsLogic.CONFIRM_ACTION, destructive = true) { performSave(signOutAfter = true) }),
        onDismiss = { showSignOutConfirm = false },
    )
}

/** Where the error line sits, for the scroll that brings it into view. */
private class ErrorLinePositions {
    var content: LayoutCoordinates? = null
    var error: LayoutCoordinates? = null

    /** The scroll value that centres the error line in the area under the bar, or null without a line. */
    fun scrollTarget(barBlockPx: Float, viewportPx: Int): Int? {
        val content = content?.takeIf { it.isAttached } ?: return null
        val error = error?.takeIf { it.isAttached && it.size.height > 0 } ?: return null
        val inScroll = barBlockPx + content.localPositionOf(error, Offset.Zero).y
        val visibleHeight = viewportPx - barBlockPx
        return (inScroll + error.size.height / 2f - barBlockPx - visibleHeight / 2f).roundToInt()
    }
}

/**
 * CONNECTION: the two mode cards (`modePicker`, `ServerSettingsView.swift:224-246`). The selected
 * card's filled radio **moves** to the other card (a shared element, iOS `matchedGeometryEffect`);
 * under Reduce Motion each card has its own key, so it fades across instead (`:268-273`).
 */
@Composable
private fun ModePicker(mode: ServerConnectionMode, reduceMotion: Boolean, onSelect: (ServerConnectionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCaption("CONNECTION")
        SharedTransitionLayout {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(
                    mode = ServerConnectionMode.Official,
                    selected = mode == ServerConnectionMode.Official,
                    title = "Official Shroud server",
                    subtitle = "Managed by Shroud · always up to date",
                    badge = ShroudIcons.SealCheckFill,
                    reduceMotion = reduceMotion,
                    onSelect = onSelect,
                )
                ModeOption(
                    mode = ServerConnectionMode.SelfHosted,
                    selected = mode == ServerConnectionMode.SelfHosted,
                    title = "Self-hosted",
                    subtitle = "Your Docker / private server",
                    badge = ShroudIcons.HardDriveFill,
                    reduceMotion = reduceMotion,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/**
 * One mode card (`modeOption`, `ServerSettingsView.swift:248-309`): radio, title and subtitle, and
 * the badge that bounces whenever the selection changes. Padding 14, radius 14, `accentSoft` with
 * a 1.5 dp `accent @ 0.35` rim when selected, `background` otherwise. Presses scale to 0.98 without
 * a haptic of its own (selecting ticks softly). TalkBack: "<title>. <subtitle>", selected.
 */
@Composable
private fun SharedTransitionScope.ModeOption(
    mode: ServerConnectionMode,
    selected: Boolean,
    title: String,
    subtitle: String,
    badge: ImageVector,
    reduceMotion: Boolean,
    onSelect: (ServerConnectionMode) -> Unit,
) {
    val colors = ShroudTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val spec = Motion.respecting(reduceMotion, Motion.standard<Color>())
    val fill by animateColorAsState(if (selected) colors.accentSoft else colors.background, spec, label = "modeFill")
    val rim by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.35f) else Color.Transparent, spec, label = "modeRim")
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.98f, haptic = Haptic.None, role = Role.RadioButton, onClick = { onSelect(mode) })
            .clearAndSetSemantics {
                this.selected = selected
                contentDescription = "$title. $subtitle"
            }
            .clip(shape)
            .background(fill)
            .border(1.5.dp, rim, shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(mode, selected, reduceMotion)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(15f, FontWeight.SemiBold), colors.textPrimary)
            ShroudText(subtitle, inter(12f), colors.textSecondary)
        }
        BouncingBadge(badge, selected, reduceMotion)
    }
}

/** The 22 dp radio: a 2 dp `textSecondary` ring, or the moving `accent` fill with an 8 dp white dot. */
@Composable
private fun SharedTransitionScope.Radio(mode: ServerConnectionMode, selected: Boolean, reduceMotion: Boolean) {
    val colors = ShroudTheme.colors
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (!selected) Box(Modifier.size(22.dp).border(2.dp, colors.textSecondary, CircleShape))
        AnimatedVisibility(selected, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            RadioFill(if (reduceMotion) "modeRadioFull.$mode" else "modeRadioFull", this)
        }
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun SharedTransitionScope.RadioFill(key: String, visibility: AnimatedVisibilityScope) {
    Box(
        Modifier
            .sharedElement(
                rememberSharedContentState(key),
                animatedVisibilityScope = visibility,
                boundsTransform = BoundsTransform { _, _ -> Motion.standard() },
            )
            .size(22.dp)
            .clip(CircleShape)
            .background(ShroudTheme.colors.accent),
    )
}

/**
 * The card's badge, 18 dp, `accent` when selected: it bounces (scale 1 → 1.2 → 1 on
 * `Motion.bouncy`) whenever the selection changes, as `symbolEffect(.bounce, value: selected)`
 * does (`:291-294`); still under Reduce Motion.
 */
@Composable
private fun BouncingBadge(icon: ImageVector, selected: Boolean, reduceMotion: Boolean) {
    val colors = ShroudTheme.colors
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (reduceMotion) return@LaunchedEffect
        scale.animateTo(1.2f, Motion.bouncy())
        scale.animateTo(1f, Motion.bouncy())
    }
    val tint by animateColorAsState(if (selected) colors.accent else colors.textSecondary, Motion.respecting(reduceMotion, Motion.standard()), label = "badgeTint")
    ShroudIcon(icon, tint, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, size = 18.dp)
}

/**
 * SELF-HOSTED DETAILS (`selfHostedSection`, `ServerSettingsView.swift:311-380`): address, port and
 * API path fields, Use HTTPS, and the live URL preview. Enters with fade + slide from the top +
 * scale 0.98 anchored at the top, leaves with fade + slide; a fade under Reduce Motion.
 */
@Composable
private fun SelfHostedSection(visible: Boolean, draft: ServerConfiguration, reduceMotion: Boolean, onChange: (ServerConfiguration) -> Unit) {
    val colors = ShroudTheme.colors
    val enter = if (reduceMotion) {
        fadeIn(Motion.reduced())
    } else {
        fadeIn(Motion.standard()) +
            slideInVertically(Motion.standard()) { -it / 4 } +
            scaleIn(Motion.standard(), initialScale = 0.98f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f))
    }
    val exit = if (reduceMotion) fadeOut(Motion.reduced()) else fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { -it / 4 }
    AnimatedVisibility(visible, enter = enter, exit = exit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCaption("SELF-HOSTED DETAILS")
            ServerField("Address / host", ShroudIcons.Globe, draft.host, { onChange(draft.copy(host = it)) }, keyboardType = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                ServerField("Port", ShroudIcons.Hash, draft.port, { onChange(draft.copy(port = it)) }, Modifier.weight(1f), KeyboardType.Number)
                ServerField(
                    "API path",
                    ShroudIcons.Folder,
                    draft.apiPath,
                    { onChange(draft.copy(apiPath = it)) },
                    Modifier.weight(1f),
                    KeyboardType.Uri,
                    ImeAction.Done,
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ShroudText("Use HTTPS", inter(15f, FontWeight.Medium), colors.textPrimary, Modifier.weight(1f))
                ShroudToggle(draft.useHTTPS, { onChange(draft.copy(useHTTPS = it)) }, "Use HTTPS")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(ShroudIcons.Link, colors.textSecondary, size = 13.dp)
                ShroudText(draft.selfHostedPreview, inter(12f, FontWeight.Medium, monospaced = true), colors.textSecondary, maxLines = 2)
            }
        }
    }
}

/**
 * A labelled field of the page (`field(...)`, `ServerSettingsView.swift:429-461`): 13 sp Medium
 * label (hidden from TalkBack — the field reads its title), a 48 dp `background` box, radius 12,
 * padding h 12, the 15 dp `accent` glyph in an 18 dp column, 15 sp Medium text. No autocorrect,
 * no capitalisation.
 */
@Composable
private fun ServerField(
    title: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = ShroudTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShroudText(title, inter(13f, FontWeight.Medium), colors.textPrimary, Modifier.clearAndSetSemantics {})
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { ShroudIcon(icon, colors.accent, size = 15.dp) }
            ShroudTextField(
                value,
                onValueChange,
                title,
                Modifier.weight(1f),
                style = inter(15f, FontWeight.Medium),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
            )
        }
    }
}

/**
 * The validation or save error, right under the fields it is about (`:72-84`): 13 sp Medium
 * `danger`, fading and sliding in from the top (a fade under Reduce Motion). The last text stays
 * while it leaves.
 */
@Composable
private fun ErrorLine(message: String?, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    val enter = if (reduceMotion) fadeIn(Motion.reduced()) else fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { -it }
    val exit = if (reduceMotion) fadeOut(Motion.reduced()) else fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { -it }
    AnimatedVisibility(message != null, modifier, enter = enter, exit = exit) {
        ShroudText(shown.orEmpty(), inter(13f, FontWeight.Medium), colors.danger)
    }
}

/** The signed-in warning, always shown on this page (`signedInWarning`, `:382-397`). */
@Composable
private fun SignedInWarning() {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.warningBackground)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShroudIcon(ShroudIcons.WarningFill, colors.warningIcon, size = 15.dp)
        ShroudText(ServerSettingsLogic.SIGNED_IN_WARNING, inter(12f), colors.warningText, Modifier.weight(1f))
    }
}

/** The info card (`infoCard`, `:399-416`): `accent` glyph, `accentText` copy on `accentSoft`. */
@Composable
private fun InfoCard(mode: ServerConnectionMode) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentSoft)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShroudIcon(ShroudIcons.InfoFill, colors.accent, size = 16.dp)
        ShroudText(ServerSettingsLogic.infoCopy(mode), inter(12f), colors.accentText, Modifier.weight(1f))
    }
}

/**
 * The pinned bottom Save (`bottomSave`, `ServerSettingsView.swift:165-220`): a 50 dp capsule on a
 * `backgroundGrouped @ 0.95` bar. Save → spinner + "Saving…" → bouncing check + "Saved", the
 * labels swapping with fade + scale 0.92 (fade under Reduce Motion); the fill cross-fades from
 * `accent` to `successFill` with a matching 16 dp shadow; 0.98 while saving.
 */
@Composable
private fun BottomSave(phase: ServerSavePhase, enabled: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val spec = Motion.respecting(reduceMotion, Motion.snappy<Float>())
    val success by animateFloatAsState(if (phase == ServerSavePhase.Success) 1f else 0f, spec, label = "saveSuccess")
    val scale by animateFloatAsState(if (phase == ServerSavePhase.Saving) 0.98f else 1f, spec, label = "saveScale")
    val shadowColor = androidx.compose.ui.graphics.lerp(colors.accent, colors.successFill, success).copy(alpha = 0.28f)
    val label = when (phase) {
        ServerSavePhase.Idle -> "Save"
        ServerSavePhase.Saving -> "Saving…"
        ServerSavePhase.Success -> "Saved"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.backgroundGrouped.copy(alpha = 0.95f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pressable(enabled = enabled, scale = 0.98f, dimming = 0.05f, haptic = Haptic.Medium, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = label
                    if (!enabled) disabled()
                }
                .dropShadow(CircleShape, Shadow(radius = 16.dp, color = shadowColor, offset = DpOffset(0.dp, 8.dp)))
                .height(50.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .background(colors.successFill.copy(alpha = success)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(Motion.reduced()) togetherWith fadeOut(Motion.reduced())
                    } else {
                        (fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), 0.92f)) togetherWith (fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), 0.92f))
                    }
                },
                label = "saveLabel",
            ) { shown ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (shown) {
                        ServerSavePhase.Idle -> Unit
                        ServerSavePhase.Saving -> Spinner(Color.White, size = 19.dp)
                        ServerSavePhase.Success -> SuccessCheck(reduceMotion)
                    }
                    val text = when (shown) {
                        ServerSavePhase.Idle -> "Save"
                        ServerSavePhase.Saving -> "Saving…"
                        ServerSavePhase.Success -> "Saved"
                    }
                    ShroudText(text, inter(17f, FontWeight.SemiBold), Color.White)
                }
            }
        }
    }
}

/** Phosphor `check-circle-fill` 20 dp with the success bounce (`symbolEffect(.bounce)`, `:185-187`). */
@Composable
private fun SuccessCheck(reduceMotion: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        scale.animateTo(1.2f, Motion.bouncy())
        scale.animateTo(1f, Motion.bouncy())
    }
    ShroudIcon(ShroudIcons.CheckCircleFill, Color.White, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, size = 20.dp)
}

@Preview(name = "Server · 412 · self-hosted", widthDp = 412, heightDp = 917)
@Composable
private fun ServerSettingsPreview() {
    val selfHosted = ServerConfiguration.localDevelopment("10.0.2.2", 8080)
    ShroudTheme(dark = false) {
        ServerSettingsPage(selfHosted, selfHosted, signedIn = true, save = {}, onSignOut = {}, onBack = {})
    }
}

@Preview(name = "Server · 360 · dark · official", widthDp = 360, heightDp = 800)
@Composable
private fun ServerSettingsDarkPreview() {
    ShroudTheme(dark = true) {
        ServerSettingsPage(ServerConfiguration.official, ServerConfiguration.official, signedIn = true, save = {}, onSignOut = {}, onBack = {})
    }
}

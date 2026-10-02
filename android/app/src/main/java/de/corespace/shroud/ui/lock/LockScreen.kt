package de.corespace.shroud.ui.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.keys.UnlockMethod
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.Avatar
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.onboarding.ServerSettingsContent
import de.corespace.shroud.ui.shell.LocalAppActions
import de.corespace.shroud.ui.shell.LockScreenRouter
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The chat lock screen (`LockScreenView.swift`; settings-lock §11; design `Locked` `yGDcx`,
 * `Locked — No Screen Lock` `o5GgZ`, `Locked — Fingerprints Changed` `LRnnR`, `Locked — System
 * Biometric Prompt` `p5OtXk`, storyboard `dRyqM`, `Locked · Dark` `Q7aTH`, `Locked · 360` `uqmy8`).
 *
 * Human: Shown instead of Welcome when this phone holds the account's keys but the chats are
 * sealed. One job: unlock — with the strong biometric or the screen lock through the system prompt,
 * or, when that is gone, with the 12-word phrase. Every prompt is a tap; nothing prompts by itself
 * (`:12`). On success the badge springs open, the chips decrypt, the rings ripple out and the shell
 * cross-fades into Chats.
 *
 * Agent: READS the session, the phone's security and the vault ([LockScreenPorts]); CALLS
 * `CryptoController.unlockHistoryIfPossible`, `MessagingController.prepareCachedState`, and on [router]
 * `prewarmMainShell` → `unlockMessages`; "Use encryption phrase" is [LockScreenRouter.showPhraseEntry].
 * The server gear opens the account-context server sheet: a new endpoint is a full Log Out through
 * [LocalAppActions] (`AppActions.logOut(switchingTo)`, `:134-142`). [LockScreenState.isUnlocking] tells
 * the shell an unlock is completing.
 */
@Composable
fun LockScreen(router: LockScreenRouter) {
    val container = LocalAppContainer.current
    val ports = remember(container) { ContainerLockScreenPorts(container) }
    val actions = LocalAppActions.current
    LockScreenContent(
        router = router,
        ports = ports,
        server = container.serverConfiguration.configuration,
        saveServer = container.serverConfiguration::save,
        switchServer = { actions.logOut(it) },
    )
}

/** [LockScreen] on explicit dependencies, for screen tests. */
@Composable
internal fun LockScreenContent(
    router: LockScreenRouter,
    ports: LockScreenPorts,
    server: StateFlow<ServerConfiguration>,
    saveServer: (ServerConfiguration) -> Unit,
    switchServer: (ServerConfiguration) -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val noun = DeviceNoun.current(LocalContext.current)
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val haptics = rememberHaptics()
    val model = remember(ports, router) {
        LockScreenModel(ports, router, haptic = haptics, showToast = { if (it == null) toast.dismiss() else toast.show(it) })
    }
    val session by ports.session.collectAsState()
    var showServerSettings by remember { mutableStateOf(false) }
    // Survives a push to the phrase step and back, as the iOS view stays in the stack (`:37`, `:104-109`).
    var hasArrived by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(model) { onDispose { model.onDispose() } }
    // First reading of the phone's security; the arrival waits for it, so no mode flashes.
    LaunchedEffect(model) { model.recheck(announce = false) }
    // Back from system Settings (a screen lock or a fingerprint added): read it again (`:131-133`).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> left = true
                Lifecycle.Event.ON_RESUME -> if (left) {
                    left = false
                    if (!model.isBusy) scope.launch { model.recheck(announce = false) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.probe != null) {
        if (model.probe == null || hasArrived) return@LaunchedEffect
        kotlinx.coroutines.delay(ARRIVAL_DELAY_MS)
        hasArrived = true
    }
    // A notice about this phone ("Local data was cleared…") and the orphan check (`:110-120`).
    LaunchedEffect(model) {
        model.presentPostAuthToastIfNeeded()
        if (router.reconcileOrphanedSessionIfNeeded()) model.presentPostAuthToastIfNeeded()
        snapshotFlow { router.postAuthToast }.collect { if (it != null) model.presentPostAuthToastIfNeeded() }
    }
    LaunchedEffect(model) { ports.chatsUnlocked.collect { unlocked -> if (!unlocked) model.onChatsLocked() } }

    val arrival by animateFloatAsState(if (hasArrived) 1f else 0f, Motion.respecting(reduce, Motion.gentle()), label = "arrival")
    val transition = updateTransition(model.phase, label = "unlock")
    val released by transition.animateFloat({ lockPhaseSpec(initialState, targetState, reduce) }, label = "released") {
        if (it.isReleased) 1f else 0f
    }
    val navAlpha by transition.animateFloat({ lockPhaseSpec(initialState, targetState, reduce) }, label = "nav") {
        if (it == UnlockPhase.Idle || it == UnlockPhase.Checking) 1f else 0f
    }
    val probe = model.probe
    val ready = if (probe != null) 1f else 0f
    val mode = model.mode
    val label = probe?.biometricLabel ?: BiometricLabel.Fingerprint
    val busy = model.isBusy

    Box(Modifier.fillMaxSize().background(colors.background)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val heroScale = LockHeroMath.heroScale(maxHeight.value)
            Column(Modifier.fillMaxSize()) {
                GlassBarRow(
                    modifier = Modifier.graphicsLayer { alpha = navAlpha }.then(if (navAlpha <= 0f) Modifier.clearAndSetSemantics {} else Modifier),
                    trailing = {
                        GlassCircleButton(
                            ShroudIcons.GearSixFill,
                            "Server settings",
                            onClick = { showServerSettings = true },
                            enabled = !busy && navAlpha > 0f,
                        )
                    },
                )
                LockHero(transition, heroScale, arrival * ready, Modifier.padding(top = 4.dp))
                LockCopyBlock(
                    username = session?.username,
                    mode = mode,
                    noun = noun,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = arrival * ready * (1f - released)
                            translationY = (released * 24f + if (reduce) 0f else (1f - arrival) * 14f).dp.toPx()
                        },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = arrival * ready * (1f - released)
                            translationY = (released * 24f + if (reduce) 0f else (1f - arrival) * 20f).dp.toPx()
                        }
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (mode) {
                        LockScreenMode.NoScreenLock -> PrimaryButton(
                            LockCopy.primaryIdle(mode, label),
                            onClick = { scope.launch { model.recheck(announce = true) } },
                            modifier = Modifier.testTag("lock.recheckPasscode"),
                            showsArrow = false,
                            enabled = !busy,
                        )
                        else -> UnlockButton(
                            title = LockCopy.primary(mode, label, model.phase),
                            icon = primaryIcon(mode, label),
                            transition = transition,
                            enabled = !busy,
                            onClick = { scope.launch { model.primary(reduce) } },
                            modifier = Modifier.testTag(
                                when (mode) {
                                    LockScreenMode.Biometric -> "lock.unlockBiometry"
                                    LockScreenMode.PhraseNeeded -> "lock.enterPhrase"
                                    else -> "lock.unlockPasscode"
                                },
                            ),
                        )
                    }
                    if (mode == LockScreenMode.Biometric) {
                        SecondaryUnlockButton(
                            title = LockCopy.secondary(model.unlockingMethod == UnlockMethod.PasscodeOnly && model.phase == UnlockPhase.Checking),
                            enabled = !busy,
                            onClick = { scope.launch { model.unlock(UnlockMethod.PasscodeOnly, reduce) } },
                            modifier = Modifier.testTag("lock.unlockPasscode"),
                        )
                    }
                    LockCopy.fallbackLead(mode, label)?.let { lead ->
                        PhraseFallbackRow(lead, enabled = !busy, onClick = model::usePhrase)
                    }
                    TrustLine(ShroudIcons.KeyRound, LockCopy.TRUST)
                    if (probe?.softwareKeystore == true) TrustLine(ShroudIcons.ShieldAlert, LockCopy.softwareKeystoreNotice(noun), Modifier.padding(top = 0.dp))
                }
            }
        }
        ToastHost(toast)
        // Signed in here: the sheet warns, and an endpoint change is Log Out. The wipe revokes the
        // session on the server that issued it, and only then is the new server saved (`:134-142`).
        ShroudSheet(visible = showServerSettings, onDismiss = { showServerSettings = false }, paneTitle = "Server") {
            ServerSettingsContent(
                initial = server.value,
                onSave = { draft ->
                    saveServer(draft)
                    showServerSettings = false
                },
                onCancel = { showServerSettings = false },
                accountContext = true,
                onSignOut = { draft ->
                    showServerSettings = false
                    switchServer(draft)
                },
            )
        }
    }
}

/** The arrival starts 50 ms after the first frame (`:106`). */
private const val ARRIVAL_DELAY_MS = 50L

/** The primary button's glyph (S6, S10; design `yGDcx`, `LRnnR`). */
private fun primaryIcon(mode: LockScreenMode, label: BiometricLabel): ImageVector = when (mode) {
    LockScreenMode.Biometric -> if (label == BiometricLabel.Face) ShroudIcons.ScanFace else ShroudIcons.Fingerprint
    LockScreenMode.PhraseNeeded -> ShroudIcons.KeyRound
    else -> ShroudIcons.LockOpen
}

/** The account chip, title and body (`content`, `:301-343`). */
@Composable
private fun LockCopyBlock(username: String?, mode: LockScreenMode, noun: String, modifier: Modifier) {
    val colors = ShroudTheme.colors
    Column(
        modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!username.isNullOrEmpty()) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.backgroundGrouped)
                    .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
                    .clearAndSetSemantics { contentDescription = LockCopy.account(username) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(AvatarPalette.initials(username), size = 26.dp, fontSize = 11.sp)
                ShroudText("@$username", inter(13f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1)
            }
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.fade()) },
            label = "lockCopy",
        ) { shown ->
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShroudText(
                    LockCopy.title(shown),
                    inter(30f, FontWeight.Bold, letterSpacing = (-0.6).sp),
                    colors.textPrimary,
                    Modifier.semantics { heading() },
                    textAlign = TextAlign.Center,
                )
                ShroudText(
                    LockCopy.body(shown, noun),
                    inter(15f, lineSpacing = 4f),
                    colors.textSecondary,
                    Modifier.widthIn(max = 300.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The filled unlock capsule (`unlockButton`, `:423-458`): 54 high, the mode's glyph (22 dp, the
 * design's metric, P13h) and title in white; accent, cross-fading to `successFill` once verified
 * (bouncy) with a matching shadow; press 0.975 / 0.05 / medium haptic. The title swaps by opacity
 * only — never a per-glyph morph (`:433-436`; memory `face-id-keeps-scene-inactive`).
 */
@Composable
private fun UnlockButton(
    title: String,
    icon: ImageVector,
    transition: Transition<UnlockPhase>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val success by transition.animateFloat(
        { if (reduce) Motion.reduced() else Motion.bouncy() },
        label = "unlockSuccess",
    ) { if (it.isVerified) 1f else 0f }
    val verified = transition.targetState.isVerified
    Row(
        modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, scale = 0.975f, dimming = 0.05f, haptic = Haptic.Medium, onClick = onClick)
            .dropShadow(
                CircleShape,
                Shadow(radius = 20.dp, color = lerp(colors.accent, colors.successFill, success), offset = DpOffset(0.dp, 8.dp), alpha = 0.25f),
            )
            .height(54.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(colors.accent)
                drawRect(colors.successFill, alpha = success.coerceIn(0f, 1f))
            }
            .semantics(mergeDescendants = true) { contentDescription = title },
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(if (verified) ShroudIcons.Check else icon, animationSpec = Motion.fade(), label = "unlockGlyph") { glyph ->
            ShroudIcon(glyph, Color.White, size = 22.dp)
        }
        AnimatedContent(title, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) }, label = "unlockTitle") { text ->
            ShroudText(text, inter(17f, FontWeight.SemiBold), Color.White, maxLines = 1)
        }
    }
}

/** "Use screen lock" (`passcodeButton`, `:460-477`): grouped capsule, accent 17 SemiBold, press 0.975 / 0.06. */
@Composable
private fun SecondaryUnlockButton(title: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, scale = 0.975f, dimming = 0.06f, onClick = onClick)
            .height(54.dp)
            .clip(CircleShape)
            .background(colors.backgroundGrouped)
            .semantics(mergeDescendants = true) { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(title, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) }, label = "secondaryTitle") { text ->
            ShroudText(text, inter(17f, FontWeight.SemiBold), colors.accent, maxLines = 1)
        }
    }
}

/**
 * "<Label> not working? **Use encryption phrase**" (`:373-395`): the whole sentence is one control
 * (TalkBack "Use encryption phrase") with a 48 dp hit area; it takes iOS's 44 pt row with top 2 and
 * bottom −6 in the layout, so the trust line keeps its place.
 */
@Composable
private fun PhraseFallbackRow(lead: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val trim = FALLBACK_LAYOUT_TRIM.roundToPx()
                layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, 0) }
            }
            .heightIn(min = 48.dp)
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = LockCopy.USE_PHRASE }
            .testTag("lock.usePhrase"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(lead, inter(13f), colors.textSecondary, maxLines = 1)
        ShroudText(LockCopy.USE_PHRASE, inter(13f, FontWeight.SemiBold), colors.accent, maxLines = 1)
    }
}

/** 48 dp hit area laid out as iOS's 44 pt row with −6 bottom padding: 48 − 38. */
private val FALLBACK_LAYOUT_TRIM = 10.dp

/** "Keys never leave this device" (`:397-404`): key-round 12 (design) + 11 sp, secondary at 75 %. */
@Composable
private fun TrustLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val tint = ShroudTheme.colors.textSecondary.copy(alpha = 0.75f)
    Row(
        modifier.padding(top = 4.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(icon, tint, size = 12.dp)
        ShroudText(text, inter(11f), tint, textAlign = TextAlign.Center)
    }
}

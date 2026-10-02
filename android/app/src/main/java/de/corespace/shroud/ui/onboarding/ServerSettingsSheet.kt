package de.corespace.shroud.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.Appear
import de.corespace.shroud.ui.components.LabeledField
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.SectionCaption
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ShroudToggle
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Server settings (`ServerSettingsSheet.swift`; settings-lock §10.2-10.3 and addendum
 * *ServerSettingsSheet.swift*; design `Server Settings — Welcome` `aNX3S`). Official vs.
 * self-hosted host / port / path, in two contexts:
 *
 * - **Onboarding** (Welcome's gear): Save validates and hands the draft to [onSave].
 * - **Account** ([accountContext], the lock screen's gear — signed in): a warning card, and an
 *   endpoint change asks **"Change server?"**; "Save and sign out" saves **nothing** — after the
 *   confirmation has left (120 ms) it hands the draft to [onSignOut], which runs the full Log Out and
 *   saves the server once the wipe is over, so the old server hears the sign-out and the new one
 *   never sees this session's token (`:127-139, 410-450`).
 *
 * [initial] is the saved configuration (the sheet never opens on the build default, `:452-473`).
 * The caller hosts it in a `ShroudSheet` and closes it from [onSave], [onCancel] and [onSignOut].
 */
@Composable
fun ServerSettingsContent(
    initial: ServerConfiguration,
    onSave: (ServerConfiguration) -> Unit,
    onCancel: () -> Unit,
    accountContext: Boolean = false,
    onSignOut: ((ServerConfiguration) -> Unit)? = null,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val haptic = rememberHaptics()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by every failed Save: the error line is scrolled into view each time (`:89-97`, V4).
    var errorToken by remember { mutableIntStateOf(0) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val errorInView = remember { BringIntoViewRequester() }
    LaunchedEffect(errorToken) {
        if (errorToken == 0) return@LaunchedEffect
        // A frame later, once the new line has a place to scroll to (`:91-96`).
        withFrameNanos { }
        errorInView.bringIntoView()
    }

    fun selectMode(mode: ServerConnectionMode) {
        // Same mode: nothing; otherwise a soft tick and the mode (`selectMode`, `:401-408`, V3).
        if (draft.mode == mode) return
        haptic(Haptic.Soft)
        draft = draft.copy(mode = mode)
        error = null
    }

    fun performSave(signOutAfter: Boolean) {
        haptic(Haptic.Success)
        if (!signOutAfter) {
            onSave(draft)
            return
        }
        // Signing out, nothing is saved here (`:430-450`); a beat for the confirmation to leave.
        val chosen = draft
        scope.launch {
            delay(CONFIRMATION_LEAVE_MS)
            onSignOut?.invoke(chosen)
        }
    }

    fun attemptSave() {
        error = null
        val validation = draft.validationError()
        if (validation != null) {
            haptic(Haptic.Error)
            error = validation
            errorToken++
            return
        }
        if (accountContext && onSignOut != null && endpointChanged(draft, initial)) {
            confirmSignOut = true
            return
        }
        performSave(signOutAfter = false)
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShroudText("Server", inter(28f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() })
            ShroudText("Use the official Shroud network or connect to your own self-hosted server.", inter(14f), colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCaption("CONNECTION")
            // The selected disc slides from one card to the other (`matchedGeometryEffect`, `:192-197`, V1).
            SharedTransitionLayout {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeOption(
                        selected = draft.mode == ServerConnectionMode.Official,
                        title = "Official Shroud server",
                        subtitle = "Managed by Shroud · always up to date",
                        badge = ShroudIcons.SealCheckFill,
                        shared = this@SharedTransitionLayout,
                    ) { selectMode(ServerConnectionMode.Official) }
                    ModeOption(
                        selected = draft.mode == ServerConnectionMode.SelfHosted,
                        title = "Self-hosted",
                        subtitle = "Your Docker / private server",
                        badge = ShroudIcons.HardDriveFill,
                        shared = this@SharedTransitionLayout,
                    ) { selectMode(ServerConnectionMode.SelfHosted) }
                }
            }
        }
        AnimatedVisibility(
            draft.mode == ServerConnectionMode.SelfHosted,
            enter = if (reduce) {
                fadeIn(Motion.reduced()) + expandVertically(Motion.reduced(), expandFrom = Alignment.Top)
            } else {
                fadeIn(Motion.standard()) + expandVertically(Motion.standard(), expandFrom = Alignment.Top) +
                    scaleIn(Motion.standard(), initialScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 0f))
            },
            exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.respecting(reduce, Motion.standard()), shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionCaption("SELF-HOSTED DETAILS")
                LabeledField("Address / host", ShroudIcons.Globe, draft.host, { draft = draft.copy(host = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        "Port",
                        ShroudIcons.Hash,
                        draft.port,
                        { value -> draft = draft.copy(port = value.filter(Char::isDigit).take(5)) },
                        Modifier.weight(1f),
                        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    )
                    // The last field: Done drops the keyboard (iOS keyboard toolbar "Done", `:115-128`, V6).
                    LabeledField(
                        "API path",
                        ShroudIcons.Folder,
                        draft.apiPath,
                        { draft = draft.copy(apiPath = it) },
                        Modifier.weight(1f),
                        KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        onImeAction = { focus.clearFocus() },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShroudText("Use HTTPS", inter(15f, FontWeight.Medium), colors.textPrimary, Modifier.weight(1f))
                    ShroudToggle(draft.useHTTPS, { draft = draft.copy(useHTTPS = it) }, "Use HTTPS")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundGrouped)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShroudIcon(ShroudIcons.Link, colors.textSecondary, size = 14.dp)
                    ShroudText(draft.selfHostedPreview, inter(12f, FontWeight.Medium, monospaced = true), colors.textSecondary, maxLines = 2)
                }
            }
        }
        // Right under the fields it is about (`:65-77`); opacity + move from the top (V4).
        AnimatedVisibility(
            error != null,
            enter = if (reduce) fadeIn(Motion.reduced()) else fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { -it / 2 } + expandVertically(Motion.standard()),
            exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.respecting(reduce, Motion.standard())),
        ) {
            ShroudText(error.orEmpty(), inter(13f, FontWeight.Medium), colors.dangerText, Modifier.bringIntoViewRequester(errorInView))
        }
        InfoCard(
            ShroudIcons.InfoFill,
            if (draft.mode == ServerConnectionMode.Official) {
                "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."
            } else {
                "For Docker Compose on an emulator use 10.0.2.2 and port 8080. On a physical device, use your computer’s LAN IP."
            },
            fill = colors.accentSoft,
            tint = colors.accentText,
            iconSize = 16,
        )
        if (accountContext) {
            InfoCard(
                ShroudIcons.WarningFill,
                SIGNED_IN_WARNING,
                fill = colors.warningBackground,
                tint = colors.warningText,
                iconTint = colors.warningIcon,
                iconSize = 15,
            )
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", onClick = ::attemptSave, showsArrow = false)
            SecondaryButton("Cancel", onCancel)
        }
    }
    // iOS 26 hides the anchored dialog's Cancel; Android shows it (memory `ios26-confirmationdialog-hides-cancel`).
    ActionSheet(
        visible = confirmSignOut,
        title = "Change server?",
        message = "Switching servers signs you out of this account on this device. You can sign in again on the new server.",
        items = listOf(ActionSheetItem("Save and sign out", destructive = true) { performSave(signOutAfter = true) }),
        onDismiss = { confirmSignOut = false },
    )
}

/** The signed-in warning (`:338-353`). */
const val SIGNED_IN_WARNING =
    "Changing the server while signed in will sign you out of this device so you can reconnect with the new endpoint."

/** "A beat for the confirmation dialog to finish leaving before the sheet goes" (`:436-437`). */
private const val CONFIRMATION_LEAVE_MS = 120L

/** `endpointChanged` (`:53-56`): another resolved URL or another mode than the saved one. */
fun endpointChanged(draft: ServerConfiguration, saved: ServerConfiguration): Boolean =
    draft.resolvedBaseUrl != saved.resolvedBaseUrl || draft.mode != saved.mode

@Composable
private fun InfoCard(icon: ImageVector, text: String, fill: Color, tint: Color, iconTint: Color = tint, iconSize: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShroudIcon(icon, iconTint, size = iconSize.dp)
        ShroudText(text, inter(12f), tint)
    }
}

/**
 * One connection card (`modeOption`, `:181-237`): radio, title/subtitle, badge; `accentSoft` with a
 * 1.5 dp accent rim when selected, scale 0.99 otherwise; press 0.98 with the light press-down tick.
 * The selected disc is a shared element of [shared] (it slides between the cards, V1; Reduce
 * Motion: a plain fade); the badge bounces when the selection changes (`.symbolEffect(.bounce)`, V2).
 */
@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    badge: ImageVector,
    shared: SharedTransitionScope,
    onSelect: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val spring = Motion.respecting<Color>(reduce, Motion.standard())
    val fill by animateColorAsState(if (selected) colors.accentSoft else colors.backgroundGrouped, spring, label = "modeFill")
    val rim by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.35f) else Color.Transparent, spring, label = "modeRim")
    val scale by animateFloatAsState(if (selected) 1f else 0.99f, Motion.respecting(reduce, Motion.standard()), label = "modeScale")
    val bounce = remember { Animatable(1f) }
    val last = remember { booleanArrayOf(selected) }
    LaunchedEffect(selected) {
        val changed = last[0] != selected
        last[0] = selected
        if (!changed || reduce) return@LaunchedEffect
        bounce.animateTo(1.2f, Motion.bouncy())
        bounce.animateTo(1f, Motion.bouncy())
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.98f, haptic = Haptic.Light, role = Role.RadioButton, onClick = onSelect)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .border(1.5.dp, rim, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$title. $subtitle"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            // Unselected ring in secondary grey (the separator tone was ~1.1:1, `:192`).
            if (!selected) Box(Modifier.size(22.dp).border(2.dp, colors.textSecondary, CircleShape))
            Appear(selected, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                SelectedDisc(shared, this, reduce)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(15f, FontWeight.SemiBold), colors.textPrimary)
            ShroudText(subtitle, inter(12f), colors.textSecondary)
        }
        ShroudIcon(
            badge,
            if (selected) colors.accent else colors.textSecondary,
            Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
            size = 18.dp,
        )
    }
}

/** The 22 dp accent disc with its white 8 dp dot; shared between the two cards ("modeRadio", `:196`). */
@Composable
private fun SelectedDisc(shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, reduce: Boolean) {
    val colors = ShroudTheme.colors
    val disc = with(shared) {
        if (reduce) {
            Modifier
        } else {
            Modifier.sharedElement(rememberSharedContentState(MODE_RADIO), visibility, boundsTransform = { _, _ -> Motion.standard() })
        }
    }
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(disc.size(22.dp).clip(CircleShape).background(colors.accent))
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
    }
}

private const val MODE_RADIO = "modeRadio"

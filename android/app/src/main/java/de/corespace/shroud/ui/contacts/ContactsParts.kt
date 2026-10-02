package de.corespace.shroud.ui.contacts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.mono
import de.corespace.shroud.ui.theme.perform

/**
 * The sort capsule of the Contacts bar (`ContactsView.swift:62-73`; design CumKS `Sort Label`):
 * "A–Z" / "Z–A" in a 44 dp glass capsule (16 sp Medium `accent`, 16 dp side padding) that
 * cross-fades its label. TalkBack: "Sorted A to Z" with the hint "Reverses the order". A light
 * haptic on press-down, as every bar control.
 *
 * Drawn here rather than with `GlassBarButton`, which has no click label for the hint.
 */
@Composable
internal fun SortCapsule(ascending: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) view.perform(Haptic.Light) }
    }
    val spoken = ContactsCopy.sortSpokenLabel(ascending)
    val toggle by rememberUpdatedState(onToggle)
    androidx.compose.foundation.layout.Box(
        modifier
            .glassSurface(CircleShape, GlassStyle.Bar, interactive = true)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = ContactsCopy.SORT_HINT) { toggle() }
            // The click (with its "Reverses the order" hint) stays; only the label replaces the text.
            .clearAndSetSemantics { contentDescription = spoken }
            .heightIn(min = GlassBarMetrics.controlSize)
            .widthIn(min = GlassBarMetrics.controlSize)
            .padding(horizontal = GlassBarMetrics.capsulePadding),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = ContactsCopy.sortLabel(ascending),
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            contentAlignment = Alignment.Center,
            label = "sortLabel",
        ) { text ->
            ShroudText(text, inter(GlassBarMetrics.LABEL_SIZE, FontWeight.Medium), colors.accent, maxLines = 1)
        }
    }
}

/**
 * A letter or "Pending" header (`ContactsView.swift:260-270`): 13 sp SemiBold `textSecondary`, full
 * width, padding 16 / 3, on `backgroundGrouped` (iOS `.ultraThinMaterial`; the design's `Section`
 * frames fill `$bg-grouped`). A heading, so TalkBack's headings navigation jumps letter by letter.
 */
@Composable
internal fun ContactsSectionHeader(title: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    ShroudText(
        title,
        inter(13f, FontWeight.SemiBold),
        colors.textSecondary,
        modifier
            .fillMaxWidth()
            .background(colors.backgroundGrouped)
            .semantics { heading() }
            .padding(horizontal = 16.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

/**
 * A pending incoming request (`ContactsView.swift:186-235`): avatar 52, the name (16 SemiBold, one
 * line, truncated in the middle — a 36-character id has no spaces) over "wants to connect" (13),
 * read by TalkBack as one; then Reject (14 SemiBold `danger`) and Accept (14 SemiBold `accent`),
 * 44 dp tall, pressable 0.9. While this request's answer is on the wire both buttons are off and
 * dim to 40 % (`Motion.fade`).
 */
@Composable
internal fun ContactRequestRow(
    request: ContactRequestDto,
    isResponding: Boolean,
    onRespond: (accept: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val name = ContactsCopy.requestName(request)
    val dim by animateFloatAsState(if (isResponding) 0.4f else 1f, Motion.fade(), label = "requestDim")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAvatar(name)
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = "$name, ${ContactsCopy.WANTS_TO_CONNECT}" },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ShroudText(name, inter(16f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            ShroudText(ContactsCopy.WANTS_TO_CONNECT, inter(13f), colors.textSecondary, maxLines = 1)
        }
        RequestButton(ContactsCopy.REJECT, colors.danger, enabled = !isResponding, alpha = dim) { onRespond(false) }
        RequestButton(ContactsCopy.ACCEPT, colors.accent, enabled = !isResponding, alpha = dim) { onRespond(true) }
    }
}

@Composable
private fun RequestButton(title: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, alpha: Float, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .graphicsLayer { this.alpha = alpha }
            .pressable(enabled = enabled, scale = 0.9f, onClick = onClick)
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(title, inter(14f, FontWeight.SemiBold), color, maxLines = 1)
    }
}

/**
 * The empty list (`ContactsView.swift:277-299`): Phosphor `users-fill` 32 dp `accent @ 0.85` with
 * one bounce on arrival (none under Reduce Motion; decorative, 6 dp extra below), the title 16
 * SemiBold and the message 14 `textSecondary` centred with 24 dp sides; spacing 8, 48 dp from the
 * top. The copy comes from `ContactsSorting.listContent` ("No matches" while searching).
 */
@Composable
internal fun ContactsEmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val bounce = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(Unit) {
        if (bounce.value != 1f) bounce.animateTo(1f, Motion.bouncy())
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShroudIcon(
            ShroudIcons.UsersFill,
            colors.accent.copy(alpha = 0.85f),
            Modifier
                .padding(bottom = 6.dp)
                .clearAndSetSemantics {}
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                },
            size = 32.dp,
        )
        ShroudText(title, inter(16f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
        ShroudText(message, inter(14f), colors.textSecondary, Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
    }
}

/**
 * "Your invite" under the list (`ContactsView.swift:301-338`): the share code (18 SemiBold
 * monospace, selectable) and its link (12 monospace `textSecondary`, selectable, two lines at
 * most), or "Loading share code…" until the session knows it; then "Show QR code" (QR glyph, 14
 * SemiBold `accent`, pressable 0.95) → My QR Code. Padding 16, 40 on top.
 */
@Composable
internal fun ShareFooter(shareCode: String?, shareUrl: String?, onShowQr: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShroudText(ContactsCopy.YOUR_INVITE, inter(12f, FontWeight.SemiBold), colors.textSecondary)
        if (shareCode != null) {
            SelectionContainer {
                ShroudText(shareCode, mono(18f, FontWeight.SemiBold), colors.textPrimary)
            }
            if (shareUrl != null) {
                SelectionContainer {
                    ShroudText(shareUrl, mono(12f), colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            ShroudText(ContactsCopy.LOADING_SHARE_CODE, inter(13f), colors.textSecondary)
        }
        Row(
            Modifier
                .pressable(scale = 0.95f, onClick = onShowQr)
                .clearAndSetSemantics { contentDescription = ContactsCopy.SHOW_QR_CODE }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudIcon(ShroudIcons.QrCode, colors.accent, size = 16.dp)
            ShroudText(ContactsCopy.SHOW_QR_CODE, inter(14f, FontWeight.SemiBold), colors.accent, maxLines = 1)
        }
    }
}

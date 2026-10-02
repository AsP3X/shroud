package de.corespace.shroud.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.contacts.ContactInviteParser
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.ToastState
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.mono
import de.corespace.shroud.ui.theme.rememberHaptics

/**
 * My QR Code (iOS `MyQRCodeSheet`, `ios/shroud/Features/Main/MyQRCodeSheet.swift:4-144`; contacts
 * §5.6, design `My QR Code` rqBW3): the large sheet with "Done" · "My QR Code", the handle, the
 * share link's QR code, the link and the short code with copy buttons, the hint and "Share invite".
 *
 * Human: Friends scan this, open the link or type the code. Copying says "Link copied" / "Code
 * copied" at the sheet's own bottom edge; "Share invite" opens the share sheet with the link. Until
 * the session knows its share code (sessions from before the feature) a spinner says "Loading your
 * code…" while the session is validated.
 *
 * Agent: Reads `SessionController.session` and the server configuration (the link follows the
 * server, `ContactInviteParser.shareUrl`); validates the session on opening when the code is
 * missing (`:95-100`). Nothing here is secret: the code is meant to be handed out.
 */
@Composable
fun MyQrCodeSheet(visible: Boolean, onDismiss: () -> Unit) {
    val ports = rememberContactsPorts()
    val session by ports.session.collectAsState()
    val server by ports.server.collectAsState()
    val toast = rememberToastState()
    val context = LocalContext.current
    val haptic = rememberHaptics()

    LaunchedEffect(visible) {
        // For sessions created before the share code existed (`:95-100`).
        if (visible && ports.session.value?.shareCode == null) ports.validateSession()
    }

    val shareCode = session?.shareCode
    ShroudSheet(
        visible = visible,
        onDismiss = onDismiss,
        style = SheetStyle.Full,
        paneTitle = ContactsCopy.MY_QR_TITLE,
        showsHandle = false,
        cornerRadius = 38.dp,
        scrim = ShroudTheme.colors.sheetScrim,
    ) {
        // The sheet covers the tab bar: its toast sits on the sheet's own bottom edge (`:85-87`).
        CompositionLocalProvider(LocalTabBarClearance provides 0.dp) {
            MyQrCodeContent(
                username = session?.username,
                shareCode = shareCode,
                shareUrl = shareCode?.let { ContactInviteParser.shareUrl(it, server) },
                toast = toast,
                onDone = onDismiss,
                onCopy = { value, confirmation ->
                    // `copy(_:label:)` (`:139-143`): one light tick, the toast.
                    copyInvite(context, value)
                    haptic(Haptic.Light)
                    toast.show(Toast.success(confirmation))
                },
                onShare = { url -> shareInvite(context, url) },
            )
        }
    }
}

/** The sheet's content, from plain values (tests, previews). */
@Composable
internal fun MyQrCodeContent(
    username: String?,
    shareCode: String?,
    shareUrl: String?,
    toast: ToastState,
    onDone: () -> Unit,
    onCopy: (value: String, confirmation: String) -> Unit,
    onShare: (String) -> Unit,
) {
    val colors = ShroudTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header 44 (16 sides): "Done" · "My QR Code" (`:88-94`); design 14 dp from the top.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText(
                    ContactsCopy.MY_QR_TITLE,
                    inter(17f, FontWeight.SemiBold),
                    colors.textPrimary,
                    Modifier.semantics { heading() },
                    maxLines = 1,
                )
                Row(Modifier.fillMaxWidth()) {
                    SheetCapsuleButton(ContactsCopy.DONE, colors.textPrimary, glass = true, onClick = onDone)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // iOS `.padding(24)` (`:79`); the design's 16 on top loses to the code (contacts §5.6).
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (username != null) {
                    ShroudText("@$username", inter(20f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
                }
                if (shareUrl != null) {
                    QrCodeView(shareUrl, size = 220.dp)
                    InviteBlock(ContactsCopy.SHARE_LINK, shareUrl, ContactsCopy.COPY_LINK) { onCopy(shareUrl, ContactsCopy.LINK_COPIED) }
                } else {
                    // `ProgressView("Loading your code…")` (`:41-42`).
                    Column(
                        Modifier.padding(vertical = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spinner(colors.textSecondary, size = 20.dp)
                        ShroudText(ContactsCopy.LOADING_YOUR_CODE, inter(14f), colors.textSecondary)
                    }
                }
                if (shareCode != null) {
                    InviteBlock(ContactsCopy.SHARE_CODE, shareCode, ContactsCopy.COPY_SHARE_CODE) { onCopy(shareCode, ContactsCopy.CODE_COPIED) }
                }
                ShroudText(
                    ContactsCopy.MY_QR_HINT,
                    inter(14f),
                    colors.textSecondary,
                    Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
                if (shareUrl != null) {
                    ShareInviteButton(Modifier.padding(top = 4.dp)) { onShare(shareUrl) }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
        ToastHost(toast)
    }
}

/**
 * A labelled value with its copy button (`MyQRCodeSheet.swift:104-137`): label 12 SemiBold
 * `textSecondary`, 8 dp, then a `backgroundGrouped` row (padding 12, radius 12, gap 10) with the
 * value in 14 sp monospace — selectable, two lines at most, shrinking to 70 % before it truncates —
 * and the copy glyph (15 dp `accent`, pressable 0.85, no press haptic: copying ticks once). The
 * glyph's 24 dp box keeps the row's height; Compose widens its touch to 48 dp.
 */
@Composable
private fun InviteBlock(label: String, value: String, copyLabel: String, onCopy: () -> Unit) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShroudText(label, inter(12f, FontWeight.SemiBold), colors.textSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.backgroundGrouped)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(Modifier.weight(1f)) {
                BasicText(
                    text = value,
                    style = mono(14f).copy(color = colors.textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = (14f * 0.7f).sp, maxFontSize = 14.sp, stepSize = 0.25.sp),
                )
            }
            // HStack spacing 10 on both sides of a `Spacer(minLength: 8)`: at least 28 dp (`:112-119`).
            Spacer(Modifier.width(28.dp))
            Box(
                Modifier
                    .size(24.dp)
                    .pressable(scale = 0.85f, haptic = Haptic.None, onClick = onCopy)
                    .clearAndSetSemantics { contentDescription = copyLabel },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.Copy, colors.accent, size = 15.dp)
            }
        }
    }
}

/**
 * "Share invite" (`MyQRCodeSheet.swift:62-77`): the PrimaryButton recipe — 54 dp `accent` capsule,
 * accent shadow (25 %, blur 20, y 8), press 0.975 / dim 0.05 with a medium haptic — with the share
 * glyph (Phosphor `export-bold` 18 white) before the 17 SemiBold label, no arrow.
 */
@Composable
private fun ShareInviteButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .pressable(scale = 0.975f, dimming = 0.05f, haptic = Haptic.Medium, onClick = onClick)
            .dropShadow(CircleShape, Shadow(radius = 20.dp, color = colors.accent, offset = DpOffset(0.dp, 8.dp), alpha = 0.25f))
            .height(54.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clearAndSetSemantics { contentDescription = ContactsCopy.SHARE_INVITE },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.ExportBold, Color.White, size = 18.dp)
        ShroudText(ContactsCopy.SHARE_INVITE, inter(17f, FontWeight.SemiBold), Color.White, maxLines = 1)
    }
}

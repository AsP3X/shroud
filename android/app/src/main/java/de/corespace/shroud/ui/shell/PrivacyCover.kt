package de.corespace.shroud.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * What covers the chats while the app is away or the screen is captured — iOS
 * `AppSwitcherPrivacyCover` (`RootView.swift:489-501`; shell-chats §3.8; design `Recents — Cover`
 * FSVJl): `background` with the 72 dp mark. One TalkBack node, "Chats are hidden while the screen is
 * recorded or mirrored" (only reachable during a capture; in the switcher nothing is read). Takes
 * every touch.
 */
@Composable
fun PrivacyCover(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(ShroudTheme.colors.background)
            .consumeAllPointers()
            .clearAndSetSemantics { contentDescription = PRIVACY_COVER_LABEL },
        contentAlignment = Alignment.Center,
    ) {
        BrandLogoMark(72.dp)
    }
}

/** `RootView.swift:499`. */
const val PRIVACY_COVER_LABEL = "Chats are hidden while the screen is recorded or mirrored"

/**
 * The two-pane detail with nothing selected (shell-chats §4.9; web `AppShell.tsx:2436-2441`; frame
 * owed by W3-DESIGN): the 48 dp mark, "Select a conversation" 16 SemiBold, "Your messages are
 * end-to-end encrypted, on this device and on theirs." 14 `textSecondary`, centred. Tabs without a
 * conversation list (Calls, Settings) show the mark alone (D5).
 */
@Composable
fun DetailPlaceholder(showsCopy: Boolean, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Box(modifier.fillMaxSize().background(colors.backgroundChat), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandLogoMark(48.dp)
            if (showsCopy) {
                ShroudText(SELECT_CONVERSATION_TITLE, inter(16f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
                ShroudText(SELECT_CONVERSATION_BODY, inter(14f), colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** `web/src/screens/AppShell.tsx:2438`. */
const val SELECT_CONVERSATION_TITLE = "Select a conversation"

/** `web/src/screens/AppShell.tsx:2439-2440`. */
const val SELECT_CONVERSATION_BODY = "Your messages are end-to-end encrypted, on this device and on theirs."

/** Swallows every pointer event before the content below sees it (covers, the stack's transitions). */
internal fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

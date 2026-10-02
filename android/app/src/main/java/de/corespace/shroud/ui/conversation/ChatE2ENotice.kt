package de.corespace.shroud.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The soft accent banner at the start of a chat (`ChatE2ENotice`, `ChatDateChip.swift:23-40`; design
 * hc3Jf; conversation-thread §2.5): a lock (Lucide `lock` 12) and "Messages and calls are end-to-end
 * encrypted" 12, centred, in `accentText` on an `accentSoft` capsule padded 12 / 6. The lock is
 * decorative; TalkBack reads the sentence.
 */
@Composable
fun ChatE2ENotice(modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.accentSoft)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.Lock, colors.accentText, size = 12.dp)
        ShroudText(E2E_NOTICE, inter(12f), colors.accentText, textAlign = TextAlign.Center)
    }
}

/** The notice's words (`ChatDateChip.swift:29`). */
const val E2E_NOTICE = "Messages and calls are end-to-end encrypted"

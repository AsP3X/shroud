package de.corespace.shroud.ui.conversation.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteContent
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteStyle
import de.corespace.shroud.ui.conversation.bubble.ReplyQuoteView
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The "Reply to …" strip above the composer while a reply is written (iOS `ChatReplyBar`,
 * `ChatReplyBar.swift:9-47`; design mIJut; conversation-compose-media §5): the same quote the sent
 * bubble will carry, so what is answered looks identical before and after the send. Tapping the
 * quote jumps to the original ([ReplyQuoteView] plays its tick); ✕ drops the reply and leaves the
 * draft alone. Full composer width (the design's 366 dp inset is drift; code wins, §26).
 */
@Composable
internal fun ChatReplyBar(content: ReplyQuoteContent, onTapPreview: (() -> Unit)?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 26.dp, height = 34.dp), contentAlignment = Alignment.Center) {
            ShroudIcon(ShroudIcons.ArrowBendUpLeft, colors.accent, size = 20.dp)
        }
        Box(Modifier.weight(1f)) {
            ReplyQuoteView(content = content, style = ReplyQuoteStyle.Composer, onTap = onTapPreview, fontSize = 15.sp)
        }
        StripCloseButton(icon = ShroudIcons.XBold, contentDescription = "Cancel reply", onClick = onCancel)
    }
}

/**
 * The strips' ✕ (`ChatReplyBar.swift:29-39`, `ChatLinkBar.swift:68-78`): a 34 dp glyph box with a
 * touch target reaching into the gap and the trailing padding, pressed down to 0.86.
 */
@Composable
internal fun StripCloseButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .touchArea(STRIP_GLYPH_BOX, ComposerMetrics.touchSize)
            .pressable(scale = 0.86f, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, ShroudTheme.colors.textSecondary, size = 16.dp)
    }
}

private val STRIP_GLYPH_BOX = 34.dp

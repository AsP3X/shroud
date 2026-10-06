package de.corespace.shroud.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.media.files.AudioFileCopy
import de.corespace.shroud.core.media.files.FileCategory
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileWarning
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ShroudTextField
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.conversation.bubble.AudioCoverDisc
import de.corespace.shroud.ui.conversation.bubble.BubbleImages
import de.corespace.shroud.ui.conversation.bubble.FileGlyphs
import de.corespace.shroud.ui.conversation.bubble.FileWarningLine
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The file composer (docs/file-sharing.md §7): an inset sheet titled "Send File" / "Send {n} Files"
 * listing the staged files — tile, name (one line, truncated in the middle), `{size} · {TYPE}`, the
 * warning line, a Remove button; an audio file with its cover, title and artist (§11.3) — then "Files are sent as they are, without compression, and keep
 * their metadata.", the caption field ("Add a caption…") and Send.
 *
 * Human: the sheet rises with the picked files; removing the last one closes it; Send closes it and
 * the files go out one bubble each, the caption on the first. The caption lives only while the sheet
 * is up. Back, a swipe down or the scrim cancel. [draft] null hides it; the last draft stays drawn
 * while it animates out.
 */
@Composable
internal fun FileComposeSheet(
    draft: FileComposeDraft?,
    onSend: (caption: String) -> Unit,
    onRemove: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf<FileComposeDraft?>(null) }
    if (draft != null) shown = draft
    var caption by remember { mutableStateOf("") }
    // A fresh sheet starts without the last one's caption.
    LaunchedEffect(draft == null) { if (draft == null) caption = "" }
    val files = shown?.files.orEmpty()
    val title = FileCopy.composerTitle(files.size)
    val colors = ShroudTheme.colors
    ShroudSheet(visible = draft != null, onDismiss = onDismiss, style = SheetStyle.Inset, paneTitle = title) {
        ShroudText(
            title,
            inter(17f, FontWeight.SemiBold),
            colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.background),
        ) {
            files.forEachIndexed { index, file ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().padding(start = 66.dp).heightIn(min = 0.5.dp, max = 0.5.dp).background(colors.separator))
                }
                FileComposeRow(file, onRemove = { onRemove(index) })
            }
        }
        ShroudText(FileCopy.COMPOSER_NOTE, inter(13f), colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.background)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            ShroudTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = FileCopy.CAPTION_PLACEHOLDER,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // A caption is message content: kept out of the keyboard's learning like the composer's text.
                noPersonalizedLearning = true,
            )
        }
        PrimaryButton(title = FileCopy.SEND, onClick = { onSend(caption) }, showsArrow = false, enabled = files.isNotEmpty())
    }
}

/**
 * One staged file: tile, name, `{size} · {TYPE}`, warning, Remove. An audio file (docs/file-sharing.md
 * §11.3) gets a 44 round cover (`th`, else the accent circle with music notes), its title tag (else
 * the name) and `{ar} · {duration} · {size} · {EXT}`, leaving out what is missing.
 */
@Composable
private fun FileComposeRow(file: PickedFile, onRemove: () -> Unit) {
    val colors = ShroudTheme.colors
    val isAudio = file.type.category == FileCategory.Audio
    val audio = file.audio.takeIf { isAudio }
    val size = file.sizeBytes.takeIf { it > 0 }?.let(ByteCountLabel::format)
    val meta = if (isAudio) {
        AudioFileCopy.composerMeta(audio?.artist, audio?.durationMs?.toLong(), size, file.type.label)
    } else {
        size?.let { FileCopy.meta(it, file.type.label) } ?: file.type.label
    }
    val title = audio?.title ?: file.name
    val cover = remember(audio?.cover) { audio?.cover?.jpeg?.let(BubbleImages::decodeSmall) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAudio) {
            AudioCoverDisc(fill = colors.accent, cover = cover) {
                ShroudIcon(ShroudIcons.MusicNotesFill, Color.White, size = 20.dp)
            }
        } else {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(FileGlyphs.category(file.type.category), Color.White, size = 22.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(15f, FontWeight.Medium), colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            ShroudText(meta, inter(13f, tabularDigits = true), colors.textSecondary, maxLines = 1)
            file.type.warning?.let { FileWarningLine(it, onAccent = false) }
        }
        Box(
            Modifier
                .size(44.dp)
                .pressable(scale = 0.9f, onClick = onRemove)
                .clearAndSetSemantics { contentDescription = FileCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(colors.textSecondary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                ShroudIcon(ShroudIcons.XBold, colors.textSecondary, size = 12.dp)
            }
        }
    }
}

/**
 * A received file's warning (docs/file-sharing.md §6) as the app's confirmation sheet: the title and
 * message on top, **Continue** in red, and Cancel (the default, and what Back or the scrim do).
 * Each answer covers the one action that asked; [prompt] null hides it. The sheet closes
 * ([onDismiss]) before [onContinue] gets the prompt it showed.
 */
@Composable
internal fun FileWarningSheet(prompt: FileWarningPrompt?, onContinue: (FileWarningPrompt) -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf<FileWarningPrompt?>(null) }
    if (prompt != null) shown = prompt
    val content = shown
    ActionSheet(
        visible = prompt != null,
        title = content?.title,
        message = content?.text,
        items = if (content == null) emptyList() else listOf(ActionSheetItem(FileWarning.CONTINUE, destructive = true) { onContinue(content) }),
        onDismiss = onDismiss,
        cancelTitle = FileWarning.CANCEL,
    )
}

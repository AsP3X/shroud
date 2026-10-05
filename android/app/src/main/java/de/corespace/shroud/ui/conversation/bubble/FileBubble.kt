package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.media.files.FileCategory
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileType
import de.corespace.shroud.core.media.files.FileWarning
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.fileType
import de.corespace.shroud.core.model.needsMediaDownload
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.PdfCardCache
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.conversation.reactions.spokenSummary
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * The file bubble (docs/file-sharing.md §7): a 44 dp tile, the name on one line truncated in the
 * middle (the extension stays visible), `{size} · {TYPE}` and the §6 warning line, then the caption
 * and the time — a fixed width of 240–300 dp on the bubble fill with the tail corner.
 *
 * - The tile says the state: not on this phone → download arrow; transferring either way → the
 *   ring with a stop glyph (a download's tap cancels it); on this phone → the category glyph; a
 *   failed send → retry arrow (tap retries); unsupported → a question mark on a neutral fill, no tap.
 * - A tap elsewhere on the row is the row's ([BubbleContext.onTapMedia]): download when needed, then
 *   open (after §4's check and §6's dialog, both the composer's).
 * - A failed send also gets the media footer: the error and Retry below the bubble.
 *
 * TalkBack reads one node: `File, {name}, {size}` with the warning, the state and the time.
 */
@Composable
internal fun FileMessageBubble(parts: BubbleParts, context: BubbleContext, modifier: Modifier) {
    val message = parts.message
    val colors = ShroudTheme.colors
    val isMine = message.isMine
    val transfer = parts.row.transfer
    val type = message.fileType
    val failed = isMine && message.receipt == ReceiptStatus.Failed
    val handlers = parts.handlers
    val state = FileBubbleMath.tileState(type, failed, transfer, message.needsMediaDownload)
    val width = FileBubbleMath.width(parts.maxBubbleWidth)
    val preview = rememberFilePreview(message)
    // A PDF's card (docs/file-sharing.md §10.1): the local render once the file is here, else `th`.
    val isPdf = type?.category == FileCategory.Pdf
    val reply = parts.row.replyQuote
    val cardWidth = width - PdfCardMetrics.Inset * 2
    val localRender = if (isPdf) rememberPdfLocalRender(message, cardWidth) else PdfLocalRender.None
    val showsCard = isPdf && state != FileTileState.Unsupported && (preview != null || localRender.image != null)
    val pages = message.pageCount ?: localRender.pageCount
    val meta = FileBubbleMath.metaLine(type, failed, transfer, message.mediaByteCount, pages)
    val name = message.fileName ?: FileCopy.FILE
    val caption = message.text.trim().takeIf { it.isNotEmpty() }
    val hasReactions = parts.chips.isNotEmpty()

    val retry: () -> Unit = { handlers.run { context.onRetry(message) } }
    val tileTap: (() -> Unit)? = when {
        !handlers.interactive -> null
        state == FileTileState.Unsupported -> null
        state == FileTileState.Retry -> retry
        state == FileTileState.Transferring && transfer?.isUpload == true -> null
        state == FileTileState.Transferring -> { { handlers.run { context.onCancelDownload(message) } } }
        else -> { { handlers.run { context.onTapMedia(message) } } }
    }
    val label = FileBubbleMath.accessibilityLabel(
        isMine = isMine,
        replyAuthor = reply?.author,
        replyText = reply?.text,
        name = name,
        type = type,
        byteCount = message.mediaByteCount,
        caption = caption,
        failed = failed,
        sendError = message.sendError,
        transfer = transfer,
        needsDownload = message.needsMediaDownload,
        chipsSummary = parts.chips.spokenSummary(),
        time = parts.time,
        receipt = message.receipt,
        pages = pages,
    )
    val quoteTap = handlers.quoteTap(message)
    val rowActions = LocalMessageRowActions.current
    val actions = buildList {
        if (failed && handlers.interactive) add(CustomAccessibilityAction("Retry") { retry(); true })
        if (state == FileTileState.Transferring && transfer?.isUpload == false && handlers.interactive) {
            add(CustomAccessibilityAction("Cancel download") { context.onCancelDownload(message); true })
        }
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        addAll(reactionAccessibilityActions(parts.chips, parts.onReaction))
        addAll(rowActions)
    }
    val defaultAction: (() -> Unit)? = when {
        !handlers.interactive || type == null -> null
        failed -> retry
        state == FileTileState.Transferring -> null
        else -> { { context.onTapMedia(message) } }
    }
    val textColor = if (isMine) Color.White else colors.textPrimary
    val secondary = if (isMine) Color.White.copy(alpha = 0.7f) else colors.textSecondary
    val metaColor = MediaBubbleColors.captionMeta(isMine)
    val shape = BubbleShapes.tail(isMine)
    val showsReceipt = isMine
    val metaRow: @Composable () -> Unit = {
        BubbleMetaRow(
            time = parts.time,
            receipt = if (showsReceipt) message.receipt else null,
            metaColor = metaColor,
            readColor = Color.White.copy(alpha = 0.95f),
            failedColor = Color.White,
        )
    }

    val core = @Composable {
        Column(
            Modifier
                .then(parts.reportBounds)
                .clearAndSetSemantics {
                    contentDescription = label
                    defaultAction?.let { action ->
                        onClick {
                            action()
                            true
                        }
                    }
                    if (actions.isNotEmpty()) customActions = actions
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        ) {
            Column(
                Modifier
                    .width(width)
                    .dropShadow(shape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.08f))
                    .then(if (failed) Modifier.border(1.5.dp, colors.danger.copy(alpha = 0.7f), shape) else Modifier)
                    .clip(shape)
                    .background(MediaBubbleColors.fill(isMine)),
            ) {
                if (reply != null) {
                    ReplyQuoteBlock(
                        content = reply,
                        style = if (isMine) ReplyQuoteStyle.Outgoing else ReplyQuoteStyle.Incoming,
                        onTap = quoteTap,
                        fontSize = REPLY_FONT,
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp),
                    )
                }
                if (showsCard) {
                    PdfPreviewCard(
                        thumbnail = preview,
                        local = localRender.image,
                        onTap = tileTap?.takeIf { state != FileTileState.Retry },
                        modifier = Modifier.padding(
                            start = PdfCardMetrics.Inset,
                            end = PdfCardMetrics.Inset,
                            top = if (reply != null) PdfCardMetrics.InsetBelowQuote else PdfCardMetrics.Inset,
                        ),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = if (caption == null && !hasReactions) 4.dp else 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(FileBubbleMetrics.TileGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // With the card above, the tile shows no `th`: just its state glyph on its fill (§10.1).
                    FileTile(state, type, isMine, transfer, if (showsCard) null else preview, tileTap)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ShroudText(name, inter(15f, FontWeight.Medium), textColor, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        ShroudText(meta, inter(13f, tabularDigits = true), secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        type?.warning?.let { warning -> FileWarningLine(warning, isMine) }
                    }
                }
                if (caption != null) {
                    val body = remember(caption) { AnnotatedString(MessageBubbleMetrics.normalizedForDisplay(caption)) }
                    val style = MessageBubbleMetrics.wrappingBodyStyle.copy(color = textColor)
                    if (hasReactions) {
                        val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 0.dp, end = 11.dp), null)
                        BubbleText(measure, Modifier.fillMaxWidth(), handlers.openLink)
                    } else {
                        val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 0.dp, end = 11.dp, bottom = 6.dp), MetaSpec(parts.time, showsReceipt))
                        BubbleText(measure, Modifier.fillMaxWidth(), handlers.openLink, meta = metaRow)
                    }
                }
                if (hasReactions) {
                    BubbleReactionFoot(
                        parts,
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = MessageBubbleMetrics.metaTrailingPad, top = 5.dp, bottom = 6.dp),
                        metaRow,
                    )
                } else if (caption == null) {
                    Box(Modifier.fillMaxWidth().padding(end = MessageBubbleMetrics.metaTrailingPad, bottom = 5.dp), contentAlignment = Alignment.CenterEnd) {
                        metaRow()
                    }
                }
            }
            AnimatedVisibility(
                visible = failed,
                enter = fadeIn(Motion.snappy()) + expandVertically(Motion.snappy(), expandFrom = Alignment.Top),
                exit = fadeOut(Motion.snappy()) + shrinkVertically(Motion.snappy(), shrinkTowards = Alignment.Top),
            ) {
                MediaFailedFooter(message.sendError, isMine, width, if (handlers.interactive) retry else null)
            }
        }
    }
    if (parts.embedded) {
        Box(modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.BottomEnd else Alignment.BottomStart) { core() }
    } else {
        Box(modifier) { core() }
    }
}

/**
 * The 44 dp tile (§7): accent on incoming bubbles, white @22 % on outgoing ones, a neutral fill for
 * unsupported files; with a `th` the preview fills it under a 35 % black scrim. The glyph is white.
 */
@Composable
private fun FileTile(
    state: FileTileState,
    type: FileType?,
    isMine: Boolean,
    transfer: MediaTransfer?,
    preview: ImageBitmap?,
    onTap: (() -> Unit)?,
) {
    val colors = ShroudTheme.colors
    val fill = when {
        state == FileTileState.Unsupported -> if (isMine) Color.White.copy(alpha = 0.22f) else colors.textSecondary.copy(alpha = 0.5f)
        isMine -> Color.White.copy(alpha = 0.22f)
        else -> colors.accent
    }
    val reduceMotion = ShroudTheme.reduceMotion
    Box(
        Modifier
            .size(FileBubbleMetrics.Tile)
            .clip(RoundedCornerShape(FileBubbleMetrics.TileRadius))
            .background(fill)
            // The tile's own tap, claimed through the bubble's handlers (scale 0.92, light haptic).
            .then(if (onTap != null) Modifier.pressable(scale = 0.92f, dimming = 0f, haptic = Haptic.Light, onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null && state != FileTileState.Unsupported) {
            Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        if (state == FileTileState.Transferring && transfer != null) {
            Box(Modifier.fillMaxSize().padding(3.dp)) { TransferRing(transfer, FileBubbleMetrics.Tile - 6.dp, reduceMotion) }
        }
        val swap = Motion.iconSwap.respecting(reduceMotion)
        AnimatedContent(targetState = state, transitionSpec = { swap.content }, contentAlignment = Alignment.Center, label = "fileGlyph") { shown ->
            val (icon, size) = FileGlyphs.of(shown, type)
            ShroudIcon(icon, Color.White, size = size)
        }
    }
}

/** `⚠ Installs an app` / `⚠ May contain macros`: 12 sp in the warning colour on incoming bubbles, white on outgoing. */
@Composable
internal fun FileWarningLine(warning: FileWarning, onAccent: Boolean) {
    val colors = ShroudTheme.colors
    val text = if (onAccent) Color.White.copy(alpha = 0.92f) else colors.warningText
    val icon = if (onAccent) Color.White.copy(alpha = 0.92f) else colors.warningIcon
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ShroudIcon(ShroudIcons.WarningFill, icon, size = 12.dp)
        ShroudText(warning.bubbleLine, inter(12f, FontWeight.Medium), text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The envelope `th` of a file (images, videos, PDFs from senders that make one), decoded once. */
@Composable
private fun rememberFilePreview(message: ChatMessage): ImageBitmap? {
    val bytes = message.previewJpeg
    return remember(message.id, bytes) { bytes?.toByteArray()?.let(BubbleImages::decodeSmall) }
}

/**
 * The PDF preview card (docs/file-sharing.md §10.1): the top of page 1 in a 2:1 frame, inset 4 dp
 * (6 dp under a reply quote), corner radius 12, white under the picture, aspect fill pinned to the
 * top edge, a 0.5 dp hairline of black at 10 % inside the edge. The local render cross-fades over
 * `th` (150 ms) when it arrives. TalkBack skips it: the bubble's one node says the page count. A
 * tap is the bubble's ([onTap]: download, open, or stop a download).
 */
@Composable
private fun PdfPreviewCard(thumbnail: ImageBitmap?, local: ImageBitmap?, onTap: (() -> Unit)?, modifier: Modifier) {
    val shape = RoundedCornerShape(PdfCardMetrics.Radius)
    val reduceMotion = ShroudTheme.reduceMotion
    val localAlpha by animateFloatAsState(
        targetValue = if (local != null) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(PdfCardMetrics.CROSSFADE_MS),
        label = "pdfCardRender",
    )
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(shape)
            .background(Color.White)
            .then(if (onTap != null) Modifier.pressable(scale = 1f, haptic = Haptic.Light, onClick = onTap) else Modifier)
            .clearAndSetSemantics { },
    ) {
        if (thumbnail != null && localAlpha < 1f) {
            Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
        if (local != null) {
            Image(
                local,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = localAlpha },
            )
        }
        Box(Modifier.matchParentSize().border(PdfCardMetrics.Hairline, Color.Black.copy(alpha = 0.1f), shape))
    }
}

/** A PDF's local card render and the page count it read (both null until there is one). */
private class PdfLocalRender(val image: ImageBitmap?, val pageCount: Int?) {
    companion object {
        val None = PdfLocalRender(null, null)
    }
}

/**
 * The card's local render (§10.1) once the PDF is on this phone: from [PdfCardCache] when it is
 * held, else drawn once through [BubbleServices.pdfCard] at the card's pixel width and kept there
 * (memory only). A PDF that does not render is remembered and keeps `th`.
 */
@Composable
private fun rememberPdfLocalRender(message: ChatMessage, cardWidth: Dp): PdfLocalRender {
    val services = rememberBubbleServices()
    val widthPx = with(LocalDensity.current) { cardWidth.roundToPx() }
    val id = message.id
    var image by remember(id, widthPx) { mutableStateOf(PdfCardCache.image(id, widthPx)) }
    var pages by remember(id) { mutableStateOf(PdfCardCache.pageCount(id)) }
    LaunchedEffect(id, widthPx, message.hasFullMedia) {
        if (!message.hasFullMedia || image != null || PdfCardCache.hasFailed(id) || widthPx <= 0) return@LaunchedEffect
        val render = services.pdfCard(id, widthPx)
        if (render == null) {
            PdfCardCache.markFailed(id)
            return@LaunchedEffect
        }
        val bitmap = render.bitmap.asImageBitmap()
        PdfCardCache.store(id, widthPx, bitmap, render.pageCount)
        image = bitmap
        pages = render.pageCount
    }
    return PdfLocalRender(image, pages)
}

/** The card's sizes (§10.1). */
internal object PdfCardMetrics {
    val Inset = 4.dp
    val InsetBelowQuote = 6.dp
    val Radius = 12.dp
    val Hairline = 0.5.dp
    const val CROSSFADE_MS = 150
}

/** What the tile shows (§7). */
enum class FileTileState { Download, Transferring, Ready, Retry, Unsupported }

/** The tile's glyphs: Phosphor fills per category, bold arrows and the question mark. */
internal object FileGlyphs {
    fun category(category: FileCategory): ImageVector = when (category) {
        FileCategory.Text -> ShroudIcons.FileTextFill
        FileCategory.Pdf -> ShroudIcons.FilePdfFill
        FileCategory.Word -> ShroudIcons.FileDocFill
        FileCategory.Excel -> ShroudIcons.FileXlsFill
        FileCategory.PowerPoint -> ShroudIcons.FilePptFill
        FileCategory.Image -> ShroudIcons.FileImageFill
        FileCategory.Video -> ShroudIcons.FileVideoFill
        FileCategory.App -> ShroudIcons.AndroidLogoFill
    }

    fun of(state: FileTileState, type: FileType?): Pair<ImageVector, Dp> = when (state) {
        FileTileState.Download -> ShroudIcons.ArrowDownBold to 18.dp
        FileTileState.Transferring -> ShroudIcons.StopFill to 14.dp
        FileTileState.Retry -> ShroudIcons.ArrowClockwiseBold to 18.dp
        FileTileState.Unsupported -> ShroudIcons.QuestionBold to 20.dp
        FileTileState.Ready -> (type?.let { category(it.category) } ?: ShroudIcons.FileFill) to 22.dp
    }
}

/** The bubble's sizes (§7). */
internal object FileBubbleMetrics {
    val Tile = 44.dp
    val TileRadius = 12.dp
    val TileGap = 10.dp
    val MinWidth = 240.dp
    val MaxWidth = 300.dp
}

/** The file bubble's rules and words (docs/file-sharing.md §7), pure and unit-tested. */
object FileBubbleMath {
    /** Unsupported first, then a failed send, a transfer either way, a download, else ready. */
    fun tileState(type: FileType?, failed: Boolean, transfer: MediaTransfer?, needsDownload: Boolean): FileTileState = when {
        type == null -> FileTileState.Unsupported
        failed -> FileTileState.Retry
        transfer != null -> FileTileState.Transferring
        needsDownload -> FileTileState.Download
        else -> FileTileState.Ready
    }

    /**
     * `Unsupported file`, `Not sent`, `{done} of {total}` while bytes move, else `{size} · {TYPE}`
     * (just the type when the size is unknown).
     */
    fun metaLine(type: FileType?, failed: Boolean, transfer: MediaTransfer?, byteCount: Long?, pages: Int? = null): String {
        if (type == null) return FileCopy.UNSUPPORTED
        if (failed) return FileCopy.NOT_SENT
        val total = transfer?.totalBytes?.takeIf { it > 0 } ?: byteCount?.takeIf { it > 0 }
        if (transfer != null && transfer.phase == MediaTransfer.Phase.Transferring && total != null) {
            val done = transfer.movedBytes ?: 0L
            return FileCopy.progress(ByteCountLabel.format(done), ByteCountLabel.format(total))
        }
        val count = pages?.takeIf { it >= 1 && type.category == FileCategory.Pdf }
        val size = byteCount?.takeIf { it > 0 } ?: return (count?.let { FileCopy.pageCount(it) + " · " } ?: "") + type.label
        return FileCopy.meta(ByteCountLabel.format(size), type.label, count)
    }

    /** 240–300 dp, never wider than the row's budget. */
    fun width(maxBubbleWidth: Dp): Dp = min(FileBubbleMetrics.MaxWidth, maxBubbleWidth).coerceAtLeast(min(FileBubbleMetrics.MinWidth, maxBubbleWidth))

    /** One node's words: speaker, quote, `File, {name}, {size}` + warning, caption, state, reactions, time, receipt. */
    fun accessibilityLabel(
        isMine: Boolean,
        replyAuthor: String?,
        replyText: String?,
        name: String,
        type: FileType?,
        byteCount: Long?,
        caption: String?,
        failed: Boolean,
        sendError: String?,
        transfer: MediaTransfer?,
        needsDownload: Boolean,
        chipsSummary: String?,
        time: String,
        receipt: ReceiptStatus,
        pages: Int? = null,
    ): String {
        val parts = mutableListOf(if (isMine) "You" else "Them")
        if (replyAuthor != null && replyText != null) parts += "Reply to $replyAuthor: $replyText"
        val size = byteCount?.takeIf { it > 0 }?.let { ByteCountLabel.format(it) }
        val count = pages?.takeIf { it >= 1 && type?.category == FileCategory.Pdf }
        parts += if (size != null) {
            FileCopy.accessibilityLabel(name, size, type?.warning, count)
        } else {
            "File, $name" + (count?.let { ", " + FileCopy.pageCount(it) } ?: "") + (type?.warning?.accessibilitySuffix ?: "")
        }
        if (caption != null) parts += caption
        when {
            type == null -> parts += FileCopy.UNSUPPORTED
            failed -> {
                parts += FileCopy.NOT_SENT
                if (!sendError.isNullOrEmpty()) parts += sendError
            }
            transfer != null -> {
                parts += if (transfer.isUpload) "Sending" else "Downloading"
                if (!transfer.isIndeterminate) parts += "${(transfer.ringFraction * 100).toInt()} percent"
            }
            needsDownload -> parts += "Not downloaded"
        }
        chipsSummary?.let(parts::add)
        parts += time
        if (isMine && !failed) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }
}

private val REPLY_FONT = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)

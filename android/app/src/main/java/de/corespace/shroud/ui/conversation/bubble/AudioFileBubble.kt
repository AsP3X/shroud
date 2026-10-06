package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.media.files.AudioDurations
import de.corespace.shroud.core.media.files.AudioFileCopy
import de.corespace.shroud.core.media.files.AudioTags
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileType
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.fileType
import de.corespace.shroud.core.model.needsMediaDownload
import de.corespace.shroud.core.voice.AudioFilePlaybackState
import de.corespace.shroud.ui.components.RollingText
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberPaced
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.conversation.reactions.spokenSummary
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.floor

/**
 * The audio bubble (docs/file-sharing.md §11.4): the file bubble's shell — width, corners, padding,
 * caption, footer and failed look — with a 44 dp round cover in place of the tile, the title on one
 * line, the detail line, and a scrubber on the active file.
 *
 * - The cover: accent on incoming bubbles, white @22 % on outgoing ones, the `th` cover art under a
 *   35 % black scrim; a white glyph by state — download arrow, the ring with a stop glyph while bytes
 *   move, play / pause on this phone, retry arrow after a failed send.
 * - A tap on the bubble (not the scrubber) is the row's ([BubbleContext.onTapMedia]): download, then
 *   play; stop a download; play / pause. The cover's own tap does the same, a failed send retries.
 * - The playhead is read in the draw phase every frame while the file plays
 *   ([de.corespace.shroud.core.voice.AudioFilePlaybackCoordinator.liveProgress]), so it glides without
 *   recomposing the bubble; the elapsed text moves once a second.
 * - A file the player could not open in this session draws as the plain file bubble with **Can't play
 *   on this phone** ([FileMessageBubble]); its tap is a file's tap.
 *
 * TalkBack reads one node, `Audio, {title}, {artist}, {duration}`, whose default action plays or pauses.
 */
@Composable
internal fun AudioFileMessageBubble(parts: BubbleParts, context: BubbleContext, services: BubbleServices, modifier: Modifier) {
    val message = parts.message
    val id = message.id
    val player = services.audioFiles
    val stateFlow = remember(player) { player?.state ?: MutableStateFlow(AudioFilePlaybackState()) }
    val playerState = stateFlow.collectAsState()
    val unplayable by remember(id, playerState) { derivedStateOf { id in playerState.value.unplayableIds } }
    if (unplayable) {
        FileMessageBubble(parts, context, modifier, infoLine = AudioFileCopy.CANT_PLAY)
        return
    }
    // Only the active file follows the player; the others stay idle and do not redraw on its ticks.
    val row by remember(id, playerState) { derivedStateOf { AudioRowPlayback.of(playerState.value, id) } }

    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val isMine = message.isMine
    val transfer = parts.row.transfer
    val type = message.fileType
    val failed = isMine && message.receipt == ReceiptStatus.Failed
    val handlers = parts.handlers
    val tile = FileBubbleMath.tileState(type, failed, transfer, message.needsMediaDownload)
    val glyph = AudioBubbleMath.glyph(tile, row.isPlaying)
    val width = AudioBubbleMath.width(parts.maxBubbleWidth)
    val cover = rememberAudioCover(message)
    val name = message.fileName ?: FileCopy.FILE
    val title = AudioTags.clean(message.audioTitle) ?: name
    val artist = AudioTags.clean(message.audioArtist)
    val durationMs = AudioBubbleMath.durationMs(message.durationMs, if (row.isActive) row.durationMs else null)
    val caption = message.text.trim().takeIf { it.isNotEmpty() }
    val hasReactions = parts.chips.isNotEmpty()
    val reply = parts.row.replyQuote
    val detail = AudioBubbleMath.detailLine(
        type = type,
        failed = failed,
        transfer = transfer,
        isActive = row.isActive,
        elapsedMs = row.elapsedMs,
        durationMs = durationMs,
        artist = artist,
        byteCount = message.mediaByteCount,
        onDevice = message.hasFullMedia,
    )

    val retry: () -> Unit = { handlers.run { context.onRetry(message) } }
    val coverTap: (() -> Unit)? = when {
        !handlers.interactive -> null
        glyph == AudioGlyph.Retry -> retry
        glyph == AudioGlyph.Transferring && transfer?.isUpload == true -> null
        glyph == AudioGlyph.Transferring -> { { handlers.run { context.onCancelDownload(message) } } }
        else -> { { handlers.run { context.onTapMedia(message) } } }
    }
    val playAction = if (row.isPlaying) AudioFileCopy.PAUSE else AudioFileCopy.PLAY
    val label = AudioBubbleMath.accessibilityLabel(
        isMine = isMine,
        replyAuthor = reply?.author,
        replyText = reply?.text,
        title = title,
        artist = artist,
        durationMs = durationMs,
        caption = caption,
        failed = failed,
        sendError = message.sendError,
        transfer = transfer,
        needsDownload = message.needsMediaDownload,
        isPlaying = row.isPlaying,
        chipsSummary = parts.chips.spokenSummary(),
        time = parts.time,
        receipt = message.receipt,
    )
    val quoteTap = handlers.quoteTap(message)
    val rowActions = LocalMessageRowActions.current
    val actions = buildList {
        if (failed && handlers.interactive) add(CustomAccessibilityAction("Retry") { retry(); true })
        if (glyph == AudioGlyph.Transferring && transfer?.isUpload == false && handlers.interactive) {
            add(CustomAccessibilityAction("Cancel download") { context.onCancelDownload(message); true })
        }
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        addAll(reactionAccessibilityActions(parts.chips, parts.onReaction))
        addAll(rowActions)
    }
    val defaultAction: (() -> Unit)? = when {
        !handlers.interactive || type == null -> null
        failed -> retry
        glyph == AudioGlyph.Transferring -> null
        else -> { { context.onTapMedia(message) } }
    }
    val textColor = if (isMine) Color.White else colors.textPrimary
    val secondary = if (isMine) Color.White.copy(alpha = 0.65f) else colors.textSecondary
    val metaColor = MediaBubbleColors.captionMeta(isMine)
    val shape = BubbleShapes.tail(isMine)
    val metaRow: @Composable () -> Unit = {
        BubbleMetaRow(
            time = parts.time,
            receipt = if (isMine) message.receipt else null,
            metaColor = metaColor,
            readColor = Color.White.copy(alpha = 0.95f),
            failedColor = Color.White,
        )
    }

    // The playhead, read only while the scrubber draws: every frame while the file plays.
    val frameClock = remember(id) { mutableLongStateOf(0L) }
    LaunchedEffect(id, row.isPlaying) {
        if (!row.isPlaying) return@LaunchedEffect
        while (true) withFrameNanos { frameClock.longValue = it }
    }
    var scrubFraction by remember(id) { mutableStateOf<Float?>(null) }
    val playhead: () -> Float = {
        scrubFraction ?: run {
            frameClock.longValue
            playerState.value
            player?.liveProgress(id)?.toFloat() ?: 0f
        }
    }

    val core = @Composable {
        Column(
            Modifier
                .then(parts.reportBounds)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    defaultAction?.let { action ->
                        onClick(label = if (failed) "Retry" else playAction) {
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = if (caption == null && !hasReactions) 4.dp else 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(FileBubbleMetrics.TileGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioCoverDisc(
                        fill = if (isMine) Color.White.copy(alpha = 0.22f) else colors.accent,
                        cover = cover,
                        onTap = coverTap,
                    ) {
                        if (glyph == AudioGlyph.Transferring && transfer != null) {
                            Box(Modifier.fillMaxSize().padding(3.dp)) { TransferRing(transfer, AudioBubbleMetrics.Cover - 6.dp, reduceMotion) }
                        }
                        val swap = Motion.iconSwap.respecting(reduceMotion)
                        AnimatedContent(targetState = glyph, transitionSpec = { swap.content }, contentAlignment = Alignment.Center, label = "audioGlyph") { shown ->
                            val (icon, size) = AudioBubbleMath.icon(shown)
                            ShroudIcon(icon, Color.White, size = size)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ShroudText(title, inter(15f, FontWeight.Medium), textColor, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        RollingText(
                            rememberPaced(detail, pacing = transfer?.phase == MediaTransfer.Phase.Transferring),
                            inter(13f, tabularDigits = true),
                            secondary,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AnimatedVisibility(
                            visible = row.isActive && message.hasFullMedia && !failed,
                            enter = fadeIn(Motion.snappy()) + expandVertically(Motion.snappy(), expandFrom = Alignment.Top),
                            exit = fadeOut(Motion.snappy()) + shrinkVertically(Motion.snappy(), shrinkTowards = Alignment.Top),
                        ) {
                            AudioScrubber(
                                progress = playhead,
                                played = if (isMine) Color.White else colors.accent,
                                rest = if (isMine) Color.White.copy(alpha = 0.3f) else colors.accent.copy(alpha = 0.25f),
                                enabled = handlers.interactive,
                                onDown = handlers::claim,
                                onScrub = { scrubFraction = it },
                                onSeek = { fraction ->
                                    scrubFraction = null
                                    if (handlers.allows() && player?.isActive(id) == true) {
                                        handlers.haptic(Haptic.Light)
                                        player.seek(id, fraction.toDouble())
                                    }
                                },
                            )
                        }
                    }
                }
                if (caption != null) {
                    val body = remember(caption) { AnnotatedString(MessageBubbleMetrics.normalizedForDisplay(caption)) }
                    val style = MessageBubbleMetrics.wrappingBodyStyle.copy(color = textColor)
                    if (hasReactions) {
                        val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 0.dp, end = 11.dp), null)
                        BubbleText(measure, Modifier.fillMaxWidth(), handlers.openLink)
                    } else {
                        val measure = rememberBubbleTextMeasure(body, style, TextPadding(start = 11.dp, top = 0.dp, end = 11.dp, bottom = 6.dp), MetaSpec(parts.time, isMine))
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
 * The 44 dp round cover (§11.3, §11.4): [fill], with [cover] filling it (aspect fill) under a 35 %
 * black scrim, and [content] (the glyph) on top. [onTap] claims the tap (scale 0.92, light haptic).
 */
@Composable
internal fun AudioCoverDisc(
    fill: Color,
    cover: ImageBitmap?,
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .size(AudioBubbleMetrics.Cover)
            .clip(CircleShape)
            .background(fill)
            .then(if (onTap != null) Modifier.pressable(scale = 0.92f, dimming = 0f, haptic = Haptic.Light, onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        content()
    }
}

/**
 * The scrubber (§11.4): a 3 dp track, [played] up to the playhead and [rest] after it, a 10 dp thumb.
 * Dragging or tapping seeks: [onScrub] follows the finger, [onSeek] fires once on release. The
 * playhead ([progress]) is read in the draw phase. Every touch claims the row's tap ([onDown]).
 */
@Composable
private fun AudioScrubber(
    progress: () -> Float,
    played: Color,
    rest: Color,
    enabled: Boolean,
    onDown: () -> Unit,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val down by rememberUpdatedState(onDown)
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val gesture = if (enabled) {
        Modifier.pointerInput(rtl) {
            val inset = AudioBubbleMetrics.Thumb.toPx() / 2
            fun fraction(x: Float): Float {
                val w = size.width - inset * 2
                if (w <= 0f) return 0f
                val f = ((x - inset) / w).coerceIn(0f, 1f)
                return if (rtl) 1 - f else f
            }
            awaitEachGesture {
                val first = awaitFirstDown()
                first.consume()
                down()
                var last = first.position.x
                var settled = false
                try {
                    scrub(fraction(last))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == first.id } ?: break
                        if (change.positionChange() != Offset.Zero) change.consume()
                        last = change.position.x
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        scrub(fraction(last))
                    }
                    settled = true
                    down()
                    seek(fraction(last))
                } finally {
                    if (!settled) scrub(null)
                }
            }
        }
    } else {
        Modifier
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(AudioBubbleMetrics.ScrubberHeight)
            .then(gesture),
    ) {
        val thumb = AudioBubbleMetrics.Thumb.toPx()
        val track = AudioBubbleMetrics.Track.toPx()
        val start = thumb / 2
        val end = size.width - thumb / 2
        if (end <= start) return@Canvas
        val p = progress().coerceIn(0f, 1f)
        val x = if (rtl) end - (end - start) * p else start + (end - start) * p
        val y = size.height / 2
        drawLine(rest, Offset(start, y), Offset(end, y), strokeWidth = track, cap = StrokeCap.Round)
        drawLine(played, Offset(if (rtl) end else start, y), Offset(x, y), strokeWidth = track, cap = StrokeCap.Round)
        drawCircle(played, radius = thumb / 2, center = Offset(x, y))
    }
}

/** The audio bubble's cover art (`th`), decoded once. */
@Composable
private fun rememberAudioCover(message: ChatMessage): ImageBitmap? {
    val bytes = message.previewJpeg
    return remember(message.id, bytes) { bytes?.toByteArray()?.let(BubbleImages::decodeSmall) }
}

/** What one bubble takes from the player: idle unless it is the active file. */
internal data class AudioRowPlayback(val isActive: Boolean, val isPlaying: Boolean, val elapsedMs: Long, val durationMs: Long?) {
    companion object {
        private val Idle = AudioRowPlayback(isActive = false, isPlaying = false, elapsedMs = 0L, durationMs = null)

        /** Whole seconds only, so the bubble recomposes once a second while its file plays. */
        fun of(state: AudioFilePlaybackState, id: java.util.UUID): AudioRowPlayback {
            if (state.activeId != id) return Idle
            return AudioRowPlayback(
                isActive = true,
                isPlaying = state.isPlaying,
                elapsedMs = floor(state.currentTime).toLong() * 1000,
                durationMs = (state.duration * 1000).toLong().takeIf { it >= 1 },
            )
        }
    }
}

/** The cover's glyph (§11.4). */
enum class AudioGlyph { Download, Transferring, Play, Pause, Retry }

/** The audio bubble's sizes (§11.4). */
internal object AudioBubbleMetrics {
    val Cover = 44.dp
    val MaxWidth = 300.dp
    val Track = 3.dp
    val Thumb = 10.dp
    val ScrubberHeight = 14.dp
}

/** The audio bubble's rules and words (docs/file-sharing.md §11.4), pure and unit-tested. */
object AudioBubbleMath {
    /** The file bubble's states read for an audio file: on this phone it plays or pauses. */
    fun glyph(tile: FileTileState, isPlaying: Boolean): AudioGlyph = when (tile) {
        FileTileState.Download -> AudioGlyph.Download
        FileTileState.Transferring -> AudioGlyph.Transferring
        FileTileState.Retry -> AudioGlyph.Retry
        FileTileState.Ready, FileTileState.Unsupported -> if (isPlaying) AudioGlyph.Pause else AudioGlyph.Play
    }

    /** White glyphs: download 18, stop 14 (in the ring), play / pause 18, retry 18. */
    fun icon(glyph: AudioGlyph): Pair<androidx.compose.ui.graphics.vector.ImageVector, Dp> = when (glyph) {
        AudioGlyph.Download -> ShroudIcons.ArrowDownBold to 18.dp
        AudioGlyph.Transferring -> ShroudIcons.StopFill to 14.dp
        AudioGlyph.Play -> ShroudIcons.PlayFill to 18.dp
        AudioGlyph.Pause -> ShroudIcons.PauseFill to 18.dp
        AudioGlyph.Retry -> ShroudIcons.ArrowClockwiseBold to 18.dp
    }

    /** min(300 dp, the row's budget). */
    fun width(maxBubbleWidth: Dp): Dp = min(AudioBubbleMetrics.MaxWidth, maxBubbleWidth)

    /** The payload's `d`, else what the player found while the file is active; null when neither is known. */
    fun durationMs(payloadMs: Int?, playerMs: Long?): Long? = payloadMs?.toLong()?.takeIf { it >= 1 } ?: playerMs?.takeIf { it >= 1 }

    /**
     * The detail line (§11.4): `{done} of {total}` while bytes move; **Not sent** after a failed
     * send; `{elapsed} / {duration}` on the active file; `{ar} · {duration}` (plus ` · {size}` while
     * not on this phone) with an artist; else `{duration} · {size} · {EXT}`. Missing parts drop out.
     */
    fun detailLine(
        type: FileType?,
        failed: Boolean,
        transfer: MediaTransfer?,
        isActive: Boolean,
        elapsedMs: Long,
        durationMs: Long?,
        artist: String?,
        byteCount: Long?,
        onDevice: Boolean,
    ): String {
        if (type == null) return FileCopy.UNSUPPORTED
        val total = transfer?.totalBytes?.takeIf { it > 0 } ?: byteCount?.takeIf { it > 0 }
        if (transfer != null && transfer.phase == MediaTransfer.Phase.Transferring && total != null) {
            return FileCopy.progress(ByteCountLabel.format(transfer.movedBytes ?: 0L), ByteCountLabel.format(total))
        }
        if (failed) return FileCopy.NOT_SENT
        if (isActive) return AudioFileCopy.progress(elapsedMs, durationMs)
        val duration = durationMs?.takeIf { it >= 1 }?.let(AudioDurations::total)
        val size = byteCount?.takeIf { it > 0 }?.let(ByteCountLabel::format)
        val name = AudioTags.clean(artist)
        return if (name != null) {
            listOfNotNull(name, duration, size.takeIf { !onDevice }).joinToString(" · ")
        } else {
            listOfNotNull(duration, size, type.label).joinToString(" · ")
        }
    }

    /** One node's words: speaker, quote, `Audio, {title}, {artist}, {duration}`, caption, state, reactions, time, receipt. */
    fun accessibilityLabel(
        isMine: Boolean,
        replyAuthor: String?,
        replyText: String?,
        title: String,
        artist: String?,
        durationMs: Long?,
        caption: String?,
        failed: Boolean,
        sendError: String?,
        transfer: MediaTransfer?,
        needsDownload: Boolean,
        isPlaying: Boolean,
        chipsSummary: String?,
        time: String,
        receipt: ReceiptStatus,
    ): String {
        val parts = mutableListOf(if (isMine) "You" else "Them")
        if (replyAuthor != null && replyText != null) parts += "Reply to $replyAuthor: $replyText"
        parts += AudioFileCopy.accessibilityLabel(title, artist, durationMs)
        if (caption != null) parts += caption
        when {
            failed -> {
                parts += FileCopy.NOT_SENT
                if (!sendError.isNullOrEmpty()) parts += sendError
            }
            transfer != null -> {
                parts += if (transfer.isUpload) "Sending" else "Downloading"
                if (!transfer.isIndeterminate) parts += "${(transfer.ringFraction * 100).toInt()} percent"
            }
            needsDownload -> parts += "Not downloaded"
            isPlaying -> parts += "Playing"
        }
        chipsSummary?.let(parts::add)
        parts += time
        if (isMine && !failed) parts += receipt.spokenLabel
        return parts.joinToString(", ")
    }
}

private val REPLY_FONT = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)

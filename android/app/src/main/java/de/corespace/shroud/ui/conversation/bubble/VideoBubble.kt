package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.displayPreview
import de.corespace.shroud.core.model.needsMediaDownload
import de.corespace.shroud.ui.components.RollingText
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.rememberPaced
import de.corespace.shroud.ui.components.shimmering
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlin.math.max

/**
 * Telegram's video bubble — iOS `VideoMessageBubble` (`VideoMessageBubble.swift:1-665`;
 * conversation-thread §9): the poster, a play-glyph duration badge top-left, a play disc in the middle,
 * and a transfer ring in its place while bytes move. The badge is what makes a still read as video: the
 * duration always, the payload size until the clip is here ("▶ 0:12 · 4.2 MB", then
 * "▶ 0:12 · 1.1 MB / 4.2 MB" while downloading).
 *
 * The same skeleton as the photo bubble ([PhotoMessageBubble]) with these differences: an outbound clip
 * still compressing or uploading shows the ring too (it cannot be cancelled); the poster is softened and
 * slightly enlarged until the clip is downloaded; scrims keep the white chrome legible; with no poster
 * a dark plate shimmers while a transfer runs. A downloaded clip opens the player on tap
 * ([BubbleContext.onTapMedia]). The play disc is a solid black @0.35 circle: Android cannot blur over
 * arbitrary content (conversation-thread §23.7).
 */
@Composable
internal fun VideoMessageBubble(parts: BubbleParts, context: BubbleContext, services: BubbleServices, modifier: Modifier) {
    val message = parts.message
    val isMine = message.isMine
    val transfer = parts.row.transfer
    val failed = message.receipt == ReceiptStatus.Failed
    val needsDownload = message.needsMediaDownload
    val isSending = transfer?.isUpload == true
    val showsTransferControl = !failed && (needsDownload || isSending)
    val canOpen = !message.deleted && !failed && !isSending && message.hasFullMedia
    val hasReactions = parts.chips.isNotEmpty() && !message.deleted
    val caption = MediaBubbleMath.caption(message.text, "Video")
    val hasFooter = caption != null || hasReactions
    val reply = parts.row.replyQuote.takeIf { !message.deleted }
    val cap = MessageBubbleMetrics.mediaWidthCap(parts.rowWidth.value)
    val size = MediaBubbleMath.videoSize(cap, message.imageWidth, message.imageHeight, wide = caption != null || reply != null)
    val width = size.width.dp
    val height = size.height.dp
    val handlers = parts.handlers
    val maxEdge = with(LocalDensity.current) { max(size.width, size.height).dp.roundToPx() }
    val poster = rememberVideoPoster(message, services, maxEdge)
    val durationLabel = MediaBubbleMath.durationLabel(message.durationMs)
    val sizeLabel = MediaBubbleMath.videoSizeLabel(transfer, isSending, needsDownload, message.mediaByteCount)

    val download: () -> Unit = { context.onTapMedia(message) }
    val cancel: () -> Unit = { context.onCancelDownload(message) }
    val retry: () -> Unit = { handlers.run { context.onRetry(message) } }
    // The poster owns every tap on it, including the ones it declines (`:589-602`).
    val tap: () -> Unit = {
        handlers.run {
            when {
                isSending -> Unit
                needsDownload -> {
                    handlers.haptic(Haptic.Light)
                    if (transfer == null) download() else cancel()
                }
                canOpen -> {
                    handlers.haptic(Haptic.Light)
                    context.onTapMedia(message)
                }
            }
        }
    }
    // The disc's own tap; none while sending — an upload is already committed to the wire (`:604-613`).
    val discTap: (() -> Unit)? = if (isSending || !handlers.interactive) {
        null
    } else {
        { handlers.run { if (transfer == null) download() else cancel() } }
    }
    val quoteTap = handlers.quoteTap(message)
    val label = BubbleAccessibility.video(
        isMine = isMine,
        reply = reply,
        durationLabel = durationLabel,
        caption = caption,
        deleted = message.deleted,
        failed = failed,
        sendError = message.sendError,
        needsDownload = needsDownload,
        transfer = transfer,
        sizeLabel = sizeLabel,
        chips = parts.chips,
        time = parts.time,
        receipt = message.receipt,
    )
    val hasDefaultAction = handlers.interactive && ((failed) || needsDownload || canOpen)
    val rowActions = LocalMessageRowActions.current
    val actions = buildList {
        if (failed && handlers.interactive) add(CustomAccessibilityAction("Retry") { retry(); true })
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        // The chips come back as actions, and the quick reaction (`ImageMessageBubble.swift:137`).
        addAll(reactionAccessibilityActions(if (hasReactions) parts.chips else emptyList(), parts.onReaction))
        addAll(rowActions)
    }
    val tapState by rememberUpdatedState(tap)
    val shape = BubbleShapes.tail(isMine)
    val danger = ShroudTheme.colors.danger

    val core = @Composable {
        Column(
            Modifier
                .then(parts.reportBounds)
                .clearAndSetSemantics {
                    contentDescription = label
                    if (hasDefaultAction) {
                        role = Role.Button
                        onClick {
                            // A failed clip can't play, so a double tap retries (`:153-161`).
                            if (failed) retry() else tap()
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
                    .dropShadow(shape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.08f))
                    .then(if (failed) Modifier.border(1.5.dp, danger.copy(alpha = 0.7f), shape) else Modifier),
            ) {
                if (reply != null) MediaReplyHeader(reply, isMine, width, quoteTap)
                Box(
                    Modifier
                        .size(width, height)
                        .clip(BubbleShapes.media(isMine, hasHeader = reply != null, hasFooter = hasFooter))
                        .pointerInput(Unit) { detectTapGestures { tapState() } },
                ) {
                    VideoPoster(poster, failed, softened = needsDownload && !isSending, transferRunning = transfer != null, width, height)
                    if (!message.deleted) VideoScrims()
                    VideoCentre(
                        failed = failed,
                        showsTransferControl = showsTransferControl,
                        deleted = message.deleted,
                        mode = transfer?.let { TransferMode.Busy(it) } ?: TransferMode.Idle(message.mediaByteCount),
                        onDisc = discTap,
                    )
                    if (!message.deleted && !failed) {
                        VideoChrome(parts, durationLabel, sizeLabel, sizeTicks = transfer?.phase == MediaTransfer.Phase.Transferring, showsTimeChip = !hasFooter)
                    }
                }
                if (hasFooter && !message.deleted) MediaCaptionFooter(parts, caption, width, hasReactions)
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
 * The poster (`VideoMessageBubble.swift:127-133, 647-664`): the envelope thumbnail or the sender's
 * poster, decoded at once; failing that, once the clip is downloaded, a still taken from it
 * ([BubbleServices.videoPoster]) — remembered in [DecodedImageCache] either way.
 */
@Composable
private fun rememberVideoPoster(message: ChatMessage, services: BubbleServices, maxEdgePx: Int): ImageBitmap? {
    val small = message.displayPreview
    val decoded = remember(message.id, small) {
        small?.let { bytes ->
            val raw = bytes.toByteArray()
            DecodedImageCache.image(message.id, DecodedImageCache.Source.Preview, raw.size)
                ?: BubbleImages.decodeSmall(raw)?.also { DecodedImageCache.store(message.id, it, DecodedImageCache.Source.Preview, raw.size) }
        }
    }
    var generated by remember(message.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(message.id, message.hasFullMedia, decoded == null) {
        // Only generated from the full clip, after the user downloaded it (`:653-657`).
        if (decoded != null || !message.hasFullMedia || generated != null) return@LaunchedEffect
        DecodedImageCache.image(message.id)?.let {
            generated = it
            return@LaunchedEffect
        }
        val jpeg = services.videoPoster(message.id) ?: return@LaunchedEffect
        val image = BubbleImages.decodeSampled(jpeg, maxEdgePx) ?: return@LaunchedEffect
        DecodedImageCache.store(message.id, image, DecodedImageCache.Source.Full, jpeg.size)
        generated = image
    }
    return decoded ?: generated ?: DecodedImageCache.image(message.id)
}

/** The poster, or a dark plate that shimmers while a transfer runs (`:255-275, 462-477`). */
@Composable
private fun VideoPoster(poster: ImageBitmap?, failed: Boolean, softened: Boolean, transferRunning: Boolean, width: Dp, height: Dp) {
    val reduceMotion = ShroudTheme.reduceMotion
    // A soft blur behind the ring is what tells you the clip isn't here yet (`:265-267`).
    val soften by animateFloatAsState(if (softened) 1f else 0f, Motion.respecting(reduceMotion, Motion.standard()), label = "posterSoften")
    Crossfade(targetState = poster, animationSpec = Motion.fade(), label = "poster") { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width, height)
                    .alpha(if (failed) 0.55f else 1f)
                    .graphicsLayer {
                        val scale = 1f + 0.04f * soften
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(if (soften > 0f) Modifier.blur((1.5f * soften).dp) else Modifier),
            )
        } else {
            Box(
                Modifier
                    .size(width, height)
                    .background(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Black.copy(alpha = 0.45f))))
                    // The plate is dark in both appearances: it keeps the bright sweep in dark mode.
                    .then(if (transferRunning) Modifier.shimmering(adaptsToAppearance = false) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.VideoCameraFill, Color.White.copy(alpha = 0.35f), size = 28.dp)
            }
        }
    }
}

/** Top and bottom darkening so white badges survive a bright poster (`:306-324`). */
@Composable
private fun VideoScrims() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)))),
        )
    }
}

/** What sits in the middle: "Not sent", the transfer ring, or the play disc (`:281-302`). */
@Composable
private fun VideoCentre(failed: Boolean, showsTransferControl: Boolean, deleted: Boolean, mode: TransferMode, onDisc: (() -> Unit)?) {
    val state = when {
        failed -> 0
        showsTransferControl -> 1
        !deleted -> 2
        else -> 3
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            val spec = Motion.snappy<Float>()
            (scaleIn(spec, initialScale = 0.8f) + fadeIn(spec)) togetherWith (scaleOut(spec, targetScale = 0.8f) + fadeOut(spec))
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
        label = "videoCentre",
    ) { shown ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (shown) {
                0 -> MediaFailedOverlay()
                1 -> MediaTransferControl(mode = mode, onTap = onDisc, diameter = 54.dp)
                2 -> PlayDisc()
                else -> Unit
            }
        }
    }
}

/** Telegram's centre play control: a dimmed disc, not a filled symbol; touch-through (`:375-390`). */
@Composable
private fun PlayDisc() {
    Box(
        Modifier
            .dropShadow(CircleShape, Shadow(radius = 6.dp, color = Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = 0.2f))
            .size(54.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        // Optical centring: a triangle's mass sits left of its box (`:385-386`).
        ShroudIcon(ShroudIcons.PlayFill, Color.White, size = 22.dp, modifier = Modifier.offset(x = 1.5.dp))
    }
}

/** The duration badge top-leading and, with no caption strip, the time chip bottom-trailing (`:326-342`). */
@Composable
private fun VideoChrome(parts: BubbleParts, durationLabel: String, sizeLabel: String?, sizeTicks: Boolean, showsTimeChip: Boolean) {
    val message = parts.message
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        DurationBadge(durationLabel, sizeLabel, sizeTicks, Modifier.align(Alignment.TopStart))
        if (showsTimeChip) {
            MediaTimeChip(
                time = parts.time,
                receipt = if (message.isMine && !message.deleted) message.receipt else null,
                metaColor = Color.White.copy(alpha = 0.8f),
                fillAlpha = 0.5f,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * "▶ 0:12" plus the size while the clip is still on the server (`:344-373`). The badge springs when the
 * size comes, goes or changes phase; while the bytes [sizeTicks] they roll, at most twice a second
 * (monospaced digits keep the badge's width steady).
 */
@Composable
private fun DurationBadge(durationLabel: String, sizeLabel: String?, sizeTicks: Boolean, modifier: Modifier) {
    Row(
        modifier
            .animateContentSize(Motion.snappy())
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 7.dp, vertical = 3.5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SF `play.fill` 8 black → Phosphor play-fill.
        ShroudIcon(ShroudIcons.PlayFill, Color.White, size = 8.dp)
        BasicText(durationLabel, style = inter(11f, FontWeight.SemiBold, tabularDigits = true).copy(color = Color.White), maxLines = 1, softWrap = false)
        if (sizeLabel != null) {
            BasicText("·", style = inter(11f, FontWeight.SemiBold).copy(color = Color.White.copy(alpha = 0.55f)))
            RollingText(
                rememberPaced(sizeLabel, pacing = sizeTicks),
                inter(11f, FontWeight.Medium, tabularDigits = true),
                Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

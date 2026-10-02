package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.needsMediaDownload
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.max

/**
 * Telegram's photo bubble — iOS `ImageMessageBubble` (`ImageMessageBubble.swift:1-510`;
 * conversation-thread §8, conversation-compose-media §19): the envelope thumbnail first, the full photo
 * only once downloaded on demand (never on appear). A reply header sits on the bubble fill above the
 * photo, a caption and/or reaction chips in a strip below it; the photo's own corners square off where
 * the bubble continues.
 *
 * - Not downloaded: the thumbnail, softened, under the transfer disc (the size under its arrow); the
 *   disc or a tap on the photo downloads, the ring cancels ([BubbleContext.onTapMedia] /
 *   [BubbleContext.onCancelDownload]).
 * - Downloaded: the full photo cross-fades in; a tap opens the viewer (light haptic).
 * - Failed: dimmed, "Not sent" over it, a red outline, the error and Retry below.
 * - No caption or chips: the time chip in the photo's corner.
 *
 * Every tap on the photo is claimed, including the ones it declines, so the row never opens a failed
 * photo or opens this one twice. TalkBack reads one node ([BubbleAccessibility.photo]); a double tap
 * retries, downloads / cancels or opens.
 */
@Composable
internal fun PhotoMessageBubble(parts: BubbleParts, context: BubbleContext, services: BubbleServices, modifier: Modifier) {
    val message = parts.message
    val isMine = message.isMine
    val transfer = parts.row.transfer
    val failed = message.receipt == ReceiptStatus.Failed
    val needsDownload = message.needsMediaDownload
    val canOpen = !message.deleted && !failed && message.hasFullMedia
    val hasReactions = parts.chips.isNotEmpty() && !message.deleted
    val caption = MediaBubbleMath.caption(message.text, "Photo")
    val hasFooter = caption != null || hasReactions
    val reply = parts.row.replyQuote.takeIf { !message.deleted }
    val cap = MessageBubbleMetrics.mediaWidthCap(parts.rowWidth.value)
    val size = MediaBubbleMath.photoSize(cap, message.imageWidth, message.imageHeight, wide = caption != null || reply != null)
    val width = size.width.dp
    val height = size.height.dp
    val handlers = parts.handlers
    val maxEdge = with(LocalDensity.current) { max(size.width, size.height).dp.roundToPx() }
    val picture = rememberPhotoPicture(message, services, maxEdge)

    val download: () -> Unit = { context.onTapMedia(message) }
    val cancel: () -> Unit = { context.onCancelDownload(message) }
    val open: () -> Unit = { context.onTapMedia(message) }
    val retry: () -> Unit = { handlers.run { context.onRetry(message) } }
    // The photo owns every tap on it, including the ones it declines (`:454-466`).
    val tap: () -> Unit = {
        handlers.run {
            when {
                needsDownload -> {
                    handlers.haptic(Haptic.Light)
                    download()
                }
                canOpen -> {
                    handlers.haptic(Haptic.Light)
                    open()
                }
            }
        }
    }
    val quoteTap = handlers.quoteTap(message)
    val label = BubbleAccessibility.photo(
        isMine = isMine,
        reply = reply,
        caption = caption,
        deleted = message.deleted,
        failed = failed,
        sendError = message.sendError,
        needsDownload = needsDownload,
        transfer = transfer,
        byteCount = message.mediaByteCount,
        chips = parts.chips,
        time = parts.time,
        receipt = message.receipt,
    )
    val defaultAction: (() -> Unit)? = when {
        !handlers.interactive -> null
        failed -> retry
        needsDownload -> if (transfer == null) download else cancel
        canOpen -> open
        else -> null
    }
    val rowActions = LocalMessageRowActions.current
    val actions = buildList {
        if (failed && handlers.interactive) add(CustomAccessibilityAction("Retry") { retry(); true })
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        if (hasReactions) addAll(reactionAccessibilityActions(parts.chips, parts.onReaction))
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
                    role = Role.Image
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
                    .dropShadow(shape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.08f))
                    // The bubble's own outline, tail corner included (`:205-211`).
                    .then(if (failed) Modifier.border(1.5.dp, danger.copy(alpha = 0.7f), shape) else Modifier),
            ) {
                if (reply != null) MediaReplyHeader(reply, isMine, width, quoteTap)
                Box(
                    Modifier
                        .size(width, height)
                        .clip(BubbleShapes.media(isMine, hasHeader = reply != null, hasFooter = hasFooter))
                        .pointerInput(Unit) { detectTapGestures { tapState() } },
                ) {
                    PhotoPicture(picture, isMine, failed, needsDownload, width, height)
                    PhotoOverlay(
                        failed = failed,
                        needsDownload = needsDownload,
                        showsTimeChip = !hasFooter,
                        parts = parts,
                        transfer = transfer,
                        onDisc = if (handlers.interactive) {
                            {
                                // The row's own tap would start the download a cancel just stopped.
                                handlers.run { if (transfer == null) download() else cancel() }
                            }
                        } else {
                            null
                        },
                    )
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

/** The photo to draw and whether it is the full one (else the envelope thumbnail). */
internal class PhotoPicture(val image: ImageBitmap?, val isFull: Boolean)

/**
 * The thumbnail decoded at once (a few KB) and the full photo decoded off the main thread once it is on
 * this phone, sampled to the bubble's size, both through [DecodedImageCache] so the hero, the quote and
 * a re-entering row never decode again (`ImageMessageBubble.swift:89-97`; plan C8).
 */
@Composable
private fun rememberPhotoPicture(message: ChatMessage, services: BubbleServices, maxEdgePx: Int): PhotoPicture {
    val previewBytes = message.previewJpeg
    val preview = remember(message.id, previewBytes) {
        previewBytes?.let { bytes ->
            val raw = bytes.toByteArray()
            DecodedImageCache.image(message.id, DecodedImageCache.Source.Preview, raw.size)
                ?: BubbleImages.decodeSmall(raw)?.also { DecodedImageCache.store(message.id, it, DecodedImageCache.Source.Preview, raw.size) }
        }
    }
    var full by remember(message.id) { mutableStateOf(DecodedImageCache.image(message.id, DecodedImageCache.Source.Full)) }
    LaunchedEffect(message.id, message.hasFullMedia, maxEdgePx) {
        if (!message.hasFullMedia || full != null) return@LaunchedEffect
        val bytes = services.mediaBytes(message.id) ?: return@LaunchedEffect
        val decoded = BubbleImages.decodeSampled(bytes, maxEdgePx) ?: return@LaunchedEffect
        DecodedImageCache.store(message.id, decoded, DecodedImageCache.Source.Full, bytes.size)
        full = decoded
    }
    val fullShown = full.takeIf { message.hasFullMedia }
    return remember(fullShown, preview) { PhotoPicture(fullShown ?: preview, isFull = fullShown != null) }
}

/**
 * The picture, cropped to fill: dimmed when failed (0.55) or not downloaded (0.92), softened while only
 * the thumbnail is here; the bubble fill with a photo glyph when nothing is (`:147-165, 336-344`).
 */
@Composable
private fun PhotoPicture(picture: PhotoPicture, isMine: Boolean, failed: Boolean, needsDownload: Boolean, width: Dp, height: Dp) {
    val opacity = when {
        failed -> 0.55f
        needsDownload -> 0.92f
        else -> 1f
    }
    Crossfade(targetState = picture.image, animationSpec = Motion.fade(), label = "photo") { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width, height)
                    .alpha(opacity)
                    // Soften an un-downloaded thumbnail so the download disc reads clearly (`:156-157`).
                    .then(if (needsDownload && !picture.isFull) Modifier.blur(0.6.dp) else Modifier),
            )
        } else {
            Box(Modifier.size(width, height).background(MediaBubbleColors.fill(isMine)), contentAlignment = Alignment.Center) {
                ShroudIcon(
                    ShroudIcons.ImageRegular,
                    if (isMine) Color.White.copy(alpha = 0.7f) else ShroudTheme.colors.textSecondary,
                    size = 30.dp,
                )
            }
        }
    }
}

/** Failed overlay, transfer disc, or the corner time chip (`:167-195`). */
@Composable
private fun PhotoOverlay(
    failed: Boolean,
    needsDownload: Boolean,
    showsTimeChip: Boolean,
    parts: BubbleParts,
    transfer: de.corespace.shroud.core.model.MediaTransfer?,
    onDisc: (() -> Unit)?,
) {
    val message = parts.message
    val state = when {
        failed -> PhotoOverlayState.Failed
        needsDownload -> PhotoOverlayState.Disc
        showsTimeChip -> PhotoOverlayState.Time
        else -> PhotoOverlayState.None
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state == PhotoOverlayState.Failed, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
            MediaFailedOverlay()
        }
        AnimatedVisibility(
            state == PhotoOverlayState.Disc,
            enter = scaleIn(Motion.snappy(), initialScale = 0.8f) + fadeIn(Motion.snappy()),
            exit = scaleOut(Motion.snappy(), targetScale = 0.8f) + fadeOut(Motion.snappy()),
            modifier = Modifier.align(Alignment.Center),
        ) {
            MediaTransferControl(
                mode = transfer?.let { TransferMode.Busy(it) } ?: TransferMode.Idle(message.mediaByteCount),
                onTap = onDisc,
            )
        }
        if (state == PhotoOverlayState.Time) {
            MediaTimeChip(
                time = parts.time,
                receipt = if (message.isMine && !message.deleted) message.receipt else null,
                metaColor = Color.White.copy(alpha = 0.75f),
                fillAlpha = 0.6f,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

private enum class PhotoOverlayState { Failed, Disc, Time, None }

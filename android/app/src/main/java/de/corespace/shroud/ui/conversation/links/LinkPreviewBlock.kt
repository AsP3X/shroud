package de.corespace.shroud.ui.conversation.links

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.hasLargeLinkImage
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.conversation.LinkPreviewImageCache
import de.corespace.shroud.ui.conversation.bubble.BubbleImages
import de.corespace.shroud.ui.conversation.bubble.BubbleServices
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/** Where a preview block is drawn — decides its palette (`LinkPreviewView.swift:4-10`). */
enum class LinkPreviewStyle { Incoming, Outgoing }

/**
 * The picture a preview block shows (`LinkPreviewView.swift:12-25`): none; a 54 dp thumbnail the text
 * flows around; or a full-width picture whose [Large.full] is null until the blob is decrypted, when
 * the blurred [Large.placeholder] (or a tint) stands in.
 */
@Immutable
sealed interface LinkPreviewImage {
    data object None : LinkPreviewImage

    class Thumbnail(val image: ImageBitmap) : LinkPreviewImage

    class Large(val full: ImageBitmap?, val placeholder: ImageBitmap?, val aspect: Float) : LinkPreviewImage

    val isLarge: Boolean get() = this is Large
}

/** The large picture's aspect when neither the blob nor the page said (`ConversationView.swift:1388-1390`). */
const val DEFAULT_LINK_IMAGE_ASPECT = 1.91f

/**
 * The picture [message]'s preview shows, decoded once and cached — iOS `linkPreviewImage(for:)`
 * (`ConversationView.swift:1378-1401`; conversation-thread §6.2):
 * - no preview, or deleted → [LinkPreviewImage.None];
 * - a large picture (the message's media blob, loaded on appear) → [LinkPreviewImage.Large] with the
 *   decrypted picture once it is here, the envelope placeholder until then, the blob's aspect
 *   (else the page's, else 1.91);
 * - an inline thumbnail (`th`) → [LinkPreviewImage.Thumbnail];
 * - else none.
 * Small pictures decode at once (no layout jump); the full picture decodes off the main thread.
 */
@Composable
fun rememberLinkPreviewImage(message: ChatMessage, services: BubbleServices?, maxEdgePx: Int): LinkPreviewImage {
    val preview = message.linkPreview
    if (preview == null || message.deleted) return LinkPreviewImage.None
    if (message.hasLargeLinkImage) {
        val placeholderBytes = message.previewJpeg
        val placeholder = remember(message.id, placeholderBytes) {
            placeholderBytes?.let { decodeCached(message, LinkPreviewImageCache.Variant.Placeholder, it.toByteArray()) }
        }
        var full by remember(message.id) { mutableStateOf(LinkPreviewImageCache.imageAnySize(message.id, LinkPreviewImageCache.Variant.Full)) }
        LaunchedEffect(message.id, message.hasFullMedia, services) {
            if (!message.hasFullMedia || services == null || full != null) return@LaunchedEffect
            val bytes = services.mediaBytes(message.id) ?: return@LaunchedEffect
            LinkPreviewImageCache.image(message.id, LinkPreviewImageCache.Variant.Full, bytes.size)?.let { full = it; return@LaunchedEffect }
            val decoded = BubbleImages.decodeSampled(bytes, maxEdgePx) ?: return@LaunchedEffect
            LinkPreviewImageCache.store(message.id, LinkPreviewImageCache.Variant.Full, bytes.size, decoded)
            full = decoded
        }
        val width = message.imageWidth
        val height = message.imageHeight
        val aspect = if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            preview.imageAspect ?: DEFAULT_LINK_IMAGE_ASPECT
        }
        return LinkPreviewImage.Large(full, placeholder, aspect)
    }
    val thumbnailBytes = preview.thumbnail ?: return LinkPreviewImage.None
    val thumbnail = remember(message.id, thumbnailBytes) {
        decodeCached(message, LinkPreviewImageCache.Variant.Thumbnail, thumbnailBytes.toByteArray())
    } ?: return LinkPreviewImage.None
    return LinkPreviewImage.Thumbnail(thumbnail)
}

private fun decodeCached(message: ChatMessage, variant: LinkPreviewImageCache.Variant, bytes: ByteArray): ImageBitmap? {
    LinkPreviewImageCache.image(message.id, variant, bytes.size)?.let { return it }
    val image = BubbleImages.decodeSmall(bytes) ?: return null
    LinkPreviewImageCache.store(message.id, variant, bytes.size, image)
    return image
}

/**
 * Telegram's link preview block — iOS `LinkPreviewView` (`LinkPreviewView.swift:27-189`;
 * conversation-thread §6.2): accent stripe, tinted card, site name, title, description, and a small
 * thumbnail the text flows around ([CutoutText]) or a large picture under the text. Same stripe,
 * radius and tint as the reply quote: in Telegram they are one component.
 *
 * Tapping opens the page ([onOpen]; the caller claims the tap); it shrinks to 0.98 while pressed
 * (ease-in-out 0.3 s in, 0.2 s out, none under reduce motion). Disabled without [onOpen].
 * TalkBack: "Link preview, {site}, {title}, {summary}", a link that "Opens the page".
 */
@Composable
fun LinkPreviewBlock(
    preview: LinkPreview,
    image: LinkPreviewImage,
    style: LinkPreviewStyle,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val accent = if (style == LinkPreviewStyle.Outgoing) Color.White else colors.accentText
    val primary = if (style == LinkPreviewStyle.Outgoing) Color.White else colors.textPrimary
    // Outgoing darkens rather than lightens: white text on a lightened accent falls below 4.5:1 (`:65-68`).
    val tint = if (style == LinkPreviewStyle.Outgoing) Color.Black.copy(alpha = 0.12f) else colors.accent.copy(alpha = 0.1f)
    val hasThumbnail = image is LinkPreviewImage.Thumbnail
    val text = remember(preview, accent, primary) { LinkPreviewText.attributed(preview, accent, primary) }
    val cutout = if (hasThumbnail) Cutout(THUMBNAIL_SIDE + 6.dp, THUMBNAIL_SIDE + THUMBNAIL_INSET - 5.dp) else Cutout.Zero
    val measure = rememberCutoutTextMeasure(text, LinkPreviewText.style, cutout, MAX_LINES, lineGap = 1.dp)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = ShroudTheme.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && onOpen != null && !reduceMotion) 0.98f else 1f,
        animationSpec = if (pressed) Motion.easeInOut(300) else Motion.easeInOut(200),
        label = "linkPreviewPress",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = RoundedCornerShape(CORNER_RADIUS)
    val label = LinkPreviewText.accessibilityLabel(preview)

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(tint)
            .drawBehind {
                val stripe = STRIPE_WIDTH.toPx()
                drawRect(accent, topLeft = Offset(if (rtl) size.width - stripe else 0f, 0f), size = Size(stripe, size.height))
            }
            .clickable(interactionSource = interaction, indication = null, enabled = onOpen != null, role = Role.Button) { onOpen?.invoke() }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (onOpen != null) {
                    onClick(label = "Opens the page") {
                        onOpen()
                        true
                    }
                }
            },
    ) {
        Column(
            Modifier.padding(
                start = STRIPE_WIDTH + 7.dp,
                end = if (hasThumbnail) THUMBNAIL_INSET else 8.dp,
                top = 5.dp,
                bottom = 7.dp,
            ),
        ) {
            // The card is never shorter than its thumbnail (+6 above and below, `:97-98`).
            CutoutText(measure, minHeight = if (hasThumbnail) THUMBNAIL_SIDE + THUMBNAIL_INSET * 2 - 12.dp else 0.dp)
            if (image is LinkPreviewImage.Large) {
                LargePicture(image, preview.isVideo, tint, Modifier.padding(top = 6.dp))
            }
        }
        if (image is LinkPreviewImage.Thumbnail) {
            Image(
                bitmap = image.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = THUMBNAIL_INSET, end = THUMBNAIL_INSET)
                    .size(THUMBNAIL_SIDE)
                    .clip(RoundedCornerShape(MEDIA_RADIUS)),
            )
        }
    }
}

/** Full-width picture with Telegram's aspect clamp; a play badge for video pages (`:132-167`). */
@Composable
private fun LargePicture(image: LinkPreviewImage.Large, isVideo: Boolean, tint: Color, modifier: Modifier) {
    val aspect = LinkPreviewText.clampAspect(image.aspect)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(MEDIA_RADIUS)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = image.full ?: image.placeholder, animationSpec = Motion.fade(), label = "linkImage") { shown ->
            if (shown == null) {
                Box(Modifier.fillMaxSize().background(tint))
            } else {
                val isPlaceholder = image.full == null
                Image(
                    bitmap = shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // The envelope placeholder is a few KB: soften it, opaque so its edges keep the colour (`:144-146`).
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isPlaceholder) Modifier.blur(10.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
                )
            }
        }
        if (isVideo) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                ShroudIcon(ShroudIcons.PlayFill, Color.White, size = 18.dp, modifier = Modifier.offset(x = 1.5.dp))
            }
        }
    }
}

/** The block's text and numbers (`LinkPreviewView.swift:42-50, 219-248`), unit-tested. */
object LinkPreviewText {
    /** 14 sp, line spacing 1, the extra leading between lines only. */
    val style: TextStyle = inter(14f, lineSpacing = 1f)
        .copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

    /**
     * Site name and title semibold, description regular, one per line (`:219-248`): the site name in
     * [accent], the rest in [primary] — the description stays plain white on outgoing bubbles, where
     * dimmed white misses 4.5:1.
     */
    fun attributed(preview: LinkPreview, accent: Color, primary: Color): AnnotatedString = buildAnnotatedString {
        var first = true
        fun part(text: String, weight: FontWeight, color: Color) {
            if (!first) append('\n')
            first = false
            withStyle(SpanStyle(fontWeight = weight, color = color)) { append(text) }
        }
        part(preview.displaySiteName, FontWeight.SemiBold, accent)
        preview.title?.let { part(it, FontWeight.SemiBold, primary) }
        preview.summary?.let { part(it, FontWeight.Normal, primary) }
    }

    /** "Link preview, {site}, {title}, {summary}" (`:169-174`). */
    fun accessibilityLabel(preview: LinkPreview): String =
        listOfNotNull("Link preview", preview.displaySiteName, preview.title, preview.summary).joinToString(", ")

    /** Very tall or very wide pictures are cropped to a sane band (`:134-135`). */
    fun clampAspect(aspect: Float): Float = aspect.coerceIn(0.75f, 2.4f)
}

private const val MAX_LINES = 10
private val STRIPE_WIDTH = 3.dp
private val CORNER_RADIUS = 6.dp
private val THUMBNAIL_SIDE = 54.dp
private val THUMBNAIL_INSET = 6.dp
private val MEDIA_RADIUS = 4.dp

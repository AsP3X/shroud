package de.corespace.shroud.ui.media.viewer

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuRows
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.glassBackdropSource
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.overlayPane
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.components.rememberOverlayBack
import de.corespace.shroud.ui.conversation.DecodedImageCache
import de.corespace.shroud.ui.media.ViewerItem
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The photo viewer: paging, zoom, swipe to dismiss, share and delete (conversation-compose-media
 * §18.1; iOS `MediaImageViewerOverlay`, `ios/shroud/ShroudUI/Components/MediaImageViewerOverlay.swift`;
 * design yb9mV / N4Oth). [onLoad] fetches an item that is not on this phone yet; [onDelete] is null
 * where deleting is not offered.
 *
 * Human: black, the photo whole (or edge to edge when it is screen-shaped); Close, the sender and
 * date, and More across the top; caption, Share, the Draw / Text recognition pair and Delete across
 * the bottom. Swipe sideways for the chat's other photos, pinch or double-tap to zoom, drag down (or
 * back) to close. More offers Save to Gallery, Share and Copy. Share and Copy hand out an in-memory
 * grant (no decrypted file); closing the viewer revokes every grant (K10).
 *
 * Agent: draws in place, full size ([MediaLayer]); the host puts it in its overlay layer, keeps it
 * composed while open and drops it in [onClose]. When the item on screen leaves [items] the viewer calls [onClose] itself.
 * Decoded pages are held by their page only (the shared [DecodedImageCache] is read, not filled:
 * a viewer-sized decode would crowd the bubbles' cache).
 */
@Composable
fun MediaImageViewer(items: List<ViewerItem>, initialId: UUID, onClose: () -> Unit, onLoad: ((UUID) -> Unit)?, onDelete: ((UUID) -> Unit)?) {
    val container = LocalAppContainer.current
    val services = remember(container) { ContainerViewerServices(container) }
    MediaImageViewerContent(items, initialId, onClose, onLoad, onDelete, services)
}

/** [MediaImageViewer] on explicit [services]; the entry point and the tests call it. */
@Composable
internal fun MediaImageViewerContent(
    items: List<ViewerItem>,
    initialId: UUID,
    onClose: () -> Unit,
    onLoad: ((UUID) -> Unit)?,
    onDelete: ((UUID) -> Unit)?,
    services: ViewerServices,
) {
    MediaLayer {
        ViewerBody(items, initialId, onClose, onLoad, onDelete, services)
    }
}

@Composable
private fun ViewerBody(
    items: List<ViewerItem>,
    initialId: UUID,
    onClose: () -> Unit,
    onLoad: ((UUID) -> Unit)?,
    onDelete: ((UUID) -> Unit)?,
    services: ViewerServices,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reduceMotion = ShroudTheme.reduceMotion
    val banner = rememberMediaBanner()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnLoad by rememberUpdatedState(onLoad)

    val initialPage = remember { items.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { items.size }
    var currentId by remember { mutableStateOf(items.getOrNull(initialPage)?.id ?: initialId) }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) } // dp
    var dim by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf(Size.Zero) } // dp
    var topBar by remember { mutableFloatStateOf(0f) } // dp
    var bottomBar by remember { mutableFloatStateOf(0f) } // dp
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var moreBounds by remember { mutableStateOf(Rect.Zero) }

    val currentItem = items.firstOrNull { it.id == currentId } ?: items.firstOrNull()

    // The page the pager is on is the photo on screen.
    val latestItems by rememberUpdatedState(items)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page -> latestItems.getOrNull(page)?.let { currentId = it.id } }
    }
    // The photo on screen left the list (deleted for everyone, say): the viewer leaves with it
    // (`:155-161`), decided in the composition that lost it — before the pager can settle on a neighbour.
    val listed = items.any { it.id == currentId }
    val closedForMissing = remember { BooleanArray(1) }
    SideEffect {
        if (!listed && !closedForMissing[0]) {
            closedForMissing[0] = true
            currentOnClose()
        }
    }
    // Another photo leaving keeps this one on screen.
    LaunchedEffect(items) {
        val index = items.indexOfFirst { it.id == currentId }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    // Every grant this viewer handed out dies with it (K10; iOS deletes its temp file, `:625-627`).
    DisposableEffect(services) {
        onDispose { services.revokeShares() }
    }

    fun dismissAnimated(direction: Float = 1f) {
        if (closing) return
        closing = true
        haptic(Haptic.Light)
        val from = drag.y
        val to = ViewerDismiss.exitOffset(from, direction)
        val fromDim = dim
        chromeVisible = false
        scope.launch {
            launch {
                animate(0f, 1f, animationSpec = Motion.easeOut(200)) { t, _ ->
                    drag = Offset(drag.x, from + (to - from) * t)
                    dim = fromDim * (1f - t)
                }
            }
            delay(CLOSE_DELAY_MS)
            currentOnClose()
        }
    }

    fun endDismissDrag(translation: Offset, velocityY: Float) {
        if (ViewerDismiss.shouldDismiss(translation.y, velocityY)) {
            dismissAnimated(if (translation.y >= 0f) 1f else -1f)
        } else {
            val from = drag
            val fromDim = dim
            scope.launch {
                animate(0f, 1f, animationSpec = Motion.standard()) { t, _ ->
                    drag = from * (1f - t)
                    dim = fromDim + (1f - fromDim) * t
                }
            }
        }
    }

    // Predictive back drags the photo down with the gesture; its commit closes from where it got to.
    val backRef = remember { arrayOfNulls<State<Float>>(1) }
    val backState = rememberOverlayBack(enabled = !closing) {
        drag = Offset(0f, ViewerDismiss.backOffset(backRef[0]?.value ?: 0f))
        dismissAnimated()
    }
    backRef[0] = backState
    val back = backState.value

    fun share() {
        val item = currentItem ?: return
        scope.launch {
            val uri = services.shareUri(item.id)
            if (uri == null) {
                haptic(Haptic.Error)
                banner.flash(scope, SHARE_FAILED, BANNER_MS)
                return@launch
            }
            val send = MediaShareIntents.send(uri, context.contentResolver.getType(uri))
            try {
                context.startActivity(MediaShareIntents.chooser(send))
            } catch (_: ActivityNotFoundException) {
                haptic(Haptic.Error)
                banner.flash(scope, SHARE_FAILED, BANNER_MS)
            }
        }
    }

    fun copy() {
        val item = currentItem ?: return
        scope.launch {
            val uri = services.shareUri(item.id)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (uri == null || clipboard == null) {
                haptic(Haptic.Error)
                banner.flash(scope, COPY_FAILED, BANNER_MS)
                return@launch
            }
            clipboard.setPrimaryClip(MediaShareIntents.clip(context.contentResolver, uri))
            haptic(Haptic.Success)
            if (MediaShareIntents.showsCopiedBanner()) banner.flash(scope, COPIED, BANNER_MS)
        }
    }

    fun save() {
        val item = currentItem ?: return
        scope.launch {
            when (val outcome = services.saveToGallery(item.id)) {
                SaveOutcome.Saved -> {
                    haptic(Haptic.Success)
                    banner.flash(scope, SAVED, BANNER_MS)
                }
                is SaveOutcome.Failed -> {
                    haptic(Haptic.Error)
                    banner.flash(scope, outcome.message, BANNER_MS)
                }
            }
        }
    }

    fun delete() {
        // The exact photo on screen, never a fallback: a stale id must not delete another message (`:660-669`).
        val item = items.firstOrNull { it.id == currentId } ?: return
        val handler = onDelete
        if (handler == null) banner.flash(scope, DELETE_SOON, BANNER_MS) else handler(item.id)
    }

    val effectiveDrag = if (back > 0f && !closing) Offset(0f, ViewerDismiss.backOffset(back)) else drag
    val effectiveDim = if (back > 0f && !closing) ViewerDismiss.dimOpacity(effectiveDrag.y) else dim
    val dragScale = ViewerDismiss.dragScale(effectiveDrag.y, reduceMotion)
    val chromeAlpha = ViewerDismiss.chromeOpacity(effectiveDrag.y)
    val topInset = mediaTopInset()
    val bottomInset = mediaBottomInset()
    val backdrop = rememberGlassBackdrop()

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size -> container = with(density) { Size(size.width.toDp().value, size.height.toDp().value) } }
                .overlayPane(title = "Photo", onDismiss = { dismissAnimated() }),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = effectiveDim }
                    .background(Color.Black),
            )

            val decodeEdge = with(density) { decodeMaxEdge(max(container.width, container.height).dp.toPx()) }
            HorizontalPager(
                state = pager,
                key = { index -> items[index].id },
                userScrollEnabled = !zoomed,
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource()
                    .graphicsLayer {
                        translationX = effectiveDrag.x * 0.4f * density.density
                        translationY = effectiveDrag.y * density.density
                        scaleX = dragScale
                        scaleY = dragScale
                    }
                    .clearAndSetSemantics {
                        contentDescription = "Photo"
                        role = Role.Image
                        stateDescription = positionValue(items, currentId)
                        customActions = listOf(
                            CustomAccessibilityAction("Next photo") { step(items, currentId, 1, pager, scope, reduceMotion) },
                            CustomAccessibilityAction("Previous photo") { step(items, currentId, -1, pager, scope, reduceMotion) },
                        )
                    },
            ) { page ->
                val item = items[page]
                ViewerPage(
                    item = item,
                    isCurrent = item.id == currentId,
                    container = container,
                    decodeEdge = decodeEdge,
                    services = services,
                    onLoad = { id -> currentOnLoad?.invoke(id) },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onZoomChange = { isZoomed ->
                        zoomed = isZoomed
                        // Zooming in is a "look closer" gesture: the chrome steps aside (`:206-211`).
                        chromeVisible = !isZoomed
                    },
                    onDismissDrag = { translation ->
                        if (!closing) {
                            drag = with(density) { Offset(translation.x.toDp().value, translation.y.toDp().value) }
                            dim = ViewerDismiss.dimOpacity(drag.y)
                        }
                    },
                    onDismissEnd = { translation, velocity ->
                        if (!closing) {
                            with(density) {
                                endDismissDrag(
                                    Offset(translation.x.toDp().value, translation.y.toDp().value),
                                    velocity.y.toDp().value,
                                )
                            }
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(Motion.easeInOut(CHROME_FADE_MS)),
                exit = fadeOut(Motion.easeInOut(CHROME_FADE_MS)),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = chromeAlpha },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // No chrome taps while the photo is being dragged away (`:122`).
                        .blockTouches(abs(effectiveDrag.y) >= 1f),
                ) {
                    val aspect = currentItem?.aspect ?: 1f
                    TopChrome(
                        item = currentItem,
                        topInset = topInset,
                        fading = MediaViewerLayout.reachesChrome(aspect, container, topBarExtent(topBar, topInset)),
                        onClose = { dismissAnimated() },
                        onMore = { moreAnchor = moreBounds },
                        onMoreBounds = { moreBounds = it },
                        onHeight = { topBar = with(density) { it.toDp().value } },
                    )
                    Spacer(Modifier.weight(1f))
                    BottomChrome(
                        item = currentItem,
                        bottomInset = bottomInset,
                        fading = MediaViewerLayout.reachesChrome(aspect, container, bottomBarExtent(bottomBar, bottomInset)),
                        onShare = { share() },
                        onDraw = { banner.flash(scope, DRAW_SOON, BANNER_MS) },
                        onRecognize = { banner.flash(scope, TEXT_SOON, BANNER_MS) },
                        onDelete = { delete() },
                        onHeight = { bottomBar = with(density) { it.toDp().value } },
                    )
                }
            }

            MediaBanner(banner.text, bottomPadding = (bottomBarExtent(bottomBar, bottomInset) + 12f).dp)

            moreAnchor?.let { anchor ->
                ContextMenu(
                    anchor = anchor,
                    actions = listOf(
                        MenuAction(SAVE_TITLE, ShroudIcons.Download) { save() },
                        MenuAction("Share", ShroudIcons.Share2) { share() },
                        MenuAction("Copy", ShroudIcons.Copy) { copy() },
                    ),
                    style = MenuStyle.Dark,
                    onDismiss = { moreAnchor = null },
                    paneTitle = "More",
                    rows = MenuRows.Trailing,
                )
            }
        }
    }
}

/** One page: decodes its own photo (never fifty at once when the viewer opens, `:228-264`). */
@Composable
private fun ViewerPage(
    item: ViewerItem,
    isCurrent: Boolean,
    container: Size,
    decodeEdge: Int,
    services: ViewerServices,
    onLoad: (UUID) -> Unit,
    onToggleChrome: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onDismissDrag: (Offset) -> Unit,
    onDismissEnd: (Offset, Velocity) -> Unit,
) {
    var image by remember(item.id) { mutableStateOf(DecodedImageCache.image(item.id)) }
    var decoded by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, item.isLoaded) {
        if (!item.isLoaded) {
            onLoad(item.id)
            return@LaunchedEffect
        }
        if (decoded) return@LaunchedEffect
        val bytes = services.mediaBytes(item.id) ?: return@LaunchedEffect
        val bitmap = services.decodePreview(MediaImageSource.FileBytes(bytes), decodeEdge) ?: return@LaunchedEffect
        val shown = image
        if (shown == null || bitmap.width.toLong() * bitmap.height > shown.width.toLong() * shown.height) {
            image = bitmap.asImageBitmap()
        }
        decoded = true
    }
    val shown: ImageBitmap? = image
    if (shown != null) {
        ZoomableImage(
            image = shown,
            isActive = isCurrent,
            onSingleTap = onToggleChrome,
            onZoomChange = onZoomChange,
            onDismissDrag = onDismissDrag,
            onDismissEnd = onDismissEnd,
        )
    } else {
        PagePlaceholder(item.aspect, container, onToggleChrome)
    }
}

/** Sized from the message's aspect, edge-to-edge treatment included, so the photo lands without a jump (`:267-284`). */
@Composable
private fun PagePlaceholder(aspect: Float, container: Size, onTap: () -> Unit) {
    val scale = MediaViewerLayout.presentationScale(aspect, container)
    val fitted = MediaViewerLayout.fittedSize(aspect, container)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((fitted.width * scale).dp, (fitted.height * scale).dp)
                .background(Color.White.copy(alpha = 0.06f)),
        )
        Spinner(Color.White.copy(alpha = 0.9f))
    }
}

@Composable
private fun TopChrome(
    item: ViewerItem?,
    topInset: Dp,
    fading: Boolean,
    onClose: () -> Unit,
    onMore: () -> Unit,
    onMoreBounds: (Rect) -> Unit,
    onHeight: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(chromeBackground(fading, fromTop = true)),
    ) {
        Spacer(Modifier.height(topInset))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No press haptic: Close fires its own as the viewer leaves (`:494-495`).
            MediaCircleButton(ShroudIcons.ChevronLeft, "Close", onClose, haptic = Haptic.None)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                TitlePill(item)
            }
            MediaCircleButton(
                ShroudIcons.Ellipsis,
                "More",
                onMore,
                modifier = Modifier.onGloballyPositioned { onMoreBounds(it.boundsInRoot()) },
                haptic = Haptic.None,
            )
        }
    }
}

/** Sender and date in a glass capsule, cross-fading from page to page (`titlePill`, `:378-395`). */
@Composable
private fun TitlePill(item: ViewerItem?) {
    Crossfade(targetState = item, animationSpec = Motion.fade(), label = "viewerTitle") { shown ->
        Column(
            Modifier
                .glassSurface(CircleShape, GlassStyle.Regular)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ShroudText(shown?.title.orEmpty(), inter(15f, FontWeight.SemiBold), Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ShroudText(shown?.dateLine.orEmpty(), inter(12f), Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BottomChrome(
    item: ViewerItem?,
    bottomInset: Dp,
    fading: Boolean,
    onShare: () -> Unit,
    onDraw: () -> Unit,
    onRecognize: () -> Unit,
    onDelete: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(chromeBackground(fading, fromTop = false)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val caption = item?.caption
        if (!caption.isNullOrEmpty()) {
            // Hugs a short caption, stops a long one at 96 dp and scrolls (`:430-445`).
            Box(
                Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 20.dp, max = 96.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ShroudText(caption, inter(16f), Color.White, Modifier.fillMaxWidth())
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaCircleButton(ShroudIcons.ArrowBendUpRight, "Share", onShare, iconSize = 18.dp)
            Spacer(Modifier.weight(1f))
            // Telegram groups the two edit tools into one capsule (`:449-458`).
            Row(
                Modifier.glassSurface(CircleShape, GlassStyle.Regular),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CapsuleAction(ShroudIcons.PencilCircle, "Draw", onDraw)
                CapsuleAction(ShroudIcons.ScanText, "Text recognition", onRecognize)
            }
            Spacer(Modifier.weight(1f))
            MediaCircleButton(ShroudIcons.Trash2, "Delete", onDelete, iconSize = 18.dp)
        }
        Spacer(Modifier.height(bottomInset))
    }
}

/** A 40 dp tool inside the shared capsule, no background of its own (`capsuleAction`, `:522-536`). */
@Composable
private fun CapsuleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressable(scale = 0.85f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, Color.White, size = 18.dp)
    }
}

/** Solid black when the photo letterboxes clear of the bar, a front-loaded scrim when it runs under it (`:303-333`). */
private fun chromeBackground(fading: Boolean, fromTop: Boolean): Brush = when {
    !fading -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
    fromTop -> Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.95f),
        0.55f to Color.Black.copy(alpha = 0.6f),
        1f to Color.Black.copy(alpha = 0f),
    )
    else -> Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0f),
        0.3f to Color.Black.copy(alpha = 0.45f),
        0.6f to Color.Black.copy(alpha = 0.8f),
        1f to Color.Black.copy(alpha = 0.95f),
    )
}

/** The bars as measured, or their caption-less height before the first layout (`:296-301`). */
internal fun topBarExtent(measured: Float, topInset: Dp): Float = if (measured > 0f) measured else topInset.value + 62f

internal fun bottomBarExtent(measured: Float, bottomInset: Dp): Float = if (measured > 0f) measured else max(bottomInset.value, 8f) + 78f

/** Swallows every touch on this element and its children while [block] is set (`.allowsHitTesting(false)`). */
internal fun Modifier.blockTouches(block: Boolean): Modifier =
    if (!block) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

/** "3 of 10" — where the pager stands, for TalkBack (`pagerPosition`, `:184-188`). */
internal fun positionValue(items: List<ViewerItem>, currentId: UUID): String {
    val index = items.indexOfFirst { it.id == currentId }
    return if (index < 0) "" else "${index + 1} of ${items.size}"
}

/** The long edge a page decodes to: 1.5 × the screen's, at least 2048 and at most 4096 px — room to zoom, bounded memory. */
internal fun decodeMaxEdge(screenLongEdgePx: Float): Int = (screenLongEdgePx * 1.5f).roundToInt().coerceIn(2048, 4096)

/** TalkBack's swipe up / down: the neighbouring page, as a sideways swipe gives (`step(by:)`, `:585-593`). */
private fun step(
    items: List<ViewerItem>,
    currentId: UUID,
    by: Int,
    pager: PagerState,
    scope: CoroutineScope,
    reduceMotion: Boolean,
): Boolean {
    val index = items.indexOfFirst { it.id == currentId }
    val target = index + by
    if (index < 0 || target !in items.indices) return false
    scope.launch {
        if (reduceMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = Motion.standard())
    }
    return true
}

/** Close lets the fly-out run this long before it calls `onClose` (`:571-573`). */
private const val CLOSE_DELAY_MS = 180L
private const val CHROME_FADE_MS = 180
private const val BANNER_MS = 1_600L

/** Android name for iOS "Save to Photos" (decision Q7). */
internal const val SAVE_TITLE = "Save to Gallery"
internal const val SAVED = "Saved to Gallery"
internal const val COPIED = "Copied"
internal const val COPY_FAILED = "Could not copy that photo."
internal const val SHARE_FAILED = "Could not share that photo."
internal const val DRAW_SOON = "Drawing coming soon"
internal const val TEXT_SOON = "Text recognition coming soon"
internal const val DELETE_SOON = "Delete coming soon"

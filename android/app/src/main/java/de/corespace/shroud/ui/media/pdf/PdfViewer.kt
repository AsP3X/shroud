package de.corespace.shroud.ui.media.pdf

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.pdf.PdfPageSize
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.CredentialRow
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuRows
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.glassBackdropSource
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.overlayPane
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.media.viewer.MediaCircleButton
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The PDF viewer's file actions (More ⋮, the error states): the conversation runs them like the bubble's. */
enum class PdfFileAction { Share, SaveToDownloads, OpenInAnotherApp }

/**
 * Shroud's own PDF viewer (docs/file-sharing.md §10.2): the pages of [messageId]'s PDF in one
 * vertical column on the canvas, read through `PdfRenderer` over a proxy descriptor of the sealed
 * cache (no plaintext on disk).
 *
 * Human: Close, the file name with `Page {n} of {count}`, and Search (Android 15+), All pages and
 * More across the top in the media viewer's glass idiom. Pages fit the width (12 dp margin, at most
 * 920 dp), white with a soft shadow, 12 dp apart. Pinch or double-tap to zoom (1×–6×); a single tap
 * hides or shows the chrome. All pages opens a sheet of thumbnails; More offers Share, Save to
 * Downloads and Open in Another App. A protected PDF asks for its password (Android 15+), a damaged
 * one says so. Back closes search first, then the viewer.
 *
 * Agent: draws in place, full size; the conversation's media layer hosts it and drops it in
 * [onClose] (Close, Back, the message deleted, the chats locked). Leaving the composition closes the
 * document. [onAction] runs a file action through the conversation (grants, toasts, haptics).
 */
@Composable
fun PdfViewer(messageId: UUID, name: String, onClose: () -> Unit, onAction: (PdfFileAction) -> Unit) {
    val container = LocalAppContainer.current
    val services = remember(container) { PdfViewerServices { id, password -> container.media.pdf.openDocument(id, password) } }
    PdfViewerContent(messageId, name, onClose, onAction, services)
}

/** [PdfViewer] on explicit [services]; the entry point and the tests call it. */
@Composable
internal fun PdfViewerContent(
    messageId: UUID,
    name: String,
    onClose: () -> Unit,
    onAction: (PdfFileAction) -> Unit,
    services: PdfViewerServices,
) {
    val scope = rememberCoroutineScope()
    val state = remember(messageId) { PdfViewerState(messageId, services, scope, PdfReadingMemory.page(messageId) ?: 0) }
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    var chromeVisible by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    var barHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    val currentPage by remember(state) {
        derivedStateOf {
            val band = PdfZoomMath.visibleBand(state.viewport.height, state.scale, state.offset.y)
            val items = state.listState.layoutInfo.visibleItemsInfo.map { Triple(it.index, it.offset.toFloat(), (it.offset + it.size).toFloat()) }
            PdfZoomMath.mostVisible(items, band, state.listState.firstVisibleItemIndex)
        }
    }
    val latestPage by rememberUpdatedState(currentPage)

    DisposableEffect(state) {
        state.open()
        onDispose {
            if (state.pageCount != null) PdfReadingMemory.remember(state.messageId, latestPage)
            state.dispose()
        }
    }
    // Back: search first, then the drawer, then the viewer (the host's handler). The handler
    // registered last wins, so the drawer's comes before search's.
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    BackHandler(enabled = state.searching) { state.endSearch() }

    val jumpTo: (Int) -> Unit = { page -> scope.launch { state.listState.scrollToItem(page) } }

    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(colors.pdfCanvas)
                .overlayPane(title = name, onDismiss = onClose),
        ) {
            val windowWidth = maxWidth
            val wide = windowWidth.value >= PdfViewerMetrics.SIDEBAR_MIN_WINDOW
            val ready = state.phase == PdfViewerState.Phase.Ready
            val count = state.pageCount
            // Wide windows: open by default for more than one page; the reader's choice holds for the session.
            val sidebarWanted = PdfReadingMemory.sidebarOpen ?: (count == null || count > 1)
            val sidebarShown = wide && ready && sidebarWanted
            val sidebarWidth = rememberSidebarWidth(sidebarShown, ready, reduceMotion)
            val sidebarPresent by remember { derivedStateOf { sidebarWidth.value > 0f } }
            val sidebarTarget = if (sidebarShown) (PdfViewerMetrics.SIDEBAR_WIDTH + PdfViewerMetrics.SIDEBAR_SEPARATOR).dp else 0.dp

            val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
            val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
            val barDp = with(density) { barHeight.toDp() }.takeIf { it > 0.dp } ?: (topInset + 60.dp)
            val contentPadding = PaddingValues(top = barDp + 4.dp, bottom = bottomInset + PdfViewerMetrics.PAGE_MARGIN.dp)

            Row(Modifier.fillMaxSize()) {
                if (sidebarPresent) {
                    Box(
                        Modifier
                            // Read while laying out, so the slide re-measures the row each frame
                            // without composing the viewer again.
                            .layout { measurable, constraints ->
                                val width = sidebarWidth.value.dp.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                                layout(width, placeable.height) { placeable.place(0, 0) }
                            }
                            .fillMaxHeight()
                            .clipToBounds(),
                    ) {
                        Row(Modifier.requiredWidth((PdfViewerMetrics.SIDEBAR_WIDTH + PdfViewerMetrics.SIDEBAR_SEPARATOR).dp).fillMaxHeight().align(Alignment.CenterEnd)) {
                            PagesList(
                                state = state,
                                currentPage = currentPage,
                                onPick = jumpTo,
                                contentPadding = PaddingValues(
                                    top = barDp + PdfViewerMetrics.THUMB_LIST_PADDING.dp,
                                    bottom = bottomInset + PdfViewerMetrics.THUMB_LIST_PADDING.dp,
                                ),
                                modifier = Modifier.width(PdfViewerMetrics.SIDEBAR_WIDTH.dp).fillMaxHeight().background(colors.pdfSidebar),
                            )
                            Box(Modifier.width(PdfViewerMetrics.SIDEBAR_SEPARATOR.dp).fillMaxHeight().background(colors.separator))
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().glassBackdropSource()) {
                    when (val phase = state.phase) {
                        PdfViewerState.Phase.Loading -> LoadingPages(contentPadding)
                        PdfViewerState.Phase.Ready -> PageColumn(
                            state = state,
                            contentPadding = contentPadding,
                            // Renders follow the width the pages settle at, not each frame of the sidebar's animation.
                            renderAreaWidth = windowWidth - sidebarTarget,
                            onToggleChrome = { if (!state.searching) chromeVisible = !chromeVisible },
                        )
                        is PdfViewerState.Phase.Password -> PasswordState(phase, contentPadding) { password -> state.open(password) }
                        PdfViewerState.Phase.PasswordUnsupported -> MessageState(
                            icon = ShroudIcons.LockFill,
                            title = null,
                            body = PdfViewerCopy.PROTECTED_ELSEWHERE,
                            contentPadding = contentPadding,
                            onOpenElsewhere = { onAction(PdfFileAction.OpenInAnotherApp) },
                        )
                        PdfViewerState.Phase.Damaged -> MessageState(
                            icon = ShroudIcons.FilePdfFill,
                            title = PdfViewerCopy.DAMAGED_TITLE,
                            body = PdfViewerCopy.DAMAGED_BODY,
                            contentPadding = contentPadding,
                            onOpenElsewhere = { onAction(PdfFileAction.OpenInAnotherApp) },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible || state.searching || !ready,
                enter = fadeIn(if (reduceMotion) Motion.reduced() else Motion.easeInOut(PdfViewerMetrics.CHROME_FADE_MS)),
                exit = fadeOut(if (reduceMotion) Motion.reduced() else Motion.easeInOut(PdfViewerMetrics.CHROME_FADE_MS)),
            ) {
                TopBar(
                    state = state,
                    name = name,
                    currentPage = currentPage,
                    topInset = topInset,
                    sidebarWidth = { sidebarWidth.value.dp },
                    pagesOpen = if (wide) sidebarShown else drawerOpen,
                    onHeight = { barHeight = it },
                    onClose = onClose,
                    onPages = {
                        if (wide) PdfReadingMemory.sidebarOpen = !sidebarShown else drawerOpen = !drawerOpen
                    },
                    onMore = { moreAnchor = moreBounds },
                    onMoreBounds = { moreBounds = it },
                    onSearchStep = { by -> state.step(by) { match -> scope.launch { scrollToMatch(state, match) } } },
                    onSearchSubmit = {
                        if (state.query != state.searchedQuery) {
                            state.search(state.query, currentPage) { match -> scope.launch { scrollToMatch(state, match) } }
                        } else {
                            state.step(1) { match -> scope.launch { scrollToMatch(state, match) } }
                        }
                    },
                )
            }

            // Narrow windows: the same list as a drawer from the left.
            PagesDrawer(
                visible = drawerOpen && !wide && ready,
                state = state,
                currentPage = currentPage,
                width = PdfViewerMetrics.drawerWidth(windowWidth.value).dp,
                topInset = topInset,
                bottomInset = bottomInset,
                onPick = { page ->
                    drawerOpen = false
                    jumpTo(page)
                },
                onDismiss = { drawerOpen = false },
            )

            // Typing pauses for a moment before the search runs; Return searches at once.
            LaunchedEffect(state, state.query) {
                if (!state.searching || state.query == state.searchedQuery) return@LaunchedEffect
                delay(SEARCH_DEBOUNCE_MS)
                state.search(state.query, latestPage) { match -> scope.launch { scrollToMatch(state, match) } }
            }

            moreAnchor?.let { anchor ->
                ContextMenu(
                    anchor = anchor,
                    actions = listOf(
                        MenuAction(PdfViewerCopy.SHARE, ShroudIcons.Share2) { onAction(PdfFileAction.Share) },
                        MenuAction(PdfViewerCopy.SAVE_TO_DOWNLOADS, ShroudIcons.Download) { onAction(PdfFileAction.SaveToDownloads) },
                        MenuAction(PdfViewerCopy.OPEN_IN_ANOTHER_APP, ShroudIcons.ExternalLink) { onAction(PdfFileAction.OpenInAnotherApp) },
                    ),
                    style = if (colors.isDark) MenuStyle.Dark else MenuStyle.Light,
                    onDismiss = { moreAnchor = null },
                    paneTitle = PdfViewerCopy.MORE,
                    rows = MenuRows.Trailing,
                )
            }
        }
    }
}

/**
 * The sidebar's width in dp (§10.2): 0 ↔ 200 + its separator, sliding in over 280 ms, none under
 * Reduce Motion; its first appearance (the document opening) does not animate. Read it in layout
 * or drawing, not in composition, so the slide doesn't compose the viewer every frame.
 */
@Composable
private fun rememberSidebarWidth(shown: Boolean, ready: Boolean, reduceMotion: Boolean): Animatable<Float, AnimationVector1D> {
    val full = PdfViewerMetrics.SIDEBAR_WIDTH + PdfViewerMetrics.SIDEBAR_SEPARATOR
    val width = remember { Animatable(if (shown) full else 0f) }
    val wasReady = remember { BooleanArray(1) }
    LaunchedEffect(shown, ready) {
        val target = if (shown) full else 0f
        if (reduceMotion || !wasReady[0]) width.snapTo(target) else width.animateTo(target, slideSpec())
        if (ready) wasReady[0] = true
    }
    return width
}

/** The pages sidebar and drawer slide (§10.2): decelerating, without a jump at the start. */
private fun <T> slideSpec(): FiniteAnimationSpec<T> = tween(PdfViewerMetrics.SIDEBAR_ANIMATION_MS, easing = SlideEasing)

private val SlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

// ---- the page column ----

@Composable
private fun PageColumn(state: PdfViewerState, contentPadding: PaddingValues, renderAreaWidth: Dp, onToggleChrome: () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduceMotion = ShroudTheme.reduceMotion
    val count = state.pageCount ?: 0
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    val scrollBy: (Float) -> Float = { delta -> state.listState.dispatchRawDelta(delta) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = PdfViewerMetrics.pageWidth(maxWidth.value).dp
        val renderWidthPx = with(density) { PdfViewerMetrics.pageWidth(renderAreaWidth.value).dp.roundToPx() }
        LaunchedEffect(renderWidthPx) { state.pageWidthPx = renderWidthPx }
        // Re-fit: when the pages narrow or widen (the sidebar, a rotation), the page at the top keeps
        // the same relative position, and the zoom stays relative to the fit.
        val layoutWidthPx = with(density) { pageWidth.roundToPx() }
        val lastLayoutWidth = remember { IntArray(1) { layoutWidthPx } }
        LaunchedEffect(layoutWidthPx) {
            val old = lastLayoutWidth[0]
            lastLayoutWidth[0] = layoutWidthPx
            if (old > 0 && old != layoutWidthPx) {
                val first = state.listState.firstVisibleItemIndex
                val offset = state.listState.firstVisibleItemScrollOffset
                state.listState.scrollToItem(first, (offset.toLong() * layoutWidthPx / old).toInt())
            }
        }

        // Visible pages ±1 are rendered at fit width × density; the rest are cancelled.
        LaunchedEffect(state) {
            snapshotFlow {
                val band = PdfZoomMath.visibleBand(state.viewport.height, state.scale, state.offset.y)
                val shown = state.listState.layoutInfo.visibleItemsInfo
                    .filter { it.offset + it.size > band.start && it.offset < band.endInclusive }
                    .map { it.index }
                val wanted = if (shown.isEmpty()) {
                    setOf(state.listState.firstVisibleItemIndex)
                } else {
                    ((shown.min() - PdfViewerMetrics.PREFETCH)..(shown.max() + PdfViewerMetrics.PREFETCH)).filter { it in 0 until count }.toSet()
                }
                wanted to state.pageWidthPx
            }.distinctUntilChanged().collect { (wanted, px) -> state.updateRenders(wanted, px) }
        }
        // Once a zoom settles, the visible parts are drawn again at the zoom: never a blurry upscale.
        LaunchedEffect(state) {
            snapshotFlow {
                listOf(
                    state.scale, state.offset, state.interacting, state.pageWidthPx,
                    state.listState.firstVisibleItemIndex, state.listState.firstVisibleItemScrollOffset,
                )
            }.collectLatest { key ->
                if (key[2] == true) return@collectLatest
                delay(DETAIL_SETTLE_MS)
                val visible = state.listState.layoutInfo.visibleItemsInfo.map { Triple(it.index, it.offset.toFloat(), it.size.toFloat()) }
                state.renderDetails(visible)
            }
        }

        fun animateZoom(target: Float, anchor: Offset) {
            flingJob?.cancel()
            flingJob = scope.launch {
                state.interacting = true
                try {
                    if (reduceMotion) {
                        state.setScale(target, anchor, scrollBy = scrollBy)
                    } else {
                        val from = state.scale
                        animate(from, target, animationSpec = Motion.standard()) { value, _ -> state.setScale(value, anchor, scrollBy = scrollBy) }
                    }
                } finally {
                    state.interacting = false
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { onToggleChrome() },
                        onDoubleTap = { position -> animateZoom(PdfZoomMath.doubleTapTarget(state.scale), position) },
                    )
                }
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        flingJob?.cancel()
                        var pinching = false
                        var panning = false
                        var travelled = 0f
                        var lastCentroid = Offset.Zero
                        val tracker = VelocityTracker()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                pinching = true
                                state.interacting = true
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) lastCentroid = centroid
                                state.transform(lastCentroid, event.calculatePan(), event.calculateZoom(), scrollBy)
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && (state.isZoomed || pinching)) {
                                val change = pressed[0]
                                val delta = change.positionChange()
                                if (!panning) {
                                    travelled += delta.getDistance()
                                    if (travelled > viewConfiguration.touchSlop) {
                                        panning = true
                                        state.interacting = true
                                    }
                                }
                                if (panning) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    state.pan(delta, scrollBy)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        when {
                            pinching && (state.scale < PdfViewerMetrics.MIN_ZOOM || state.scale > PdfViewerMetrics.MAX_ZOOM) ->
                                animateZoom(state.scale.coerceIn(PdfViewerMetrics.MIN_ZOOM, PdfViewerMetrics.MAX_ZOOM), lastCentroid)
                            panning -> {
                                val velocity = tracker.calculateVelocity()
                                flingJob = scope.launch {
                                    try {
                                        coroutineScope {
                                            launch {
                                                state.listState.scroll { with(flingBehavior) { performFling(-velocity.y / state.scale) } }
                                            }
                                            launch {
                                                AnimationState(state.offset.x, velocity.x).animateDecay(decay) {
                                                    state.setOffsetX(value)
                                                    if (state.offset.x != value) cancelAnimation()
                                                }
                                            }
                                        }
                                    } finally {
                                        state.interacting = false
                                    }
                                }
                            }
                            else -> state.interacting = false
                        }
                    }
                },
        ) {
            LazyColumn(
                state = state.listState,
                userScrollEnabled = !state.isZoomed,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(PdfViewerMetrics.PAGE_GAP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = state.scale
                        scaleY = state.scale
                        translationX = state.offset.x
                        translationY = state.offset.y
                        transformOrigin = TransformOrigin.Center
                    },
            ) {
                items(count, key = { it }) { page ->
                    PdfPage(state, page, count, pageWidth)
                }
            }
            if (!state.firstPageDrawn) Spinner(ShroudTheme.colors.textSecondary, Modifier.align(Alignment.Center), size = 24.dp)
        }
    }
}

/** One page: white, radius 2, a soft shadow; its bitmap, a zoomed page's sharp part, and the search highlights. */
@Composable
private fun PdfPage(state: PdfViewerState, page: Int, count: Int, width: Dp) {
    val colors = ShroudTheme.colors
    val aspect = state.aspect(page)
    val render = state.pages[page]
    val detail = state.details[page]
    val size = state.sizes.getOrNull(page)
    val hits = if (state.matches.isEmpty()) emptyList() else state.matches.withIndex().filter { it.value.page == page }
    val current = state.currentMatch
    val highlight = colors.accent
    val shape = RoundedCornerShape(PdfViewerMetrics.PAGE_RADIUS.dp)
    Box(
        Modifier
            .size(width, width * aspect)
            .dropShadow(shape, Shadow(radius = 6.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.14f))
            .clip(shape)
            .background(Color.White)
            .drawWithContent {
                render?.let { drawImage(it.image, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                if (detail != null && detail.widthPx.toFloat() == this.size.width.roundToInt().toFloat()) {
                    drawImage(
                        detail.image,
                        dstOffset = IntOffset(detail.region.left.roundToInt(), detail.region.top.roundToInt()),
                        dstSize = IntSize(detail.region.width.roundToInt(), detail.region.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                if (size != null && hits.isNotEmpty()) {
                    val k = this.size.width / size.width
                    for ((index, match) in hits) {
                        val fill = highlight.copy(alpha = if (index == current) 0.6f else 0.3f)
                        for (r in match.rects) {
                            drawRect(fill, topLeft = Offset(r.left * k, r.top * k), size = Size(r.width() * k, r.height() * k))
                        }
                    }
                }
            }
            .clearAndSetSemantics { contentDescription = PdfViewerCopy.pageLabel(page + 1, count) },
    )
}

/** Loading (§10.2): white A4 placeholders and a spinner on the canvas until the document is parsed. */
@Composable
private fun LoadingPages(contentPadding: PaddingValues) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = PdfViewerMetrics.pageWidth(maxWidth.value).dp
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PdfViewerMetrics.PAGE_GAP.dp),
        ) {
            repeat(2) {
                Box(
                    Modifier
                        .size(width, width * PdfPageSize.A4.aspect)
                        .dropShadow(RoundedCornerShape(2.dp), Shadow(radius = 6.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.14f))
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            }
        }
        Spinner(ShroudTheme.colors.textSecondary, Modifier.align(Alignment.Center), size = 24.dp)
    }
}

/** Scrolls [match] into view: its page, the match a third down the screen. */
private suspend fun scrollToMatch(state: PdfViewerState, match: SearchMatch) {
    val size = state.sizes.getOrNull(match.page)
    val top = match.rects.minOfOrNull { it.top } ?: 0f
    val offsetPx = if (size != null && state.pageWidthPx > 0) top * state.pageWidthPx / size.width - state.viewport.height / 3f else 0f
    state.listState.animateScrollToItem(match.page, max(0f, offsetPx).roundToInt())
}

// ---- the top bar ----

@Composable
private fun TopBar(
    state: PdfViewerState,
    name: String,
    currentPage: Int,
    topInset: Dp,
    /** Read while drawing: it moves every frame of the sidebar's slide. */
    sidebarWidth: () -> Dp,
    pagesOpen: Boolean,
    onHeight: (Int) -> Unit,
    onClose: () -> Unit,
    onPages: () -> Unit,
    onMore: () -> Unit,
    onMoreBounds: (Rect) -> Unit,
    onSearchStep: (Int) -> Unit,
    onSearchSubmit: () -> Unit,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            // A fade of the surface underneath: the sidebar's fill over the sidebar, the canvas over the pages.
            .drawBehind {
                val split = sidebarWidth().toPx().coerceIn(0f, size.width)
                fun fade(color: Color) = Brush.verticalGradient(
                    0f to color.copy(alpha = 0.92f),
                    0.6f to color.copy(alpha = 0.6f),
                    1f to color.copy(alpha = 0f),
                )
                if (split > 0f) drawRect(fade(colors.pdfSidebar), size = Size(split, size.height))
                drawRect(fade(colors.pdfCanvas), topLeft = Offset(split, 0f), size = Size(size.width - split, size.height))
            },
    ) {
        Spacer(Modifier.height(topInset))
        Crossfade(targetState = state.searching, animationSpec = Motion.fade(), label = "pdfBar") { searching ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    SearchBar(state, onSearchStep, onSearchSubmit)
                } else {
                    val ready = state.phase == PdfViewerState.Phase.Ready
                    MediaCircleButton(ShroudIcons.X, PdfViewerCopy.CLOSE, onClose, tint = colors.textPrimary, haptic = Haptic.None)
                    // Pages sits right after Close, above the sidebar it opens; accent while it is open.
                    if (ready) {
                        MediaCircleButton(
                            ShroudIcons.PanelLeft,
                            PdfViewerCopy.PAGES,
                            onPages,
                            modifier = Modifier.semantics { selected = pagesOpen },
                            tint = if (pagesOpen) colors.accentText else colors.textPrimary,
                        )
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        TitlePill(name, state, currentPage)
                    }
                    Row(
                        Modifier
                            .padding(4.dp)
                            .glassSurface(CircleShape),
                    ) {
                        if (ready && state.canSearch) BarAction(ShroudIcons.Search, PdfViewerCopy.SEARCH) { state.beginSearch() }
                        BarAction(
                            ShroudIcons.EllipsisVertical,
                            PdfViewerCopy.MORE,
                            Modifier.onGloballyPositioned { onMoreBounds(it.boundsInRoot()) },
                            onClick = onMore,
                        )
                    }
                }
            }
        }
    }
}

/** The file name (one line, middle-truncated) over the live page, in a glass capsule. */
@Composable
private fun TitlePill(name: String, state: PdfViewerState, currentPage: Int) {
    val colors = ShroudTheme.colors
    val subtitle = when (state.phase) {
        PdfViewerState.Phase.Loading, PdfViewerState.Phase.Ready -> PdfViewerCopy.subtitle(currentPage + 1, state.pageCount)
        else -> null
    }
    Column(
        Modifier
            .glassSurface(CircleShape)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = if (subtitle == null) 10.dp else 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ShroudText(name, inter(15f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        if (subtitle != null) ShroudText(subtitle, inter(12f, tabularDigits = true), colors.textSecondary, maxLines = 1)
    }
}

/** A 40 dp action inside the bar's shared glass capsule. */
@Composable
private fun BarAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Box(
        modifier
            .size(40.dp)
            .pressable(enabled = enabled, scale = 0.85f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f), size = 18.dp)
    }
}

/** Search (§10.2): the field (`Search in PDF`) with `{i} of {n}`, Previous / Next result and Done. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchBar(state: PdfViewerState, onStep: (Int) -> Unit, onSubmit: () -> Unit) {
    val colors = ShroudTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val status = when {
        state.query.isBlank() || state.query != state.searchedQuery -> null
        state.matches.isNotEmpty() -> PdfViewerCopy.searchPosition(state.currentMatch.coerceAtLeast(0) + 1, state.matches.size)
        state.searchFinished -> PdfViewerCopy.NO_RESULTS
        else -> null
    }
    Row(
        Modifier
            .weight(1f)
            .padding(4.dp)
            .glassSurface(CircleShape)
            .heightIn(min = 40.dp)
            .padding(start = 14.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.Search, colors.textSecondary, size = 16.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            NoLearningTextInput {
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = PdfViewerCopy.SEARCH_PLACEHOLDER },
                    textStyle = inter(15f).copy(color = colors.textPrimary),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { onSubmit() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.query.isEmpty()) ShroudText(PdfViewerCopy.SEARCH_PLACEHOLDER, inter(15f), colors.textSecondary, maxLines = 1)
                            field()
                        }
                    },
                )
            }
        }
        if (status != null) ShroudText(status, inter(13f, tabularDigits = true), colors.textSecondary, maxLines = 1)
    }
    val hasMatches = state.matches.isNotEmpty()
    Row(Modifier.padding(4.dp).glassSurface(CircleShape)) {
        BarAction(ShroudIcons.ChevronUp, PdfViewerCopy.PREVIOUS_RESULT, enabled = hasMatches) { onStep(-1) }
        BarAction(ShroudIcons.ChevronDown, PdfViewerCopy.NEXT_RESULT, enabled = hasMatches) { onStep(1) }
    }
    Box(
        Modifier
            .padding(4.dp)
            .pressable(scale = 0.94f, dimming = 0f, onClick = { state.endSearch() })
            .glassSurface(CircleShape)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(PdfViewerCopy.DONE, inter(15f, FontWeight.SemiBold), colors.accentText, maxLines = 1)
    }
}

// ---- states ----

/** Password (§10.2, API 35+): lock, copy, a secure field and Open; a wrong one says so. */
@Composable
private fun PasswordState(phase: PdfViewerState.Phase.Password, contentPadding: PaddingValues, onOpen: (String) -> Unit) {
    val colors = ShroudTheme.colors
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val submit = { if (password.isNotEmpty() && !phase.unlocking) onOpen(password) }
    StateColumn(contentPadding) {
        ShroudIcon(ShroudIcons.LockFill, colors.textSecondary, size = 44.dp)
        Spacer(Modifier.height(16.dp))
        ShroudText(PdfViewerCopy.PROTECTED_TITLE, inter(17f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        ShroudText(PdfViewerCopy.PROTECTED_BODY, inter(15f), colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.background)) {
            CredentialRow(
                icon = ShroudIcons.KeyRound,
                iconTint = colors.textSecondary,
                placeholder = PdfViewerCopy.PASSWORD,
                value = password,
                onValueChange = { password = it },
                isSecret = true,
                revealed = revealed,
                onToggleVisible = { revealed = !revealed },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                onImeAction = submit,
            )
        }
        if (phase.wrong) {
            Spacer(Modifier.height(8.dp))
            ShroudText(PdfViewerCopy.WRONG_PASSWORD, inter(13f), colors.dangerText, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(PdfViewerCopy.OPEN, onClick = submit, showsArrow = false, isLoading = phase.unlocking, enabled = password.isNotEmpty())
    }
}

/** Damaged, or protected below API 35 (§10.2): a glyph, the copy and Open in Another App. */
@Composable
private fun MessageState(icon: ImageVector, title: String?, body: String, contentPadding: PaddingValues, onOpenElsewhere: () -> Unit) {
    val colors = ShroudTheme.colors
    StateColumn(contentPadding) {
        ShroudIcon(icon, colors.textSecondary, size = 44.dp)
        Spacer(Modifier.height(16.dp))
        if (title != null) {
            ShroudText(title, inter(17f, FontWeight.SemiBold), colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
        }
        ShroudText(body, inter(15f), colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        SecondaryButton(PdfViewerCopy.OPEN_IN_ANOTHER_APP, onOpenElsewhere)
    }
}

@Composable
private fun StateColumn(contentPadding: PaddingValues, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

// ---- the pages list ----

/**
 * The pages list (§10.2): one column of thumbnails, 128 dp wide at each page's aspect (no taller
 * than 182), white, radius 2, a hairline and a soft shadow, the number 12 sp under it; rows 20 apart.
 * The current page has a 2 dp accent outline 3 dp outside and its number in accent, semibold. Rows
 * are sized from the page sizes alone; only the rows in view and a few ahead render, two at a time.
 * The list follows the current page when it leaves the visible part, unless the reader is
 * scrolling the list. A tap jumps to the page's top ([onPick]).
 */
@Composable
private fun PagesList(state: PdfViewerState, currentPage: Int, onPick: (Int) -> Unit, contentPadding: PaddingValues, modifier: Modifier) {
    val count = state.pageCount ?: 0
    val density = LocalDensity.current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentPage - 1).coerceIn(0, max(0, count - 1)))
    val latestCount by rememberUpdatedState(count)

    // Render the rows in view, nearest first, then a few ahead; the rest are cancelled.
    LaunchedEffect(state, list) {
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
            if (visible.isEmpty()) {
                emptyList()
            } else {
                val ahead = PdfViewerMetrics.THUMB_AHEAD
                visible + ((visible.max() + 1)..(visible.max() + ahead)) + ((visible.min() - ahead) until visible.min()).reversed()
            }.filter { it in 0 until latestCount }
        }.distinctUntilChanged().collect { wanted ->
            state.updateThumbnails(wanted) { page ->
                with(density) { PdfViewerMetrics.thumbnailSize(state.aspect(page)).first.dp.roundToPx() }
            }
        }
    }
    // Follow the document: only when the current page leaves the visible part, never while the list is being scrolled.
    LaunchedEffect(list, currentPage) {
        if (list.isScrollInProgress || count == 0) return@LaunchedEffect
        val info = list.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == currentPage }
        val inView = row != null && row.offset >= info.viewportStartOffset && row.offset + row.size <= info.viewportEndOffset
        if (inView) return@LaunchedEffect
        val rows = info.visibleItemsInfo
        val target = if (rows.isNotEmpty() && currentPage > rows.last().index) (currentPage - (rows.size - 2).coerceAtLeast(0)) else currentPage
        list.animateScrollToItem(target.coerceIn(0, count - 1))
    }

    LazyColumn(
        state = list,
        modifier = modifier.semantics {
            contentDescription = PdfViewerCopy.PAGES
            isTraversalGroup = true
        },
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(PdfViewerMetrics.THUMB_ROW_GAP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count, key = { it }) { page ->
            PageThumbnail(state, page, count, page == currentPage) { onPick(page) }
        }
    }
}

@Composable
private fun PageThumbnail(state: PdfViewerState, page: Int, count: Int, current: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    val (width, height) = PdfViewerMetrics.thumbnailSize(state.aspect(page))
    val image = state.thumbnails[page]
    val shape = RoundedCornerShape(PdfViewerMetrics.PAGE_RADIUS.dp)
    val outline = colors.accent
    Column(
        Modifier
            .pressable(scale = 0.96f, dimming = 0.04f, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = PdfViewerCopy.pageLabel(page + 1, count)
                role = Role.Button
                selected = current
                onClick {
                    onClick()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PdfViewerMetrics.THUMB_LABEL_GAP.dp),
    ) {
        Box(
            Modifier
                .size(width.dp, height.dp)
                .drawBehind {
                    if (current) {
                        // 2 dp outline, 3 dp outside the thumbnail: its centre line sits 4 dp out.
                        val out = (PdfViewerMetrics.THUMB_OUTLINE_GAP + PdfViewerMetrics.THUMB_OUTLINE / 2f).dp.toPx()
                        drawRoundRect(
                            outline,
                            topLeft = Offset(-out, -out),
                            size = Size(size.width + 2 * out, size.height + 2 * out),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                            style = Stroke(PdfViewerMetrics.THUMB_OUTLINE.dp.toPx()),
                        )
                    }
                }
                .dropShadow(shape, Shadow(radius = 4.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.12f))
                .clip(shape)
                .background(Color.White)
                .drawWithContent {
                    image?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                }
                .border(0.5.dp, Color.Black.copy(alpha = 0.1f), shape),
        )
        ShroudText(
            "${page + 1}",
            inter(12f, if (current) FontWeight.SemiBold else FontWeight.Normal, tabularDigits = true),
            if (current) colors.accentText else colors.textSecondary,
        )
    }
}

/**
 * Narrow windows (§10.2): the pages list as a drawer from the left, `min(280, 80 %)` wide with a
 * shadow, over a 30 % black scrim. A scrim tap, a swipe to the left, Back or picking a page close it.
 */
@Composable
private fun PagesDrawer(
    visible: Boolean,
    state: PdfViewerState,
    currentPage: Int,
    width: Dp,
    topInset: Dp,
    bottomInset: Dp,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val density = LocalDensity.current
    val spec = if (reduceMotion) Motion.reduced<Float>() else slideSpec()
    var drag by remember { mutableStateOf(0f) }
    LaunchedEffect(visible) { if (visible) drag = 0f }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(spec), exit = fadeOut(spec)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - (-drag / with(density) { width.toPx() }).coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PdfViewerMetrics.DRAWER_SCRIM_ALPHA))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .clearAndSetSemantics { },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reduceMotion) fadeIn(spec) else slideInHorizontally(slideSpec()) { -it },
            exit = if (reduceMotion) fadeOut(spec) else slideOutHorizontally(slideSpec()) { -it },
        ) {
            PagesList(
                state = state,
                currentPage = currentPage,
                onPick = onPick,
                contentPadding = PaddingValues(
                    top = topInset + PdfViewerMetrics.THUMB_LIST_PADDING.dp,
                    bottom = bottomInset + PdfViewerMetrics.THUMB_LIST_PADDING.dp,
                ),
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = drag }
                    .dropShadow(RectangleShape, Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(4.dp, 0.dp), alpha = 0.18f))
                    .background(colors.pdfSidebar)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtMost(0f) },
                        onDragStopped = { velocity ->
                            val widthPx = with(density) { width.toPx() }
                            if (drag < -widthPx / 3f || velocity < -DRAWER_FLING_VELOCITY) {
                                onDismiss()
                            } else {
                                animate(drag, 0f, animationSpec = Motion.standard()) { value, _ -> drag = value }
                            }
                        },
                    )
                    .overlayPane(title = PdfViewerCopy.PAGES, onDismiss = onDismiss),
            )
        }
    }
}

/** A flick to the left this fast (px/s) closes the drawer. */
private const val DRAWER_FLING_VELOCITY = 1_200f
private const val DETAIL_SETTLE_MS = 160L
private const val SEARCH_DEBOUNCE_MS = 300L

package de.corespace.shroud.ui.media.compose

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.edit.MediaEditRenderer
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.media.ComposeDraft
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.media.edit.CaptionPlaceholder
import de.corespace.shroud.ui.media.edit.DarkMediaSurface
import de.corespace.shroud.ui.media.edit.MediaCropEditor
import de.corespace.shroud.ui.media.edit.MediaDrawEditor
import de.corespace.shroud.ui.media.edit.MediaEditorLayer
import de.corespace.shroud.ui.media.edit.MediaTextEditor
import de.corespace.shroud.ui.media.edit.bleed
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The photo compose screen (conversation-compose-media §9; iOS `MediaComposeOverlay`; design
 * m7AL9, xFhll, q6TJHc): the photo(s) about to be sent, a caption, Original / HD, and the crop,
 * draw, text and filter editors. [onSend] gets the caption, the quality and one [MediaEdits] per
 * photo in [draft] order — the empty value for an untouched photo, so it still goes out byte for
 * byte; the bake at full resolution is core's ([de.corespace.shroud.core.media.ImageEncoder] through
 * the registered `MediaEditRenderer`). [onAddMore] opens the picker again (unusable once ten
 * photos are staged), [onRemove] drops a photo from the draft by index, [onClose] is Back.
 *
 * **Owner W3-MEDIA-EDIT** (C12). The recipient's name comes from the open chat
 * (`messaging.controller`); the previews and filter names from `images.editRenderer` (K11).
 */
@Composable
fun MediaComposeScreen(
    draft: ComposeDraft,
    onSend: (caption: String, quality: MediaComposeQuality, edits: List<MediaEdits>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val messaging = container.messaging.controller
    val peerName = messaging.activePeerId.value?.let { messaging.username(it) }.orEmpty()
    MediaComposeContent(
        draft = draft,
        peerName = peerName,
        renderer = container.images.editRenderer,
        onSend = onSend,
        onAddMore = onAddMore,
        onRemove = onRemove,
        onClose = onClose,
    )
}

/** The draw editor as the compose screen opens it; tests stand in for Jetpack Ink, which needs a device. */
internal typealias DrawEditorSlot = @Composable (image: Bitmap, edits: MediaEdits, onCancel: () -> Unit, onDone: (MediaEdits) -> Unit) -> Unit

private val InkDrawEditor: DrawEditorSlot = { image, edits, onCancel, onDone -> MediaDrawEditor(image, edits, onCancel, onDone) }

/** How long a tool banner stays up (`MediaComposeOverlay.swift:838-850`). */
internal const val TOOL_BANNER_MS = 1_600L

/** The 40 ms pause that lets slider scrubs settle before a render (`MediaComposeOverlay.swift:798-800`). */
internal const val RENDER_DEBOUNCE_MS = 40L

/** [MediaComposeScreen] with its dependencies passed in: [peerName] and the [renderer] (K11). */
@Composable
internal fun MediaComposeContent(
    draft: ComposeDraft,
    peerName: String,
    renderer: MediaEditRenderer,
    onSend: (caption: String, quality: MediaComposeQuality, edits: List<MediaEdits>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    drawEditor: DrawEditorSlot = InkDrawEditor,
) {
    val state = remember { MediaComposeState(draft) }
    state.syncPhotos(draft.photos)
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerGeneration by remember { mutableIntStateOf(0) }
    var editorSession by remember { mutableIntStateOf(0) }

    val dismissKeyboard: () -> Unit = { focusManager.clearFocus() }
    val flashBanner: (String) -> Unit = { text ->
        val generation = ++bannerGeneration
        banner = text
        scope.launch {
            delay(TOOL_BANNER_MS)
            if (bannerGeneration == generation) banner = null
        }
    }
    val openEditor: (ComposeEditor) -> Unit = open@{ editor ->
        state.showFilters = false
        val photo = state.current
        editorSession++
        if (editor == ComposeEditor.Crop || photo == null) {
            state.editorBase = null
            state.activeEditor = editor
            return@open
        }
        // Draw and Text first render their canvas off the main thread (`MediaComposeOverlay.swift:604-624`).
        val base = state.annotationEdits(includingDrawing = editor == ComposeEditor.Text)
        scope.launch {
            val image = renderer.preview(photo.preview, base, photo.preview.longEdge())
            // A second tap, or a switch to another photo, may have landed meanwhile.
            if (state.activeEditor != null || state.current?.id != photo.id) return@launch
            state.editorBase = image
            state.activeEditor = editor
        }
    }

    // Bakes the current edits into the screen-sized preview, off the main thread; a newer edit or
    // photo cancels this one (`renderPreview`, `MediaComposeOverlay.swift:784-809`).
    val current = state.current
    val currentEdits = state.currentEdits
    LaunchedEffect(current?.id, currentEdits) {
        val photo = current ?: return@LaunchedEffect
        if (currentEdits.isIdentity) {
            state.rendered[photo.id] = photo.preview
            return@LaunchedEffect
        }
        delay(RENDER_DEBOUNCE_MS)
        val image = renderer.preview(photo.preview, currentEdits, photo.preview.longEdge())
        if (state.currentEdits == currentEdits && state.current?.id == photo.id) state.rendered[photo.id] = image
    }

    BackHandler(onBack = onClose)

    DarkMediaSurface {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                val topAlpha by animateFloatAsState(if (focused) 0.35f else 1f, Motion.scrim(), label = "topChrome")
                TopChrome(
                    peerName = peerName,
                    canAddMore = state.canAddMore,
                    modifier = Modifier.alpha(topAlpha),
                ) {
                    dismissKeyboard()
                    onAddMore()
                }
                AnimatedVisibility(
                    visible = !focused,
                    enter = expandVertically(Motion.scrim()) + fadeIn(Motion.scrim()),
                    exit = shrinkVertically(Motion.scrim()) + fadeOut(Motion.scrim()),
                ) {
                    EditChipRow(
                        state = state,
                        onClearEdits = { state.confirmClearEdits = true },
                        onSelect = { id ->
                            dismissKeyboard()
                            state.select(id)
                        },
                        onRemove = { index ->
                            state.prepareRemoval(index)
                            onRemove(index)
                        },
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(Unit) { detectTapGestures { dismissKeyboard() } },
                ) {
                    Crossfade(state.displayed, animationSpec = Motion.fade(), label = "composePhoto") { bitmap ->
                        if (bitmap != null) {
                            Image(
                                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Box {
                    BottomChrome(
                        state = state,
                        renderer = renderer,
                        focused = focused,
                        onFocusChanged = { focused = it },
                        onBack = {
                            dismissKeyboard()
                            onClose()
                        },
                        onOpenEditor = { editor ->
                            dismissKeyboard()
                            openEditor(editor)
                        },
                        onToggleFilters = {
                            dismissKeyboard()
                            state.showFilters = !state.showFilters
                        },
                        onToggleQuality = {
                            dismissKeyboard()
                            flashBanner(state.toggleQuality())
                        },
                        onCaptionOptions = { flashBanner("Caption options coming soon") },
                        onEmoji = {
                            haptic(Haptic.Light)
                            flashBanner(EMOJI_BANNER)
                        },
                        onDone = dismissKeyboard,
                        onSend = {
                            dismissKeyboard()
                            onSend(state.caption, state.quality, state.sendEdits())
                        },
                    )
                    ToolBanner(banner, Modifier.align(Alignment.TopCenter))
                }
            }

            ActionSheet(
                visible = state.confirmClearEdits,
                title = "Clear all edits to this photo?",
                message = "The crop, drawing, text and filter on this photo will be removed.",
                items = listOf(
                    ActionSheetItem("Clear Edits", destructive = true) {
                        state.setEdits(MediaEdits.Identity)
                        flashBanner("Edits cleared")
                    },
                ),
                onDismiss = { state.confirmClearEdits = false },
            )

            EditorLayer(state, renderer, editorSession, drawEditor)
        }
    }
}

/** Android's emoji hint (P14 decided; iOS says "use the globe key", `MediaComposeOverlay.swift:523`). */
internal const val EMOJI_BANNER = "Emoji keyboard: use your keyboard's emoji key"

private fun Bitmap.longEdge(): Int = max(width, height).coerceAtLeast(1)

/**
 * The open editor over the compose screen, kept on screen while it slides away. Each opening is a
 * fresh session ([session]): its live state starts from the photo's edits again. Back is the
 * editor's Cancel.
 */
@Composable
private fun EditorLayer(state: MediaComposeState, renderer: MediaEditRenderer, session: Int, drawEditor: DrawEditorSlot) {
    val editor = state.activeEditor
    val photo = state.current
    // What was last shown, kept for the exit animation (written while open only, like ActionSheet's).
    val shown = remember { ShownEditor() }
    if (editor != null && photo != null) {
        shown.editor = editor
        shown.photo = photo
        shown.base = state.editorBase
        shown.edits = state.currentEdits
    }
    val close: () -> Unit = { state.activeEditor = null }
    val done: (MediaEdits) -> Unit = { value ->
        state.setEdits(value)
        state.activeEditor = null
    }
    BackHandler(enabled = editor != null, onBack = close)
    MediaEditorLayer(visible = editor != null) {
        val lastEditor = shown.editor ?: return@MediaEditorLayer
        val lastPhoto = shown.photo ?: return@MediaEditorLayer
        val base = shown.base ?: lastPhoto.preview
        key(session) {
            when (lastEditor) {
                ComposeEditor.Crop -> MediaCropEditor(lastPhoto.preview, shown.edits, renderer, onCancel = close, onDone = done)
                ComposeEditor.Draw -> drawEditor(base, shown.edits, close, done)
                ComposeEditor.Text -> MediaTextEditor(base, shown.edits, onCancel = close, onDone = done)
            }
        }
    }
}

private class ShownEditor {
    var editor: ComposeEditor? = null
    var photo: PickedPhoto? = null
    var base: Bitmap? = null
    var edits: MediaEdits = MediaEdits.Identity
}

/**
 * Status-bar inset, then the recipient ("Sending to {peer}": Lucide arrow-up 14 + the name 16 sp
 * semibold) and the "Add" capsule (`chrome`, 30 dp, plus-bold 13 + "Add" 14 sp semibold; 40 % and
 * unusable once the album is full) (`MediaComposeOverlay.swift:229-277`).
 */
@Composable
private fun TopChrome(peerName: String, canAddMore: Boolean, modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(modifier.fillMaxWidth().background(Color.Black)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics { contentDescription = "Sending to $peerName" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(ShroudIcons.ArrowUp, tint = Color.White, size = 14.dp)
                ShroudText(peerName, inter(16f, FontWeight.SemiBold), Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .alpha(if (canAddMore) 1f else 0.4f)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(MediaColors.chrome)
                    .pressable(enabled = canAddMore, scale = 0.9f, dimming = 0f, onClick = onAdd)
                    .semantics { contentDescription = "Add more photos" }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(ShroudIcons.PlusBold, tint = Color.White, size = 13.dp)
                ShroudText("Add", inter(14f, FontWeight.SemiBold), Color.White, Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * The edit chip ("NO EDITS", or "EDITED" that offers to clear them) and, with several photos, the
 * thumbnail strip (`MediaComposeOverlay.swift:279-389`).
 */
@Composable
private fun EditChipRow(
    state: MediaComposeState,
    onClearEdits: () -> Unit,
    onSelect: (UUID) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(
            targetState = state.currentEdits.isIdentity,
            transitionSpec = {
                (scaleIn(Motion.snappy(), initialScale = 0.85f) + fadeIn(Motion.snappy())) togetherWith
                    (scaleOut(Motion.snappy(), targetScale = 0.85f) + fadeOut(Motion.snappy()))
            },
            contentAlignment = Alignment.CenterStart,
            label = "editChip",
        ) { identity ->
            if (identity) {
                EditChip(ShroudIcons.MagicWand, "NO EDITS", active = false)
            } else {
                EditChip(
                    ShroudIcons.ArrowUUpLeft,
                    "EDITED",
                    active = true,
                    modifier = Modifier
                        .pressable(scale = 0.9f, onClick = onClearEdits)
                        .semantics { contentDescription = "Clear edits" },
                )
            }
        }
        AnimatedVisibility(
            visible = state.photos.size > 1,
            enter = fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { -it } + expandVertically(Motion.standard()),
            exit = fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { -it } + shrinkVertically(Motion.standard()),
        ) {
            PhotoStrip(state, onSelect, onRemove)
        }
    }
}

@Composable
private fun EditChip(icon: ImageVector, text: String, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) MediaColors.blue else Color.White.copy(alpha = 0.85f)
    Row(
        modifier
            .clip(CircleShape)
            .background(MediaColors.chrome.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(icon, tint = color, size = 11.dp)
        ShroudText(text, inter(11f, FontWeight.SemiBold), color)
    }
}

/**
 * Thumbnails of the album, 54 dp radius 8, the one on screen ringed 2.5 dp `blue` and grown to
 * 1.05; tap to switch, long-press for "Remove". The strip scrolls edge to edge (`MediaComposeOverlay.swift:344-389`).
 */
@Composable
private fun PhotoStrip(state: MediaComposeState, onSelect: (UUID) -> Unit, onRemove: (Int) -> Unit) {
    val haptic = rememberHaptics()
    val bounds = remember { HashMap<UUID, Rect>() }
    var menuFor by remember { mutableStateOf<UUID?>(null) }
    val photos = state.photos
    val count = photos.size
    Row(
        Modifier
            .bleed(16.dp)
            .fillMaxWidth()
            .height(62.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        photos.forEachIndexed { index, photo ->
            val selected = index == state.selection
            val grown by animateFloatAsState(if (selected) 1.05f else 1f, Motion.snappy(), label = "stripThumb")
            val image = state.rendered[photo.id] ?: photo.preview
            val shape = RoundedCornerShape(8.dp)
            val remove = {
                onRemove(index)
            }
            Box(
                Modifier
                    .onGloballyPositioned { bounds[photo.id] = it.boundsInRoot() }
                    .scale(grown)
                    .size(54.dp)
                    .clip(shape)
                    .border(2.5.dp, if (selected) MediaColors.blue else Color.Transparent, shape)
                    .pressable(scale = 0.9f, dimming = 0f)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onLongClick = {
                            haptic(Haptic.LongPress)
                            menuFor = photo.id
                        },
                        hapticFeedbackEnabled = false,
                        onClick = { onSelect(photo.id) },
                    )
                    .semantics {
                        contentDescription = "Photo ${index + 1} of $count"
                        if (selected) this.selected = true
                        customActions = listOf(
                            CustomAccessibilityAction("Remove") {
                                remove()
                                true
                            },
                        )
                    },
            ) {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val menuPhoto = menuFor
    if (menuPhoto != null) {
        val index = photos.indexOfFirst { it.id == menuPhoto }
        ContextMenu(
            anchor = bounds[menuPhoto] ?: Rect.Zero,
            actions = listOf(
                MenuAction("Remove", ShroudIcons.Trash2, destructive = true) {
                    if (index >= 0) onRemove(index)
                },
            ),
            style = MenuStyle.Dark,
            onDismiss = { menuFor = null },
        )
    }
}

/**
 * The caption row, then (idle only) the filter strip and the tools, then the keyboard or
 * navigation bar room (`MediaComposeOverlay.swift:403-600`). The caption field stays the same
 * composable through focus changes; only the controls beside it change.
 */
@Composable
private fun BottomChrome(
    state: MediaComposeState,
    renderer: MediaEditRenderer,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenEditor: (ComposeEditor) -> Unit,
    onToggleFilters: () -> Unit,
    onToggleQuality: () -> Unit,
    onCaptionOptions: () -> Unit,
    onEmoji: () -> Unit,
    onDone: () -> Unit,
    onSend: () -> Unit,
) {
    val reduce = ShroudTheme.reduceMotion
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = focused,
                enter = expandHorizontally(Motion.easeOut(200)) + scaleIn(Motion.easeOut(200)) + fadeIn(Motion.easeOut(200)),
                exit = shrinkHorizontally(Motion.easeOut(200)) + scaleOut(Motion.easeOut(200)) + fadeOut(Motion.easeOut(200)),
            ) {
                Row {
                    RoundControl(ShroudIcons.List, "Caption options", background = MediaColors.chrome, tint = Color.White, onClick = onCaptionOptions)
                    Spacer(Modifier.size(10.dp))
                }
            }
            CaptionField(
                caption = state.caption,
                onCaptionChange = { state.caption = it },
                focused = focused,
                photoCount = state.photos.size,
                onFocusChanged = onFocusChanged,
                onEmoji = onEmoji,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(10.dp))
            AnimatedContent(
                targetState = focused,
                transitionSpec = {
                    (scaleIn(Motion.easeOut(200)) + fadeIn(Motion.easeOut(200))) togetherWith
                        (scaleOut(Motion.easeOut(200)) + fadeOut(Motion.easeOut(200)))
                },
                contentAlignment = Alignment.Center,
                label = "captionTrailing",
            ) { isFocused ->
                if (isFocused) {
                    RoundControl(ShroudIcons.Check, "Done", background = Color.White, tint = Color.Black, onClick = onDone)
                } else {
                    SendButton(MediaComposeState.sendLabel(state.photos.size), onSend)
                }
            }
        }

        AnimatedVisibility(
            visible = !focused,
            enter = expandVertically(Motion.scrim()) + fadeIn(Motion.scrim()),
            exit = shrinkVertically(Motion.scrim()) + fadeOut(Motion.scrim()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = state.current
                AnimatedVisibility(
                    visible = state.showFilters && current != null,
                    enter = if (reduce) fadeIn(Motion.reduced()) else fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it / 2 } + expandVertically(Motion.standard()),
                    exit = if (reduce) fadeOut(Motion.reduced()) else fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { it / 2 } + shrinkVertically(Motion.standard()),
                ) {
                    if (current != null) {
                        val edits = state.currentEdits
                        MediaFilterStrip(
                            image = current.preview,
                            renderer = renderer,
                            filter = edits.filter,
                            intensity = edits.filterIntensity,
                            onFilter = { preset ->
                                state.updateEdits { value ->
                                    if (preset == MediaFilter.None) value.copy(filter = preset) else value.copy(filter = preset, filterIntensity = 1f)
                                }
                            },
                            onIntensity = { value -> state.updateEdits { it.copy(filterIntensity = value) } },
                            modifier = Modifier.bleed(12.dp),
                        )
                    }
                }
                ToolsRow(state, onBack, onOpenEditor, onToggleFilters, onToggleQuality)
            }
        }

        // The keyboard's height while it is up, else the navigation bar, at least 8 dp (`:145-148`).
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets(bottom = 8.dp))))
    }
}

/**
 * The caption capsule (`chrome`, ≥ 44 dp, padding 14 × 10): "Add a caption..." up to four lines in
 * 16 sp white with a `blue` cursor; while typing an emoji button inside it, else the photo count in
 * a ringed 22 dp circle (`MediaComposeOverlay.swift:510-553`).
 */
@Composable
private fun CaptionField(
    caption: String,
    onCaptionChange: (String) -> Unit,
    focused: Boolean,
    photoCount: Int,
    onFocusChanged: (Boolean) -> Unit,
    onEmoji: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnFocus by rememberUpdatedState(onFocusChanged)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MediaColors.chrome)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NoLearningTextInput {
            BasicTextField(
                value = caption,
                onValueChange = onCaptionChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { currentOnFocus(it.isFocused) },
                textStyle = inter(16f).copy(color = Color.White),
                cursorBrush = SolidColor(MediaColors.blue),
                minLines = 1,
                maxLines = 4,
                decorationBox = { field ->
                    Box {
                        if (caption.isEmpty()) ShroudText(CAPTION_PLACEHOLDER, inter(16f), CaptionPlaceholder)
                        field()
                    }
                },
            )
        }
        AnimatedContent(
            targetState = focused,
            transitionSpec = { fadeIn(Motion.easeOut(200)) togetherWith fadeOut(Motion.easeOut(200)) },
            label = "captionInner",
        ) { isFocused ->
            if (isFocused) {
                Box(
                    Modifier
                        .size(28.dp)
                        .pressable(scale = 0.85f, haptic = Haptic.None, onClick = onEmoji)
                        .semantics { contentDescription = "Emoji" },
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudIcon(ShroudIcons.Smile, tint = Color.White.copy(alpha = 0.85f), size = 20.dp)
                }
            } else {
                PhotoCountBadge(photoCount)
            }
        }
    }
}

/** "Add a caption..." with three ASCII dots, as iOS (`MediaComposeOverlay.swift:513`). */
internal const val CAPTION_PLACEHOLDER = "Add a caption..."

@Composable
private fun PhotoCountBadge(count: Int) {
    Box(
        Modifier
            .size(22.dp)
            .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clearAndSetSemantics { contentDescription = MediaComposeState.countLabel(count) },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(Motion.snappy()) { if (up) it else -it } + fadeIn(Motion.snappy())) togetherWith
                    (slideOutVertically(Motion.snappy()) { if (up) -it else it } + fadeOut(Motion.snappy()))
            },
            label = "photoCount",
        ) { value ->
            ShroudText("$value", inter(12f, FontWeight.SemiBold), Color.White, textAlign = TextAlign.Center)
        }
    }
}

/** A 44 dp circle control: caption options (`chrome`) or Done (white with a black check) (`:456-490`). */
@Composable
private fun RoundControl(icon: ImageVector, label: String, background: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .pressable(scale = 0.85f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, tint = tint, size = 17.dp)
    }
}

/** Send: Lucide arrow-up 18 white in a 50 dp `blue` circle, medium haptic (`:492-506`). */
@Composable
private fun SendButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(MediaColors.blue)
            .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.Medium, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.ArrowUp, tint = Color.White, size = 18.dp)
    }
}

/**
 * Back, Crop, Draw, Text, Filters and the quality badge as glass circles scrolling sideways; a tool
 * in use stays lit in `blue` (`idleToolsRow`, `MediaComposeOverlay.swift:555-600`).
 */
@Composable
private fun ToolsRow(
    state: MediaComposeState,
    onBack: () -> Unit,
    onOpenEditor: (ComposeEditor) -> Unit,
    onToggleFilters: () -> Unit,
    onToggleQuality: () -> Unit,
) {
    val edits = state.currentEdits
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolCircle(ShroudIcons.CaretLeftBold, "Back", active = false, onClick = onBack)
        ToolCircle(ShroudIcons.Crop, "Crop", active = edits.hasCrop) { onOpenEditor(ComposeEditor.Crop) }
        ToolCircle(ShroudIcons.PencilCircle, "Draw", active = edits.hasDrawing) { onOpenEditor(ComposeEditor.Draw) }
        ToolCircle(ShroudIcons.TextT, "Text", active = edits.texts.isNotEmpty()) { onOpenEditor(ComposeEditor.Text) }
        ToolCircle(ShroudIcons.SlidersHorizontal, "Filters", active = edits.filter != MediaFilter.None || state.showFilters, onClick = onToggleFilters)
        QualityBadge(state.quality, onToggleQuality)
    }
}

/** Glass on black: fill white 12 %, 1 dp white 10 % rim (design m7AL9 tool circles). */
private val ToolGlassFill = Color(0x1FFFFFFF)
private val ToolGlassRim = Color(0x1AFFFFFF)

@Composable
private fun ToolCircle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptics()
    val ring by animateDpAsState(if (active) 1.5.dp else 0.dp, Motion.snappy(), label = "toolRing")
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ToolGlassFill)
            .border(1.dp, ToolGlassRim, CircleShape)
            .then(if (active) Modifier.border(ring, MediaColors.blue.copy(alpha = 0.85f), CircleShape) else Modifier)
            .pressable(scale = 1f, dimming = 0f, haptic = Haptic.None) {
                haptic(Haptic.Light)
                onClick()
            }
            .semantics {
                contentDescription = label
                if (active) selected = true
            },
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(icon, tint = if (active) MediaColors.blue else Color.White, size = 17.dp)
    }
}

/**
 * "Original" (`blue`, ringed) or "HD" (white) in a 44 dp glass circle, 11 sp bold shrinking to 70 %
 * to fit (`qualityBadge`, `MediaComposeOverlay.swift:686-719`).
 */
@Composable
private fun QualityBadge(quality: MediaComposeQuality, onClick: () -> Unit) {
    val original = quality == MediaComposeQuality.Original
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ToolGlassFill)
            .border(1.dp, ToolGlassRim, CircleShape)
            .then(if (original) Modifier.border(1.5.dp, MediaColors.blue.copy(alpha = 0.85f), CircleShape) else Modifier)
            .pressable(scale = 1f, dimming = 0f, haptic = Haptic.None, onClickLabel = QUALITY_CLICK_LABEL, onClick = onClick)
            .semantics { contentDescription = "Quality ${quality.label}" }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            quality.label,
            style = inter(11f, FontWeight.Bold).copy(color = if (original) MediaColors.blue else Color.White, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.7.sp, maxFontSize = 11.sp, stepSize = 0.1.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * TalkBack's "double-tap to …" for the quality badge; iOS's hint is "Tap to switch between Original
 * and HD" (`MediaComposeOverlay.swift:718`), which TalkBack would read after its own "double-tap to".
 */
internal const val QUALITY_CLICK_LABEL = "switch between Original and HD"

/**
 * The tool banner: 14 sp semibold white in a `chrome` 95 % capsule, its bottom 12 dp above the
 * caption row; no touches; fades in over 150 ms, out over 200 ms (`MediaComposeOverlay.swift:183-198`).
 */
@Composable
private fun ToolBanner(text: String?, modifier: Modifier = Modifier) {
    val shown = remember { mutableStateOf<String?>(null) }
    if (text != null) shown.value = text
    Box(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            // Zero height in the layout: the banner hangs above its anchor without moving anything.
            layout(placeable.width, 0) { placeable.place(0, -placeable.height - 12.dp.roundToPx()) }
        },
    ) {
        AnimatedVisibility(
            visible = text != null,
            enter = fadeIn(Motion.easeOut(150)),
            exit = fadeOut(Motion.easeOut(200)),
        ) {
            ShroudText(
                shown.value.orEmpty(),
                inter(14f, FontWeight.SemiBold),
                Color.White,
                Modifier
                    .clip(CircleShape)
                    .background(MediaColors.chrome.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

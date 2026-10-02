package de.corespace.shroud.ui.media.video

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoTrim
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ContextMenu
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.MenuAction
import de.corespace.shroud.ui.components.MenuRows
import de.corespace.shroud.ui.components.MenuStyle
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.media.PickedVideo
import de.corespace.shroud.ui.media.VideoComposeDraft
import de.corespace.shroud.ui.media.viewer.MediaBanner
import de.corespace.shroud.ui.media.viewer.MediaLayer
import de.corespace.shroud.ui.media.viewer.mediaBottomInset
import de.corespace.shroud.ui.media.viewer.mediaTopInset
import de.corespace.shroud.ui.media.viewer.rememberMediaBanner
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.toList
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID

/**
 * The video compose screen: trim, quality, mute, caption, send (conversation-compose-media §15;
 * iOS `VideoComposeOverlay`, `ios/shroud/ShroudUI/Components/VideoComposeOverlay.swift`). [onSend]
 * gets one [VideoSendPlan] per clip.
 *
 * Human: the confirm step between picking a clip and sending it, mirroring the photo compose
 * screen — black, Add at the top, a looping preview, a filmstrip to trim, the caption field with the
 * blue Send, and Back, Sound and Reset trim below. One quality for the whole send; the line under
 * the strip says how long the kept part is, at what resolution and roughly how big it will be, and
 * Send holds back (with the planner's sentence) while a clip cannot fit.
 *
 * Agent: [onClose] is iOS `onCancel` (the host cleans the movies up); [onRemove] takes the clip's
 * index and the host drops (and cleans) it; Add is disabled once [VideoComposeScreen]'s draft holds
 * ten clips. The caption field stays in the tree across focus changes (iOS froze when it did not).
 */
@Composable
fun VideoComposeScreen(
    draft: VideoComposeDraft,
    onSend: (List<VideoSendPlan>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val container = LocalAppContainer.current
    val services = remember(container) { ContainerVideoComposeServices(container) }
    VideoComposeContent(draft, onSend, onAddMore, onRemove, onClose, services)
}

/** [VideoComposeScreen] on explicit [services]; the entry point and the tests call it. */
@Composable
internal fun VideoComposeContent(
    draft: VideoComposeDraft,
    onSend: (List<VideoSendPlan>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    services: VideoComposeServices,
) {
    MediaLayer {
        ComposeBody(draft, onSend, onAddMore, onRemove, onClose, services)
    }
}

@Composable
private fun ComposeBody(
    draft: VideoComposeDraft,
    onSend: (List<VideoSendPlan>) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    services: VideoComposeServices,
) {
    val videos = draft.videos
    val clips = remember(videos) { videos.map { ComposeClip(it.id, it.probe, it.movie.uri) } }
    val ids = remember(clips) { clips.map { it.id } }
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val banner = rememberMediaBanner()
    val player = remember(services) { services.newPlayer() }
    val playback by player.state.collectAsState()
    val surface by player.player.collectAsState()
    val caption = rememberTextFieldState(draft.caption)
    val recipient = remember(services) { services.recipientName() }

    var selectedId by remember { mutableStateOf<UUID?>(null) }
    val trims = remember { mutableStateMapOf<UUID, VideoTrim>() }
    var muted by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var quality by remember { mutableStateOf(VideoUploadQuality.High) }
    val strips = remember { mutableStateMapOf<UUID, List<Bitmap>>() }
    var focused by remember { mutableStateOf(false) }
    var qualityAnchor by remember { mutableStateOf<Rect?>(null) }
    var qualityBounds by remember { mutableStateOf(Rect.Zero) }
    var removeFor by remember { mutableStateOf<Pair<Int, Rect>?>(null) }

    val selection = VideoComposeRules.selection(ids, selectedId)
    val current = clips.getOrNull(selection)
    val currentVideo = videos.getOrNull(selection)
    val currentTrim = current?.let { trims[it.id] ?: VideoComposeRules.fullTrim(it.probe) } ?: VideoTrim(0.0, 1.0)
    val currentMuted = current != null && current.id in muted
    val currentPlan = current?.let { VideoComposeRules.plan(it.probe, currentTrim, currentMuted, quality) }
    val sendBlocked = VideoComposeRules.sendBlocked(clips, current?.id, trims, muted, quality)

    // Every newly staged clip gets a full-range trim; dropped clips forget theirs (`syncTrims`, `:620-628`).
    LaunchedEffect(clips) {
        val next = VideoComposeRules.syncTrims(clips, trims)
        trims.keys.filter { it !in next }.forEach(trims::remove)
        next.forEach { (id, trim) -> if (trims[id] == null) trims[id] = trim }
        muted = VideoComposeRules.syncMuted(clips, muted)
        strips.keys.filter { it !in next }.forEach(strips::remove)
    }

    fun applyLoopRange() {
        val trim = current?.let { trims[it.id] ?: VideoComposeRules.fullTrim(it.probe) } ?: return
        player.loopRange = trim.start..trim.effectiveEnd
    }

    // Re-arm the player and the filmstrip whenever the shown clip changes (`loadCurrent`, `:630-643`).
    LaunchedEffect(current?.id) {
        player.teardown()
        val clip = current ?: return@LaunchedEffect
        applyLoopRange()
        player.start(clip.uri)
        applyLoopRange()
        // The preview honours this clip's own mute choice, not the last one's.
        player.setMuted(clip.id in muted)
        if (strips[clip.id] == null) {
            val frames = try {
                services.filmstrip(clip.uri, VideoComposeRules.STRIP_TILE_COUNT).toList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            strips[clip.id] = frames
        }
    }
    DisposableEffect(player) {
        onDispose { player.teardown() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    fun clearFocus() {
        if (focused) focusManager.clearFocus()
    }

    fun cancel() {
        clearFocus()
        player.pause()
        onClose()
    }

    BackHandler(enabled = true) {
        if (focused) focusManager.clearFocus() else cancel()
    }

    fun toggleMute() {
        val clip = current ?: return
        muted = if (clip.id in muted) muted - clip.id else muted + clip.id
        val now = clip.id in muted
        // The preview follows the choice, so "muted" is not a promise heard only after sending (`:659-660`).
        player.setMuted(now)
        banner.flash(scope, VideoComposeRules.muteBanner(now), VideoComposeRules.BANNER_MS)
    }

    fun resetTrim() {
        val clip = current ?: return
        trims[clip.id] = VideoComposeRules.fullTrim(clip.probe)
        applyLoopRange()
        player.seek(0.0, precise = true)
    }

    fun chooseQuality(next: VideoUploadQuality) {
        if (next == quality) return
        quality = next
        haptic(Haptic.Light)
        val plan = current?.let { VideoComposeRules.plan(it.probe, currentTrim, currentMuted, next) }
        banner.flash(scope, VideoComposeRules.qualityBanner(next, plan), VideoComposeRules.BANNER_MS)
    }

    fun send() {
        sendBlocked?.let {
            banner.flash(scope, it, VideoComposeRules.BANNER_MS)
            return
        }
        // The Send button's press-down Medium is the haptic; no second one here (`:678`).
        player.pause()
        val plans = VideoComposeRules.plans(clips, caption.text.toString(), trims, muted, quality) { clip, trim ->
            posterJpeg(videos.firstOrNull { it.id == clip.id }, strips[clip.id].orEmpty(), trim, services)
        }
        onSend(plans)
    }

    fun removeVideo(index: Int) {
        selectedId = VideoComposeRules.selectionAfterRemoval(ids, index, selection, selectedId)
        onRemove(index)
    }

    val topInset = mediaTopInset()
    val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val bottomInset = mediaBottomInset()
    val bottomSpace = if (imeBottom > 0.dp) imeBottom else bottomInset

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { clearFocus() } },
    ) {
        Column(Modifier.fillMaxSize().animateContentSize(Motion.scrim())) {
            TopBar(
                recipient = recipient,
                topInset = topInset,
                addEnabled = videos.size < VideoComposeRules.MAX_VIDEOS,
                dimmed = focused,
                onAdd = {
                    clearFocus()
                    onAddMore()
                },
            )
            if (videos.size > 1 && !focused) {
                ClipStrip(
                    videos = videos,
                    selection = selection,
                    onSelect = { id ->
                        clearFocus()
                        selectedId = id
                    },
                    onLongPress = { index, bounds -> removeFor = index to bounds },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ClipPreview(
                    surface = surface,
                    poster = currentVideo?.poster,
                    ready = playback.isReady,
                    playing = playback.isPlaying,
                    muted = currentMuted,
                    onTap = {
                        // While typing, a tap on the clip only drops the keyboard (`:369-373`).
                        if (focused) {
                            focusManager.clearFocus()
                        } else {
                            player.toggle()
                            haptic(Haptic.Light)
                        }
                    },
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(visible = !focused && current != null, enter = fadeIn(Motion.scrim()), exit = fadeOut(Motion.scrim())) {
                    if (current != null) {
                        TrimSection(
                            frames = strips[current.id].orEmpty(),
                            duration = current.probe.durationSeconds,
                            trim = currentTrim,
                            onTrimChange = { trims[current.id] = it },
                            playhead = playback.currentTime,
                            onSeek = { seconds ->
                                player.pause()
                                player.seek(seconds)
                            },
                            onScrubEnd = {
                                applyLoopRange()
                                player.play()
                            },
                            selectionLabel = VideoComposeRules.selectionLabel(currentTrim, currentPlan),
                            planFails = currentPlan?.isFailure == true,
                            trimmed = VideoComposeRules.isTrimmed(currentTrim, current.probe.durationSeconds),
                            quality = quality,
                            sendBlocked = sendBlocked,
                            onQualityBounds = { qualityBounds = it },
                            onQuality = { qualityAnchor = qualityBounds },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    CaptionField(
                        state = caption,
                        count = videos.size,
                        focused = focused,
                        onFocusChange = { focused = it },
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedContent(
                        targetState = focused,
                        transitionSpec = {
                            (scaleIn(Motion.easeOut(200)) + fadeIn(Motion.easeOut(200))) togetherWith
                                (scaleOut(Motion.easeOut(200)) + fadeOut(Motion.easeOut(200)))
                        },
                        label = "videoComposeTrailing",
                    ) { isFocused ->
                        if (isFocused) {
                            DoneButton { focusManager.clearFocus() }
                        } else {
                            SendButton(count = videos.size, enabled = sendBlocked == null) {
                                clearFocus()
                                send()
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = !focused, enter = fadeIn(Motion.scrim()), exit = fadeOut(Motion.scrim())) {
                    ToolRow(
                        muted = currentMuted,
                        canMute = current?.probe?.hasAudio == true,
                        trimmed = current != null && VideoComposeRules.isTrimmed(currentTrim, current.probe.durationSeconds),
                        onBack = { cancel() },
                        onMute = { toggleMute() },
                        onReset = { resetTrim() },
                    )
                }
                Spacer(Modifier.height(bottomSpace))
            }
        }

        MediaBanner(banner.text, bottomPadding = 240.dp, fill = MediaColors.chrome.copy(alpha = 0.95f))

        qualityAnchor?.let { anchor ->
            ContextMenu(
                anchor = anchor,
                actions = VideoUploadQuality.entries.map { item ->
                    val plan = current?.let { VideoComposeRules.plan(it.probe, currentTrim, currentMuted, item) }
                    MenuAction(
                        title = VideoComposeRules.qualityTitle(item, plan),
                        enabled = plan?.isSuccess == true,
                        checked = item == quality,
                        onClick = { chooseQuality(item) },
                    )
                },
                style = MenuStyle.Dark,
                onDismiss = { qualityAnchor = null },
                paneTitle = "Video quality",
                rows = MenuRows.Trailing,
            )
        }
        removeFor?.let { (index, bounds) ->
            ContextMenu(
                anchor = bounds,
                actions = listOf(MenuAction("Remove", ShroudIcons.Trash2, destructive = true) { removeVideo(index) }),
                style = MenuStyle.Dark,
                onDismiss = { removeFor = null },
                paneTitle = "Video options",
            )
        }
    }
}

/** The bubble poster: the filmstrip tile nearest the trim start, else the clip's first frame; JPEG 70 (`posterJPEG`, `:720-730`). */
private fun posterJpeg(video: PickedVideo?, frames: List<Bitmap>, trim: VideoTrim, services: VideoComposeServices): ByteArray? {
    val duration = video?.probe?.durationSeconds ?: return null
    val index = VideoComposeRules.posterIndex(trim.start, duration, frames.size)
    val bitmap = index?.let(frames::get) ?: video.poster ?: return null
    return runCatching { services.compressJpeg(bitmap, VideoComposeRules.POSTER_JPEG_QUALITY) }.getOrNull()
}

@Composable
private fun TopBar(recipient: String?, topInset: Dp, addEnabled: Boolean, dimmed: Boolean, onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .alpha(if (dimmed) 0.35f else 1f),
    ) {
        Spacer(Modifier.height(topInset))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { if (recipient != null) contentDescription = "Sending to $recipient" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recipient != null) {
                    ShroudIcon(ShroudIcons.ArrowUp, Color.White, size = 14.dp)
                    ShroudText(recipient, inter(16f, FontWeight.SemiBold), Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // A 44 dp target around the 30 dp capsule; disabled at 40 % once the send is full (`:229-252`).
            Box(
                Modifier
                    .alpha(if (addEnabled) 1f else 0.4f)
                    .pressable(enabled = addEnabled, scale = 0.9f, dimming = 0f, onClick = onAdd)
                    .semantics { contentDescription = "Add more videos" }
                    .padding(vertical = 7.dp),
            ) {
                Row(
                    Modifier
                        .height(30.dp)
                        .clip(CircleShape)
                        .background(MediaColors.chrome)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShroudIcon(ShroudIcons.PlusBold, Color.White, size = 13.dp)
                    ShroudText("Add", inter(14f, FontWeight.SemiBold), Color.White)
                }
            }
        }
    }
}

/** Posters of everything queued for this send; tap to switch, long-press to remove (`videoStrip`, `:256-283`). */
@Composable
private fun ClipStrip(
    videos: List<PickedVideo>,
    selection: Int,
    onSelect: (UUID) -> Unit,
    onLongPress: (Int, Rect) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().height(78.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
            val selected = index == selection
            val scale by animateFloatAsState(if (selected) 1.05f else 1f, Motion.snappy(), label = "clipScale")
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val haptic = rememberHaptics()
            Box(
                Modifier
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .pressable(scale = 0.9f, dimming = 0f)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = {
                            haptic(Haptic.LongPress)
                            onLongPress(index, bounds)
                        },
                        onClick = { onSelect(video.id) },
                    )
                    .semantics {
                        contentDescription = "Video ${index + 1} of ${videos.size}"
                        stateDescription = spokenDuration(video.probe.durationSeconds)
                        this.selected = selected
                    }
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(2.5.dp, if (selected) MediaColors.blue else Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.BottomStart,
            ) {
                video.poster?.let { poster ->
                    val image = remember(poster) { poster.asImageBitmap() }
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                ShroudText(
                    ChatVideoPlayer.timeLabel(video.probe.durationSeconds),
                    inter(9f, FontWeight.Bold, tabularDigits = true),
                    Color.White,
                    Modifier
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.5.dp),
                )
            }
        }
    }
}

/** The looping preview, its play badge and the MUTED label (`preview`, `:297-361`). */
@Composable
private fun ClipPreview(
    surface: Player?,
    poster: Bitmap?,
    ready: Boolean,
    playing: Boolean,
    muted: Boolean,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .clearAndSetSemantics {
                contentDescription = "Video preview"
                stateDescription = (if (playing) "Playing" else "Paused") + if (muted) ", sound off" else ""
                role = Role.Button
                onClick {
                    onTap()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            surface != null -> VideoSurface(surface, Modifier.fillMaxSize())
            poster != null -> {
                val image = remember(poster) { poster.asImageBitmap() }
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            else -> Spinner(Color.White, size = 24.dp)
        }
        AnimatedVisibility(
            visible = ready && !playing,
            enter = scaleIn(Motion.fade(), initialScale = 0.8f) + fadeIn(Motion.fade()),
            exit = scaleOut(Motion.fade(), targetScale = 0.8f) + fadeOut(Motion.fade()),
        ) {
            // Black 32 % over a light frost; Android draws the frost as white 10 % (no blur under it, §15.4).
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.PlayFill, Color.White, size = 26.dp, modifier = Modifier.offset(x = 2.dp))
            }
        }
        AnimatedVisibility(
            visible = muted,
            enter = fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.snappy()),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShroudIcon(ShroudIcons.SpeakerSlashFill, Color.White, size = 11.dp)
                ShroudText(MUTED, inter(11f, FontWeight.Bold), Color.White)
            }
        }
    }
}

@Composable
private fun TrimSection(
    frames: List<Bitmap>,
    duration: Double,
    trim: VideoTrim,
    onTrimChange: (VideoTrim) -> Unit,
    playhead: Double,
    onSeek: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    selectionLabel: String,
    planFails: Boolean,
    trimmed: Boolean,
    quality: VideoUploadQuality,
    sendBlocked: String?,
    onQualityBounds: (Rect) -> Unit,
    onQuality: () -> Unit,
) {
    val images: List<ImageBitmap> = remember(frames) { frames.map { it.asImageBitmap() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VideoTrimStrip(
            frames = images,
            duration = duration,
            trim = trim,
            onTrimChange = onTrimChange,
            playhead = playhead,
            onSeek = onSeek,
            onScrubEnd = onScrubEnd,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // A cross-fade, never a per-digit morph (that is a CPU blur on iOS, `:423-424`).
            ShroudText(
                selectionLabel,
                inter(12f, FontWeight.Medium, tabularDigits = true),
                if (planFails) MediaColors.warningText else Color.White.copy(alpha = 0.75f),
                Modifier.weight(1f),
                maxLines = 2,
            )
            QualityLabel(quality, onBounds = onQualityBounds, onClick = onQuality)
            AnimatedVisibility(visible = trimmed, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
                ShroudText(TRIMMED, inter(10f, FontWeight.Bold), MediaColors.blue)
            }
        }
        if (sendBlocked != null) {
            ShroudText(sendBlocked, inter(12f, FontWeight.Medium), MediaColors.warningText, Modifier.fillMaxWidth())
        }
    }
}

/** "{label} ⌃⌄" in a 26 dp chrome capsule; opens the quality menu (`qualityMenu`, `:497-520`). */
@Composable
private fun QualityLabel(quality: VideoUploadQuality, onBounds: (Rect) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pressable(scale = 0.94f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = "Video quality, ${quality.label}" }
            .height(26.dp)
            .clip(CircleShape)
            .background(MediaColors.chrome)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(quality.label, inter(12f, FontWeight.SemiBold), Color.White)
        ShroudIcon(ShroudIcons.ChevronsUpDown, Color.White, size = 9.dp)
    }
}

/** The stable caption field: never destroyed on focus change (`captionField`, `:452-479`). */
@Composable
private fun CaptionField(
    state: TextFieldState,
    count: Int,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                textStyle = inter(16f).copy(color = Color.White),
                cursorBrush = SolidColor(MediaColors.blue),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
                decorator = { field ->
                    Box {
                        if (state.text.isEmpty()) ShroudText(CAPTION_PLACEHOLDER, inter(16f), Color.White.copy(alpha = 0.45f))
                        field()
                    }
                },
            )
        }
        if (!focused && count > 1) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    .clearAndSetSemantics { contentDescription = "$count videos" },
                contentAlignment = Alignment.Center,
            ) {
                ShroudText("$count", inter(12f, FontWeight.SemiBold), Color.White)
            }
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .pressable(scale = 0.85f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = "Done" }
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.Check, Color.Black, size = 17.dp)
    }
}

@Composable
private fun SendButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    // Disabled at 40 % while a clip cannot fit (`:493-494`).
    Box(
        Modifier
            .size(50.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled = enabled, scale = 0.85f, dimming = 0f, haptic = Haptic.Medium, onClick = onClick)
            .semantics { contentDescription = VideoComposeRules.sendLabel(count) }
            .clip(CircleShape)
            .background(MediaColors.blue),
        contentAlignment = Alignment.Center,
    ) {
        ShroudIcon(ShroudIcons.ArrowUp, Color.White, size = 18.dp)
    }
}

/** Back, Sound and Reset trim in 44 dp glass circles (`toolRow`, `:551-614`). */
@Composable
private fun ToolRow(muted: Boolean, canMute: Boolean, trimmed: Boolean, onBack: () -> Unit, onMute: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolCircle(ShroudIcons.CaretLeftBold, "Back", onClick = onBack)
        ToolCircle(
            if (muted) ShroudIcons.SpeakerSlashFill else ShroudIcons.SpeakerHighFill,
            if (muted) "Sound off" else "Sound on",
            active = muted,
            enabled = canMute,
            onClick = onMute,
        )
        ToolCircle(MediaViewIcons.ArrowCounterClockwise, "Reset trim", enabled = trimmed, onClick = onReset)
    }
}

@Composable
private fun ToolCircle(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .pressable(enabled = enabled, scale = 1f, dimming = 0f, onClick = onClick)
            .semantics { contentDescription = label }
            .glassSurface(CircleShape, GlassStyle.Regular, interactive = true),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = icon, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) }, label = "toolGlyph") { glyph ->
            ShroudIcon(glyph, if (active) MediaColors.blue else Color.White, size = 17.dp, modifier = Modifier.alpha(if (enabled) 1f else 0.35f))
        }
    }
}

/** iOS `Duration.formatted(.units(allowed: [.minutes, .seconds], width: .wide))`, rounded down (`:276-279`). */
internal fun spokenDuration(seconds: Double): String {
    val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
    val minutes = total / 60
    val rest = total % 60
    fun unit(value: Long, one: String, many: String) = "$value ${if (value == 1L) one else many}"
    return when {
        minutes > 0 && rest > 0 -> "${unit(minutes, "minute", "minutes")}, ${unit(rest, "second", "seconds")}"
        minutes > 0 -> unit(minutes, "minute", "minutes")
        else -> unit(rest, "second", "seconds")
    }
}

/** iOS copy (`VideoComposeOverlay.swift:455`, `:336`, `:428`). */
internal const val CAPTION_PLACEHOLDER = "Add a caption..."
internal const val MUTED = "MUTED"
internal const val TRIMMED = "TRIMMED"

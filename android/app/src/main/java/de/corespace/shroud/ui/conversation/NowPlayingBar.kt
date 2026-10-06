package de.corespace.shroud.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.files.AudioFileCopy
import de.corespace.shroud.core.media.files.AudioTags
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.voice.AudioFilePlaybackCoordinator
import de.corespace.shroud.core.voice.AudioFilePlaybackState
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.ui.components.RollingText
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.util.UUID
import kotlin.math.floor

/**
 * The now-playing bar (docs/file-sharing.md §11.6): while an audio file of this chat is active
 * (playing or paused part-way) and its bubble is not on screen, a 52 dp card under the header —
 * bubble-in fill, radius 16, elevation 2, 16 dp from the sides: a 34 dp accent play/pause circle,
 * the title (14 medium) over `{ar} · {elapsed} / {duration}` (12, secondary), the speed chip (files
 * of 10 minutes or more), close (✕, stops), and a 2 dp accent progress line along the bottom edge.
 * A tap on the text scrolls to the bubble. Voice notes don't get the bar.
 *
 * The progress line is read in the draw phase every frame while the file plays, so it glides without
 * recomposing the bar; the text moves once a second.
 */
@Composable
internal fun NowPlayingBarHost(vm: ConversationViewModel, list: LazyListState, top: () -> Dp, modifier: Modifier = Modifier) {
    val player = vm.audioFiles ?: return
    val state = player.state.collectAsState()
    val activeId by remember(state) { derivedStateOf { state.value.activeId } }
    val message = activeId?.let { id -> vm.messages.firstOrNull { it.id == id && !it.deleted } }
    val bubbleOnScreen by remember(list, activeId) {
        derivedStateOf { activeId?.let { NowPlayingBarMath.isOnScreen(list.layoutInfo, it.toString()) } ?: false }
    }
    var shown by remember { mutableStateOf<ChatMessage?>(null) }
    if (message != null) shown = message
    val visible = message != null && !bubbleOnScreen
    val reduceMotion = ShroudTheme.reduceMotion
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = top() + NowPlayingBarMetrics.BelowHeader),
        enter = fadeIn(Motion.respecting(reduceMotion, Motion.snappy())) +
            slideInVertically(Motion.respecting(reduceMotion, Motion.snappy())) { -it / 2 },
        exit = fadeOut(Motion.respecting(reduceMotion, Motion.snappy())) +
            slideOutVertically(Motion.respecting(reduceMotion, Motion.snappy())) { -it / 2 },
    ) {
        val current = shown ?: return@AnimatedVisibility
        NowPlayingBar(
            message = current,
            state = state,
            player = player,
            onJump = { vm.jumpToQuoted(current.id) },
        )
    }
}

@Composable
internal fun NowPlayingBar(message: ChatMessage, state: State<AudioFilePlaybackState>, player: AudioFilePlaybackCoordinator, onJump: () -> Unit) {
    val colors = ShroudTheme.colors
    val id = message.id
    val shape = RoundedCornerShape(NowPlayingBarMetrics.Radius)
    val isPlaying by remember(state, id) { derivedStateOf { state.value.activeId == id && state.value.isPlaying } }
    val speedApplies by remember(state) { derivedStateOf { state.value.speedApplies } }
    val rate by remember(state) { derivedStateOf { state.value.rate } }
    val playerDurationMs by remember(state) { derivedStateOf { (state.value.duration * 1000).toLong() } }
    // Whole seconds: the text recomposes once a second.
    val elapsedMs by remember(state) { derivedStateOf { floor(state.value.currentTime).toLong() * 1000 } }
    val title = AudioTags.clean(message.audioTitle) ?: message.fileName ?: FileCopy.FILE
    val durationMs = message.durationMs?.toLong()?.takeIf { it >= 1 } ?: playerDurationMs.takeIf { it >= 1 }
    val detail = AudioFileCopy.nowPlayingDetail(message.audioArtist, elapsedMs, durationMs)

    val frameClock = remember(id) { mutableLongStateOf(0L) }
    LaunchedEffect(id, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) withFrameNanos { frameClock.longValue = it }
    }

    Box(
        Modifier
            .padding(horizontal = NowPlayingBarMetrics.Side)
            .fillMaxWidth()
            .height(NowPlayingBarMetrics.Height)
            .shadow(NowPlayingBarMetrics.Elevation, shape)
            .clip(shape)
            .background(colors.bubbleIncoming),
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 9.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val playLabel = if (isPlaying) AudioFileCopy.PAUSE else AudioFileCopy.PLAY
            Box(
                Modifier
                    .size(NowPlayingBarMetrics.PlayButton)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .pressable(scale = 0.9f, haptic = Haptic.Light, onClickLabel = playLabel) { player.toggle(id) }
                    .semantics { contentDescription = playLabel },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(if (isPlaying) ShroudIcons.PauseFill else ShroudIcons.PlayFill, Color.White, size = 15.dp)
            }
            Column(
                Modifier
                    .weight(1f)
                    .pressable(scale = 1f, dimming = 0.3f, haptic = Haptic.None, onClick = onJump),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                ShroudText(title, inter(14f, FontWeight.Medium), colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                RollingText(detail, inter(12f, tabularDigits = true), colors.textSecondary, overflow = TextOverflow.Ellipsis)
            }
            if (speedApplies) {
                val rateLabel = VoicePlaybackCoordinator.rateLabel(rate)
                Box(
                    Modifier
                        .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.Light, onClickLabel = AudioFileCopy.PLAYBACK_SPEED) { player.cycleRate() }
                        .semantics {
                            contentDescription = AudioFileCopy.PLAYBACK_SPEED
                            stateDescription = rateLabel
                        }
                        .padding(4.dp),
                ) {
                    BasicText(
                        rateLabel,
                        style = inter(11f, FontWeight.Bold, tabularDigits = true).copy(color = colors.accentText),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clip(CircleShape).background(colors.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .pressable(scale = 0.85f, haptic = Haptic.Light, onClickLabel = AudioFileCopy.STOP) { player.stop() }
                    .semantics { contentDescription = AudioFileCopy.STOP },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.XBold, colors.textSecondary, size = 13.dp)
            }
        }
        val accent = colors.accent
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(NowPlayingBarMetrics.ProgressLine)) {
            frameClock.longValue
            state.value
            val fraction = player.liveProgress(id).toFloat().coerceIn(0f, 1f)
            if (fraction <= 0f) return@Canvas
            val y = size.height / 2
            drawLine(accent, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = size.height)
        }
    }
}

/** The bar's sizes (§11.6, Android column). */
internal object NowPlayingBarMetrics {
    val Height = 52.dp
    val Radius = 16.dp
    val Side = 16.dp
    val Elevation = 2.dp
    val PlayButton = 34.dp
    val ProgressLine = 2.dp
    val BelowHeader = 8.dp
}

/** When the bar shows (§11.6), pure. */
object NowPlayingBarMath {
    /** Rows at least this many px into the content area count as on screen; less is a sliver under the header or composer. */
    const val MIN_VISIBLE_PX = 24

    /**
     * Whether the row keyed [key] shows inside the list's content area — not in its padding, where
     * the header and the composer cover it.
     */
    fun isOnScreen(info: LazyListLayoutInfo, key: String): Boolean {
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        val end = info.viewportEndOffset - info.afterContentPadding
        return isOnScreen(item.offset, item.size, start = 0, end = end)
    }

    /** An item at [offset] of [size] overlaps [start]…[end] by at least [MIN_VISIBLE_PX] (or wholly, when smaller). */
    fun isOnScreen(offset: Int, size: Int, start: Int, end: Int): Boolean {
        val overlap = minOf(offset + size, end) - maxOf(offset, start)
        return overlap >= minOf(MIN_VISIBLE_PX, size.coerceAtLeast(1))
    }

    /** Whether the bar shows: [activeId] is a message of this chat and its row is not on screen. */
    fun shows(activeId: UUID?, messages: List<ChatMessage>, bubbleOnScreen: Boolean): Boolean =
        activeId != null && !bubbleOnScreen && messages.any { it.id == activeId && !it.deleted }
}

package de.corespace.shroud.ui.conversation.reactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.OverlayLayer
import de.corespace.shroud.ui.conversation.menu.EmojiGlyph
import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An emoji on its way from where it was picked (the menu's bar, a double tap) to the chip it becomes
 * (`ReactionFlight`, `ReactionFlight.swift:1-25`; conversation-thread §14.6).
 *
 * Human: the chip is laid out first with its emoji hidden, reports where that emoji sits, and the
 * flying copy arcs there and hands over without a seam: same place, same size, then the chip's own
 * emoji shows.
 *
 * Agent: rects are root px. [ReactionFlightState] owns it. Never prints the emoji.
 */
@Immutable
data class ReactionFlight(
    val id: Long,
    val emoji: String,
    val messageId: UUID,
    /** Root frame the emoji leaves from. */
    val from: Rect,
    /** Scale at the start, relative to [ReactionFlightPath.FONT_SIZE] (a double tap starts big, 1.6). */
    val fromScale: Float = 1f,
    /** Root frame of the chip's emoji, once the chip reported it. */
    val to: Rect? = null,
    /** The chip never appeared: the flight fades where it is. */
    val fading: Boolean = false,
) {
    override fun toString(): String = "ReactionFlight(id=$id, landed=${to != null}, fading=$fading)"
}

/**
 * The conversation's one flight at a time (`reactionFlight`, `ConversationView.swift:31-32,
 * 2149-2177`).
 *
 * Human: a pick starts a flight; the chip that receives it reports where its emoji is drawn
 * ([land]) and the layer animates the landing, then hands over ([landed]). A chip that never shows
 * up — the bubble scrolled away, the save refused at once — lets the flight fade after 1.2 s.
 *
 * Agent: main thread. [scope] only runs the timeout (no frame clock needed). While [flight] is set,
 * the thread tells the chips which (message, emoji) to keep hidden — W3-THREAD-BUBBLES' chips read
 * `LocalReactionFlightTarget` and answer through `BubbleContext.reportChipBounds`, which calls
 * [land]. Snapshot state: the layer and the thread recompose on a change.
 */
@Stable
class ReactionFlightState(private val scope: CoroutineScope) {
    var flight: ReactionFlight? by mutableStateOf(null)
        private set

    private var nextId = 0L

    /** Starts a flight of [emoji] onto [messageId]'s chip from [from] (`beginReactionFlight`, CV:2149-2160). */
    fun begin(emoji: String, messageId: UUID, from: Rect, scale: Float = 1f) {
        val started = ReactionFlight(id = ++nextId, emoji = emoji, messageId = messageId, from = from, fromScale = scale)
        flight = started
        scope.launch {
            delay(ReactionFlightPath.TIMEOUT_MS)
            val current = flight
            if (current?.id != started.id || current.to != null) return@launch
            // `withAnimation(Motion.fade) { reactionFlight = nil }`: fade where it is, then let go.
            flight = current.copy(fading = true)
            delay(FADE_MS)
            if (flight?.id == started.id) flight = null
        }
    }

    /**
     * The landing chip of [messageId] reported where its emoji sits (root px): fly there; a later
     * report while in the air re-aims (the thread moved under it) (`landReactionFlight`, CV:2162-2177).
     */
    fun land(messageId: UUID, frame: Rect) {
        val current = flight ?: return
        if (current.messageId != messageId || current.fading || current.to == frame) return
        flight = current.copy(to = frame)
    }

    /** The landing animation of flight [id] finished: the chip's own emoji shows from here. */
    fun landed(id: Long) {
        if (flight?.id == id) flight = null
    }

    /** Drops any flight at once (the chat closed or locked). */
    fun clear() {
        flight = null
    }

    private companion object {
        /** `Motion.fade` (0.18 s ease-out) plus a frame. */
        const val FADE_MS = 200L
    }
}

/**
 * The arc of a flight (`ReactionFlightPath`, `ReactionFlight.swift:79-108`). Pure; coordinates in
 * px, `maxLift` the 90 pt bow cap in px.
 */
object ReactionFlightPath {
    /** Emoji size in flight at scale 1 — the bar's size, so a pick lifts off unchanged (`:59`). */
    const val FONT_SIZE = 26f

    /** The chip's emoji size, where the flight ends (`ReactionChipView.emojiFontSize`). */
    const val CHIP_EMOJI_SIZE = 17f

    /** `landedScale` = 17 / 26 (`:61`). */
    const val LANDED_SCALE = CHIP_EMOJI_SIZE / FONT_SIZE

    /** The bow never rises more than this (dp) above the higher end (`:96`). */
    const val MAX_LIFT_DP = 90f

    /** A flight whose chip never reported is let go after this (`ConversationView.swift:2155-2159`). */
    const val TIMEOUT_MS = 1_200L

    /** A spring may overshoot a little (`:93`). */
    const val MAX_PROGRESS = 1.15f

    /** The double tap's start: a 44 dp square around the finger, at 1.6× (`quickReact`, CV:2139-2147). */
    const val QUICK_START_SIDE_DP = 44f
    const val QUICK_START_SCALE = 1.6f

    /** Point on the quadratic curve bowed upward: `P = u²F + 2utC + t²T` (`:92-102`). */
    fun point(progress: Float, from: Offset, to: Offset?, maxLift: Float): Offset {
        val end = to ?: from
        val t = progress.coerceIn(0f, MAX_PROGRESS)
        // The bow grows with the distance, so a short hop stays short.
        val lift = min(maxLift, hypot(end.x - from.x, end.y - from.y) * 0.35f)
        val control = Offset((from.x + end.x) / 2f, min(from.y, end.y) - lift)
        val u = 1f - t
        return Offset(
            u * u * from.x + 2f * u * t * control.x + t * t * end.x,
            u * u * from.y + 2f * u * t * control.y + t * t * end.y,
        )
    }

    /** Shrinks from [fromScale] to the chip's size (`:103`). */
    fun scale(progress: Float, fromScale: Float, toScale: Float = LANDED_SCALE): Float {
        val t = progress.coerceIn(0f, MAX_PROGRESS)
        return fromScale + (toScale - fromScale) * min(t, 1f)
    }
}

/**
 * Draws the flying emoji above everything, never taking touches and hidden from TalkBack
 * (`ReactionFlightLayer`, `ReactionFlight.swift:52-76`).
 *
 * Agent: declare it after the message menu's layer, so it draws over the menu (a pick leaves the bar
 * while the menu is still fading out, CV:222-223). When [ReactionFlight.to] first arrives it animates
 * the landing with `Motion.reactionFlight` and then calls [onLanded] with the flight's id; later
 * reports re-aim it mid-air. A [ReactionFlight.fading] flight fades out with `Motion.fade`.
 */
@Composable
fun ReactionFlightLayer(flight: ReactionFlight?, onLanded: (Long) -> Unit) {
    var shown by remember { mutableStateOf<ReactionFlight?>(null) }
    if (flight != null) shown = flight
    OverlayLayer(active = flight != null, modal = false) {
        val current = shown ?: return@OverlayLayer
        FlyingEmoji(current, onLanded)
    }
}

@Composable
private fun FlyingEmoji(flight: ReactionFlight, onLanded: (Long) -> Unit) {
    val density = LocalDensity.current
    val landed by rememberUpdatedState(onLanded)
    val progress = remember(flight.id) { Animatable(0f) }
    val hasTarget = flight.to != null
    LaunchedEffect(flight.id, hasTarget) {
        if (!hasTarget) return@LaunchedEffect
        progress.animateTo(1f, Motion.reactionFlight())
        landed(flight.id)
    }
    val alpha by animateFloatAsState(if (flight.fading) 0f else 1f, Motion.fade(), label = "reactionFlightFade")
    var origin by remember { mutableStateOf(Offset.Zero) }
    val glyphBox = 34.dp
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val maxLift = with(density) { ReactionFlightPath.MAX_LIFT_DP.dp.toPx() }
        val from = flight.from.center - origin
        val to = flight.to?.center?.minus(origin)
        val t = progress.value
        val point = ReactionFlightPath.point(t, from, to, maxLift)
        val scale = ReactionFlightPath.scale(t, flight.fromScale)
        EmojiGlyph(
            flight.emoji,
            ReactionFlightPath.FONT_SIZE,
            Modifier
                .offset {
                    val half = glyphBox.toPx() / 2f
                    IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt())
                }
                .size(glyphBox)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
        )
    }
}

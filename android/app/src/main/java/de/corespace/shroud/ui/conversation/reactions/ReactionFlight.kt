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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
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
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An emoji on its way from where it was picked (the menu's bar, a double tap) to the chip it becomes
 * (`ReactionFlight`, `ReactionFlight.swift:1-25`; conversation-thread §14.6).
 *
 * Human: the chip is laid out first with its emoji hidden ([LocalReactionFlightTarget]), reports
 * where that emoji sits, and the flying copy arcs there and hands over without a seam: same place,
 * same size, then the chip's own emoji shows.
 *
 * Agent: rects are root px. The conversation screen owns the state: sets [to] from the chip's
 * report, animates the landing, clears the flight when it lands — or fades it out ([fading]) when the
 * chip never shows up (scrolled away, the save refused at once) after [ReactionFlightPath.TIMEOUT_MS].
 */
@Immutable
data class ReactionFlight(
    val id: Long,
    val emoji: String,
    val messageId: UUID,
    /** Root frame the emoji leaves from. */
    val from: Rect,
    /** Scale at the start, relative to [ReactionFlightPath.FONT_SIZE] (a double tap starts big). */
    val fromScale: Float = 1f,
    /** Root frame of the chip's emoji, once it is laid out. */
    val to: Rect? = null,
    /** The chip never appeared: the flight fades where it is. */
    val fading: Boolean = false,
) {
    val target: ReactionFlightTarget get() = ReactionFlightTarget(messageId, emoji)

    /** Never prints the emoji. */
    override fun toString(): String = "ReactionFlight(id=$id, landed=${to != null}, fading=$fading)"
}

/** Which chip a flight lands on; that chip hides its emoji until the flight is over (`:27-31`). */
@Immutable
data class ReactionFlightTarget(val messageId: UUID, val emoji: String) {
    override fun toString(): String = "ReactionFlightTarget(messageId=$messageId)"
}

/**
 * The flight in progress, for the reaction chips (`reactionFlightTarget` environment, `:33-35`).
 *
 * Contract for the chips (W3-THREAD-BUBBLES): while this equals (message, emoji), that emoji is drawn
 * invisible and reports its exact root frame through
 * `BubbleContext.reportChipBounds(messageId, ReactionFlightPath.emojiFrameId(emoji), bounds)`. A chip
 * that only reports its own bounds (any other chip id) still gets a landing, on the emoji's place
 * by the chip metrics ([ReactionFlightPath.approximateEmojiFrame]).
 */
val LocalReactionFlightTarget: ProvidableCompositionLocal<ReactionFlightTarget?> = compositionLocalOf { null }

/**
 * The arc of a flight (`ReactionFlightPath`, `ReactionFlight.swift:79-108`). Pure; coordinates in
 * px, [maxLift] the 90 pt bow cap in px.
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

    /** The chip id under which a chip reports the exact frame of [emoji] while a flight targets it. */
    fun emojiFrameId(emoji: String): String = "emoji:$emoji"

    /**
     * Where the [index]-th emoji of a chip sits, from the chip's frame and the chip metrics
     * (conversation-thread §14.2: leading padding 6, each emoji a 20 dp box with 2 dp padding on both
     * sides, 2 dp between emoji buttons, vertically centred in the 30 dp chip). [dp] = px per dp.
     */
    fun approximateEmojiFrame(chip: Rect, index: Int, dp: Float): Rect {
        val left = chip.left + (6f + index * (24f + 2f) + 2f) * dp
        val top = chip.center.y - 10f * dp
        return Rect(left, top, left + 20f * dp, top + 20f * dp)
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

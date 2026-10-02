package de.corespace.shroud.ui.conversation.menu

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * The open long-press menu: the message as it was when the hold began and where its bubble sat
 * (`FocusedMessageMenu`, `ConversationView.swift:1957-1964`). The hero reads the decoded-image cache
 * itself and never decodes on the open path (CV:1974-1977).
 *
 * @property sourceInRoot the bubble's slot in the list, root px ([MessageMenuLayout.sourceFrame]).
 */
@Immutable
data class FocusedMessageMenu(val message: ChatMessage, val sourceInRoot: Rect) {
    override fun toString(): String = "FocusedMessageMenu(message=${message.id})"
}

/**
 * The long-press menu's session and its one animated value (`focusedMenu`, `menuProgress`,
 * `menuOpenedAt`, `menuAnimationGeneration`, `ConversationView.swift:33-40, 1966-2025`;
 * conversation-thread §16.1).
 *
 * Human: opening snaps the bubble to its list slot (progress 0) and lifts it with the Telegram
 * spring; closing drops it back with a 0.2 s ease-in-out and only then hands over to the list's own
 * bubble, which sits exactly under it. A close that a new open overtakes never clears the new menu.
 * The release of the finger that opened the menu lands on the backdrop; taps there in the first
 * 0.4 s are ignored.
 *
 * Agent: main thread; snapshot state. [open] and [dismiss] only change state; [MessageMenuAnimator]
 * (composed by the screen) runs the animation in the composition's frame clock and calls
 * [finishClosing]. [now] is injectable for tests.
 */
@Stable
class MessageMenuState(private val now: () -> Long = { SystemClock.uptimeMillis() }) {
    /** The open menu, also while it animates closed; null when the list's own bubble shows. */
    var session: FocusedMessageMenu? by mutableStateOf(null)
        private set

    /** 0 = bubble in its list slot, 1 = the resting stack. Written by [MessageMenuAnimator]. */
    var progress: Float by mutableFloatStateOf(0f)

    /** The menu is dropping back into the list. */
    var isClosing: Boolean by mutableStateOf(false)
        private set

    /** Bumped by every open and close, so a stale close can't clear a newer menu (CV:1970-1972, 2003-2016). */
    var generation: Int by mutableIntStateOf(0)
        private set

    private var openedAt: Long? = null

    /** A menu owns the screen: the hold's release must not open links, viewers or a second menu (CV:1349-1353). */
    val isOpen: Boolean get() = session != null

    /** Lifts [message]'s bubble out of [sourceInRoot] (`openMessageMenu`, CV:1966-1999). */
    fun open(message: ChatMessage, sourceInRoot: Rect) {
        generation++
        // Start at the list slot, without animation; the animator lifts it from there.
        progress = 0f
        isClosing = false
        session = FocusedMessageMenu(message, sourceInRoot)
        openedAt = now()
    }

    /** Drops the bubble back into the list (`dismissMessageMenu`, CV:2001-2025). */
    fun dismiss() {
        if (session == null || isClosing) return
        generation++
        isClosing = true
    }

    /**
     * A tap on the dimmed thread: closes the menu unless it is the release of the hold that opened it,
     * within [TAP_GRACE_MS] (`menuTapGrace`, CV:1936-1937, 2054-2061).
     */
    fun backdropTapped() {
        val opened = openedAt ?: return
        if (now() - opened <= TAP_GRACE_MS) return
        dismiss()
    }

    /** The close of [generation] has run its course: the list's bubble takes over (CV:2014-2024). */
    fun finishClosing(generation: Int) {
        if (generation != this.generation || !isClosing) return
        session = null
        isClosing = false
        progress = 0f
        openedAt = null
    }

    /** Gone at once, without animation: the chat closed or locked. */
    fun clear() {
        generation++
        session = null
        isClosing = false
        progress = 0f
        openedAt = null
    }

    companion object {
        /** How long after opening the backdrop ignores taps (`menuTapGrace`, CV:1937). */
        const val TAP_GRACE_MS = 400L

        /** The hand-over to the list after the drop: its duration plus 20 ms (CV:2015). */
        const val HAND_OVER_SLACK_MS = 20L
    }
}

/**
 * Runs [state]'s animation: the lift with `Motion.menuLift` (Telegram's mass 5 / k 900 / c 104
 * spring), the drop with `Motion.menuDrop` (ease-in-out 0.2 s), both `Motion.reduced` under Reduce
 * Motion; after a drop, [MessageMenuState.HAND_OVER_SLACK_MS] later, the session ends
 * (`ConversationView.swift:1991-1993, 2008-2024`). Compose it while the screen is up.
 */
@Composable
fun MessageMenuAnimator(state: MessageMenuState, reduceMotion: Boolean) {
    val generation = state.generation
    LaunchedEffect(generation) {
        if (state.session == null) return@LaunchedEffect
        if (!state.isClosing) {
            animate(state.progress, 1f, animationSpec = Motion.respecting(reduceMotion, Motion.menuLift())) { value, _ ->
                state.progress = value
            }
        } else {
            animate(state.progress, 0f, animationSpec = Motion.respecting(reduceMotion, Motion.menuDrop())) { value, _ ->
                state.progress = value
            }
            delay(MessageMenuState.HAND_OVER_SLACK_MS)
            state.finishClosing(generation)
        }
    }
}

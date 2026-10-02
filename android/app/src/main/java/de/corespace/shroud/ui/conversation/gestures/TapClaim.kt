package de.corespace.shroud.ui.conversation.gestures

import android.os.SystemClock

/**
 * Lets a control *inside* a bubble claim a tap the row's own gesture also sees (`MessageTapClaim`,
 * `MessageLongPressGesture.swift:39-62`; conversation-thread §13.2).
 *
 * Human: Compose's consumption already keeps most inner taps from the row (a clickable consumes the
 * release, so the row's tap detector never fires), but a few inner controls act without consuming —
 * the link opener, the transfer disc's cancel, a reaction chip's face area — so they claim, and the
 * row checks the claim before acting. Without it, tapping a photo's reaction chip also opened the photo.
 *
 * Agent: one per conversation screen; main thread only. WRITES a timestamp; [isClaimed] is true for
 * [WINDOW_MS] after the last [claim]. [now] is injectable for tests.
 */
class TapClaim(private val now: () -> Long = { SystemClock.uptimeMillis() }) {
    private var claimedAt: Long? = null

    fun claim() {
        claimedAt = now()
    }

    /** True when something inside the bubble just handled this tap. */
    fun isClaimed(): Boolean {
        val at = claimedAt ?: return false
        return now() - at < WINDOW_MS
    }

    companion object {
        /** How long a claim suppresses the row's own tap — one event loop's worth, generously (`:46`). */
        const val WINDOW_MS = 400L
    }
}

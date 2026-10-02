package de.corespace.shroud.ui.calls

import androidx.compose.ui.graphics.Color
import de.corespace.shroud.core.calls.ActiveCall
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.calls.CallUiState
import de.corespace.shroud.core.calls.ScreenShareQuality
import de.corespace.shroud.core.calls.label
import de.corespace.shroud.core.net.CallModality
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * What the call screen shows, derived from the controller's state (iOS `InCallOverlay`,
 * `InCallOverlay.swift:111-144`; calls §8.1). Pure, so the rules are unit-tested.
 *
 * @property showsRemoteVideo their camera is on and its frames arrive (never a black or stale frame).
 * @property showsRemoteScreen they share their screen and its frames arrive; not while the call ends.
 * @property showsLocalVideo our camera is on and its frames arrive; not while the call ends.
 * @property sharing our screen goes out or is starting; not while the call ends.
 * @property picture a picture of theirs fills the screen: the name docks in the corner.
 * @property badgeRoom the safety number is not compared and the phone has one: the badge's room.
 * @property unverified the "Not verified" badge shows (it goes at once when the call ends).
 * @property chromeAway the controls and the name stepped aside over their screen.
 */
data class CallScreenFlags(
    val showsRemoteVideo: Boolean,
    val showsRemoteScreen: Boolean,
    val showsLocalVideo: Boolean,
    val sharing: Boolean,
    val picture: Boolean,
    val ending: Boolean,
    val badgeRoom: Boolean,
    val unverified: Boolean,
    val chromeAway: Boolean,
)

/** The call screen's pure rules and copy (iOS `InCallOverlay.swift`, line numbers per member; calls §8). */
object CallScreenRules {
    /** How long the controls stay up over their shared screen once nothing is touched (`chromeLinger`, :60). */
    const val CHROME_LINGER_MS = 4_000L

    /** How long the badge reads "Not verified" before it closes onto its shield (`safetyBadgeLinger`, :558). */
    const val SAFETY_BADGE_LINGER_MS = 3_000L

    /** The screen covers the app after its fade in; only then the status bar turns light (`fadeInCover`, :97). */
    const val FADE_IN_COVER_MS = 600L

    /** Share Screen waits for its menu to go before the consent shows (`toggleShare`, :478-485). */
    const val CONSENT_DELAY_MS = 350L

    fun flags(state: CallUiState, call: ActiveCall, hasSafetyNumber: Boolean, chromeHidden: Boolean): CallScreenFlags {
        val ending = call.phase == CallPhase.Ending
        val remoteVideo = state.remoteVideoTrack != null && !call.remoteCameraOff && state.remoteVideoLive
        val remoteScreen = state.remoteScreenTrack != null && call.remoteSharingScreen && state.remoteScreenLive && !ending
        val localVideo = call.isVideoEnabled && !ending && state.localVideoTrack != null && state.localVideoLive
        val badgeRoom = !call.safetyVerified && hasSafetyNumber
        return CallScreenFlags(
            showsRemoteVideo = remoteVideo,
            showsRemoteScreen = remoteScreen,
            showsLocalVideo = localVideo,
            sharing = (call.isSharingScreen || call.screenShareStarting) && !ending,
            picture = CallScreenMetrics.nameBelongsInCorner(remotePicture = remoteVideo || remoteScreen),
            ending = ending,
            badgeRoom = badgeRoom,
            unverified = badgeRoom && !ending,
            chromeAway = chromeHidden && remoteScreen,
        )
    }

    /** Their shared screen is mounted from the moment they say they share (:170). */
    fun mountsRemoteScreen(state: CallUiState, call: ActiveCall): Boolean =
        state.remoteScreenTrack != null && call.remoteSharingScreen && call.phase != CallPhase.Ending

    /** Ringing either way or connecting: the face breathes (`isRinging`, :819-821). */
    fun isRinging(phase: CallPhase): Boolean =
        phase == CallPhase.OutgoingRinging || phase == CallPhase.IncomingRinging || phase == CallPhase.Connecting

    /** The status line without the running timer (`statusLine(for:)`, :848-863). */
    fun statusLine(call: ActiveCall): String = when (call.phase) {
        CallPhase.Idle -> ""
        CallPhase.OutgoingRinging -> "Calling…"
        CallPhase.IncomingRinging -> if (call.modality == CallModality.Video) "Incoming video call" else "Incoming call"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Active -> "Connected"
        CallPhase.Ending -> call.endedText ?: "Call ended"
    }

    /** What the status line shows at [now]: "Reconnecting…", the running time, or [statusLine] (`statusLabel`, :826-846). */
    fun status(call: ActiveCall, now: Instant): CallStatusText {
        if (call.phase == CallPhase.Active && call.reconnecting) return CallStatusText("Reconnecting…", isTimer = false)
        val start = call.startedAt
        if (call.phase == CallPhase.Active && start != null) {
            return CallStatusText(elapsed(Duration.between(start, now).seconds), isTimer = true)
        }
        return CallStatusText(statusLine(call), isTimer = false)
    }

    /** "0:42", "12:05", then "1:02:03" past an hour (`elapsed(from:now:)`, :866-872). */
    fun elapsed(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Their picture hides the face's muted badge, so the block says it (:586-594). */
    fun showsMutedLine(call: ActiveCall, flags: CallScreenFlags): Boolean =
        call.remoteMicMuted && call.phase == CallPhase.Active && (flags.showsRemoteVideo || flags.showsRemoteScreen)

    /** "You're speaking" only while the call runs with an open mic (:597-601). */
    fun showsSpeaking(call: ActiveCall): Boolean = call.phase == CallPhase.Active && !call.isMuted

    fun mutedText(name: String): String = "$name is muted"

    /**
     * The Speaker control is on while audio really plays on the speaker: the call asked for it and
     * the route left the earpiece (`CallSystem.isOnEarpiece`; calls §6.4). A refused speaker
     * endpoint therefore never shows as on.
     */
    fun speakerShownOn(speakerOn: Boolean, onEarpiece: Boolean): Boolean = speakerOn && !onEarpiece

    /** Video is offered when this call can carry our camera, or it is on already (:759). */
    fun videoAvailable(call: ActiveCall): Boolean = call.canVideo || call.isVideoEnabled

    /** Share can be used, or stopped (:425-426). */
    fun shareAvailable(call: ActiveCall): Boolean = call.canShareScreen || call.isSharingScreen || call.screenShareStarting

    fun shareLabel(sharing: Boolean): String = if (sharing) "Screen sharing" else "Share your screen"

    fun shareValue(sharing: Boolean, quality: ScreenShareQuality): String = if (sharing) "On, ${quality.label}" else quality.label

    const val SHARE_UNAVAILABLE_HINT = "Not available yet."

    /** The sharing pill's words (:360, :384). */
    fun sharingPillText(starting: Boolean): String = if (starting) "Starting…" else "Sharing screen"

    fun sharingPillLabel(starting: Boolean): String = if (starting) "Starting to share your screen" else "You’re sharing your screen"

    /** Twelve groups of five, four to a row (`SafetyNumberPopover.rows`, :1078-1081). */
    fun safetyRows(number: String): List<List<String>> =
        number.split(' ').filter { it.isNotEmpty() }.chunked(4)

    fun safetyCompareText(name: String): String =
        "Compare it with $name: read it out on this call, or check it in person. If it matches, nobody else can listen in."

    /** iOS's hint "Shows the safety number to compare with <name>.", as TalkBack's "Double-tap to …" (:530). */
    fun safetyAction(name: String): String = "show the safety number to compare with $name"

    /** The controls step aside over their screen after [CHROME_LINGER_MS]; never with TalkBack, never with the number open (:399-412). */
    fun chromeShouldLinger(showsRemoteScreen: Boolean, chromeHidden: Boolean, safetyOpen: Boolean, touchExploration: Boolean): Boolean =
        showsRemoteScreen && !chromeHidden && !safetyOpen && !touchExploration

    /**
     * The top shade's stops, generalised for Android's top inset [topInset] (iOS tunes them on a
     * 59 pt inset: 0.42 and 0.70 at [drop] 0; :621-643, calls §8.8): black 62 % at the top, 58 %
     * at `(t + 58 + drop) / (t + 220 + drop)`, 30 % at `(t + 136 + drop) / (t + 220 + drop)`, 0 at
     * the bottom. The shade runs down to `t + 220 + drop`.
     */
    fun shadeStops(topInset: Float, drop: Float): List<Pair<Float, Color>> {
        val span = topInset + 220f + drop
        return listOf(
            0f to Color.Black.copy(alpha = 0.62f),
            (topInset + 58f + drop) / span to Color.Black.copy(alpha = 0.58f),
            (topInset + 136f + drop) / span to Color.Black.copy(alpha = 0.3f),
            1f to Color.Black.copy(alpha = 0f),
        )
    }

    fun shadeHeight(topInset: Float, drop: Float): Float = topInset + 220f + drop

    /** How far the docked name and the shade sit lower: the badge's room and the sharing pill's (:182). */
    fun shadeDrop(badgeRoom: Boolean, sharing: Boolean): Float =
        (if (badgeRoom) CallScreenMetrics.SAFETY_BADGE_RESERVE else 0f) + (if (sharing) CallScreenMetrics.SHARING_INSET else 0f)
}

/** A status line and whether it is the running timer (tabular digits that roll). */
data class CallStatusText(val text: String, val isTimer: Boolean)

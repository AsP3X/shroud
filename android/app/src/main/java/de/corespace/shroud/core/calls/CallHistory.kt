package de.corespace.shroud.core.calls

import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * One call in the Calls tab (iOS `CallController.RecentCall`, `CallController.swift:97-122`;
 * calls §3.3). [id] is the server's call id, the same for both people and all their devices.
 *
 * @property peerDeleted their account is gone: no name, and no calling back.
 * @property isOutgoing we placed it, from this or another of our devices.
 * @property status how it ended in the server's words ("ended", "missed", "rejected", "cancelled",
 *   "busy"; [CallHistory.recentStatus]), or "answered_elsewhere" when another of our devices took it.
 * @property connected media connected at some point. An "ended" call that never did is not a completed one.
 * @property duration how long the two talked, from the answer to the end; null when they never did.
 * @property at when it was placed (a call that just ended here: when it ended, until the server's row replaces it).
 */
data class RecentCall(
    val id: UUID,
    val peerUserId: UUID,
    val peerUsername: String,
    val peerDeleted: Boolean = false,
    val modality: CallModality,
    val isOutgoing: Boolean,
    val status: String,
    val connected: Boolean,
    val duration: Duration? = null,
    val at: Instant,
) {
    /** Never prints the name. */
    override fun toString(): String = "RecentCall(id=$id, status=$status, outgoing=$isOutgoing, at=$at)"
}

/**
 * Calls in a row with the same person within an hour, newest first: one row for a single call, a
 * section for several (iOS `CallsView.CallRun`, `CallsView.swift:11-19`).
 */
data class CallRun(val calls: List<RecentCall>) {
    /** The oldest call's id: a new call with the same person joins the section rather than replacing it. */
    val id: UUID get() = calls.last().id
    val latest: RecentCall get() = calls.first()
}

/** The Calls tab's rows and labels (iOS `CallController.swift:392-417, 2099-2103`; `CallsView.swift:21-37, 404-474`). */
object CallHistory {
    /** Calls per history page; the server allows up to 100 (`CallController.swift:124-125`). */
    const val PAGE_SIZE = 100

    /** The most a section spans, from its newest call to its oldest (`CallsView.swift:21-22`). */
    val RUN_SPAN: Duration = Duration.ofHours(1)

    const val DELETED_ACCOUNT = "Deleted account"

    /**
     * A server call as the Calls tab lists it, from [me]'s side; null while it rings or runs
     * (`CallController.recentCall(from:me:myDevice:connectedHere:)`, `CallController.swift:392-417`).
     * What this phone saw ([connectedHere]) beats the server's "answered", but only for the device
     * that ran the call.
     */
    fun recentCall(
        call: CallDto,
        me: UUID,
        myDevice: UUID?,
        connectedHere: Boolean?,
        nameOf: (UUID) -> String? = { null },
    ): RecentCall? {
        if (call.isLive) return null
        val outgoing = call.callerUserId == me
        val peerId = if (outgoing) call.calleeUserId else call.callerUserId
        val deleted = if (outgoing) call.calleeDeleted else call.callerDeleted
        val serverName = if (outgoing) call.calleeUsername else call.callerUsername
        val known = nameOf(peerId)?.takeIf { it.isNotBlank() && it != "Contact" }
        val peerName = if (deleted) DELETED_ACCOUNT else known ?: serverName
        val ranHere = myDevice != null && (call.callerDeviceId == myDevice || call.calleeDeviceId == myDevice)
        val connected = (if (ranHere) connectedHere else null) ?: (call.answeredAt != null)
        val answered = call.answeredAt
        val ended = call.endedAt
        val duration = if (connected && call.status == "ended" && answered != null && ended != null) {
            Duration.between(answered, ended).let { if (it.isNegative) Duration.ZERO else it }
        } else {
            null
        }
        return RecentCall(
            id = call.id,
            peerUserId = if (outgoing) call.calleeUserId else call.callerUserId,
            peerUsername = if (deleted) DELETED_ACCOUNT else peerName ?: "Contact",
            peerDeleted = deleted,
            modality = call.callModality,
            isOutgoing = outgoing,
            status = recentStatus(call.status, call.endedReason),
            connected = connected,
            duration = duration,
            at = call.createdAt,
        )
    }

    /**
     * How Recents files a call the server ended: as the server has it, except a decline it files
     * as missed, which reads "Declined" there as on the call screen (`CallController.swift:2099-2103`).
     */
    fun recentStatus(status: String, reason: String?): String = if (reason == "declined") "rejected" else status

    /**
     * [calls] (newest first) cut into runs of consecutive calls with the same person, each within
     * [RUN_SPAN] of the run's newest call (`CallsView.runs(of:)`, `CallsView.swift:24-37`).
     */
    fun runs(calls: List<RecentCall>): List<CallRun> {
        val runs = ArrayList<MutableList<RecentCall>>()
        for (call in calls) {
            val run = runs.lastOrNull()
            if (run != null && run[0].peerUserId == call.peerUserId && Duration.between(call.at, run[0].at) <= RUN_SPAN) {
                run += call
            } else {
                runs += mutableListOf(call)
            }
        }
        return runs.map { CallRun(it.toList()) }
    }

    /** A call of theirs that we never took: rang out, or they gave up (`CallsView.isMissed`, `CallsView.swift:413-416`). */
    fun isMissed(call: RecentCall): Boolean = !call.isOutgoing && (call.status == "missed" || call.status == "cancelled")

    /** "0:42", "4:12", "1:02:03" — as the call screen's timer counts it (`CallsView.durationLabel`, `CallsView.swift:458-467`). */
    fun durationLabel(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
        val hours = total / 3600
        val minutes = total / 60 % 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    fun durationLabel(duration: Duration): String = durationLabel(duration.toMillis() / 1000.0)

    /** "voice" / "video" (`CallsView.swift:418-420`). */
    fun kindLabel(call: RecentCall): String = if (call.modality == CallModality.Video) "video" else "voice"

    /**
     * How it went, in a few words (`CallsView.outcome`, `CallsView.swift:422-447`): an unanswered
     * call of ours is "No answer", theirs is "Missed", a call that talked shows how long, one that
     * never connected reads "Failed". [spoken] gives TalkBack's long forms.
     */
    fun outcome(call: RecentCall, spoken: Boolean = false): String = when (call.status) {
        "missed" -> if (call.isOutgoing) "No answer" else "Missed"
        "rejected" -> "Declined"
        "cancelled" -> if (call.isOutgoing) "Cancelled" else "Missed"
        "busy" -> "Busy"
        "ended" -> {
            val duration = call.duration
            when {
                call.connected && duration != null -> if (spoken) spokenDuration(duration) else durationLabel(duration)
                call.connected -> "Completed"
                else -> if (spoken) "not connected" else "Failed"
            }
        }
        // Shows only until the call ends; the server's row then has its length.
        "answered_elsewhere" -> if (spoken) "answered on another device" else "Other device"
        else -> call.status.replaceFirstChar { it.uppercaseChar() }
    }

    /** "Outgoing voice · 4:12", "Incoming video · Missed" (`CallsView.statusText`, `CallsView.swift:403-411`). */
    fun statusLine(call: RecentCall): String = "${if (call.isOutgoing) "Outgoing" else "Incoming"} ${kindLabel(call)} · ${outcome(call)}"

    /**
     * "4 minutes, 12 seconds" (`Duration.formatted(.units(allowed: [.hours, .minutes, .seconds],
     * width: .wide))`, `CallsView.swift:469-472`): zero units left out, "0 seconds" for nothing.
     */
    fun spokenDuration(duration: Duration): String {
        val total = duration.seconds.coerceAtLeast(0)
        val parts = listOf(total / 3600 to "hour", total / 60 % 60 to "minute", total % 60 to "second")
            .filter { it.first > 0 }
            .map { (value, unit) -> "$value $unit${if (value == 1L) "" else "s"}" }
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(", ")
    }
}

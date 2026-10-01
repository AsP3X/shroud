package de.corespace.shroud.core.calls.signal

/**
 * Why a call ended, as this device tells its user — iOS `CallEndReason`
 * (`ios/shroud/Services/Calls/CallSignal.swift:200-258`), the table in `docs/calls.md` and calls
 * §4.14, verbatim. Web `endedText` (`web/src/calls/logic.ts`) agrees on every row the server sends.
 */
sealed interface CallEndReason {
    val title: String

    /**
     * What to say when the call screen closes (`CallSignal.swift:228-235`). Null closes it with
     * nothing said: the caller hung up their own ring, or this phone only heard that another of
     * its devices declined.
     */
    val announcement: String? get() = title

    data object Ended : CallEndReason {
        override val title = "Call ended"
    }

    data object Declined : CallEndReason {
        override val title = "Declined"
    }

    data object NoAnswer : CallEndReason {
        override val title = "No answer"
    }

    data object Missed : CallEndReason {
        override val title = "Missed call"
    }

    data object Busy : CallEndReason {
        override val title = "Busy"
    }

    data object AnsweredElsewhere : CallEndReason {
        override val title = "Answered on another device"
    }

    data object DeclinedElsewhere : CallEndReason {
        override val title = "Declined on another device"
        override val announcement: String? get() = null
    }

    data object ConnectionLost : CallEndReason {
        override val title = "Connection lost"
    }

    data object CouldNotConnect : CallEndReason {
        override val title = "Couldn't connect"
    }

    data object Cancelled : CallEndReason {
        override val title = "Call ended"
        override val announcement: String? get() = null
    }

    data class Failed(val message: String) : CallEndReason {
        override val title: String get() = message
    }

    companion object {
        /**
         * How the server's `status` / `ended_reason` reads on this side of the call
         * (`CallSignal.swift:237-257`); null while it still rings or runs.
         *
         * | status | reason | caller | callee |
         * | --- | --- | --- | --- |
         * | ringing, active | any | null | null |
         * | rejected | any | Declined | DeclinedElsewhere |
         * | missed | declined | Declined | DeclinedElsewhere |
         * | missed | other | NoAnswer | Missed |
         * | cancelled | connection_lost | ConnectionLost | Missed |
         * | cancelled | other | Cancelled | Missed |
         * | busy | any | Busy | Busy |
         * | ended | connection_lost | ConnectionLost | ConnectionLost |
         * | anything else | | Ended | Ended |
         */
        fun from(status: String, reason: String?, isOutgoing: Boolean): CallEndReason? = when {
            status == "ringing" || status == "active" -> null
            status == "rejected" || (status == "missed" && reason == "declined") -> if (isOutgoing) Declined else DeclinedElsewhere
            status == "missed" -> if (isOutgoing) NoAnswer else Missed
            status == "cancelled" && reason == "connection_lost" -> if (isOutgoing) ConnectionLost else Missed
            status == "cancelled" -> if (isOutgoing) Cancelled else Missed
            status == "busy" -> Busy
            status == "ended" && reason == "connection_lost" -> ConnectionLost
            else -> Ended
        }
    }
}

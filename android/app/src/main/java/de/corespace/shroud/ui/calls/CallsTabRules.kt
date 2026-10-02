package de.corespace.shroud.ui.calls

import de.corespace.shroud.core.calls.CallHistory
import de.corespace.shroud.core.calls.CallHistoryState
import de.corespace.shroud.core.calls.CallRun
import de.corespace.shroud.core.calls.RecentCall
import de.corespace.shroud.core.messaging.ChatListFormatting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the Calls tab shows (`CallsView.body`, `CallsView.swift:52-63`; calls §9). */
enum class CallsTabContent {
    /** Nothing loaded yet: the skeleton chat list. */
    Skeleton,

    /** The first load failed: "Can't load calls" with the error and Try Again. */
    Error,

    /** Loaded, no calls: "No calls yet". */
    Empty,

    /** The runs. */
    List,
}

/** What ends the list (`CallsView.swift:85-101`). */
enum class CallsTabFooter { None, Loading, LoadOlder }

/**
 * The Calls tab's pure rules and words (iOS `CallsView`, line numbers per member; calls §9). The
 * history rules themselves (runs, outcomes, durations) are core's [CallHistory].
 */
object CallsTabRules {
    const val TITLE = "Calls"
    const val ERROR_TITLE = "Can't load calls"
    const val EMPTY_TITLE = "No calls yet"
    const val EMPTY_MESSAGE = "Your recent calls show up here. To call someone, open their chat or profile and tap Call or Video."
    const val LOAD_OLDER = "Load older calls"
    const val LOADING_OLDER = "Loading older calls"

    /** Placeholders only for the first load; a reload over rows keeps the list (`showsSkeleton`, :41-43, 52-63). */
    fun content(state: CallHistoryState): CallsTabContent = when {
        !state.hasLoaded && state.recent.isEmpty() -> CallsTabContent.Skeleton
        state.error != null && state.recent.isEmpty() -> CallsTabContent.Error
        state.recent.isEmpty() -> CallsTabContent.Empty
        else -> CallsTabContent.List
    }

    fun footer(state: CallHistoryState): CallsTabFooter = when {
        state.loadingOlder -> CallsTabFooter.Loading
        state.olderFailed -> CallsTabFooter.LoadOlder
        else -> CallsTabFooter.None
    }

    /** "Outgoing voice · " — the status line before its outcome ([CallHistory.statusLine] in two parts, so the outcome can turn red). */
    fun statusPrefix(call: RecentCall): String = "${if (call.isOutgoing) "Outgoing" else "Incoming"} ${CallHistory.kindLabel(call)} · "

    /** "3 calls", or "3 calls · " + "1 missed" with the missed part in red (`runSummary`, :241-247). */
    fun runSummary(run: CallRun): Pair<String, String?> {
        val missed = run.calls.count(CallHistory::isMissed)
        val count = "${run.calls.size} calls"
        return if (missed > 0) "$count · " to "$missed missed" else count to null
    }

    /** "3 calls, 1 missed" (`runSummaryLabel`, :249-252). */
    fun runSummaryLabel(run: CallRun): String {
        val missed = run.calls.count(CallHistory::isMissed)
        return if (missed > 0) "${run.calls.size} calls, $missed missed" else "${run.calls.size} calls"
    }

    /** The section header's TalkBack label (:228-232). */
    fun runHeaderLabel(run: CallRun): String = "${run.latest.peerUsername}, ${runSummaryLabel(run)}"

    fun expandedValue(expanded: Boolean): String = if (expanded) "Expanded" else "Collapsed"

    /** iOS hint "Hides the calls" / "Shows the calls", as TalkBack's "Double-tap to …". */
    fun expandAction(expanded: Boolean): String = if (expanded) "hide the calls" else "show the calls"

    /**
     * "14:02" today, "Yesterday, 14:02", "28 Sep, 14:02" (`runTimeLabel(for:)`, :280-287).
     * [dayMonthPattern] is the locale's day-and-abbreviated-month pattern
     * (`DateFormat.getBestDateTimePattern(locale, "dMMM")`).
     */
    fun runTimeLabel(at: Instant, now: Instant, zone: ZoneId, locale: Locale, is24h: Boolean, dayMonthPattern: String): String {
        val time = ChatListFormatting.clockTimeLabel(at, zone, locale, is24h)
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        if (day == today) return time
        if (day == today.minusDays(1)) return "Yesterday, $time"
        return "${DateTimeFormatter.ofPattern(dayMonthPattern, locale).format(at.atZone(zone))}, $time"
    }

    /**
     * "Anna, outgoing voice call, 4 minutes, 12 seconds, 30 September 2026 at 09:41"; inside a
     * section, which said the name already, without it and capitalised (`accessibilityLabel(_:named:)`, :451-458).
     */
    fun accessibilityLabel(call: RecentCall, named: Boolean, zone: ZoneId, locale: Locale, is24h: Boolean): String {
        val direction = if (call.isOutgoing) "outgoing" else "incoming"
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(call.at.atZone(zone))
        val time = ChatListFormatting.clockTimeLabel(call.at, zone, locale, is24h)
        val spoken = "$direction ${CallHistory.kindLabel(call)} call, ${CallHistory.outcome(call, spoken = true)}, $date at $time"
        return if (named) "${call.peerUsername}, $spoken" else spoken.replaceFirstChar { it.uppercaseChar() }
    }

    fun callLabel(name: String): String = "Call $name"

    fun videoCallLabel(name: String): String = "Video call $name"
}

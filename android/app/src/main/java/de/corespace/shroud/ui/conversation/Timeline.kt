package de.corespace.shroud.ui.conversation

import androidx.compose.runtime.Immutable
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * One entry of the thread in reading order (`TimelineItem`, `ConversationView.swift:1161-1171`;
 * conversation-thread §2.1): a day chip, or a message.
 */
@Immutable
sealed interface TimelineItem {
    val key: String

    /** The day chip above the first message of a local-calendar day; key `day-y-m-d`. */
    data class Day(val label: String, override val key: String) : TimelineItem

    data class Message(val message: ChatMessage) : TimelineItem {
        override val key: String get() = message.id.toString()
    }
}

/**
 * What sits at the top of the thread, above its oldest message (`ConversationView.swift:846-865,
 * 1106-1117, 1148-1157`; conversation-thread §2.3) — exactly one of these.
 */
@Immutable
sealed interface ThreadHeader {
    /** The server has older messages: a row of fixed height, with a spinner while a page is on its way. */
    data class OlderHistory(val showsSpinner: Boolean) : ThreadHeader

    /** Nothing on this device yet and the first page is loading: "Loading messages". */
    data object LoadingFirstPage : ThreadHeader

    /** The first page failed and nothing is here: "Can't load messages" with Try Again. */
    data class LoadError(val message: String) : ThreadHeader

    /** The start of the chat: a "Today" chip when it is empty, then the end-to-end notice. */
    data class Chips(val showsToday: Boolean) : ThreadHeader

    companion object {
        /**
         * The header for the thread's state. Android renders every loaded message (a lazy list, decision
         * D1), so iOS's `hiddenCount` is always 0 and the older-history row's spinner shows exactly
         * while a page loads.
         */
        fun of(
            isEmpty: Boolean,
            hasOlderOnServer: Boolean,
            isLoadingOlder: Boolean,
            loadingFirstPage: Boolean,
            firstLoadError: String?,
        ): ThreadHeader = when {
            hasOlderOnServer -> OlderHistory(showsSpinner = isLoadingOlder)
            isEmpty && loadingFirstPage -> LoadingFirstPage
            isEmpty && firstLoadError != null -> LoadError(firstLoadError)
            else -> Chips(showsToday = isEmpty)
        }
    }
}

/**
 * One row of the thread's lazy list, newest first (`LazyColumn(reverseLayout = true)`, decision D1;
 * conversation-thread §3.1): `[bottom spacer] [typing?] [newest message] … [oldest + day chips] [header]`.
 */
@Immutable
sealed interface ThreadItem {
    val key: String
    val contentType: String

    /** The 1 dp spacer under everything: the scroll target of "to the bottom" (CV:954-957). */
    data object Bottom : ThreadItem {
        override val key: String = "thread-bottom"
        override val contentType: String = "bottom"
    }

    /**
     * The peer's typing or recording bubble after the newest message (CV:949-952). Always in the
     * list, empty (zero height) while [activity] is null, so the bubble can shrink away in place
     * instead of vanishing with its row.
     */
    data class Typing(val activity: ChatPeerActivity?) : ThreadItem {
        override val key: String = "typing-indicator"
        override val contentType: String = "typing"
    }

    data class Day(val label: String, override val key: String) : ThreadItem {
        override val contentType: String get() = "day"
    }

    data class Row(val model: MessageRowModel) : ThreadItem {
        override val key: String get() = model.message.id.toString()
        override val contentType: String get() = "message"
    }

    data class Header(val header: ThreadHeader) : ThreadItem {
        override val key: String = "thread-header"
        override val contentType: String = "header"
    }
}

/** Pure thread building blocks (conversation-thread §2, §3). */
object Timeline {
    /**
     * The reading-order timeline (`groupedTimeline`, `ConversationView.swift:1173-1186`): walking
     * oldest → newest, a [TimelineItem.Day] goes before each message whose local day differs from the
     * previous one's. No bubble grouping (decision D6).
     */
    fun items(messages: List<ChatMessage>, zone: ZoneId, today: LocalDate, locale: Locale): List<TimelineItem> {
        val items = ArrayList<TimelineItem>(messages.size + 8)
        var lastDay: LocalDate? = null
        for (message in messages) {
            val day = message.createdAt.atZone(zone).toLocalDate()
            if (day != lastDay) {
                items += TimelineItem.Day(dayLabel(day, today, locale), "day-" + dayKey(day))
                lastDay = day
            }
            items += TimelineItem.Message(message)
        }
        return items
    }

    /** `"\(year)-\(month)-\(day)"` without zero padding, as iOS builds it (`:1188-1191`). */
    fun dayKey(day: LocalDate): String = "${day.year}-${day.monthValue}-${day.dayOfMonth}"

    /**
     * "Today", "Yesterday", else the locale's abbreviated date (`dayLabel`, `:1193-1197`): iOS
     * `.abbreviated` is the medium localized date — "27 Sep 2026" (en_GB), "Sep 27, 2026" (en_US).
     */
    fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }

    /** [dayLabel] for an instant in [zone]. */
    fun dayLabel(at: Instant, now: Instant, zone: ZoneId, locale: Locale): String =
        dayLabel(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate(), locale)

    /**
     * Ids of the newest voice notes with nothing newer under them, newest first, at most two — their
     * transcripts unfold unasked (`VoiceTranscriptDisclosure.tail(of:)`,
     * `VoiceTranscriptDisclosure.swift:58-65`; the disclosure state itself is the bubbles').
     */
    fun transcriptTail(messages: List<ChatMessage>): List<UUID> {
        val tail = ArrayList<UUID>(TRANSCRIPT_TAIL_COUNT)
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (tail.size >= TRANSCRIPT_TAIL_COUNT || message.kind != ChatMessageKind.Voice || message.deleted) break
            tail += message.id
        }
        return tail
    }

    /** `VoiceTranscriptDisclosure.tailCount`. */
    const val TRANSCRIPT_TAIL_COUNT = 2

    /**
     * The lazy list's rows, newest first (conversation-thread §3.1): the bottom spacer, the typing
     * slot (holding the bubble while the peer types or records), the timeline reversed, the header
     * last (topmost). [row] builds a message's row model.
     */
    fun threadItems(
        timeline: List<TimelineItem>,
        activity: ChatPeerActivity?,
        header: ThreadHeader,
        row: (ChatMessage) -> MessageRowModel,
    ): List<ThreadItem> {
        val items = ArrayList<ThreadItem>(timeline.size + 3)
        items += ThreadItem.Bottom
        items += ThreadItem.Typing(activity)
        for (index in timeline.indices.reversed()) {
            items += when (val item = timeline[index]) {
                is TimelineItem.Day -> ThreadItem.Day(item.label, item.key)
                is TimelineItem.Message -> ThreadItem.Row(row(item.message))
            }
        }
        items += ThreadItem.Header(header)
        return items
    }

    /**
     * Every quoted message still in the thread, so each reply header resolves in one pass instead of
     * scanning the thread per bubble (`quotedMessagesByID`, `ConversationView.swift:1243-1252`).
     */
    fun quotedMessages(messages: List<ChatMessage>): Map<UUID, ChatMessage> {
        val wanted = messages.mapNotNullTo(HashSet()) { it.replyTo?.messageId }
        if (wanted.isEmpty()) return emptyMap()
        val quoted = HashMap<UUID, ChatMessage>(wanted.size)
        for (message in messages) if (message.id in wanted) quoted.putIfAbsent(message.id, message)
        return quoted
    }
}

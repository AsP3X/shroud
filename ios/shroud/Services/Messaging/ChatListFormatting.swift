import Foundation

/// List / bubble time and preview strings (pure; no network or disk).
enum ChatListFormatting {
    static func preview(
        forPeer peerID: UUID,
        threads: [UUID: [MessagingController.ChatMessage]],
        isNotes: Bool
    ) -> String {
        if let last = threads[peerID]?.last {
            if last.deleted { return "Message deleted" }
            switch last.kind {
            case .image:
                return last.text.isEmpty || last.text == "Photo" ? "Photo" : last.text
            case .video:
                return last.text.isEmpty || last.text == "Video" ? "Video" : last.text
            case .voice:
                if let t = last.transcript, !t.isEmpty { return t }
                return "Voice message"
            case .todo:
                let mark = (last.todoDone == true) ? "✓ " : "○ "
                return mark + last.text
            case .text:
                return last.text
            }
        }
        if isNotes { return "Personal notes, photos & todos" }
        return "Encrypted conversation"
    }

    /// Relative day label for list rows (Today → time, Yesterday, else date).
    static func timeLabel(for date: Date?) -> String {
        guard let date else { return "" }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if calendar.isDateInYesterday(date) {
            return "Yesterday"
        }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    /// Presence line under a name: "online", "last seen 9:41", "last seen yesterday",
    /// "last seen 27 Sep 2026", or "offline". Same wording as the web client's `presenceLabel`.
    /// `nil` when the server has told us nothing about this user yet, so each screen can
    /// fall back to its own context line.
    static func presenceLabel(for presence: PresenceDTO?) -> String? {
        guard let presence else { return nil }
        if presence.online { return "online" }
        guard let last = presence.lastSeenAt else { return "offline" }
        return lastSeenLabel(for: last)
    }

    /// "last seen 9:41" today, "last seen yesterday", else "last seen 27 Sep 2026".
    /// Lower-case mid-sentence, unlike the chat list's capitalised day labels.
    static func lastSeenLabel(for date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return "last seen \(date.formatted(date: .omitted, time: .shortened))"
        }
        if calendar.isDateInYesterday(date) {
            return "last seen yesterday"
        }
        return "last seen \(date.formatted(date: .abbreviated, time: .omitted))"
    }

    /// Clock time for in-bubble meta (always `11:05`-style).
    static func clockTimeLabel(for date: Date?) -> String {
        guard let date else { return "" }
        return date.formatted(date: .omitted, time: .shortened)
    }
}

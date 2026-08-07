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

    /// Clock time for in-bubble meta (always `11:05`-style).
    static func clockTimeLabel(for date: Date?) -> String {
        guard let date else { return "" }
        return date.formatted(date: .omitted, time: .shortened)
    }
}

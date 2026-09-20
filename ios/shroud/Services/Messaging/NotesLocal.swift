import Foundation

/// Local-only "Notes to me" helpers (never hit the network).
enum NotesLocal {
    static let peerID = LocalMessageStore.notesPeerID
    static let displayName = "Notes to me"

    static func isNotes(_ peerID: UUID) -> Bool {
        peerID == Self.peerID
    }

    /// Appends a text or todo note; returns the new message for the caller to place in `threads`.
    static func makeNote(
        text: String,
        kind: MessagingController.ChatMessageKind,
        senderUserID: UUID,
        todoDone: Bool? = nil,
        replyTo: MessageReplyReference? = nil
    ) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peerID,
            senderUserID: senderUserID,
            text: text,
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .sent,
            kind: kind,
            todoDone: todoDone,
            replyTo: replyTo
        )
    }

    /// Toggles todo completion in a notes thread. Returns updated list, or nil if no-op.
    static func toggleTodo(
        messageID: UUID,
        in messages: [MessagingController.ChatMessage]
    ) -> [MessagingController.ChatMessage]? {
        guard let idx = messages.firstIndex(where: { $0.id == messageID && $0.kind == .todo })
        else { return nil }
        var list = messages
        let current = list[idx].todoDone ?? false
        list[idx].todoDone = !current
        return list
    }

    /// Removes a note by id. Returns updated list and whether anything changed.
    static func delete(
        messageID: UUID,
        in messages: [MessagingController.ChatMessage]
    ) -> (messages: [MessagingController.ChatMessage], removed: Bool) {
        var list = messages
        let before = list.count
        list.removeAll { $0.id == messageID }
        return (list, list.count != before)
    }

    // MARK: - Multi-device todo wire format

    /// Plaintext sealed for server Notes (text content_type).
    static func syncedTodoPlaintext(text: String, done: Bool) -> String {
        let flag = done ? "1" : "0"
        return "[todo:\(flag)]\(text)"
    }

    /// Parse synced todo marker; returns nil if not a todo payload.
    static func parseSyncedTodo(_ plaintext: String) -> (text: String, done: Bool)? {
        guard plaintext.hasPrefix("[todo:") else { return nil }
        let rest = plaintext.dropFirst("[todo:".count)
        guard let close = rest.firstIndex(of: "]") else { return nil }
        let flag = rest[..<close]
        let body = String(rest[rest.index(after: close)...])
        let done = flag == "1"
        return (body, done)
    }
}

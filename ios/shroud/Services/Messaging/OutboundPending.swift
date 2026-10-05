import Foundation

/// Work items derived from `pendingSync` messages waiting for a network flush.
enum OutboundPendingItem: Equatable, Sendable {
    case text(messageID: UUID, peerID: UUID, text: String)
    case image(messageID: UUID, peerID: UUID, caption: String)
    case video(messageID: UUID, peerID: UUID, caption: String)
    case voice(messageID: UUID, peerID: UUID)
    /// The sealed blob and its payload (caption, quote, key) are already on disk.
    case file(messageID: UUID, peerID: UUID)
}

/// Pure extraction of offline outbound work from in-memory threads.
enum OutboundPending {
    /// Oldest-first so multi-message threads flush in send order.
    static func items(
        from threads: [UUID: [MessagingController.ChatMessage]],
        notesPeerID: UUID = LocalMessageStore.notesPeerID
    ) -> [OutboundPendingItem] {
        var collected: [(Date, OutboundPendingItem)] = []
        for (peerID, messages) in threads {
            if peerID == notesPeerID { continue }
            for message in messages where message.pendingSync && message.isMine {
                let item: OutboundPendingItem?
                switch message.kind {
                case .text:
                    item = .text(
                        messageID: message.id,
                        peerID: peerID,
                        text: message.text
                    )
                case .image:
                    let caption = (message.text == "Photo" || message.text.isEmpty)
                        ? ""
                        : message.text
                    item = .image(
                        messageID: message.id,
                        peerID: peerID,
                        caption: caption
                    )
                case .video:
                    let caption = (message.text == "Video" || message.text.isEmpty)
                        ? ""
                        : message.text
                    item = .video(
                        messageID: message.id,
                        peerID: peerID,
                        caption: caption
                    )
                case .voice:
                    item = .voice(messageID: message.id, peerID: peerID)
                case .file:
                    item = .file(messageID: message.id, peerID: peerID)
                case .todo:
                    item = nil
                }
                if let item {
                    collected.append((message.createdAt, item))
                }
            }
        }
        return collected.sorted { $0.0 < $1.0 }.map(\.1)
    }
}

/// Serializes reconnect flushes so poll + connectivity + foreground don't double-send.
@MainActor
final class OutboundSendQueue {
    private var flushTask: Task<Void, Never>?

    /// Runs `work` once at a time; overlapping callers await the same in-flight flush.
    func flush(_ work: @escaping @MainActor () async -> Void) async {
        if let existing = flushTask {
            await existing.value
            return
        }
        let task = Task { @MainActor in
            await work()
        }
        flushTask = task
        await task.value
        if flushTask == task { flushTask = nil }
    }

    func cancel() {
        flushTask?.cancel()
        flushTask = nil
    }
}

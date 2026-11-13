import Foundation

/// Transcripts shared as annotations whose voice note is not in its thread yet (the annotation
/// arrived first), by the chat they were shared in, then voice note id.
///
/// Human: A transcript names its note only by id. Keyed by id alone, a contact's annotation naming
/// a note in another chat set that note's transcript there, ahead of the real one (security audit
/// W2, fixed on the web and Android the same way). Either person in the chat may share it: iOS and
/// Android share one for a note they received, so the sender is not checked, only the chat.
/// Agent: `storePeerID` is the `threads` key the annotation was decoded under (Notes: its local
/// peer id); fold with the same key. No I/O; lives for the session in `MessagingController`.
struct PendingSharedTranscripts {
    private var byChat: [UUID: [UUID: String]] = [:]

    /// Keeps the first transcript shared for a note in this chat; a later one never replaces it.
    mutating func note(_ text: String, for messageID: UUID, inChat storePeerID: UUID) {
        if byChat[storePeerID]?[messageID] == nil {
            byChat[storePeerID, default: [:]][messageID] = text
        }
    }

    /// Applies the transcripts shared in this chat whose voice note is now in `thread`, and drops
    /// those keys. Transcripts shared in other chats are left alone.
    mutating func fold(
        into thread: [MessagingController.ChatMessage],
        chat storePeerID: UUID
    ) -> [MessagingController.ChatMessage] {
        guard var pending = byChat[storePeerID], !pending.isEmpty else { return thread }
        let updated = ThreadMessageMerge.applySharedTranscripts(pending, to: thread)
        for message in updated where message.kind == .voice {
            pending.removeValue(forKey: message.id)
        }
        byChat[storePeerID] = pending.isEmpty ? nil : pending
        return updated
    }
}

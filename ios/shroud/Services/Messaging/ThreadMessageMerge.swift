import Foundation

/// Pure helpers for merging server reloads with local plaintext (DR is one-shot).
enum ThreadMessageMerge {
    static func isFailedDecryptText(_ text: String) -> Bool {
        text == "[Unable to decrypt]" || text == "Media" || text == "[Binary message]"
    }

    /// Never let a failed re-decrypt replace a previously readable bubble.
    static func preferReadable(
        _ decoded: MessagingController.ChatMessage,
        previous: [MessagingController.ChatMessage]
    ) -> MessagingController.ChatMessage {
        preferReadable(decoded, prior: previous.first(where: { $0.id == decoded.id }))
    }

    /// `preferReadable(_:previous:)` with the prior copy already looked up.
    static func preferReadable(
        _ decoded: MessagingController.ChatMessage,
        prior: MessagingController.ChatMessage?
    ) -> MessagingController.ChatMessage {
        guard let prior else { return decoded }
        let decodedFailed = isFailedDecryptText(decoded.text)
        let priorFailed = isFailedDecryptText(prior.text)

        if decodedFailed, !priorFailed, !prior.deleted {
            var kept = prior
            if decoded.isMine, decoded.receipt.rank > prior.receipt.rank {
                kept.receipt = decoded.receipt
            }
            if kept.imageData == nil { kept.imageData = decoded.imageData }
            if kept.previewData == nil { kept.previewData = decoded.previewData }
            if kept.mediaByteCount == nil { kept.mediaByteCount = decoded.mediaByteCount }
            if kept.voiceData == nil { kept.voiceData = decoded.voiceData }
            if kept.videoData == nil { kept.videoData = decoded.videoData }
            if kept.mediaObjectId == nil { kept.mediaObjectId = decoded.mediaObjectId }
            if kept.transcript == nil { kept.transcript = decoded.transcript }
            if kept.replyTo == nil { kept.replyTo = decoded.replyTo }
            if kept.linkPreview == nil { kept.linkPreview = decoded.linkPreview }
            kept.reactions = prior.reactions
            // Prefer a more specific media kind once we know it (e.g. "Media" → video).
            if kept.kind != decoded.kind, !decodedFailed {
                if decoded.kind == .video || decoded.kind == .image || decoded.kind == .voice {
                    kept.kind = decoded.kind
                }
            }
            return kept
        }

        var merged = decoded
        if merged.imageData == nil { merged.imageData = prior.imageData }
        if merged.previewData == nil { merged.previewData = prior.previewData }
        if merged.mediaByteCount == nil { merged.mediaByteCount = prior.mediaByteCount }
        if merged.voiceData == nil { merged.voiceData = prior.voiceData }
        if merged.videoData == nil { merged.videoData = prior.videoData }
        if merged.transcript == nil { merged.transcript = prior.transcript }
        // Reactions live only on this device's copy until a page reconciles them (never on a
        // fresh decode), so the held ones carry over — unless the message is gone.
        merged.reactions = merged.deleted ? [] : prior.reactions
        // A re-decode from a cache written before replies existed has no quote; keep the one
        // the bubble already showed rather than dropping the reply header on a reload.
        if merged.replyTo == nil { merged.replyTo = prior.replyTo }
        // Same for a link preview: a cache written before previews existed has none.
        if merged.linkPreview == nil, merged.kind == .text { merged.linkPreview = prior.linkPreview }
        // Don't clobber a hydrated video with a decode that still lacks bytes.
        if prior.kind == .video, merged.kind != .video, prior.videoData != nil || prior.mediaObjectId != nil {
            merged.kind = .video
            if merged.videoData == nil { merged.videoData = prior.videoData }
            if merged.voiceDurationMs == nil { merged.voiceDurationMs = prior.voiceDurationMs }
        }
        if !priorFailed, decodedFailed {
            return prior
        }
        return merged
    }

    /// Merge a freshly decoded page with the prior in-memory thread.
    static func mergeThread(
        decoded: [MessagingController.ChatMessage],
        previous: [MessagingController.ChatMessage],
        pendingLocal: [MessagingController.ChatMessage]
    ) -> [MessagingController.ChatMessage] {
        // Keyed lookups: a thread holds thousands once older pages are in, and this runs per page.
        let previousByID = Dictionary(previous.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var result = decoded.map { preferReadable($0, prior: previousByID[$0.id]) }
        var seen = Set(result.map(\.id))

        for pending in pendingLocal where seen.insert(pending.id).inserted {
            result.append(pending)
        }
        for prior in previous where !isFailedDecryptText(prior.text) && seen.insert(prior.id).inserted {
            result.append(prior)
        }
        result.sort { $0.createdAt < $1.createdAt }
        return result
    }

    /// Folds transcripts shared as annotations into the voice messages they point at.
    ///
    /// A transcript sealed by the sender, or one made on this device, always wins; a shared one
    /// only fills a gap. Returns the thread unchanged when nothing applies.
    static func applySharedTranscripts(
        _ transcripts: [UUID: String],
        to thread: [MessagingController.ChatMessage]
    ) -> [MessagingController.ChatMessage] {
        guard !transcripts.isEmpty else { return thread }
        var result = thread
        for index in result.indices {
            let message = result[index]
            guard message.kind == .voice,
                  !message.deleted,
                  (message.transcript ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let shared = transcripts[message.id]
            else { continue }
            result[index].transcript = shared
        }
        return result
    }
}

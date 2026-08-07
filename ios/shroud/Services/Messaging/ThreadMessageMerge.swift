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
        guard let prior = previous.first(where: { $0.id == decoded.id }) else {
            return decoded
        }
        let decodedFailed = isFailedDecryptText(decoded.text)
        let priorFailed = isFailedDecryptText(prior.text)

        if decodedFailed, !priorFailed, !prior.deleted {
            var kept = prior
            if decoded.isMine, decoded.receipt.rank > prior.receipt.rank {
                kept.receipt = decoded.receipt
            }
            if kept.imageData == nil { kept.imageData = decoded.imageData }
            if kept.voiceData == nil { kept.voiceData = decoded.voiceData }
            if kept.mediaObjectId == nil { kept.mediaObjectId = decoded.mediaObjectId }
            if kept.transcript == nil { kept.transcript = decoded.transcript }
            return kept
        }

        var merged = decoded
        if merged.imageData == nil { merged.imageData = prior.imageData }
        if merged.voiceData == nil { merged.voiceData = prior.voiceData }
        if merged.transcript == nil { merged.transcript = prior.transcript }
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
        var result = decoded.map { preferReadable($0, previous: previous) }

        for pending in pendingLocal where !result.contains(where: { $0.id == pending.id }) {
            result.append(pending)
        }
        for prior in previous where !result.contains(where: { $0.id == prior.id }) {
            if !isFailedDecryptText(prior.text) {
                result.append(prior)
            }
        }
        result.sort { $0.createdAt < $1.createdAt }
        return result
    }
}

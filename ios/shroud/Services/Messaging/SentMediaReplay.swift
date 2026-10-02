import Foundation

/// What to keep after a media send when the server may have replayed an older row.
///
/// A retried `client_message_id` is idempotent: the response is the first attempt's message,
/// whose blob and file key are not this attempt's upload (left unlinked, deleted after an hour).
/// Caching this attempt's key would pair the server's blob with the wrong key.
enum SentMediaReplay {
    struct Decision: Equatable {
        var mediaObjectId: UUID
        /// Nil means cache nothing. The caller stores this under the server row's id and sender.
        var payload: Data?
    }

    static func decide(
        serverMediaObjectId: UUID?,
        uploadedMediaObjectId: UUID,
        thisAttemptPayload: Data,
        serverCiphertextBase64: String?,
        openKept: (Data) throws -> Data
    ) throws -> Decision {
        let stored = serverMediaObjectId ?? uploadedMediaObjectId
        if stored == uploadedMediaObjectId {
            return Decision(mediaObjectId: stored, payload: thisAttemptPayload)
        }
        guard let serverCiphertextBase64,
              let envelope = Data(base64Encoded: serverCiphertextBase64)
        else {
            return Decision(mediaObjectId: stored, payload: nil)
        }
        let kept: Data
        do {
            kept = try openKept(envelope)
        } catch let error as CancellationError {
            throw error
        } catch {
            return Decision(mediaObjectId: stored, payload: nil)
        }
        guard MediaMessagePayload.parse(kept) != nil else {
            return Decision(mediaObjectId: stored, payload: nil)
        }
        return Decision(mediaObjectId: stored, payload: kept)
    }
}

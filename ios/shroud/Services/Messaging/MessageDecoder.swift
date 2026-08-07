import Foundation

/// Decrypts a server `MessageDTO` into a UI `ChatMessage` (one-shot DR + sealed caches).
///
/// Human: First open spends the ratchet key; later opens must use local sealed plaintext.
/// Agent: Pure-ish pipeline; peer key resolution injected so Networking stays outside.
@MainActor
enum MessageDecoder {
    struct Context {
        let me: UUID
        let material: IdentityKeyMaterial
        let token: String
        let conversations: [ConversationItemDTO]
        let threads: [UUID: [MessagingController.ChatMessage]]
        let local: MessagingLocalRepository
        let mediaService: MediaService
        let resolvePeerIdentityPublicKey: (UUID, String) async throws -> Data
    }

    static func receiptStatus(from dto: MessageDTO) -> MessageReceiptStatus {
        if dto.read == true { return .read }
        if dto.delivered == true { return .delivered }
        return .sent
    }

    static func isMediaPayloadData(_ data: Data) -> Bool {
        (try? JSONDecoder().decode(MediaMessagePayload.self, from: data)) != nil
    }

    static func decode(
        _ dto: MessageDTO,
        context: Context
    ) async -> MessagingController.ChatMessage {
        let me = context.me
        let isMine = dto.senderUserId == me
        let peerUserID = isMine
            ? (context.conversations.first(where: { $0.id == dto.conversationId })?.peer.id
                ?? dto.senderUserId)
            : dto.senderUserId
        let receipt = isMine ? receiptStatus(from: dto) : MessageReceiptStatus.sent
        let isMedia = dto.contentType == "media"
        let local = context.local

        if dto.deletedForEveryone {
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "Message deleted",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: true,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        let existing = context.threads[peerUserID]?.first(where: { $0.id == dto.id })
            ?? context.threads.values.lazy.flatMap({ $0 }).first(where: { $0.id == dto.id })
        if let existing {
            var merged = existing
            if isMine {
                let serverReceipt = receiptStatus(from: dto)
                if serverReceipt.rank > existing.receipt.rank {
                    merged.receipt = serverReceipt
                }
            }
            let needsMediaBytes = (existing.kind == .image || existing.kind == .voice)
                && existing.imageData == nil
                && existing.voiceData == nil
                && !existing.deleted
                && local.sealedPlaintext(for: dto.id) != nil
            if !existing.deleted,
               !ThreadMessageMerge.isFailedDecryptText(existing.text),
               !needsMediaBytes
            {
                if !isMedia, local.sealedPlaintext(for: dto.id) == nil {
                    local.saveSealedPlaintext(messageID: dto.id, text: existing.text)
                }
                return merged
            }
        }

        if let cachedData = local.sealedPlaintext(for: dto.id),
           isMediaPayloadData(cachedData) || !isMedia
        {
            if isMedia {
                return await decodeMedia(
                    dto: dto,
                    plain: cachedData,
                    peerUserID: peerUserID,
                    isMine: isMine,
                    receipt: receipt,
                    context: context
                )
            }
            let text = String(data: cachedData, encoding: .utf8) ?? "[Binary message]"
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: text,
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt
            )
        }

        guard let ciphertextB64 = dto.ciphertext,
              let envelopeData = Data(base64Encoded: ciphertextB64)
        else {
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: isMedia ? "Photo" : "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        do {
            let plain: Data
            if isMine {
                plain = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: peerUserID,
                    with: context.material.agreementPrivateKey,
                    ourIdentityPublicKey: context.material.identityPublicKeyData,
                    senderIdentityPublicKey: context.material.identityPublicKeyData,
                    as: .sender
                )
            } else {
                let senderPub = try await context.resolvePeerIdentityPublicKey(
                    dto.senderUserId,
                    context.token
                )
                plain = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: dto.senderUserId,
                    with: context.material.agreementPrivateKey,
                    ourIdentityPublicKey: context.material.identityPublicKeyData,
                    senderIdentityPublicKey: senderPub,
                    as: .recipient
                )
            }

            local.saveSealedPlaintext(messageID: dto.id, data: plain)

            if isMedia {
                return await decodeMedia(
                    dto: dto,
                    plain: plain,
                    peerUserID: peerUserID,
                    isMine: isMine,
                    receipt: receipt,
                    context: context
                )
            }

            let text = String(data: plain, encoding: .utf8) ?? "[Binary message]"
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: text,
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt
            )
        } catch {
            if let cached = local.sealedPlaintextText(for: dto.id), !isMedia {
                return MessagingController.ChatMessage(
                    id: dto.id,
                    peerUserID: peerUserID,
                    senderUserID: dto.senderUserId,
                    text: cached,
                    createdAt: dto.createdAt,
                    isMine: isMine,
                    deleted: false,
                    receipt: receipt
                )
            }
            if isMedia, let cachedData = local.sealedPlaintext(for: dto.id) {
                return await decodeMedia(
                    dto: dto,
                    plain: cachedData,
                    peerUserID: peerUserID,
                    isMine: isMine,
                    receipt: receipt,
                    context: context
                )
            }
            if let existing,
               !existing.deleted,
               !ThreadMessageMerge.isFailedDecryptText(existing.text)
            {
                return existing
            }
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: isMedia ? "Media" : "[Unable to decrypt]",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId,
                imageData: local.sealedMedia(for: dto.id)
            )
        }
    }

    private static func decodeMedia(
        dto: MessageDTO,
        plain: Data,
        peerUserID: UUID,
        isMine: Bool,
        receipt: MessageReceiptStatus,
        context: Context
    ) async -> MessagingController.ChatMessage {
        let local = context.local
        let cached = local.sealedMedia(for: dto.id)
        let payload = try? JSONDecoder().decode(MediaMessagePayload.self, from: plain)

        if payload?.isVoice == true {
            var voiceData = cached
            if voiceData == nil,
               let payload,
               let mediaID = dto.mediaObjectId,
               let keyData = Data(base64Encoded: payload.k)
            {
                if let sealed = try? await context.mediaService.downloadContent(
                    mediaID: mediaID,
                    token: context.token
                ),
                   let audio = try? MediaCrypto.openFile(sealed: sealed, keyData: keyData)
                {
                    local.saveSealedMedia(messageID: dto.id, data: audio)
                    voiceData = audio
                }
            }
            let transcript = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines)
            let mediaText = (transcript?.isEmpty == false) ? transcript! : "Voice message"
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: mediaText,
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .voice,
                mediaObjectId: dto.mediaObjectId,
                voiceData: voiceData,
                voiceDurationMs: payload?.d,
                voiceWaveform: VoiceWaveform.decode(payload?.wf),
                transcript: transcript
            )
        }

        var imageData = cached
        let width = payload?.w
        let height = payload?.h

        if imageData == nil,
           let payload,
           let mediaID = dto.mediaObjectId,
           let keyData = Data(base64Encoded: payload.k)
        {
            if let sealed = try? await context.mediaService.downloadContent(
                mediaID: mediaID,
                token: context.token
            ),
               let jpeg = try? MediaCrypto.openFile(sealed: sealed, keyData: keyData)
            {
                local.saveSealedMedia(messageID: dto.id, data: jpeg)
                imageData = jpeg
            }
        }

        let caption = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let mediaText = caption.isEmpty ? "Photo" : caption

        if imageData == nil, payload == nil {
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "Media",
                createdAt: dto.createdAt,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .image,
                mediaObjectId: dto.mediaObjectId
            )
        }

        return MessagingController.ChatMessage(
            id: dto.id,
            peerUserID: peerUserID,
            senderUserID: dto.senderUserId,
            text: mediaText,
            createdAt: dto.createdAt,
            isMine: isMine,
            deleted: false,
            receipt: receipt,
            kind: .image,
            mediaObjectId: dto.mediaObjectId,
            imageWidth: width,
            imageHeight: height,
            imageData: imageData
        )
    }
}

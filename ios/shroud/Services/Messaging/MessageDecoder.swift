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
        MediaMessagePayload.parse(data) != nil
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
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: true,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        // A held copy skips the envelope. Message ids are chosen by the server, so reuse one
        // only for the same sender — and, for a peer's message, only from that peer's chat.
        let candidate = context.threads[peerUserID]?.first(where: { $0.id == dto.id })
            ?? context.threads.values.lazy.flatMap({ $0 }).first(where: { $0.id == dto.id })
        let existing: MessagingController.ChatMessage? = {
            guard let candidate else { return nil }
            guard candidate.senderUserID == dto.senderUserId else { return nil }
            guard isMine || candidate.peerUserID == dto.senderUserId else { return nil }
            return candidate
        }()
        if let existing {
            var merged = existing
            if isMine {
                let serverReceipt = receiptStatus(from: dto)
                if serverReceipt.rank > existing.receipt.rank {
                    merged.receipt = serverReceipt
                }
            }
            // Keep a readable bubble even when media bytes are still missing — UI lazy-loads
            // images/voice on appear. Never block history decode on media download.
            if !existing.deleted,
               !ThreadMessageMerge.isFailedDecryptText(existing.text)
            {
                if !isMedia, local.sealedPlaintext(for: dto.id, senderUserID: dto.senderUserId) == nil {
                    // Re-seal what the bubble actually carries, quote included, so a later
                    // decode from this cache rebuilds the same reply header.
                    local.saveSealedPlaintext(
                        messageID: dto.id,
                        senderUserID: dto.senderUserId,
                        text: MessageTextPayload.wire(
                            body: existing.text,
                            replyTo: existing.replyTo,
                            linkPreview: existing.linkPreview
                        )
                    )
                }
                // A build without reply support stored the raw envelope as the bubble's text.
                // Read it back rather than leaving JSON on screen forever.
                if !isMedia, merged.replyTo == nil, merged.linkPreview == nil {
                    let parsed = MessageTextPayload.parse(existing.text)
                    if parsed.replyTo != nil || parsed.linkPreview != nil {
                        merged.text = parsed.body
                        merged.replyTo = parsed.replyTo
                        merged.linkPreview = parsed.linkPreview
                    }
                }
                if merged.imageData == nil, existing.kind == .image,
                   let cached = local.sealedMedia(for: dto.id)
                {
                    merged.imageData = cached
                }
                if merged.imageData == nil, existing.hasLargeLinkImage,
                   let cached = local.sealedMedia(for: dto.id)
                {
                    merged.imageData = cached
                }
                if merged.voiceData == nil, existing.kind == .voice,
                   let cached = local.sealedMedia(for: dto.id)
                {
                    merged.voiceData = cached
                }
                if merged.videoData == nil, existing.kind == .video,
                   let cached = local.sealedMedia(for: dto.id)
                {
                    merged.videoData = cached
                }
                if existing.kind == .file {
                    merged.fileStored = local.hasFileBlob(dto.id)
                }
                // iOS 26/27 JSONDecoder used to fail the payload and stamp every media
                // note as a photo. Re-read `t` from the sealed JSON so a voice/video
                // bubble is restored without wiping the thread.
                if isMedia, existing.kind == .image,
                   let plain = local.sealedPlaintext(for: dto.id, senderUserID: dto.senderUserId),
                   let payload = MediaMessagePayload.parse(plain),
                   payload.isFile || payload.isVoice || payload.isVideo || payload.isLink
                {
                    return await decodeMedia(
                        dto: dto,
                        plain: plain,
                        peerUserID: peerUserID,
                        isMine: isMine,
                        receipt: receipt,
                        context: context
                    )
                }
                return merged
            }
        }

        if let cachedData = local.sealedPlaintext(for: dto.id, senderUserID: dto.senderUserId),
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
            let raw = String(data: cachedData, encoding: .utf8) ?? "[Binary message]"
            let parsed = MessageTextPayload.parse(raw)
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: parsed.body,
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                replyTo: parsed.replyTo,
                linkPreview: parsed.linkPreview
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
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId
            )
        }

        do {
            let plain: Data
            // Notes (self): always open as sender (self dual-seal). Never advance a peer DR.
            let isSelfNote = dto.senderUserId == context.me && peerUserID == context.me
            if isMine || isSelfNote {
                plain = try MessageCrypto.open(
                    envelopeData: envelopeData,
                    peerUserID: isSelfNote ? context.me : peerUserID,
                    with: context.material.agreementPrivateKey,
                    ourIdentityPublicKey: context.material.identityPublicKeyData,
                    senderIdentityPublicKey: context.material.identityPublicKeyData,
                    as: .sender,
                    sentAt: dto.createdAt
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
                    as: .recipient,
                    sentAt: dto.createdAt
                )
            }

            local.saveSealedPlaintext(messageID: dto.id, senderUserID: dto.senderUserId, data: plain)

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

            // A reply seals its quote in the same plaintext; plain messages stay raw UTF-8.
            let parsed = MessageTextPayload.parse(
                String(data: plain, encoding: .utf8) ?? "[Binary message]"
            )
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: parsed.body,
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                replyTo: parsed.replyTo,
                linkPreview: parsed.linkPreview
            )
        } catch {
            if let cached = local.sealedPlaintextText(for: dto.id, senderUserID: dto.senderUserId), !isMedia {
                let parsed = MessageTextPayload.parse(cached)
                return MessagingController.ChatMessage(
                    id: dto.id,
                    peerUserID: peerUserID,
                    senderUserID: dto.senderUserId,
                    text: parsed.body,
                    createdAt: dto.createdAt,
                    createdAtWire: dto.createdAtWire,
                    isMine: isMine,
                    deleted: false,
                    receipt: receipt,
                    replyTo: parsed.replyTo,
                    linkPreview: parsed.linkPreview
                )
            }
            if isMedia, let cachedData = local.sealedPlaintext(for: dto.id, senderUserID: dto.senderUserId) {
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
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: isMedia ? .image : .text,
                mediaObjectId: dto.mediaObjectId,
                imageData: local.sealedMedia(for: dto.id)
            )
        }
    }

    /// Builds a media bubble from plaintext payload + **local** cache only.
    ///
    /// Network download is intentionally skipped here so history paging stays fast.
    /// `MessagingController.ensureImageLoaded` / `ensureVoiceLoaded` / `ensureVideoLoaded`
    /// fill bytes when a row appears.
    private static func decodeMedia(
        dto: MessageDTO,
        plain: Data,
        peerUserID: UUID,
        isMine: Bool,
        receipt: MessageReceiptStatus,
        context: Context
    ) async -> MessagingController.ChatMessage {
        let local = context.local
        let payload = MediaMessagePayload.parse(plain)

        // A file first, before any of the MIME sniffing below: its blob is SHRF1, and an
        // `image/png` file must never reach the photo path. The name is cleaned again here —
        // the sender's cleaning is not trusted.
        if let payload, payload.isFile {
            let caption = payload.c?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: caption,
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .file,
                mediaObjectId: dto.mediaObjectId,
                imageWidth: payload.w > 0 ? payload.w : nil,
                imageHeight: payload.h > 0 ? payload.h : nil,
                previewData: payload.previewJPEG,
                mediaByteCount: payload.s,
                replyTo: payload.re,
                fileName: SharedFile.cleanName(payload.n ?? ""),
                // Whether the blob is here, never its bytes: a file is opened on demand only.
                fileStored: local.hasFileBlob(dto.id),
                filePageCount: payload.pg
            )
        }

        // Disk cache only — never await media download during thread history decode.
        let cached = local.sealedMedia(for: dto.id)

        // A text message whose link preview has a large image: the blob is that image.
        if let payload, payload.isLink, let preview = payload.lp {
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: payload.c ?? "",
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .text,
                mediaObjectId: dto.mediaObjectId,
                imageWidth: payload.w > 0 ? payload.w : nil,
                imageHeight: payload.h > 0 ? payload.h : nil,
                // Picture only from the local cache; the bubble downloads it when it appears.
                imageData: cached,
                previewData: payload.previewJPEG,
                mediaByteCount: payload.s,
                replyTo: payload.re,
                linkPreview: preview
            )
        }

        if payload?.isVoice == true {
            let transcript = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines)
            let mediaText = (transcript?.isEmpty == false) ? transcript! : "Voice message"
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: mediaText,
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .voice,
                mediaObjectId: dto.mediaObjectId,
                voiceData: cached,
                voiceDurationMs: payload?.d,
                voiceWaveform: VoiceWaveform.decode(payload?.wf),
                transcript: transcript,
                replyTo: payload?.re
            )
        }

        if payload?.isVideo == true {
            let width = payload?.w
            let height = payload?.h
            let caption = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let mediaText = caption.isEmpty ? "Video" : caption
            let preview = payload?.previewJPEG
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: mediaText,
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
                isMine: isMine,
                deleted: false,
                receipt: receipt,
                kind: .video,
                mediaObjectId: dto.mediaObjectId,
                imageWidth: width,
                imageHeight: height,
                // Full video only when already downloaded to the local media cache.
                imageData: cached != nil ? preview : nil,
                previewData: preview,
                mediaByteCount: payload?.s,
                videoData: cached,
                voiceDurationMs: payload?.d,
                replyTo: payload?.re
            )
        }

        let width = payload?.w
        let height = payload?.h
        let caption = payload?.c?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let mediaText = caption.isEmpty ? "Photo" : caption
        let preview = payload?.previewJPEG

        if cached == nil, payload == nil {
            return MessagingController.ChatMessage(
                id: dto.id,
                peerUserID: peerUserID,
                senderUserID: dto.senderUserId,
                text: "Media",
                createdAt: dto.createdAt,
                createdAtWire: dto.createdAtWire,
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
            createdAtWire: dto.createdAtWire,
            isMine: isMine,
            deleted: false,
            receipt: receipt,
            kind: .image,
            mediaObjectId: dto.mediaObjectId,
            imageWidth: width,
            imageHeight: height,
            // Full photo only from local cache; envelope thumb is enough for the bubble.
            imageData: cached,
            previewData: preview,
            mediaByteCount: payload?.s,
            replyTo: payload?.re
        )
    }
}

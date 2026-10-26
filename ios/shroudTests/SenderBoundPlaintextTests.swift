import CryptoKit
import XCTest
@testable import shroud

/// A message id is not a capability. Cached plaintext and a held bubble are reused only for
/// the same sender, a plaintext naming another recipient is not shown, and an idempotent media
/// replay remembers the row the server kept.
@MainActor
final class SenderBoundPlaintextTests: XCTestCase {
    private let me = UUID()
    private let alice = UUID()
    private let bob = UUID()
    private let carol = UUID()
    private let key = SymmetricKey(size: .bits256)
    private var messageIDs: [UUID] = []

    private enum ProbeError: Error {
        case opened
    }

    /// The decoder's resolver outlives the call that builds its context.
    private final class Resolved {
        var ids: [UUID] = []
    }

    override func tearDown() {
        MessagingLocalRepository().removeCaches(messageIDs: messageIDs)
        super.tearDown()
    }

    /// A re-served id from another sender is opened for real and fails closed.
    func testResentMessageIdFromAnotherSenderDoesNotReturnCachedPlaintext() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        repository.saveSealedPlaintext(messageID: id, senderUserID: alice, text: "for your eyes")

        let resolved = Resolved()
        let bobMessage = try await MessageDecoder.decode(
            dto(id: id, sender: bob, ciphertext: Data("not-an-envelope".utf8).base64EncodedString()),
            context: context(repository: repository, threads: [:], resolved: resolved)
        )

        XCTAssertEqual(bobMessage.senderUserID, bob)
        XCTAssertEqual(bobMessage.text, "[Unable to decrypt]")
        XCTAssertEqual(resolved.ids, [bob])
        XCTAssertNil(repository.sealedPlaintextText(for: id, senderUserID: bob))

        resolved.ids.removeAll()
        let aliceMessage = try await MessageDecoder.decode(
            dto(id: id, sender: alice, ciphertext: Data("still-not-an-envelope".utf8).base64EncodedString()),
            context: context(repository: repository, threads: [:], resolved: resolved)
        )
        XCTAssertEqual(aliceMessage.senderUserID, alice)
        XCTAssertEqual(aliceMessage.text, "for your eyes")
        XCTAssertTrue(resolved.ids.isEmpty)
    }

    /// A peer bubble is reused only from that peer's chat. The same id under another chat is opened.
    func testHeldPeerBubbleIsReusedOnlyFromThatPeersChat() async throws {
        let repository = unlocked()
        let sameChat = UUID()
        let otherChat = UUID()
        messageIDs.append(contentsOf: [sameChat, otherChat])
        let resolved = Resolved()

        let kept = try await MessageDecoder.decode(
            dto(id: sameChat, sender: alice, ciphertext: nil),
            context: context(
                repository: repository,
                threads: [alice: [bubble(id: sameChat, peer: alice, sender: alice, text: "hello")]],
                resolved: resolved
            )
        )
        XCTAssertEqual(kept.text, "hello")
        XCTAssertTrue(resolved.ids.isEmpty)

        let refused = try await MessageDecoder.decode(
            dto(id: otherChat, sender: alice, ciphertext: Data("open-me".utf8).base64EncodedString()),
            context: context(
                repository: repository,
                threads: [carol: [bubble(id: otherChat, peer: carol, sender: alice, text: "not this chat")]],
                resolved: resolved
            )
        )
        XCTAssertEqual(refused.text, "[Unable to decrypt]")
        XCTAssertEqual(refused.senderUserID, alice)
        XCTAssertEqual(resolved.ids, [alice])
    }

    /// A history page lists only my messages and the peer's. One the server names as from
    /// anyone else is not opened, not even from a cache or a held bubble, and their key is
    /// never asked for (security audit M7).
    func testHistoryNeverOpensAMessageFromOutsideTheChat() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        repository.saveSealedPlaintext(messageID: id, senderUserID: carol, text: "from carol")
        let resolved = Resolved()
        var aliceChat = context(
            repository: repository,
            threads: [carol: [bubble(id: id, peer: carol, sender: carol, text: "from carol")]],
            resolved: resolved
        )
        aliceChat.chatPeerUserID = alice

        let stranger = try await MessageDecoder.decode(
            dto(id: id, sender: carol, ciphertext: Data("sealed-by-carol".utf8).base64EncodedString()),
            context: aliceChat
        )
        XCTAssertEqual(stranger.text, "[Unable to decrypt]")
        XCTAssertEqual(stranger.peerUserID, alice)
        XCTAssertEqual(stranger.senderUserID, carol)
        XCTAssertFalse(stranger.isMine)
        XCTAssertTrue(resolved.ids.isEmpty)

        let peerID = UUID()
        messageIDs.append(peerID)
        _ = try await MessageDecoder.decode(
            dto(id: peerID, sender: alice, ciphertext: Data("sealed-by-alice".utf8).base64EncodedString()),
            context: aliceChat
        )
        XCTAssertEqual(resolved.ids, [alice])
    }

    /// An idempotent replay stores the server row's payload and blob, not this attempt's key.
    func testReplayCachesTheKeptRow() throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let serverBlob = UUID()
        let uploadBlob = UUID()
        let kept = Data(#"{"t":"image","k":"kept-key","mime":"image/jpeg","w":1,"h":1}"#.utf8)
        let attempt = Data(#"{"t":"image","k":"this-attempt","mime":"image/jpeg","w":1,"h":1}"#.utf8)
        let wire = Data("server-envelope".utf8).base64EncodedString()

        let replay = try SentMediaReplay.decide(
            serverMediaObjectId: serverBlob,
            uploadedMediaObjectId: uploadBlob,
            thisAttemptPayload: attempt,
            serverCiphertextBase64: wire
        ) { envelope in
            XCTAssertEqual(envelope, Data("server-envelope".utf8))
            return kept
        }
        if let payload = replay.payload {
            repository.saveSealedPlaintext(messageID: id, senderUserID: me, data: payload)
        }

        XCTAssertEqual(replay.mediaObjectId, serverBlob)
        XCTAssertEqual(repository.sealedPlaintext(for: id, senderUserID: me), kept)
        XCTAssertNotEqual(repository.sealedPlaintext(for: id, senderUserID: me), attempt)

        let failedOpen = try SentMediaReplay.decide(
            serverMediaObjectId: serverBlob,
            uploadedMediaObjectId: uploadBlob,
            thisAttemptPayload: attempt,
            serverCiphertextBase64: wire
        ) { _ in
            throw ProbeError.opened
        }
        XCTAssertEqual(failedOpen.mediaObjectId, serverBlob)
        XCTAssertNil(failedOpen.payload)

        let sameBlob = try SentMediaReplay.decide(
            serverMediaObjectId: uploadBlob,
            uploadedMediaObjectId: uploadBlob,
            thisAttemptPayload: attempt,
            serverCiphertextBase64: nil,
            openKept: { _ in
                XCTFail("a matching blob is this attempt")
                return Data()
            }
        )
        XCTAssertEqual(sameBlob.mediaObjectId, uploadBlob)
        XCTAssertEqual(sameBlob.payload, attempt)

        XCTAssertThrowsError(
            try SentMediaReplay.decide(
                serverMediaObjectId: serverBlob,
                uploadedMediaObjectId: uploadBlob,
                thisAttemptPayload: attempt,
                serverCiphertextBase64: wire,
                openKept: { _ in throw CancellationError() }
            )
        ) { error in
            XCTAssertTrue(error is CancellationError)
        }
    }

    /// A file sealed before senders were part of the record opens for the sender stored with it.
    func testLegacyPlaintextBindsToTheStoredSender() throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let plain = Data("legacy body".utf8)
        let sealed = try LocalHistoryCrypto.seal(plain, masterKey: key, context: .plaintextPayload)
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("shroud/plaintext", isDirectory: true)
            .appendingPathComponent(id.uuidString.lowercased() + ".sealed")
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try sealed.write(to: url, options: .atomic)

        repository.persist(
            userID: me,
            contacts: [],
            incomingRequests: [],
            conversations: [],
            threads: [alice: [bubble(id: id, peer: alice, sender: alice, text: "legacy body")]],
            unreadByPeer: [:]
        )

        XCTAssertEqual(repository.sealedPlaintext(for: id, senderUserID: alice), plain)
        XCTAssertNil(repository.sealedPlaintext(for: id, senderUserID: bob))

        let reopened = MessagingLocalRepository()
        reopened.setHistoryKey(key)
        XCTAssertEqual(reopened.sealedPlaintext(for: id, senderUserID: alice), plain)
        XCTAssertNil(reopened.sealedPlaintext(for: id, senderUserID: bob))
        LocalMessageStore().clear(userID: me)
    }

    // MARK: - The sealed recipient (security audit M7, the `self` box)

    /// The server lists my message to Alice in my chat with Bob: my self box opens, but the
    /// plaintext names Alice, so it does not show there or in Notes, is not cached, and is
    /// refused (no ack). In Alice's chat it opens.
    func testMyMessageToSomeoneElseDoesNotOpenInThisChat() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let alicePublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let wire = MessageTextPayload.wire(body: "only for Alice", replyTo: nil, to: alice)
        let message = try dto(id: id, sender: me, ciphertext: sealedByMe(wire, for: alicePublic))
        let resolved = Resolved()

        var bobChat = context(repository: repository, threads: [:], resolved: resolved)
        bobChat.chatPeerUserID = bob
        let inBob = await MessageDecoder.decodeChecked(message, context: bobChat)
        XCTAssertTrue(inBob.refused)
        XCTAssertEqual(inBob.message.text, "[Unable to decrypt]")
        XCTAssertTrue(inBob.message.isMine)
        XCTAssertEqual(inBob.message.peerUserID, bob)
        XCTAssertNil(repository.sealedPlaintext(for: id, senderUserID: me))

        var notes = context(repository: repository, threads: [:], resolved: resolved)
        notes.chatPeerUserID = me
        let inNotes = await MessageDecoder.decodeChecked(message, context: notes)
        XCTAssertTrue(inNotes.refused)
        XCTAssertNil(repository.sealedPlaintext(for: id, senderUserID: me))

        var aliceChat = context(repository: repository, threads: [:], resolved: resolved)
        aliceChat.chatPeerUserID = alice
        let inAlice = await MessageDecoder.decodeChecked(message, context: aliceChat)
        XCTAssertFalse(inAlice.refused)
        XCTAssertEqual(inAlice.message.text, "only for Alice")
        XCTAssertEqual(repository.sealedPlaintextText(for: id, senderUserID: me), wire)
        XCTAssertTrue(resolved.ids.isEmpty)
    }

    /// A body cached for this id and sender in Alice's chat is checked like the envelope.
    func testACachedBodyNamingSomeoneElseIsNotShown() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let wire = MessageTextPayload.wire(body: "only for Alice", replyTo: nil, to: alice)
        repository.saveSealedPlaintext(messageID: id, senderUserID: me, text: wire)
        var bobChat = context(repository: repository, threads: [:], resolved: Resolved())
        bobChat.chatPeerUserID = bob

        let listed = try dto(id: id, sender: me, ciphertext: Data("not-an-envelope".utf8).base64EncodedString())
        let refused = await MessageDecoder.decodeChecked(listed, context: bobChat)
        XCTAssertTrue(refused.refused)
        XCTAssertEqual(refused.message.text, "[Unable to decrypt]")
        // Alice's chat keeps its body.
        XCTAssertEqual(repository.sealedPlaintextText(for: id, senderUserID: me), wire)
    }

    /// My bubble held in Alice's thread is not reused for the same id listed in Bob's chat.
    func testMyHeldBubbleFromAnotherChatIsNotReused() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let alicePublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let wire = MessageTextPayload.wire(body: "only for Alice", replyTo: nil, to: alice)
        var bobChat = context(
            repository: repository,
            threads: [alice: [bubble(id: id, peer: alice, sender: me, text: "only for Alice")]],
            resolved: Resolved()
        )
        bobChat.chatPeerUserID = bob

        let listed = try dto(id: id, sender: me, ciphertext: sealedByMe(wire, for: alicePublic))
        let refused = await MessageDecoder.decodeChecked(listed, context: bobChat)
        XCTAssertTrue(refused.refused)
        XCTAssertEqual(refused.message.text, "[Unable to decrypt]")
    }

    /// A message of the peer's that names someone else is refused; one naming me opens.
    func testAPeersMessageNamingSomeoneElseIsRefused() async throws {
        let repository = unlocked()
        let elsewhere = UUID()
        let forMe = UUID()
        messageIDs.append(contentsOf: [elsewhere, forMe])
        repository.saveSealedPlaintext(
            messageID: elsewhere,
            senderUserID: alice,
            text: MessageTextPayload.wire(body: "meant for Bob", replyTo: nil, to: bob)
        )
        repository.saveSealedPlaintext(
            messageID: forMe,
            senderUserID: alice,
            text: MessageTextPayload.wire(body: "for you", replyTo: nil, to: me)
        )
        var aliceChat = context(repository: repository, threads: [:], resolved: Resolved())
        aliceChat.chatPeerUserID = alice

        let misaddressed = try dto(id: elsewhere, sender: alice, ciphertext: nil)
        let refused = await MessageDecoder.decodeChecked(misaddressed, context: aliceChat)
        XCTAssertTrue(refused.refused)
        XCTAssertEqual(refused.message.text, "[Unable to decrypt]")
        XCTAssertFalse(refused.message.isMine)

        let addressed = try dto(id: forMe, sender: alice, ciphertext: nil)
        let opened = await MessageDecoder.decodeChecked(addressed, context: aliceChat)
        XCTAssertFalse(opened.refused)
        XCTAssertEqual(opened.message.text, "for you")
    }

    /// A photo of mine for Alice shows nothing of its payload in Bob's chat.
    func testMyMediaForSomeoneElseIsNotShown() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let payload = MediaMessagePayload(t: "image", mime: "image/jpeg", w: 4, h: 3, k: "a2V5", c: "for Alice", to: alice)
        let encoded = try payload.encoded()
        repository.saveSealedPlaintext(messageID: id, senderUserID: me, data: encoded)
        var bobChat = context(repository: repository, threads: [:], resolved: Resolved())
        bobChat.chatPeerUserID = bob

        let listed = try dto(id: id, sender: me, ciphertext: nil, contentType: "media")
        let refused = await MessageDecoder.decodeChecked(listed, context: bobChat)
        XCTAssertTrue(refused.refused)
        XCTAssertEqual(refused.message.text, "Media")
        XCTAssertNil(refused.message.previewData)
        XCTAssertNil(refused.message.mediaObjectId)
    }

    /// Bodies from builds before the recipient was sealed open wherever they are listed.
    func testAPlaintextNamingNobodyStillOpens() async throws {
        let repository = unlocked()
        let id = UUID()
        messageIDs.append(id)
        let alicePublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        var bobChat = context(repository: repository, threads: [:], resolved: Resolved())
        bobChat.chatPeerUserID = bob

        let listed = try dto(id: id, sender: me, ciphertext: sealedByMe("from an older build", for: alicePublic))
        let legacy = await MessageDecoder.decodeChecked(listed, context: bobChat)
        XCTAssertFalse(legacy.refused)
        XCTAssertEqual(legacy.message.text, "from an older build")
    }

    // MARK: - Helpers

    private func unlocked() -> MessagingLocalRepository {
        let repository = MessagingLocalRepository()
        repository.setHistoryKey(key)
        return repository
    }

    private func bubble(id: UUID, peer: UUID, sender: UUID, text: String) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: id,
            peerUserID: peer,
            senderUserID: sender,
            text: text,
            createdAt: Date(),
            isMine: sender == me,
            deleted: false
        )
    }

    /// My identity for every decode of one test, so an envelope I seal opens from my self box.
    private lazy var material = IdentityKeyMaterial(
        userID: me,
        registrationID: 1,
        agreementPrivateKey: Curve25519.KeyAgreement.PrivateKey(),
        signingPrivateKey: Curve25519.Signing.PrivateKey(),
        historyKey: key,
        signedPreKeyID: 1,
        signedPreKeyPrivate: Curve25519.KeyAgreement.PrivateKey(),
        oneTimePreKeys: []
    )

    /// A v2 envelope of mine (no ratchet state) sealed for `peerPublic`, as the wire carries it.
    private func sealedByMe(_ plaintext: String, for peerPublic: Data) throws -> String {
        try MessageCrypto.seal(
            plaintext: Data(plaintext.utf8),
            toPeerIdentityPublicKey: peerPublic,
            ourPrivateKey: material.agreementPrivateKey,
            ourIdentityPublicKey: material.identityPublicKeyData
        ).base64EncodedString()
    }

    private func context(
        repository: MessagingLocalRepository,
        threads: [UUID: [MessagingController.ChatMessage]],
        resolved: Resolved
    ) -> MessageDecoder.Context {
        MessageDecoder.Context(
            me: me,
            material: material,
            token: "token",
            conversations: [],
            threads: threads,
            local: repository,
            mediaService: MediaService(),
            resolvePeerIdentityPublicKey: { userID, _ in
                resolved.ids.append(userID)
                throw ProbeError.opened
            }
        )
    }

    private func dto(id: UUID, sender: UUID, ciphertext: String?, contentType: String = "text") throws -> MessageDTO {
        var json: [String: Any] = [
            "id": id.uuidString,
            "conversation_id": UUID().uuidString,
            "sender_user_id": sender.uuidString,
            "sender_device_id": UUID().uuidString,
            "client_message_id": UUID().uuidString,
            "content_type": contentType,
            "deleted_for_everyone": false,
            "created_at": "2026-10-02T12:00:00Z",
        ]
        if let ciphertext {
            json["ciphertext"] = ciphertext
        }
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(MessageDTO.self, from: data)
    }
}

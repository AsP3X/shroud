import CryptoKit
import XCTest
@testable import shroud

/// A message id is not a capability. Cached plaintext and a held bubble are reused only for
/// the same sender, and an idempotent media replay remembers the row the server kept.
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

    private func context(
        repository: MessagingLocalRepository,
        threads: [UUID: [MessagingController.ChatMessage]],
        resolved: Resolved
    ) -> MessageDecoder.Context {
        MessageDecoder.Context(
            me: me,
            material: IdentityKeyMaterial(
                userID: me,
                registrationID: 1,
                agreementPrivateKey: Curve25519.KeyAgreement.PrivateKey(),
                signingPrivateKey: Curve25519.Signing.PrivateKey(),
                historyKey: key,
                signedPreKeyID: 1,
                signedPreKeyPrivate: Curve25519.KeyAgreement.PrivateKey(),
                oneTimePreKeys: []
            ),
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

    private func dto(id: UUID, sender: UUID, ciphertext: String?) throws -> MessageDTO {
        var json: [String: Any] = [
            "id": id.uuidString,
            "conversation_id": UUID().uuidString,
            "sender_user_id": sender.uuidString,
            "sender_device_id": UUID().uuidString,
            "client_message_id": UUID().uuidString,
            "content_type": "text",
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

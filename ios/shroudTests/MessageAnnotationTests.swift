import Foundation
import Testing
@testable import shroud

/// Guards the transcript share-back: the annotation wire format (shared with the web client)
/// and how a shared transcript folds into a thread.
@MainActor
struct MessageAnnotationTests {
    private let peer = UUID()

    private func voiceNote(transcript: String? = nil, deleted: Bool = false) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "Voice message",
            createdAt: Date(),
            isMine: false,
            deleted: deleted,
            kind: .voice,
            transcript: transcript
        )
    }

    // MARK: - Wire format

    @Test
    func transcriptRoundTrips() throws {
        let id = UUID()
        let data = try JSONEncoder().encode(MessageAnnotation.transcript("See you at eight", for: id))
        let parsed = try #require(MessageAnnotation.parseTranscript(data))
        #expect(parsed.messageID == id)
        #expect(parsed.text == "See you at eight")
    }

    /// Server ids travel lowercased; the web client compares them that way.
    @Test
    func referenceIsLowercased() throws {
        let id = UUID()
        let data = try JSONEncoder().encode(MessageAnnotation.transcript("hi", for: id))
        let json = try #require(String(data: data, encoding: .utf8))
        #expect(json.contains(id.uuidString.lowercased()))
        #expect(!json.contains(id.uuidString))
    }

    /// Exactly what the web client would send, key order and all.
    @Test
    func readsTheWebEncoding() throws {
        let raw = #"{"t":"transcript","r":"3f2b8c4e-6a1d-4e3b-9c7a-1b2c3d4e5f60","c":"  Bis gleich!  "}"#
        let parsed = try #require(MessageAnnotation.parseTranscript(raw))
        #expect(parsed.messageID == UUID(uuidString: "3F2B8C4E-6A1D-4E3B-9C7A-1B2C3D4E5F60"))
        #expect(parsed.text == "Bis gleich!")
    }

    @Test
    func rejectsAnythingElse() {
        let id = UUID().uuidString.lowercased()
        #expect(MessageAnnotation.parseTranscript("just text") == nil)
        #expect(MessageAnnotation.parseTranscript(#"{"t":"reaction","r":"\#(id)","c":"👍"}"#) == nil)
        #expect(MessageAnnotation.parseTranscript(#"{"t":"transcript","r":"not-a-uuid","c":"x"}"#) == nil)
        #expect(MessageAnnotation.parseTranscript(#"{"t":"transcript","r":"\#(id)","c":"   "}"#) == nil)
    }

    @Test
    func capsAnOverlongTranscript() throws {
        let long = String(repeating: "a", count: MessageAnnotation.maxTranscriptLength + 500)
        let data = try JSONEncoder().encode(MessageAnnotation.transcript(long, for: UUID()))
        let parsed = try #require(MessageAnnotation.parseTranscript(data))
        #expect(parsed.text.count == MessageAnnotation.maxTranscriptLength)
    }

    // MARK: - Folding into a thread

    @Test
    func fillsAVoiceNoteWithoutATranscript() {
        let note = voiceNote()
        let merged = ThreadMessageMerge.applySharedTranscripts([note.id: "Running late"], to: [note])
        #expect(merged.first?.transcript == "Running late")
    }

    /// The sender's sealed transcript (or one made on this device) always wins.
    @Test
    func neverReplacesAnExistingTranscript() {
        let note = voiceNote(transcript: "Sealed by the sender")
        let merged = ThreadMessageMerge.applySharedTranscripts([note.id: "Shared later"], to: [note])
        #expect(merged.first?.transcript == "Sealed by the sender")
    }

    @Test
    func ignoresTranscriptsForUnknownIds() {
        let note = voiceNote()
        let merged = ThreadMessageMerge.applySharedTranscripts([UUID(): "no such note"], to: [note])
        #expect(merged == [note])
        #expect(merged.first?.transcript == nil)
    }

    @Test
    func leavesTextAndDeletedMessagesAlone() {
        let deleted = voiceNote(deleted: true)
        let text = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "hello",
            createdAt: Date(),
            isMine: false,
            deleted: false
        )
        let thread = [deleted, text]
        let merged = ThreadMessageMerge.applySharedTranscripts(
            [deleted.id: "gone", text.id: "not a voice note"],
            to: thread
        )
        #expect(merged == thread)
    }
}

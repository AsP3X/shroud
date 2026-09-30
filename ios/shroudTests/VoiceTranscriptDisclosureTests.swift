import Foundation
import Testing
@testable import shroud

/// The automatic unfold rule is shared with the web client (`transcriptView.ts`).
@MainActor
struct VoiceTranscriptDisclosureTests {
    private let peer = UUID()

    private func voice(deleted: Bool = false) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "Voice message",
            createdAt: Date(),
            isMine: false,
            deleted: deleted,
            kind: .voice
        )
    }

    private func text() -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: peer,
            text: "hello",
            createdAt: Date(),
            isMine: false,
            deleted: false,
            kind: .text
        )
    }

    @Test
    func tailIsTheNewestContiguousVoiceNotes() {
        let older = voice()
        let newer = voice()
        let tail = VoiceTranscriptDisclosure.tail(of: [older, newer])
        #expect(tail == [newer.id, older.id])
    }

    @Test
    func tailStopsAtATextMessage() {
        let voiceNote = voice()
        let tail = VoiceTranscriptDisclosure.tail(of: [voiceNote, text()])
        #expect(tail.isEmpty)
    }

    @Test
    func tailStopsAtADeletedVoiceNote() {
        let live = voice()
        let tail = VoiceTranscriptDisclosure.tail(of: [live, voice(deleted: true)])
        #expect(tail.isEmpty)
    }

    @Test
    func tailCapsAtTwo() {
        let notes = [voice(), voice(), voice()]
        let tail = VoiceTranscriptDisclosure.tail(of: notes)
        #expect(tail == [notes[2].id, notes[1].id])
    }

    @Test
    func shortTranscriptsOpenUnasked() {
        #expect(VoiceTranscriptDisclosure.opensUnasked(transcript: "Hi", durationMs: 1_000, isWorking: false))
        let long = String(repeating: "a", count: VoiceTranscriptDisclosure.autoOpenMaxCharacters + 1)
        #expect(!VoiceTranscriptDisclosure.opensUnasked(transcript: long, durationMs: 1_000, isWorking: false))
    }

    @Test
    func aShortNoteStillTranscribingOpensUnasked() {
        #expect(VoiceTranscriptDisclosure.opensUnasked(transcript: nil, durationMs: 5_000, isWorking: true))
        #expect(!VoiceTranscriptDisclosure.opensUnasked(transcript: nil, durationMs: 5_000, isWorking: false))
        #expect(!VoiceTranscriptDisclosure.opensUnasked(
            transcript: nil,
            durationMs: VoiceTranscriptDisclosure.autoOpenMaxWorkingMs + 1,
            isWorking: true
        ))
    }

    @Test
    func handOffCarriesTheReadersChoice() {
        let disclosure = VoiceTranscriptDisclosure.shared
        let old = UUID()
        let new = UUID()
        defer {
            disclosure.clearChoice(for: old)
            disclosure.clearChoice(for: new)
        }
        disclosure.setOpen(false, for: old)
        disclosure.handOff(from: old, to: new)
        #expect(disclosure.choice(for: old) == nil)
        #expect(disclosure.choice(for: new) == false)
        #expect(disclosure.wasHandedOff(new))
    }
}

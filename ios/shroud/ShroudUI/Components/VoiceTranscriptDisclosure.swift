import Foundation
import Observation

/// How voice notes show their transcripts.
///
/// Human: The newest voice notes at the bottom of a thread — two at most, with nothing newer
/// under them — unfold by themselves unless the transcript runs long. Anything newer folds them
/// away again and drops the reader's own choice for them, so the bottom of a thread always reads
/// the same way. Everywhere else a transcript stays folded until tapped. The web client applies
/// the same rule (`web/src/voice/transcriptView.ts`).
/// Agent: In-memory, session-only view state shared by the list bubble and the long-press hero.
/// `MessagingController` hands a note's state over when the server re-keys it after sending.
@Observable
@MainActor
final class VoiceTranscriptDisclosure {
    static let shared = VoiceTranscriptDisclosure()

    /// How many of the newest voice notes show their transcript without a tap.
    static let tailCount = 2
    /// Longer transcripts wait for a tap even at the bottom: two of them would fill the thread.
    static let autoOpenMaxCharacters = 400
    /// A note still being transcribed only unfolds to show progress when it is this short.
    static let autoOpenMaxWorkingMs = 30_000
    /// Notes younger than this when their bubble appears arrived while the reader was watching.
    static let arrivalWindow: TimeInterval = 15
    /// How long a new note stays folded so it can land before the transcript unfolds.
    static let landingDelayMs: Int = 350

    /// The reader's own fold or unfold; wins over the automatic rule until the note leaves the tail.
    private var choices: [UUID: Bool] = [:]
    /// Server ids that took over a sent bubble, which carries on rather than arriving anew.
    private var handedOff: Set<UUID> = []

    func choice(for id: UUID) -> Bool? {
        choices[id]
    }

    func setOpen(_ open: Bool, for id: UUID) {
        choices[id] = open
    }

    func clearChoice(for id: UUID) {
        choices[id] = nil
    }

    /// The server re-keyed a sent note; its bubble carries on under the new id.
    func handOff(from oldID: UUID, to newID: UUID) {
        guard oldID != newID else { return }
        handedOff.insert(newID)
        if let choice = choices.removeValue(forKey: oldID) {
            choices[newID] = choice
        }
    }

    func wasHandedOff(_ id: UUID) -> Bool {
        handedOff.contains(id)
    }

    /// Ids of the newest voice notes with nothing newer under them, newest first.
    static func tail(of messages: [MessagingController.ChatMessage]) -> [UUID] {
        var tail: [UUID] = []
        for message in messages.reversed() {
            guard tail.count < tailCount, message.kind == .voice, !message.deleted else { break }
            tail.append(message.id)
        }
        return tail
    }

    /// Whether a note in the tail unfolds by itself: short text, or a short note still transcribing.
    static func opensUnasked(transcript: String?, durationMs: Int, isWorking: Bool) -> Bool {
        if let transcript {
            return transcript.count <= autoOpenMaxCharacters
        }
        return isWorking && durationMs <= autoOpenMaxWorkingMs
    }
}

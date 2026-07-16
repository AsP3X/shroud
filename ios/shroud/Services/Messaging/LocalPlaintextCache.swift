import Foundation

/// Stores plaintext for messages this device sent (server ciphertext is sealed to the peer only).
struct LocalPlaintextCache: Sendable {
    private let defaults: UserDefaults
    private let prefix = "msg_plain."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func text(for messageID: UUID) -> String? {
        defaults.string(forKey: prefix + messageID.uuidString.lowercased())
    }

    func save(messageID: UUID, text: String) {
        defaults.set(text, forKey: prefix + messageID.uuidString.lowercased())
    }
}

import Foundation

/// On-device plaintext after first successful decrypt (or send).
///
/// Human: Ratchet keys are one-shot — never re-open the same ciphertext as recipient.
/// Agent: Cache raw payload bytes (UTF-8 text or media JSON) keyed by message id.
struct LocalPlaintextCache: Sendable {
    private let defaults: UserDefaults
    private let prefix = "msg_plain_v2."

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func data(for messageID: UUID) -> Data? {
        defaults.data(forKey: key(messageID))
    }

    func text(for messageID: UUID) -> String? {
        guard let data = data(for: messageID) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func save(messageID: UUID, data: Data) {
        defaults.set(data, forKey: key(messageID))
    }

    func save(messageID: UUID, text: String) {
        save(messageID: messageID, data: Data(text.utf8))
    }

    private func key(_ messageID: UUID) -> String {
        prefix + messageID.uuidString.lowercased()
    }
}

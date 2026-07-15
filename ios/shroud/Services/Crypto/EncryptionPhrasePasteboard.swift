import UIKit
import UniformTypeIdentifiers

/// Local-only pasteboard helpers for copying and reading encryption phrases.
enum EncryptionPhrasePasteboard {
    /// Reverse-DNS UTI so Shroud can prefer its own paste payload over generic text.
    static let customType = "com.shroud.encryption-phrase"
    private static let expirationInterval: TimeInterval = 60

    // Human: Copies the phrase to the device pasteboard with a 60-second local-only expiry.
    // Agent: WRITES UIPasteboard (plainText + custom UTI); never logs phrase content; RETURNS success flag.
    static func copy(_ phrase: String) -> Bool {
        let trimmed = phrase.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }

        UIPasteboard.general.setItems(
            [[
                UTType.plainText.identifier: trimmed,
                customType: trimmed,
            ]],
            options: [
                .expirationDate: Date().addingTimeInterval(expirationInterval),
                .localOnly: true,
            ]
        )

        return UIPasteboard.general.string == trimmed
    }

    // Human: Reads a phrase copied from Shroud or any plain-text source on the pasteboard.
    // Agent: READS UIPasteboard custom UTI first, then plain string; never logs returned phrase.
    static func read() -> String? {
        let pasteboard = UIPasteboard.general
        if let items = pasteboard.items.first,
           let phrase = items[customType] as? String,
           !phrase.isEmpty {
            return phrase
        }

        if let string = pasteboard.string?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !string.isEmpty {
            return string
        }

        return nil
    }
}

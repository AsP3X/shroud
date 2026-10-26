import UIKit
import UniformTypeIdentifiers

/// Local-only pasteboard helpers for copying and reading encryption phrases.
enum EncryptionPhrasePasteboard {
    /// Reverse-DNS type so Shroud can prefer its own paste payload over generic text.
    static let customType = "com.shroud.encryption-phrase"
    /// Auto-clear after 60s so a leftover phrase is less likely to leak later.
    private static let expirationInterval: TimeInterval = 60

    // Human: Copy phrase so the user can paste into Notes / a password manager (or Login paste).
    // Agent: WRITES UIPasteboard plain text (+ custom type); never logs phrase; RETURNS success flag.
    //
    // `.localOnly` keeps the phrase off Universal Clipboard (the user's other Apple devices). It
    // does not confine it to this app: other apps on this iPhone still paste it.
    @MainActor
    static func copy(_ phrase: String) -> Bool {
        let trimmed = phrase.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }

        let pasteboard = UIPasteboard.general
        let options = copyOptions(now: Date())

        // Prefer the multi-representation write with expiry. Include both plain-text UTIs so
        // UIPasteboard.string and other apps resolve the payload reliably (esp. Simulator).
        pasteboard.setItems(
            [[
                UTType.utf8PlainText.identifier: trimmed,
                UTType.plainText.identifier: trimmed,
                customType: trimmed,
            ]],
            options: options
        )

        if payloadMatches(trimmed, on: pasteboard) {
            return true
        }

        // Fallback: plain text alone, with the same options. Never `pasteboard.string = …`,
        // which would sync the phrase to other devices and never expire.
        pasteboard.setItems([[UTType.utf8PlainText.identifier: trimmed]], options: options)
        return payloadMatches(trimmed, on: pasteboard)
    }

    /// Options for every phrase write: this device only, cleared after `expirationInterval`.
    static func copyOptions(now: Date) -> [UIPasteboard.OptionsKey: Any] {
        [
            .localOnly: true,
            .expirationDate: now.addingTimeInterval(expirationInterval),
        ]
    }

    // Human: Reads a phrase copied from Shroud or any plain-text source on the pasteboard.
    // Agent: READS UIPasteboard custom type first, then plain string; never logs returned phrase.
    @MainActor
    static func read() -> String? {
        let pasteboard = UIPasteboard.general

        if let items = pasteboard.items.first,
           let phrase = stringValue(from: items[customType]),
           !phrase.isEmpty {
            return phrase
        }

        if let string = pasteboard.string?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !string.isEmpty {
            return string
        }

        // Some paste sources only expose utf8-plain-text in items, not via `.string`.
        if let items = pasteboard.items.first {
            for key in [UTType.utf8PlainText.identifier, UTType.plainText.identifier] {
                if let phrase = stringValue(from: items[key]), !phrase.isEmpty {
                    return phrase
                }
            }
        }

        return nil
    }

    // MARK: - Private

    @MainActor
    private static func payloadMatches(_ expected: String, on pasteboard: UIPasteboard) -> Bool {
        if pasteboard.string == expected {
            return true
        }
        guard let items = pasteboard.items.first else { return false }
        for key in [customType, UTType.utf8PlainText.identifier, UTType.plainText.identifier] {
            if stringValue(from: items[key]) == expected {
                return true
            }
        }
        return false
    }

    /// Pasteboard item values may arrive as String or Data depending on the writer.
    private static func stringValue(from value: Any?) -> String? {
        switch value {
        case let string as String:
            return string.trimmingCharacters(in: .whitespacesAndNewlines)
        case let data as Data:
            return String(data: data, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        default:
            return nil
        }
    }
}

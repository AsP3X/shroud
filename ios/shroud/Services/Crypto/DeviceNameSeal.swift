import CryptoKit
import Foundation

/// Seals a device's name so only the account's own devices can read it.
///
/// Human: The server used to keep device names ("Niklas's iPhone") in the clear. Now it holds
/// only `devices.sealed_name`, sealed under a key from the phrase's history key, which every
/// device of the account shares and the server never sees. The device id is bound in, so a
/// stored name cannot be moved onto another device, and every name pads to one size, so its
/// length does not show. The kind (iPhone, iPad, browser) rides inside the seal for the icon,
/// with a bit saying a person typed the name, so this iPhone keeps a rename instead of its own.
/// Agent: Same bytes as web `crypto/deviceName.ts` (golden vector in `DeviceNameSealTests`).
///   key       = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-device-name-v1", 32)
///   aad       = "shroud-device-name-v1:" + lowercase device id
///   plaintext = kind(1, | 0x80 when typed by a person) ‖ UTF-8 name ‖ 0x80 ‖ 0x00… to 128 bytes
///   sealed    = nonce(12) ‖ AES-256-GCM ciphertext(128) ‖ tag(16), sent as standard Base64
nonisolated enum DeviceNameSeal {
    enum Kind: UInt8, Sendable, Equatable {
        case other = 0
        case iPhone = 1
        case iPad = 2
        case web = 3
    }

    struct Label: Sendable, Equatable {
        let name: String
        let kind: Kind
        /// A person chose this name (a rename), rather than the device naming itself.
        var custom = false
    }

    enum SealError: Error, Equatable {
        case emptyName
    }

    /// Longest name in UTF-8 bytes; the kind and the padding need two bytes more.
    static let maxNameBytes = 96

    private static let label = "shroud-device-name-v1"
    private static let customBit: UInt8 = 0x80
    private static let paddedBytes = 128
    private static let sealedBytes = 12 + 128 + 16

    /// `nonce` is for test vectors only; leave it out.
    static func seal(
        _ value: Label,
        deviceID: UUID,
        historyKey: SymmetricKey,
        nonce: AES.GCM.Nonce = AES.GCM.Nonce()
    ) throws -> String {
        let name = normalize(value.name)
        guard !name.isEmpty else { throw SealError.emptyName }
        var padded = Data(count: paddedBytes)
        let bytes = Data(name.utf8)
        padded[0] = value.kind.rawValue | (value.custom ? customBit : 0)
        padded.replaceSubrange(1 ..< 1 + bytes.count, with: bytes)
        padded[1 + bytes.count] = 0x80
        let box = try AES.GCM.seal(
            padded,
            using: key(historyKey),
            nonce: nonce,
            authenticating: associatedData(deviceID)
        )
        guard let combined = box.combined else { throw SealError.emptyName }
        return combined.base64EncodedString()
    }

    /// Nil when it does not open: another account's key, another device, or damaged.
    static func open(_ sealedBase64: String?, deviceID: UUID, historyKey: SymmetricKey) -> Label? {
        guard let sealedBase64,
              let combined = Data(base64Encoded: sealedBase64),
              combined.count == sealedBytes,
              let box = try? AES.GCM.SealedBox(combined: combined),
              let padded = try? AES.GCM.open(box, using: key(historyKey), authenticating: associatedData(deviceID))
        else { return nil }
        let bytes = [UInt8](padded)
        guard let end = bytes.lastIndex(where: { $0 != 0 }), end >= 1, bytes[end] == 0x80,
              let name = String(bytes: bytes[1 ..< end], encoding: .utf8), !name.isEmpty
        else { return nil }
        return Label(
            name: name,
            kind: Kind(rawValue: bytes[0] & ~customBit) ?? .other,
            custom: bytes[0] & customBit != 0
        )
    }

    /// One line, no control or bidi characters, at most `maxNameBytes` bytes cut between
    /// characters (an emoji is never split). Same rules as the web client.
    static func normalize(_ raw: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in raw.unicodeScalars {
            scalars.append(isStripped(scalar) ? " " : scalar)
        }
        let oneLine = String(scalars)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        var out = ""
        var used = 0
        for character in oneLine {
            let size = String(character).utf8.count
            if used + size > maxNameBytes { break }
            out.append(character)
            used += size
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    /// Controls, line and paragraph separators, and bidi marks and overrides (a name must not
    /// reorder the text around it). Joiners stay so emoji sequences survive.
    private static func isStripped(_ scalar: Unicode.Scalar) -> Bool {
        if scalar.properties.generalCategory == .control { return true }
        switch scalar.value {
        case 0x2028, 0x2029, 0x200E, 0x200F, 0x202A ... 0x202E, 0x2066 ... 0x2069: return true
        default: return false
        }
    }

    private static func key(_ historyKey: SymmetricKey) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: historyKey,
            salt: Data("shroud-v1".utf8),
            info: Data(label.utf8),
            outputByteCount: 32
        )
    }

    private static func associatedData(_ deviceID: UUID) -> Data {
        Data("\(label):\(deviceID.uuidString.lowercased())".utf8)
    }
}

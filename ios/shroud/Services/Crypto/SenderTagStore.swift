import CryptoKit
import Foundation
import Security

/// When each sender was first seen tagging their identity boxes, as server time.
///
/// Human: A box without a sender tag could have been built by anyone who knows the two public
/// keys, the server included. Once a contact's tagged messages show up, their untagged ones
/// from that time on are refused, so the server cannot slip in a message "from" them.
/// Agent: Keyed by the sender's identity public key (our own for self boxes), so a new phrase
/// is a new sender. Service = bundle id + ".sender-tags"; values are the watermark sealed
/// with `LocalHistoryCrypto` context `.senderTagKeychain`. While locked `taggedSince` reports
/// `.locked` and `noteTagged` drops the write. Wiped with the ratchets on sign-out.
nonisolated enum SenderTagStore {
    enum Watermark: Equatable {
        case locked
        case untagged
        case since(Date)
    }

    private static var service: String {
        (Bundle.main.bundleIdentifier ?? "de.corespace.shroud") + ".sender-tags"
    }

    private static let memoryLock = NSLock()
    /// Serializes `noteTagged`: two decodes racing their read-modify-write could otherwise
    /// leave the later of two watermarks behind.
    private static let writeLock = NSLock()
    private nonisolated(unsafe) static var memory: [String: Date]?

    /// Unit tests: simulator test hosts lack the Keychain entitlement.
    static func useInMemoryStorageForTesting() {
        memoryLock.withLock { if memory == nil { memory = [:] } }
    }

    static func taggedSince(senderIdentityPublic: Data) -> Watermark {
        let account = account(for: senderIdentityPublic)
        if let date = memoryLock.withLock({ memory.map { $0[account] } }) {
            return date.map(Watermark.since) ?? .untagged
        }
        guard let historyKey = SealedLocalState.historyKey else { return .locked }
        let stored: Data
        switch readItem(account: account) {
        case let .found(data): stored = data
        case .notFound: return .untagged
        // A Keychain error is not "never tagged" either; the decode is retried later.
        case .failed: return .since(.distantPast)
        }
        guard let opened = try? LocalHistoryCrypto.open(stored, masterKey: historyKey, context: .senderTagKeychain),
              let seconds = Double(String(decoding: opened, as: UTF8.self))
        else {
            // Unreadable is not "never tagged": that would reopen the door this closes.
            return .since(.distantPast)
        }
        return .since(Date(timeIntervalSince1970: seconds))
    }

    /// Records a verified tag. The watermark only ever moves earlier.
    static func noteTagged(senderIdentityPublic: Data, sentAt: Date) {
        writeLock.withLock { noteTaggedLocked(senderIdentityPublic: senderIdentityPublic, sentAt: sentAt) }
    }

    private static func noteTaggedLocked(senderIdentityPublic: Data, sentAt: Date) {
        if case let .since(existing) = taggedSince(senderIdentityPublic: senderIdentityPublic),
           existing <= sentAt
        {
            return
        }
        let account = account(for: senderIdentityPublic)
        let stored = memoryLock.withLock { () -> Bool in
            guard memory != nil else { return false }
            memory?[account] = sentAt
            return true
        }
        if stored { return }
        guard let historyKey = SealedLocalState.historyKey,
              let sealed = try? LocalHistoryCrypto.seal(
                  Data(String(sentAt.timeIntervalSince1970).utf8),
                  masterKey: historyKey,
                  context: .senderTagKeychain
              )
        else { return }
        writeItem(account: account, value: sealed)
    }

    static func deleteAll() {
        memoryLock.withLock { if memory != nil { memory = [:] } }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Keychain

    private static func account(for senderIdentityPublic: Data) -> String {
        SHA256.hash(data: senderIdentityPublic).map { String(format: "%02x", $0) }.joined()
    }

    private enum ReadResult {
        case found(Data)
        case notFound
        case failed
    }

    private static func readItem(account: String) -> ReadResult {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &item) {
        case errSecSuccess:
            guard let data = item as? Data else { return .failed }
            return .found(data)
        case errSecItemNotFound:
            return .notFound
        default:
            return .failed
        }
    }

    private static func writeItem(account: String, value: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = value
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

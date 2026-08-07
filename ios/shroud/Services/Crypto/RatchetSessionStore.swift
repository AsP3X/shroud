import Foundation
import Security

/// Keychain-backed Double Ratchet sessions keyed by peer user id.
/// Human: Ratchet state never leaves the device.
/// Agent: Service = bundle id + ".dr-sessions"; one item per peer.
enum RatchetSessionStore {
    private static var service: String {
        (Bundle.main.bundleIdentifier ?? "de.corespace.shroud") + ".dr-sessions"
    }

    static func load(peerUserID: UUID) -> DoubleRatchet.Session? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: peerUserID.uuidString.lowercased(),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(DoubleRatchet.Session.self, from: data)
    }

    static func save(_ session: DoubleRatchet.Session, peerUserID: UUID) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        let account = peerUserID.uuidString.lowercased()
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func delete(peerUserID: UUID) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: peerUserID.uuidString.lowercased(),
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

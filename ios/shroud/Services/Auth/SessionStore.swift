import Foundation
import Security

/// Persists session token + device id in the Keychain (never logs secrets).
/// Human: Survives app restarts so the user stays logged in like Signal.
/// Agent: READS/WRITES Keychain; never stores encryption phrase here.
nonisolated struct SessionStore: Sendable {
    private let service: String

    init(service: String = "com.shroud.session") {
        self.service = service
    }

    /// In-memory snapshot of the signed-in session (token never printed).
    struct Session: Equatable, Sendable {
        let token: String
        let userID: UUID
        let username: String
        /// Short public share code for QR / links (may be nil for pre-migration sessions).
        let shareCode: String?
        let deviceID: UUID
        let deviceName: String?
    }

    func load() -> Session? {
        guard
            let token = read(key: Key.token),
            let userIDString = read(key: Key.userID),
            let userID = UUID(uuidString: userIDString),
            let username = read(key: Key.username),
            let deviceIDString = read(key: Key.deviceID),
            let deviceID = UUID(uuidString: deviceIDString)
        else {
            return nil
        }
        let deviceName = read(key: Key.deviceName)
        let shareCode = read(key: Key.shareCode)
        return Session(
            token: token,
            userID: userID,
            username: username,
            shareCode: shareCode,
            deviceID: deviceID,
            deviceName: deviceName
        )
    }

    func save(_ session: Session) throws {
        try write(key: Key.token, value: session.token)
        try write(key: Key.userID, value: session.userID.uuidString)
        try write(key: Key.username, value: session.username)
        try write(key: Key.deviceID, value: session.deviceID.uuidString)
        if let shareCode = session.shareCode, !shareCode.isEmpty {
            try write(key: Key.shareCode, value: shareCode)
        } else {
            delete(key: Key.shareCode)
        }
        if let deviceName = session.deviceName {
            try write(key: Key.deviceName, value: deviceName)
        } else {
            delete(key: Key.deviceName)
        }
    }

    /// Removes every Keychain item for this service (not only known account keys).
    /// Prefer this over piecemeal deletes so a partial write can never leave a loadable session.
    func clear() {
        let allForService: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(allForService as CFDictionary)
        // Belt-and-suspenders for accounts written under older clients.
        for key in Key.all {
            delete(key: key)
        }
    }

    // MARK: - Keychain

    private enum Key {
        static let token = "session_token"
        static let userID = "user_id"
        static let username = "username"
        static let shareCode = "share_code"
        static let deviceID = "device_id"
        static let deviceName = "device_name"

        static let all = [token, userID, username, shareCode, deviceID, deviceName]
    }

    private func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func write(key: String, value: String) throws {
        delete(key: key)
        guard let data = value.data(using: .utf8) else {
            throw SessionStoreError.encodingFailed
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw SessionStoreError.keychain(status)
        }
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

enum SessionStoreError: Error, Equatable {
    case encodingFailed
    case keychain(OSStatus)
}

import Foundation
import Security

/// Persists session token + device id in the Keychain (never logs secrets).
/// Human: Survives app restarts so the user stays logged in like Signal.
/// Agent: READS/WRITES Keychain; never stores encryption phrase here.
nonisolated struct SessionStore: Sendable {
    static let defaultService = "com.shroud.session"
    /// Device id kept across logout. The next login of this username sends it back, so the
    /// server reuses the device instead of minting one toward the 5-device cap.
    static var appDeviceAnchorService: String { defaultService + ".device-anchor" }

    private let service: String

    init(service: String = SessionStore.defaultService) {
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

    /// Removes session Keychain items. Device id for the last username is kept so a later
    /// login of the same account can reuse the server device instead of minting a new one.
    func clear() {
        let allForService: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(allForService as CFDictionary)
        for key in Key.all {
            delete(key: key)
        }
    }

    struct DeviceAnchorRecord: Equatable, Sendable {
        var username: String
        var deviceID: UUID
    }

    /// Device id last persisted for `username` (case-insensitive), if any.
    func loadDeviceID(matchingUsername username: String) -> UUID? {
        guard let record = loadDeviceAnchorRecord() else { return nil }
        let needle = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty, record.username.lowercased() == needle else { return nil }
        return record.deviceID
    }

    /// Username and device id, if both are stored. Logout reads this so it can put the anchor
    /// back when a keychain wipe cannot list items and has to delete the whole class.
    func loadDeviceAnchorRecord() -> DeviceAnchorRecord? {
        guard let username = readDevice(key: DeviceKey.username),
              !username.isEmpty,
              let idString = readDevice(key: DeviceKey.deviceID),
              let deviceID = UUID(uuidString: idString)
        else { return nil }
        return DeviceAnchorRecord(username: username, deviceID: deviceID)
    }

    func saveDeviceAnchor(username: String, deviceID: UUID) {
        let normalized = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        try? writeDevice(key: DeviceKey.username, value: normalized)
        try? writeDevice(key: DeviceKey.deviceID, value: deviceID.uuidString)
    }

    /// True only when the Keychain answers "no session item". Human: a locked Keychain (before the
    /// first unlock after a restart, e.g. a VoIP push launch) is not an answer — `load()` returns
    /// nil then too, and must never be read as "logged out" by anything that deletes data.
    func hasNoSession() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Key.token,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        return SecItemCopyMatching(query as CFDictionary, nil) == errSecItemNotFound
    }

    func clearDeviceAnchor() {
        deleteDevice(key: DeviceKey.username)
        deleteDevice(key: DeviceKey.deviceID)
        let all: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: deviceService,
        ]
        SecItemDelete(all as CFDictionary)
    }

    // MARK: - Keychain

    var deviceAnchorService: String { service + ".device-anchor" }

    private var deviceService: String { deviceAnchorService }

    private enum Key {
        static let token = "session_token"
        static let userID = "user_id"
        static let username = "username"
        static let shareCode = "share_code"
        static let deviceID = "device_id"
        static let deviceName = "device_name"

        static let all = [token, userID, username, shareCode, deviceID, deviceName]
    }

    private enum DeviceKey {
        static let username = "anchor_username"
        static let deviceID = "anchor_device_id"
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

    private func readDevice(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: deviceService,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func writeDevice(key: String, value: String) throws {
        deleteDevice(key: key)
        guard let data = value.data(using: .utf8) else {
            throw SessionStoreError.encodingFailed
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: deviceService,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw SessionStoreError.keychain(status)
        }
    }

    private func deleteDevice(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: deviceService,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

enum SessionStoreError: Error, Equatable {
    case encodingFailed
    case keychain(OSStatus)
}

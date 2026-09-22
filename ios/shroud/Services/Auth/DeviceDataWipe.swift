import Foundation
import LocalAuthentication
import Security
import UIKit
import UserNotifications

/// Removes everything Shroud keeps on this device for the account, and proves it.
///
/// The device-id anchor (`SessionStore.appDeviceAnchorService`) is the exception: it is not
/// account content, and dropping it makes the next login mint a device until the 5-device cap.
///
/// Human: "Logged out" has to mean nothing of the account is left — not the messages or the
/// keys, and not a cached HTTP response, an app-switcher screenshot or the keyboard's learned
/// words either. So the wipe works on *locations* rather than on the stores that exist today:
/// every file in the app's container goes except a short keep-list (the Whisper model weights),
/// every Keychain item the app can see goes, and every UserDefaults key except the server
/// address and which speech model is installed. `leftovers()` scans the same places again, so a
/// store someone adds later shows up as a leftover instead of quietly surviving a logout.
///
/// Agent: DELETES container files, Keychain items, UserDefaults keys, URLCache, cookies,
/// notifications and a phrase on the pasteboard; READS them back in `leftovers()`. Never deletes
/// files in `Library/Preferences` (owned by cfprefsd) or `Library/HTTPStorages` (held open by
/// CFNetwork) — their contents go through `UserDefaults` / `HTTPCookieStorage`.
struct DeviceDataWipe {
    enum Step: String, CaseIterable, Sendable {
        case session, messages, media, keys, settings, verify
    }

    /// What was stored when the wipe began — the numbers the overlay reports.
    struct Inventory: Equatable, Sendable {
        var messages = 0
        var mediaFiles = 0
        var mediaBytes: Int64 = 0
        var keys = 0
        var settings = 0
    }

    /// Something `leftovers()` found, and the step that should have removed it.
    struct Leftover: Equatable, Sendable {
        let step: Step
        let label: String
    }

    /// Where the app keeps data. Tests point this at a temporary directory.
    struct Locations: Sendable {
        var library: URL
        var documents: URL
        var temporary: URL

        var applicationSupport: URL { library.appendingPathComponent("Application Support", isDirectory: true) }
        var caches: URL { library.appendingPathComponent("Caches", isDirectory: true) }
        var shroud: URL { applicationSupport.appendingPathComponent("shroud", isDirectory: true) }

        static var app: Locations {
            let files = FileManager.default
            return Locations(
                library: files.urls(for: .libraryDirectory, in: .userDomainMask)[0],
                documents: files.urls(for: .documentDirectory, in: .userDomainMask)[0],
                temporary: files.temporaryDirectory
            )
        }
    }

    enum KeychainScope: Sendable {
        /// Every item this app can read, except the device-id anchor. There are no shared access
        /// groups, so that is the session, identity, history vault, ratchets and peer pins.
        case app
        /// Only these services — unit tests, so they never touch the simulator's real session.
        case services([String])
    }

    /// Survives the wipe: the server this app talks to, which Whisper model is on disk (the
    /// weights stay, so their bookkeeping does too), and the system per-app language — an iOS
    /// Settings choice, not something the account stored. The marker goes last, once verified.
    static let pendingKey = "shroud.deviceWipe.pending"
    static let keptDefaultsKeys: Set<String> = [
        "shroud.server.configuration", "transcription.model", pendingKey,
        "AppleLanguages", "AppleLocale",
    ]

    static func keepsDefaultsKey(_ key: String) -> Bool {
        keptDefaultsKeys.contains(key) || (key.hasPrefix("transcription.") && key.hasSuffix(".ready"))
    }

    let locations: Locations
    let keychain: KeychainScope
    let defaults: UserDefaults
    /// The persistent domain `defaults` writes to (the bundle id, or a test suite name).
    let defaultsDomain: String
    /// URLCache, cookies, delivered notifications, the badge and the pasteboard. Off in tests.
    let touchesSystemState: Bool

    static var app: DeviceDataWipe {
        DeviceDataWipe(
            locations: .app,
            keychain: .app,
            defaults: .standard,
            defaultsDomain: Bundle.main.bundleIdentifier ?? "de.corespace.shroud",
            touchesSystemState: true
        )
    }

    // MARK: - Pending marker

    /// Set before the first deletion, cleared once `leftovers()` comes back empty. A launch that
    /// finds it finishes the wipe the user asked for (the app was killed mid-way).
    var isPending: Bool { defaults.bool(forKey: Self.pendingKey) }

    func markPending() {
        defaults.set(true, forKey: Self.pendingKey)
    }

    func clearPending() {
        defaults.removeObject(forKey: Self.pendingKey)
    }

    // MARK: - Inventory

    func inventory() -> Inventory {
        let media = mediaFiles()
        return Inventory(
            messages: messageFiles().count,
            mediaFiles: media.count,
            mediaBytes: media.reduce(0) { $0 + $1.bytes },
            keys: keychainItemCount() ?? 0,
            settings: removableDefaultsKeys().count
        )
    }

    // MARK: - Steps

    /// Messages: the sealed thread snapshots, rosters and message bodies.
    func wipeMessages() {
        for root in messageRoots { remove(root) }
    }

    /// Photos, videos, voice notes and anything cached from the network: the sealed media
    /// store, temporary files (decrypted clips, recordings, exports) and Library/Caches.
    func wipeMedia() {
        if touchesSystemState { URLCache.shared.removeAllCachedResponses() }
        remove(locations.shroud.appendingPathComponent("media", isDirectory: true))
        sweep(locations.temporary)
        sweep(locations.caches)
    }

    /// Keys: every Keychain item — session, identity, history vault, ratchets, peer pins — except
    /// the device-id anchor. Deleting never asks for Face ID, even for an item that needs it to
    /// be read.
    func wipeKeys() {
        let preserving = preservedKeychainServices
        // Tests name the services they own. An empty preserve set means "delete exactly those",
        // never a scan of the simulator's real keychain.
        if preserving.isEmpty || !deleteEnumeratedGenericPasswords(preserving: preserving) {
            let anchor = preserving.isEmpty ? nil : SessionStore().loadDeviceAnchorRecord()
            for query in keychainQueries() {
                SecItemDelete(query as CFDictionary)
            }
            if let anchor {
                SessionStore().saveDeviceAnchor(username: anchor.username, deviceID: anchor.deviceID)
            }
            return
        }
        for secClass in Self.keychainClasses where secClass != kSecClassGenericPassword {
            let query: [String: Any] = [
                kSecClass as String: secClass,
                kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            ]
            SecItemDelete(query as CFDictionary)
        }
    }

    /// True when this item belongs to the account. A nil service is not the device anchor.
    static func shouldDeleteKeychainService(_ service: String?, preserving: Set<String>) -> Bool {
        guard let service else { return true }
        return !preserving.contains(service)
    }

    /// Settings and traces: UserDefaults (minus the keep-list), every other file in the container
    /// — app-switcher snapshots, saved scene state, the keyboard's learned words — then cookies,
    /// delivered notifications, the badge and a phrase still on the pasteboard.
    @MainActor
    func wipeSettings() async {
        for key in removableDefaultsKeys() { defaults.removeObject(forKey: key) }
        sweep(locations.applicationSupport)
        sweep(locations.documents)
        for child in children(of: locations.library) where !Self.libraryHandledElsewhere.contains(child.lastPathComponent) {
            sweep(child)
        }
        guard touchesSystemState else { return }
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
        try? await center.setBadgeCount(0)
        if UIPasteboard.general.contains(pasteboardTypes: [EncryptionPhrasePasteboard.customType]) {
            UIPasteboard.general.items = []
        }
    }

    /// Runs the four deleting steps again — `verify` does this once before it gives up.
    @MainActor
    func wipeEverything() async {
        wipeMessages()
        wipeMedia()
        wipeKeys()
        await wipeSettings()
    }

    // MARK: - Verify

    /// Everything that would contradict "nothing of the account is left". Empty means clean.
    @MainActor
    func leftovers() async -> [Leftover] {
        var found: [Leftover] = []
        if !messageFiles().isEmpty { found.append(Leftover(step: .messages, label: "messages")) }
        if !mediaFiles().isEmpty { found.append(Leftover(step: .media, label: "media and cached files")) }
        if keychainItemCount() != 0 { found.append(Leftover(step: .keys, label: "encryption keys")) }
        if !removableDefaultsKeys().isEmpty || !settingsFiles().isEmpty {
            found.append(Leftover(step: .settings, label: "settings"))
        }
        if touchesSystemState {
            if !(HTTPCookieStorage.shared.cookies ?? []).isEmpty {
                found.append(Leftover(step: .settings, label: "cookies"))
            }
            if !(await UNUserNotificationCenter.current().deliveredNotifications()).isEmpty {
                found.append(Leftover(step: .settings, label: "notifications"))
            }
        }
        return found
    }

    // MARK: - Where things are

    private var messageRoots: [URL] {
        [
            locations.shroud.appendingPathComponent("messages", isDirectory: true),
            locations.shroud.appendingPathComponent("plaintext", isDirectory: true),
        ]
    }

    /// Public model weights, and shader caches the GPU driver holds open. Nothing of the account.
    private var keptPaths: [URL] {
        [
            locations.applicationSupport.appendingPathComponent("huggingface", isDirectory: true),
            locations.documents.appendingPathComponent("huggingface", isDirectory: true),
        ]
    }

    /// `Library` children that are swept by their own step or only through an API.
    private static let libraryHandledElsewhere: Set<String> = [
        "Application Support", "Caches", "Preferences", "HTTPStorages",
    ]

    private static let keychainClasses: [CFString] = [
        kSecClassGenericPassword, kSecClassInternetPassword, kSecClassKey,
        kSecClassCertificate, kSecClassIdentity,
    ]

    private func isKept(_ url: URL) -> Bool {
        let path = url.standardizedFileURL.path
        if keptPaths.contains(where: { path == $0.standardizedFileURL.path || path.hasPrefix($0.standardizedFileURL.path + "/") }) {
            return true
        }
        return url.pathComponents.contains { $0.hasPrefix("com.apple.metal") }
    }

    private func messageFiles() -> [URL] {
        messageRoots.flatMap { regularFiles(under: $0).map(\.url) }
    }

    private func mediaFiles() -> [(url: URL, bytes: Int64)] {
        [locations.shroud.appendingPathComponent("media", isDirectory: true), locations.temporary, locations.caches]
            .flatMap { regularFiles(under: $0) }
    }

    private func settingsFiles() -> [URL] {
        let library = children(of: locations.library)
            .filter { !Self.libraryHandledElsewhere.contains($0.lastPathComponent) }
        let accounted = Set((messageFiles() + mediaFiles().map(\.url)).map(\.standardizedFileURL.path))
        return ([locations.applicationSupport, locations.documents] + library)
            .flatMap { regularFiles(under: $0).map(\.url) }
            .filter { !accounted.contains($0.standardizedFileURL.path) }
    }

    private func removableDefaultsKeys() -> [String] {
        (defaults.persistentDomain(forName: defaultsDomain) ?? [:]).keys.filter { !Self.keepsDefaultsKey($0) }
    }

    // MARK: - Files

    private func children(of directory: URL) -> [URL] {
        (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: []
        )) ?? []
    }

    private func isDirectory(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
    }

    /// URLCache's own database (`Library/Caches/<bundle id>/Cache.db`, `-wal`, `-shm`). The
    /// sweep deletes it like everything else, which unlinks what it held — but CFNetwork keeps it
    /// open and re-creates an empty one on the next touch, so it is checked through the API.
    private func isURLCacheStore(_ url: URL) -> Bool {
        ["Cache.db", "Cache.db-wal", "Cache.db-shm"].contains(url.lastPathComponent)
            && url.deletingLastPathComponent().deletingLastPathComponent().standardizedFileURL.path
                == locations.caches.standardizedFileURL.path
    }

    /// Every regular file under `root` that the wipe owns, with its size.
    private func regularFiles(under root: URL) -> [(url: URL, bytes: Int64)] {
        guard !isKept(root), !isURLCacheStore(root), FileManager.default.fileExists(atPath: root.path) else {
            return []
        }
        guard isDirectory(root) else {
            let size = (try? root.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return [(root, Int64(size))]
        }
        return children(of: root).flatMap { regularFiles(under: $0) }
    }

    private func remove(_ url: URL) {
        guard !isKept(url) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Deletes everything inside `directory` except kept paths. The directory itself stays: the
    /// system owns `tmp`, `Caches` and friends. A child that holds something kept is swept into.
    private func sweep(_ directory: URL) {
        for child in children(of: directory) where !isKept(child) {
            if isDirectory(child), holdsKept(child) {
                sweep(child)
            } else {
                try? FileManager.default.removeItem(at: child)
            }
        }
    }

    private func holdsKept(_ directory: URL) -> Bool {
        children(of: directory).contains { isKept($0) || (isDirectory($0) && holdsKept($0)) }
    }

    // MARK: - Keychain

    /// Items left, or nil when that cannot be read without asking for Face ID — which `verify`
    /// counts as "still here" rather than as clean. The device-id anchor is not account data.
    private func keychainItemCount() -> Int? {
        let context = LAContext()
        context.interactionNotAllowed = true
        let preserving = preservedKeychainServices
        var total = 0
        for base in keychainQueries() {
            var query = base
            query[kSecReturnAttributes as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitAll
            query[kSecUseAuthenticationContext as String] = context
            var result: CFTypeRef?
            switch SecItemCopyMatching(query as CFDictionary, &result) {
            case errSecSuccess:
                let rows = result as? [[String: Any]] ?? []
                if rows.isEmpty {
                    total += 1
                } else {
                    total += rows.filter {
                        Self.shouldDeleteKeychainService(
                            $0[kSecAttrService as String] as? String,
                            preserving: preserving
                        )
                    }.count
                }
            case errSecItemNotFound:
                continue
            default:
                return nil
            }
        }
        return total
    }

    /// The real app keeps its device anchor. A test scope deletes only the services it created.
    private var preservedKeychainServices: Set<String> {
        switch keychain {
        case .app: [SessionStore.appDeviceAnchorService]
        case .services: []
        }
    }

    /// Deletes generic passwords except `preserving`. False when the items cannot be listed —
    /// the caller then deletes the class and writes the anchor back.
    private func deleteEnumeratedGenericPasswords(preserving: Set<String>) -> Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true,
            kSecReturnPersistentRef as String: true,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecUseAuthenticationContext as String: context,
        ]
        var result: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &result) {
        case errSecItemNotFound:
            return true
        case errSecSuccess:
            break
        default:
            return false
        }
        for row in (result as? [[String: Any]]) ?? [] {
            let service = row[kSecAttrService as String] as? String
            guard Self.shouldDeleteKeychainService(service, preserving: preserving) else { continue }
            if let ref = row[kSecValuePersistentRef as String] as? Data {
                SecItemDelete([kSecValuePersistentRef as String: ref] as CFDictionary)
                continue
            }
            var delete: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            ]
            if let service { delete[kSecAttrService as String] = service }
            if let account = row[kSecAttrAccount as String] as? String {
                delete[kSecAttrAccount as String] = account
            }
            guard service != nil || row[kSecAttrAccount as String] != nil else { continue }
            SecItemDelete(delete as CFDictionary)
        }
        return true
    }

    /// Synced and device-only items alike: a query without `kSecAttrSynchronizable` only matches
    /// the device-only ones.
    private func keychainQueries() -> [[String: Any]] {
        let any: [String: Any] = [kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        switch keychain {
        case .app:
            return Self.keychainClasses.map { any.merging([kSecClass as String: $0]) { $1 } }
        case let .services(services):
            return services.map {
                any.merging([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: $0]) { $1 }
            }
        }
    }
}

extension DeviceDataWipe.Inventory {
    /// "37 files · 18.2 MB"
    var mediaSummary: String {
        let files = mediaFiles == 1 ? "1 file" : "\(mediaFiles) files"
        guard mediaBytes > 0 else { return files }
        let size = ByteCountFormatter.string(fromByteCount: mediaBytes, countStyle: .file)
        return "\(files) · \(size)"
    }
}

import Foundation
import LocalAuthentication
import Security
import UIKit
import UserNotifications

/// Removes everything Shroud keeps on this device for the account, and proves it.
///
/// The device-id anchor goes too: at the 5-device cap the server hands the next login the
/// account's longest-idle device with no live session.
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
/// CFNetwork) — their contents go through `UserDefaults` / `HTTPCookieStorage`. Never unlinks
/// `Library/Caches/<bundle>/Cache.db` either: CFNetwork keeps that sqlite file open, and
/// deleting it logs "vnode unlinked while in use". `URLCache.removeAllCachedResponses` empties it.
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
        /// Matches `TranscriptionLanguageMemory.defaultFileURL`'s directory.
        var voiceLanguageStats: URL { shroud.appendingPathComponent("voice", isDirectory: true) }

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
        /// Every item this app can read: the session, identity, history vault, ratchets, peer
        /// pins and the device-id anchor, and in the app group's access group the key the
        /// notification extension opens sender names with. A query without an access group
        /// covers all of the app's groups.
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

    /// Keys: every Keychain item — session, identity, history vault, ratchets, peer pins, the
    /// device anchor. Deleting never asks for Face ID, even for an item that needs it to be read.
    func wipeKeys() {
        for query in keychainQueries() {
            SecItemDelete(query as CFDictionary)
        }
    }

    /// Settings and traces: UserDefaults (minus the keep-list), every other file in the container
    /// — app-switcher snapshots, saved scene state, the keyboard's learned words — then cookies,
    /// delivered notifications, the badge and a phrase still on the pasteboard.
    @MainActor
    func wipeSettings() async {
        for key in removableDefaultsKeys() { defaults.removeObject(forKey: key) }
        // Sealed voice language statistics, keyed by who the user exchanges voice notes with.
        // The sweep below would take it too; named so a narrower sweep cannot miss it.
        remove(locations.voiceLanguageStats)
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

    /// URLCache's own database (`Library/Caches/<bundle id>/Cache.db`, `-wal`, `-shm`).
    /// CFNetwork holds these open for the life of the process. Unlinking them is the sqlite
    /// error "vnode unlinked while in use". The wipe empties the cache through `URLCache` and
    /// leaves the files in place, the same way it leaves `Library/HTTPStorages` alone.
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
        guard !isKept(url), !isURLCacheStore(url) else { return }
        if isDirectory(url), holdsProtected(url) {
            sweep(url)
            return
        }
        try? FileManager.default.removeItem(at: url)
    }

    /// Deletes everything inside `directory` except kept paths and URLCache's open database.
    /// The directory itself stays: the system owns `tmp`, `Caches` and friends. A child that
    /// holds something we must not unlink is swept into, so the parent `removeItem` cannot
    /// unlink `Cache.db` out from under CFNetwork.
    private func sweep(_ directory: URL) {
        for child in children(of: directory) where !isKept(child) && !isURLCacheStore(child) {
            if isDirectory(child), holdsProtected(child) {
                sweep(child)
            } else {
                try? FileManager.default.removeItem(at: child)
            }
        }
    }

    private func holdsProtected(_ directory: URL) -> Bool {
        children(of: directory).contains {
            isKept($0) || isURLCacheStore($0) || (isDirectory($0) && holdsProtected($0))
        }
    }

    // MARK: - Keychain

    /// Items left, or nil when that cannot be read without asking for Face ID — which `verify`
    /// counts as "still here" rather than as clean.
    private func keychainItemCount() -> Int? {
        let context = LAContext()
        context.interactionNotAllowed = true
        var total = 0
        for base in keychainQueries() {
            var query = base
            query[kSecReturnAttributes as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitAll
            query[kSecUseAuthenticationContext as String] = context
            var result: CFTypeRef?
            switch SecItemCopyMatching(query as CFDictionary, &result) {
            case errSecSuccess:
                total += max(1, (result as? [[String: Any]])?.count ?? 0)
            case errSecItemNotFound:
                continue
            default:
                return nil
            }
        }
        return total
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

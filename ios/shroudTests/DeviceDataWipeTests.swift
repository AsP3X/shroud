import Foundation
import Security
import Testing
@testable import shroud

/// The logout wipe against a fake app container, a test UserDefaults suite and Keychain items
/// under a throwaway service — never the simulator's real session.
@MainActor
struct DeviceDataWipeTests {
    /// A container laid out like the real one on a simulator that has been used for a while.
    @MainActor
    private struct Fixture {
        let root: URL
        let wipe: DeviceDataWipe
        let service: String
        let suite: String
        let defaults: UserDefaults

        init() throws {
            root = FileManager.default.temporaryDirectory
                .appendingPathComponent("wipe-\(UUID().uuidString)", isDirectory: true)
            service = "com.shroud.tests.wipe." + UUID().uuidString
            suite = "shroud.tests.wipe." + UUID().uuidString
            defaults = try #require(UserDefaults(suiteName: suite))
            wipe = DeviceDataWipe(
                locations: .init(
                    library: root.appendingPathComponent("Library", isDirectory: true),
                    documents: root.appendingPathComponent("Documents", isDirectory: true),
                    temporary: root.appendingPathComponent("tmp", isDirectory: true)
                ),
                keychain: .services([service]),
                defaults: defaults,
                defaultsDomain: suite,
                touchesSystemState: false
            )
        }

        func write(_ path: String, bytes: Int = 16) throws {
            let url = root.appendingPathComponent(path)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(repeating: 7, count: bytes).write(to: url)
        }

        func exists(_ path: String) -> Bool {
            FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path)
        }

        func addKeychainItem(_ account: String) throws {
            let status = SecItemAdd([
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
                kSecValueData as String: Data("secret".utf8),
            ] as CFDictionary, nil)
            #expect(status == errSecSuccess)
        }

        func tearDown() {
            try? FileManager.default.removeItem(at: root)
            SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
            UserDefaults.standard.removePersistentDomain(forName: suite)
        }

        /// Account data in every place the app (or iOS on its behalf) writes it.
        func seedAccount() throws {
            let user = "bc26c1ed-22cb-4a74-b98f-581c6faca09d"
            try write("Library/Application Support/shroud/messages/\(user)/roster.sealed")
            try write("Library/Application Support/shroud/messages/\(user)/threads/aa8c4d04.sealed")
            try write("Library/Application Support/shroud/plaintext/e09da208.sealed")
            try write("Library/Application Support/shroud/plaintext/bc159ab5.sealed")
            try write("Library/Application Support/shroud/media/46177c15.sealed", bytes: 4096)
            try write("Library/Application Support/shroud/voice/language-stats.sealed")
            try write("tmp/decrypted-clip.mov", bytes: 2048)
            try write("Library/Caches/de.corespace.shroud/Cache.db", bytes: 1024)
            try write("Library/Caches/de.corespace.shroud/Cache.db-wal", bytes: 64)
            try write("Library/Caches/de.corespace.shroud/Cache.db-shm", bytes: 32)
            try write("Library/Caches/de.corespace.shroud/fsCachedData/0F1E", bytes: 512)
            try write("Library/Caches/com.apple.speech.localspeechrecognition/cache.bin")
            try write("Library/SplashBoard/Snapshots/sceneID:de.corespace.shroud-default/chat@3x.ktx")
            try write("Library/Saved Application State/de.corespace.shroud.savedState/data.data")
            try write("Library/LanguageModeling/en-dynamic.lm/Info.plist")
            try write("Documents/export.txt")
            // Not the account's: must survive.
            try write("Library/Application Support/huggingface/models/whisper/weights.bin")
            try write("Documents/huggingface/legacy/weights.bin")
            try write("Library/Caches/de.corespace.shroud/com.apple.metal/shaders.data")
            try write("Library/Preferences/de.corespace.shroud.plist")
            try write("Library/HTTPStorages/de.corespace.shroud/httpstorages.sqlite")
            try addKeychainItem("session_token")
            try addKeychainItem("identity")
            try addKeychainItem("ratchet-aa8c4d04")
            defaults.set(Data("{}".utf8), forKey: "shroud.server.configuration")
            defaults.set("base", forKey: "transcription.model")
            defaults.set("base", forKey: "transcription.whisperkit.ready")
            defaults.set(false, forKey: "security.lockChatsOnBackground")
            defaults.set(["*": ["de": 2.0]], forKey: "transcription.languageStats")
            defaults.set("de-DE", forKey: "transcription.locale")
            defaults.set(Data("hello".utf8), forKey: "msg_plain_v2.e09da208")
        }
    }

    @Test
    func inventoryCountsWhatIsThere() async throws {
        let fixture = try Fixture()
        defer { fixture.tearDown() }
        try fixture.seedAccount()

        let inventory = fixture.wipe.inventory()
        #expect(inventory.messages == 4)
        // The sealed media, the temp clip and two cached files. Not the shader cache, and not
        // URLCache's Cache.db: CFNetwork keeps that file open, so the wipe must not unlink it.
        #expect(inventory.mediaFiles == 4)
        #expect(inventory.mediaBytes == 4096 + 2048 + 512 + 16)
        #expect(inventory.keys == 3)
        #expect(inventory.settings == 4)
    }

    @Test
    func wipeLeavesOnlyTheKeepList() async throws {
        let fixture = try Fixture()
        defer { fixture.tearDown() }
        try fixture.seedAccount()
        #expect(await !fixture.wipe.leftovers().isEmpty)

        fixture.wipe.wipeMessages()
        fixture.wipe.wipeMedia()
        fixture.wipe.wipeKeys()
        await fixture.wipe.wipeSettings()

        #expect(await fixture.wipe.leftovers().isEmpty)
        #expect(fixture.wipe.inventory() == DeviceDataWipe.Inventory())
        for gone in [
            "Library/Application Support/shroud",
            "tmp/decrypted-clip.mov",
            "Library/Caches/de.corespace.shroud/fsCachedData",
            "Library/Caches/com.apple.speech.localspeechrecognition",
            "Library/SplashBoard/Snapshots/sceneID:de.corespace.shroud-default",
            "Library/Saved Application State/de.corespace.shroud.savedState",
            "Library/LanguageModeling/en-dynamic.lm",
            "Documents/export.txt",
        ] {
            #expect(!fixture.exists(gone), "\(gone) survived the wipe")
        }
        for kept in [
            "Library/Application Support/huggingface/models/whisper/weights.bin",
            "Documents/huggingface/legacy/weights.bin",
            "Library/Caches/de.corespace.shroud/Cache.db",
            "Library/Caches/de.corespace.shroud/Cache.db-wal",
            "Library/Caches/de.corespace.shroud/Cache.db-shm",
            "Library/Caches/de.corespace.shroud/com.apple.metal/shaders.data",
            "Library/Preferences/de.corespace.shroud.plist",
            "Library/HTTPStorages/de.corespace.shroud/httpstorages.sqlite",
        ] {
            #expect(fixture.exists(kept), "\(kept) should have been kept")
        }
        let remaining = Set(fixture.defaults.persistentDomain(forName: fixture.suite)?.keys.map { $0 } ?? [])
        #expect(remaining == ["shroud.server.configuration", "transcription.model", "transcription.whisperkit.ready"])
    }

    @Test
    func verifyNamesTheStepThatMissedSomething() async throws {
        let fixture = try Fixture()
        defer { fixture.tearDown() }
        await fixture.wipe.wipeEverything()
        #expect(await fixture.wipe.leftovers().isEmpty)

        // A store added later that the wipe does not know about still shows up.
        try fixture.write("Library/Application Support/shroud/plaintext/late.sealed")
        try fixture.write("Library/Application Support/new-feature/cache.json")
        try fixture.addKeychainItem("late")
        fixture.defaults.set(true, forKey: "someFeature.flag")

        let found = Set(await fixture.wipe.leftovers().map(\.step))
        #expect(found == [.messages, .keys, .settings])

        await fixture.wipe.wipeEverything()
        #expect(await fixture.wipe.leftovers().isEmpty)
    }

    @Test
    func pendingMarkerOutlivesTheSettingsStepUntilCleared() async throws {
        let fixture = try Fixture()
        defer { fixture.tearDown() }
        fixture.wipe.markPending()
        await fixture.wipe.wipeSettings()
        #expect(fixture.wipe.isPending)
        #expect(await fixture.wipe.leftovers().isEmpty)
        fixture.wipe.clearPending()
        #expect(!fixture.wipe.isPending)
    }

    @Test
    func keepListIsNarrow() {
        #expect(DeviceDataWipe.keepsDefaultsKey("shroud.server.configuration"))
        #expect(DeviceDataWipe.keepsDefaultsKey("transcription.model"))
        #expect(DeviceDataWipe.keepsDefaultsKey("transcription.whisperkit.ready"))
        #expect(DeviceDataWipe.keepsDefaultsKey(DeviceDataWipe.pendingKey))
        #expect(DeviceDataWipe.keepsDefaultsKey("AppleLanguages"))
        #expect(DeviceDataWipe.keepsDefaultsKey("AppleLocale"))
        #expect(!DeviceDataWipe.keepsDefaultsKey("transcription.locale"))
        #expect(!DeviceDataWipe.keepsDefaultsKey(TranscriptionPreferences.automaticKey))
        #expect(!DeviceDataWipe.keepsDefaultsKey("transcription.languageStats"))
        #expect(!DeviceDataWipe.keepsDefaultsKey("security.requireUserPresence"))
        #expect(!DeviceDataWipe.keepsDefaultsKey("msg_plain_v2.e09da208"))
    }

    @Test
    func noSessionOnlyWhenTheKeychainSaysSo() throws {
        let store = SessionStore(service: "com.shroud.session.test." + UUID().uuidString)
        #expect(store.hasNoSession())
        try store.save(
            SessionStore.Session(
                token: "tok",
                userID: UUID(),
                username: "alice",
                shareCode: nil,
                deviceID: UUID()
            )
        )
        #expect(!store.hasNoSession())
        store.clear()
        #expect(store.hasNoSession())
    }

    @Test
    func mediaSummaryReadsLikeTheDesign() {
        var inventory = DeviceDataWipe.Inventory()
        inventory.mediaFiles = 37
        inventory.mediaBytes = 18_200_000
        #expect(inventory.mediaSummary.hasPrefix("37 files · "))
        inventory.mediaFiles = 1
        inventory.mediaBytes = 0
        #expect(inventory.mediaSummary == "1 file")
    }
}

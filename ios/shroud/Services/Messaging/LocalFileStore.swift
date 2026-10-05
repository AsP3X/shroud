import CryptoKit
import Foundation

/// Shared files on this device: the SHRF1 blob exactly as it came from the server, or as it was
/// sealed for sending (`docs/file-sharing.md` §8).
///
/// Human: No decrypted file stays on disk. The blob is opened by its own random key `k`, and
/// `k` lives only in the history-sealed payload cache, so a blob here is as protected as every
/// other sealed media file. Opening one decrypts into `tmp/shroud-file-{id}/` for as long as
/// Quick Look or the share sheet needs it (`FileOpenStaging`).
/// Agent: Application Support `shroud/files/{message id}.shrf`, file protection `complete`,
/// excluded from backup. Removed with the message's other caches
/// (`MessagingLocalRepository.removeCaches` / `clearAll`) and by `DeviceDataWipe.wipeMedia`.
nonisolated struct LocalFileStore: Sendable {
    let directory: URL

    init(directory: URL = LocalFileStore.defaultDirectory) {
        self.directory = directory
    }

    static var defaultDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base
            .appendingPathComponent("shroud", isDirectory: true)
            .appendingPathComponent("files", isDirectory: true)
    }

    func url(for messageID: UUID) -> URL {
        directory.appendingPathComponent(messageID.uuidString.lowercased() + ".shrf")
    }

    func hasBlob(_ messageID: UUID) -> Bool {
        FileManager.default.fileExists(atPath: url(for: messageID).path)
    }

    /// A fresh path inside the store to seal or download into; `adopt` moves it into place.
    ///
    /// Human: A blob is only ever visible under its message id once it is whole, so a kill
    /// half-way through a seal never leaves a short blob that looks downloaded.
    func makeStagingURL() -> URL {
        prepare()
        return directory.appendingPathComponent(".staging-\(UUID().uuidString.lowercased())")
    }

    /// Moves a finished blob (sealed here, or a download) in as `messageID`'s.
    func adopt(_ source: URL, as messageID: UUID) throws {
        prepare()
        let target = url(for: messageID)
        let files = FileManager.default
        try? files.removeItem(at: target)
        try files.moveItem(at: source, to: target)
        lockDown(target)
    }

    /// The server keyed the message anew: the blob follows its id.
    func rekey(from oldID: UUID, to newID: UUID) {
        guard oldID != newID, hasBlob(oldID) else { return }
        try? adopt(url(for: oldID), as: newID)
    }

    func remove(messageIDs: [UUID]) {
        for id in messageIDs {
            try? FileManager.default.removeItem(at: url(for: id))
        }
    }

    /// Removes half-written blobs a kill left behind (`makeStagingURL`); run at launch, before
    /// anything seals or downloads.
    func sweepStaging() {
        let files = FileManager.default
        guard let names = try? files.contentsOfDirectory(atPath: directory.path) else { return }
        for name in names where name.hasPrefix(".staging-") {
            try? files.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    /// Logout / local wipe.
    func clearAll() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func prepare() {
        LocalDataProtection.prepareDirectory(directory)
    }

    private func lockDown(_ url: URL) {
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        LocalDataProtection.lockDown(url: url)
    }
}

/// Where an opened file's plaintext lives while Quick Look or the share sheet has it:
/// `tmp/shroud-file-{id}/{name}`.
///
/// Human: The folder is decrypted into under a hidden staging name and the file only takes its
/// real name once the last tag checked, so nothing is ever handed on half-verified. It is
/// removed when the preview or the share sheet closes; `SensitiveTempFiles` sweeps whatever a
/// crash left (`shroud-*`).
nonisolated enum FileOpenStaging {
    static func folder(for messageID: UUID) -> URL {
        FileManager.default.temporaryDirectory
            // `SensitiveTempFiles.prefix` + "file-": the sweep matches on the prefix.
            .appendingPathComponent("shroud-file-\(messageID.uuidString.lowercased())", isDirectory: true)
    }

    /// An empty folder for `messageID` and the staging path inside it.
    static func prepare(for messageID: UUID) throws -> URL {
        let folder = folder(for: messageID)
        let files = FileManager.default
        try? files.removeItem(at: folder)
        try files.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(".staging")
    }

    /// The longest file name APFS takes, in UTF-8 bytes.
    static let maxDiskNameBytes = 255

    /// `name` as it can sit on disk: a cleaned name is at most 120 code points, which in CJK or
    /// emoji is far more than 255 UTF-8 bytes. The stem is cut at a character boundary until
    /// stem + "." + extension fits; the extension stays. The UI keeps the full name.
    ///
    /// Agent: Measured decomposed (NFD), the longer form, since Foundation may hand the file
    /// system either; a name that fits as NFD fits as NFC too.
    static func diskName(for name: String) -> String {
        guard diskBytes(name) > maxDiskNameBytes else { return name }
        let ext = SharedFile.fileExtension(of: name)
        let suffix = ext.map { "." + $0 } ?? ""
        var stem = Substring(name.dropLast(suffix.count))
        let budget = maxDiskNameBytes - suffix.utf8.count
        while diskBytes(String(stem)) > budget, !stem.isEmpty {
            stem = stem.dropLast()
        }
        let trimmed = stem.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        return (trimmed.isEmpty ? "file" : trimmed) + suffix
    }

    /// UTF-8 length of `name` in its longer, decomposed form.
    static func diskBytes(_ name: String) -> Int {
        name.decomposedStringWithCanonicalMapping.utf8.count
    }

    /// Opens the stored blob into the folder, checks its first bytes against `type` (§4) and
    /// gives it its on-disk name. Blocking: run it off the main actor.
    ///
    /// Agent: On any throw the folder is gone. `isDamage` tells a blob that failed its checks
    /// from a file-system refusal.
    static func open(
        blob: URL,
        key: SymmetricKey,
        plaintextSize: Int64,
        messageID: UUID,
        name: String,
        type: SharedFile.FileType
    ) throws -> (url: URL, contentMatches: Bool) {
        do {
            let staging = try prepare(for: messageID)
            try FileBlob.open(from: blob, to: staging, key: key, plaintextSize: plaintextSize)
            let header = (try? FileHandle(forReadingFrom: staging)).flatMap { handle in
                defer { try? handle.close() }
                return try? handle.read(upToCount: SharedFile.contentCheckBytes)
            } ?? Data()
            let url = try commit(staging, name: name)
            return (url, SharedFile.contentMatches(type, header: header))
        } catch {
            remove(for: messageID)
            throw error
        }
    }

    /// True when `error` says the blob itself is wrong (and worth downloading again), not that
    /// the disk or the file system refused.
    static func isDamage(_ error: Error) -> Bool {
        switch error as? FileBlob.BlobError {
        case .tampered, .wrongLength, .badHeader: true
        case .invalidKey, .unreadable, nil: false
        }
    }

    /// Gives the verified plaintext its cleaned name (cut to fit the disk, `diskName`).
    static func commit(_ staging: URL, name: String) throws -> URL {
        let target = staging.deletingLastPathComponent().appendingPathComponent(diskName(for: name))
        let files = FileManager.default
        try? files.removeItem(at: target)
        try files.moveItem(at: staging, to: target)
        return target
    }

    static func remove(for messageID: UUID) {
        try? FileManager.default.removeItem(at: folder(for: messageID))
    }
}

import Foundation

/// Decrypted media that has to exist as a file for a moment — a video for AVPlayer, a note for
/// Whisper, a photo for the share sheet, an export or a recording — lives in `tmp/` as
/// `shroud-*`. Every writer removes its own file, but a crash or a kill skips that, and the
/// file then sits there in the clear until the next logout.
///
/// Two layers keep it off the disk in readable form:
/// - `tmp/` is set to `completeUnlessOpen` (the default class stays readable from the first
///   unlock after boot on): new files inherit it, so once the phone locks nothing in there can
///   be read, while a file the app still holds open keeps working.
/// - `sweep` deletes leftovers: all of them at launch, and stale ones when chats lock.
enum SensitiveTempFiles {
    static let prefix = "shroud-"

    /// Sets the protection class new `tmp/` files inherit, and clears what an earlier run left.
    static func prepareAtLaunch(fileManager: FileManager = .default) {
        let tmp = fileManager.temporaryDirectory
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: tmp.path
        )
        sweep(fileManager: fileManager)
    }

    /// Deletes `shroud-*` files in `tmp/`. With `olderThan`, only those not written to for that
    /// long — so a recording or a playback that is still running keeps its file.
    static func sweep(olderThan age: TimeInterval? = nil, fileManager: FileManager = .default) {
        let tmp = fileManager.temporaryDirectory
        guard let names = try? fileManager.contentsOfDirectory(atPath: tmp.path) else { return }
        let cutoff = age.map { Date().addingTimeInterval(-$0) }
        for name in names where name.hasPrefix(prefix) {
            let url = tmp.appendingPathComponent(name)
            if let cutoff {
                let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
                    .contentModificationDate
                if let modified, modified > cutoff { continue }
            }
            try? fileManager.removeItem(at: url)
        }
    }
}

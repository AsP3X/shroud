import Foundation

/// On-device cache of decrypted media bytes keyed by message id (not a secret store).
struct LocalMediaCache: Sendable {
    private let directory: URL

    init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directory = base.appendingPathComponent("shroud-media", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func data(for messageID: UUID) -> Data? {
        let url = fileURL(messageID)
        return try? Data(contentsOf: url)
    }

    func save(messageID: UUID, data: Data) {
        try? data.write(to: fileURL(messageID), options: .atomic)
    }

    private func fileURL(_ messageID: UUID) -> URL {
        directory.appendingPathComponent(messageID.uuidString.lowercased() + ".bin")
    }
}

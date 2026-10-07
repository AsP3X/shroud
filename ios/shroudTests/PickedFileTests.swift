import CryptoKit
import Foundation
import Synchronization
import Testing
@testable import shroud

/// A document-picker pick (`docs/file-sharing.md` §7 "Attach"): kept where it is, never copied
/// at pick time, and never deleted by its cleanup.
struct PickedFileTests {
    private static func makeFile(_ name: String, bytes: Int64) throws -> URL {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("picked-file-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(name)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: UInt64(bytes))
        try handle.close()
        return url
    }

    /// The files in `tmp/` (but this suite's own folders) as large as the pick: a copy of it.
    /// Only those, since the host app may write small files there meanwhile.
    private static func largeTmpFiles(atLeast bytes: Int64) -> Set<String> {
        let tmp = FileManager.default.temporaryDirectory
        let names = (try? FileManager.default.contentsOfDirectory(atPath: tmp.path)) ?? []
        return Set(names.filter { name in
            !name.hasPrefix("picked-file-tests-")
                && (FileBlob.fileSize(at: tmp.appendingPathComponent(name)) ?? 0) >= bytes
        })
    }

    /// A scope whose start says yes, counting its stops.
    private final class StopCounter: Sendable {
        let stops = Mutex(0)
    }

    @Test
    func aLargePickIsNotCopied() throws {
        let source = try Self.makeFile("Backup.mkv", bytes: 1_500_000_000)
        defer { try? FileManager.default.removeItem(at: source.deletingLastPathComponent()) }
        let before = Self.largeTmpFiles(atLeast: 1_500_000_000)

        let file = try PickedFile.open(source).get()

        #expect(file.url == source)
        #expect(file.byteCount == 1_500_000_000)
        #expect(file.name == "Backup.mkv")
        #expect(Self.largeTmpFiles(atLeast: 1_500_000_000) == before)
        file.cleanup()
    }

    @Test
    func aHeldScopeStopsExactlyOnce() {
        let counter = StopCounter()
        let access = ScopedAccess(start: { true }, stop: { counter.stops.withLock { $0 += 1 } })
        let file = PickedFile(
            url: URL(filePath: "/nowhere/Report.pdf"),
            access: access,
            name: "Report.pdf",
            byteCount: 1,
            type: SharedFile.type(forName: "Report.pdf")!
        )
        let movie = PickedMovie(url: file.url, access: file.access)

        #expect(counter.stops.withLock { $0 } == 0)
        file.cleanup()
        file.cleanup()
        movie.cleanup()
        #expect(counter.stops.withLock { $0 } == 1)
    }

    @Test
    func aDroppedScopeStopsOnce() {
        let counter = StopCounter()
        do {
            _ = ScopedAccess(start: { true }, stop: { counter.stops.withLock { $0 += 1 } })
        }
        #expect(counter.stops.withLock { $0 } == 1)
    }

    @Test
    func aScopeThatNeverOpenedNeverStops() {
        let counter = StopCounter()
        let access = ScopedAccess(start: { false }, stop: { counter.stops.withLock { $0 += 1 } })
        access.release()
        #expect(counter.stops.withLock { $0 } == 0)
    }

    @Test
    func aMissingFileIsUnavailable() throws {
        let gone = FileManager.default.temporaryDirectory.appendingPathComponent("picked-file-tests-\(UUID().uuidString).pdf")
        #expect(throws: PickedFile.ReadError.unavailable) {
            try PickedFile.coordinatedRead(gone) { try Data(contentsOf: $0) }
        }
    }

    @Test
    func cleanupKeepsTheOriginal() throws {
        let source = try Self.makeFile("Notes.pdf", bytes: 1024)
        defer { try? FileManager.default.removeItem(at: source.deletingLastPathComponent()) }

        let file = try PickedFile.open(source).get()
        file.cleanup()
        file.cleanup()

        #expect(FileManager.default.fileExists(atPath: source.path))
        #expect(FileBlob.fileSize(at: source) == 1024)
    }

    @Test
    func aMovieFromFilesKeepsTheOriginal() throws {
        let source = try Self.makeFile("Clip.mov", bytes: 2048)
        defer { try? FileManager.default.removeItem(at: source.deletingLastPathComponent()) }

        let file = try PickedFile.open(source).get()
        PickedMovie(url: file.url, access: file.access).cleanup()

        #expect(FileManager.default.fileExists(atPath: source.path))
    }

    @Test
    func theSealReadsTheOriginal() throws {
        let source = try Self.makeFile("Data.csv", bytes: 0)
        defer { try? FileManager.default.removeItem(at: source.deletingLastPathComponent()) }
        let plaintext = Data((0 ..< 200_000).map { UInt8($0 % 251) })
        try plaintext.write(to: source)
        let sealed = source.deletingLastPathComponent().appendingPathComponent("sealed")
        let key = FileBlob.makeKey()

        let file = try PickedFile.open(source).get()
        let count = try file.read { try FileBlob.seal(from: $0, to: sealed, key: key) }
        file.cleanup()

        #expect(count == file.byteCount)
        #expect(try FileBlob.open(Data(contentsOf: sealed), key: key, plaintextSize: count) == plaintext)
    }

    @Test
    func refusalsComeFromTheMetadata() throws {
        let empty = try Self.makeFile("Empty.txt", bytes: 0)
        let large = try Self.makeFile("Huge.pdf", bytes: SharedFile.maxPlaintextBytes + 1)
        defer {
            try? FileManager.default.removeItem(at: empty.deletingLastPathComponent())
            try? FileManager.default.removeItem(at: large.deletingLastPathComponent())
        }

        #expect(PickedFile.open(empty).failure == .empty("Empty.txt"))
        #expect(PickedFile.open(large).failure == .tooLarge("Huge.pdf"))
    }
}

private extension Result {
    var failure: Failure? {
        if case let .failure(error) = self { return error }
        return nil
    }
}

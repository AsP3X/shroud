import CryptoKit
import Foundation
import Testing
@testable import shroud

/// SHRF1 (`docs/file-sharing.md` §3), pinned to `node scripts/gen_file_vectors.mjs` — the same
/// bytes the web client and Android produce.
struct FileBlobTests {
    private static let key = SymmetricKey(data: Data((0 ..< 32).map { UInt8($0) }))
    private static let prefix = Data([0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6])

    private static func pattern(_ count: Int) -> Data {
        Data((0 ..< count).map { UInt8($0 % 251) })
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256(_ data: Data) -> String {
        hex(Data(SHA256.hash(data: data)))
    }

    // MARK: - Vectors

    @Test
    func helloMatchesTheVector() throws {
        let blob = try FileBlob.seal(Data("hello".utf8), key: Self.key, noncePrefix: Self.prefix)
        #expect(blob.count == 37)
        #expect(Self.hex(blob) == "5348524631a0a1a2a3a4a5a6000100001f042572d7a195d96a199b78b66a618521c6b35bc9")
    }

    @Test(arguments: [
        (65536, 65568, "440b505128af2e20c12d50b1009d4113697e1ecb8b9791171608c1e3fc83d74e"),
        (65537, 65585, "6caead1d0580c871ba5d374b0ba5ff1482ebaf769370a637c6597b832d5f3b08"),
        (200_000, 200_080, "b59a0b0206599c2fb139a31cc5a82d0feb6e8e6a715f5e4dbc03081275f924a5"),
    ])
    func patternMatchesTheVector(plaintext: Int, sealed: Int, digest: String) throws {
        let blob = try FileBlob.seal(Self.pattern(plaintext), key: Self.key, noncePrefix: Self.prefix)
        #expect(blob.count == sealed)
        #expect(Self.sha256(blob) == digest)
        #expect(FileBlob.sealedSize(Int64(plaintext)) == Int64(sealed))
    }

    @Test
    func sealedSizeFollowsTheFormula() {
        #expect(FileBlob.sealedSize(0) == 32)
        #expect(FileBlob.sealedSize(5) == 37)
        #expect(FileBlob.sealedSize(65536) == 65568)
        #expect(FileBlob.sealedSize(65537) == 65585)
        #expect(FileBlob.sealedSize(Int64(SharedFile.maxPlaintextBytes)) < Int64(VideoMedia.maxSealedBytes))
    }

    // MARK: - Streaming through files

    @Test(arguments: [1, 5, 65535, 65536, 65537, 200_000])
    func streamingRoundTripMatchesTheInMemoryBlob(count: Int) throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = dir.appendingPathComponent("plain.bin")
        let sealed = dir.appendingPathComponent("sealed.shrf")
        let opened = dir.appendingPathComponent("opened.bin")
        let data = Self.pattern(count)
        try data.write(to: plain)

        let written = try FileBlob.seal(from: plain, to: sealed, key: Self.key, noncePrefix: Self.prefix)
        #expect(written == Int64(count))
        let blob = try Data(contentsOf: sealed)
        #expect(blob == (try FileBlob.seal(data, key: Self.key, noncePrefix: Self.prefix)))
        #expect(FileBlob.hasValidHeader(at: sealed))

        try FileBlob.open(from: sealed, to: opened, key: Self.key, plaintextSize: Int64(count))
        #expect(try Data(contentsOf: opened) == data)
    }

    @Test
    func streamingSealReportsProgressToTheEnd() throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = dir.appendingPathComponent("plain.bin")
        try Self.pattern(3 * 65536 + 7).write(to: plain)
        var last = 0.0
        try FileBlob.seal(from: plain, to: dir.appendingPathComponent("s"), key: Self.key) { last = $0 }
        #expect(last == 1)
    }

    // MARK: - Refusals

    @Test
    func aFlippedByteFailsItsTag() throws {
        var blob = try FileBlob.seal(Self.pattern(200_000), key: Self.key, noncePrefix: Self.prefix)
        blob[blob.startIndex + 70_000] ^= 0x01
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(blob, key: Self.key, plaintextSize: 200_000)
        }
    }

    @Test
    func aChangedHeaderFailsEveryTag() throws {
        var blob = try FileBlob.seal(Self.pattern(10), key: Self.key, noncePrefix: Self.prefix)
        blob[blob.startIndex + 6] ^= 0x01 // inside the nonce prefix, which is also the AAD
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(blob, key: Self.key, plaintextSize: 10)
        }
    }

    @Test
    func truncationIsRefusedByLength() throws {
        let blob = try FileBlob.seal(Self.pattern(200_000), key: Self.key, noncePrefix: Self.prefix)
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.open(blob.dropLast(16), key: Self.key, plaintextSize: 200_000)
        }
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.open(blob + Data([0]), key: Self.key, plaintextSize: 200_000)
        }
    }

    @Test
    func aWrongSizeIsRefused() throws {
        let blob = try FileBlob.seal(Self.pattern(1000), key: Self.key, noncePrefix: Self.prefix)
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.open(blob, key: Self.key, plaintextSize: 999)
        }
    }

    @Test
    func reorderedSegmentsFailTheirTags() throws {
        let blob = try FileBlob.seal(Self.pattern(3 * 65536), key: Self.key, noncePrefix: Self.prefix)
        let segment = 65536 + 16
        let header = blob.prefix(16)
        let first = blob.subdata(in: 16 ..< 16 + segment)
        let second = blob.subdata(in: 16 + segment ..< 16 + 2 * segment)
        let rest = blob.suffix(from: 16 + 2 * segment)
        let swapped = header + second + first + rest
        #expect(swapped.count == blob.count)
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(Data(swapped), key: Self.key, plaintextSize: 3 * 65536)
        }
    }

    /// Dropping the real last segment and claiming the shorter size leaves a final segment
    /// without the last flag: the length fits, the tag does not.
    @Test
    func aMissingLastFlagFailsTheTag() throws {
        let blob = try FileBlob.seal(Self.pattern(65537), key: Self.key, noncePrefix: Self.prefix)
        let cut = blob.prefix(16 + 65536 + 16)
        #expect(Int64(cut.count) == FileBlob.sealedSize(65536))
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(Data(cut), key: Self.key, plaintextSize: 65536)
        }
    }

    @Test
    func aLastFlagOnAnEarlierSegmentFailsItsTag() throws {
        // Two single-segment blobs (each flagged last) spliced into one two-segment blob.
        let a = try FileBlob.seal(Self.pattern(65536), key: Self.key, noncePrefix: Self.prefix)
        let b = try FileBlob.seal(Data([7]), key: Self.key, noncePrefix: Self.prefix)
        let spliced = a + b.suffix(from: 16)
        #expect(Int64(spliced.count) == FileBlob.sealedSize(65537))
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(Data(spliced), key: Self.key, plaintextSize: 65537)
        }
    }

    @Test
    func theWrongKeyFails() throws {
        let blob = try FileBlob.seal(Self.pattern(100), key: Self.key, noncePrefix: Self.prefix)
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(blob, key: SymmetricKey(size: .bits256), plaintextSize: 100)
        }
    }

    @Test
    func aForeignMagicOrSegmentSizeIsRefused() throws {
        var magic = try FileBlob.seal(Self.pattern(100), key: Self.key, noncePrefix: Self.prefix)
        magic[magic.startIndex + 4] = UInt8(ascii: "2")
        #expect(throws: FileBlob.BlobError.badHeader) {
            try FileBlob.open(magic, key: Self.key, plaintextSize: 100)
        }
        var size = try FileBlob.seal(Self.pattern(100), key: Self.key, noncePrefix: Self.prefix)
        size[size.startIndex + 13] = 0x02 // 0x00020000 = 128 KiB
        #expect(throws: FileBlob.BlobError.badHeader) {
            try FileBlob.open(size, key: Self.key, plaintextSize: 100)
        }
    }

    @Test
    func streamingOpenRefusesTamperedFiles() throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let sealed = dir.appendingPathComponent("sealed.shrf")
        var blob = try FileBlob.seal(Self.pattern(200_000), key: Self.key, noncePrefix: Self.prefix)
        blob[blob.endIndex - 1] ^= 0x80 // the last tag
        try blob.write(to: sealed)
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.open(from: sealed, to: dir.appendingPathComponent("out"), key: Self.key, plaintextSize: 200_000)
        }
        try blob.dropLast().write(to: sealed)
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.open(from: sealed, to: dir.appendingPathComponent("out"), key: Self.key, plaintextSize: 200_000)
        }
        #expect(!FileBlob.hasValidHeader(at: dir.appendingPathComponent("missing")))
    }

    private static func scratch() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("file-blob-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

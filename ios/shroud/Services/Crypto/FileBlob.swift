import CryptoKit
import Foundation

/// The SHRF1 blob a shared file travels in (`docs/file-sharing.md` §3).
///
/// Human: A file can be 2 GB, so it is never sealed as one AES-GCM message the way a photo is.
/// It is cut into 64 KiB segments, each a plain one-shot AES-GCM call with the hardware AES,
/// and read and written one segment at a time from file handles — memory stays at a couple of
/// segments whatever the file's size. The header is every segment's AAD and the nonce carries
/// the index and a "last" flag, so reordering, dropping, truncating, appending or splicing
/// segments fails a tag.
/// Agent: Same bytes as `scripts/gen_file_vectors.mjs`, the web client and Android
/// (`FileBlobTests` pins them). `open` writes plaintext only to the output it is given, and the
/// caller commits that output only after it returned — a tag that fails half way leaves a
/// staging file the caller deletes.
nonisolated enum FileBlob {
    enum BlobError: Error, Equatable {
        /// Not "SHRF1", or a segment size other than 64 KiB.
        case badHeader
        /// The blob is not `sealedSize(s)` bytes for the payload's `s`.
        case wrongLength
        /// A tag did not check: tampered, reordered, truncated or the wrong key.
        case tampered
        case invalidKey
        /// The input ended early or changed under the reader.
        case unreadable
    }

    static let magic = Data("SHRF1".utf8)
    static let segmentSize = 65_536
    static let headerSize = 16
    static let noncePrefixSize = 7
    static let tagSize = 16
    /// Progress is reported every this many segments (1 MiB) — often enough for the ring.
    private static let progressStride = 16

    /// Blob length for `plaintextBytes` of file: header + plaintext + one tag per segment.
    static func sealedSize(_ plaintextBytes: Int64) -> Int64 {
        Int64(headerSize) + plaintextBytes + Int64(tagSize) * segmentCount(plaintextBytes)
    }

    static func segmentCount(_ plaintextBytes: Int64) -> Int64 {
        max(1, (plaintextBytes + Int64(segmentSize) - 1) / Int64(segmentSize))
    }

    /// A fresh 256-bit key for one file. Never reused: a retry uploads the same sealed bytes.
    static func makeKey() -> SymmetricKey {
        SymmetricKey(size: .bits256)
    }

    static func makeNoncePrefix() -> Data {
        var bytes = [UInt8](repeating: 0, count: noncePrefixSize)
        for i in bytes.indices { bytes[i] = UInt8.random(in: 0 ... 255) }
        return Data(bytes)
    }

    static func header(noncePrefix: Data) -> Data {
        var header = magic
        header.append(noncePrefix)
        header.append(contentsOf: bigEndian(UInt32(segmentSize)))
        return header
    }

    // MARK: - Streaming (files)

    /// Seals the file at `input` into a new file at `output`, one segment at a time.
    ///
    /// Agent: RETURNS the plaintext byte count. `output` is created with file protection
    /// `complete`; an existing file there is replaced. Checks for cancellation per segment.
    @discardableResult
    static func seal(
        from input: URL,
        to output: URL,
        key: SymmetricKey,
        noncePrefix: Data = makeNoncePrefix(),
        onProgress: ((Double) -> Void)? = nil
    ) throws -> Int64 {
        guard noncePrefix.count == noncePrefixSize else { throw BlobError.invalidKey }
        let total = fileSize(at: input) ?? 0
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        let writer = try createOutput(output)
        defer { try? writer.close() }

        let header = header(noncePrefix: noncePrefix)
        try writer.write(contentsOf: header)

        // One segment of lookahead tells which segment is the last.
        var current = try readSegment(reader)
        var index: UInt32 = 0
        var written: Int64 = 0
        while true {
            try Task.checkCancellation()
            let next = try readSegment(reader)
            let last = next.isEmpty
            try autoreleasepool {
                let sealed = try sealSegment(current, index: index, last: last, key: key, noncePrefix: noncePrefix, header: header)
                try writer.write(contentsOf: sealed)
            }
            written += Int64(current.count)
            if let onProgress, total > 0, index % UInt32(progressStride) == 0 || last {
                onProgress(min(1, Double(written) / Double(total)))
            }
            if last { break }
            guard index < UInt32.max else { throw BlobError.unreadable }
            index += 1
            current = next
        }
        return written
    }

    /// Opens the blob at `input` into `output`, checking every tag.
    ///
    /// Agent: Throws before writing anything when the header or the length is wrong. A tag
    /// failure part-way leaves `output` partly written: the caller must delete it and never
    /// show, save or hand it on. Only a normal return means every segment, the last flag
    /// included, checked.
    static func open(
        from input: URL,
        to output: URL,
        key: SymmetricKey,
        plaintextSize: Int64,
        onProgress: ((Double) -> Void)? = nil
    ) throws {
        guard key.bitCount == 256 else { throw BlobError.invalidKey }
        let length = fileSize(at: input)
        guard plaintextSize >= 0, length == sealedSize(plaintextSize) else { throw BlobError.wrongLength }
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        let header = try reader.read(upToCount: headerSize) ?? Data()
        let noncePrefix = try parseHeader(header)
        let writer = try createOutput(output)
        defer { try? writer.close() }
        try openSegments(
            reader,
            header: header,
            noncePrefix: noncePrefix,
            key: key,
            plaintextSize: plaintextSize,
            onProgress: onProgress
        ) { try writer.write(contentsOf: $0) }
    }

    /// Opens the blob at `input` into memory, with every check of `open(from:to:)`.
    ///
    /// Agent: For small files only (the PDF preview card caps it at 64 MB): the whole plaintext
    /// is returned, and only after the last tag checked.
    static func openIntoMemory(from input: URL, key: SymmetricKey, plaintextSize: Int64) throws -> Data {
        guard key.bitCount == 256 else { throw BlobError.invalidKey }
        let length = fileSize(at: input)
        guard plaintextSize >= 0, plaintextSize <= Int64(Int.max / 2), length == sealedSize(plaintextSize) else {
            throw BlobError.wrongLength
        }
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        let header = try reader.read(upToCount: headerSize) ?? Data()
        let noncePrefix = try parseHeader(header)
        var plaintext = Data(capacity: Int(plaintextSize))
        try openSegments(
            reader,
            header: header,
            noncePrefix: noncePrefix,
            key: key,
            plaintextSize: plaintextSize,
            onProgress: nil
        ) { plaintext.append($0) }
        return plaintext
    }

    /// Checks the whole blob — the header, the length, every tag and the last flag — without
    /// writing anything, and RETURNS its first `prefixBytes` of plaintext (for §4's content
    /// check), handed out only after the last tag checked.
    ///
    /// Agent: The audio player's gate (`docs/file-sharing.md` §11.5): it runs once per message
    /// and session before `SegmentReader` serves ranges of the same blob to AVFoundation.
    static func verify(at input: URL, key: SymmetricKey, plaintextSize: Int64, prefixBytes: Int) throws -> Data {
        guard key.bitCount == 256 else { throw BlobError.invalidKey }
        guard plaintextSize >= 0, fileSize(at: input) == sealedSize(plaintextSize) else { throw BlobError.wrongLength }
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        let header = try reader.read(upToCount: headerSize) ?? Data()
        let noncePrefix = try parseHeader(header)
        var prefix = Data()
        try openSegments(
            reader,
            header: header,
            noncePrefix: noncePrefix,
            key: key,
            plaintextSize: plaintextSize,
            onProgress: nil
        ) { plain in
            if prefix.count < prefixBytes { prefix.append(plain.prefix(prefixBytes - prefix.count)) }
        }
        return prefix
    }

    /// Random access to a stored blob's plaintext, one checked segment at a time.
    ///
    /// Human: The audio player asks for byte ranges of a file that can be 2 GB; this opens just
    /// the segments a range touches. Every segment is still a whole AES-GCM open with its index
    /// and last flag in the nonce, so a byte is never handed out unchecked.
    /// Agent: Not thread-safe — one reader per queue. Keeps the last opened segment, since
    /// AVFoundation asks for neighbouring small ranges.
    nonisolated final class SegmentReader {
        let plaintextSize: Int64
        private let handle: FileHandle
        private let key: SymmetricKey
        private let header: Data
        private let noncePrefix: Data
        private var cached: (index: Int64, plain: Data)?

        init(url: URL, key: SymmetricKey, plaintextSize: Int64) throws {
            guard key.bitCount == 256 else { throw BlobError.invalidKey }
            guard plaintextSize >= 0, fileSize(at: url) == sealedSize(plaintextSize) else { throw BlobError.wrongLength }
            let handle = try FileHandle(forReadingFrom: url)
            do {
                let header = try handle.read(upToCount: headerSize) ?? Data()
                self.noncePrefix = try parseHeader(header)
                self.header = header
            } catch {
                try? handle.close()
                throw error
            }
            self.handle = handle
            self.key = key
            self.plaintextSize = plaintextSize
        }

        deinit {
            try? handle.close()
        }

        /// The plaintext of segment `index`.
        func segment(_ index: Int64) throws -> Data {
            if let cached, cached.index == index { return cached.plain }
            let count = segmentCount(plaintextSize)
            guard index >= 0, index < count else { throw BlobError.unreadable }
            let last = index == count - 1
            let plainLength = last ? Int(plaintextSize - index * Int64(segmentSize)) : segmentSize
            try handle.seek(toOffset: UInt64(Int64(headerSize) + index * Int64(segmentSize + tagSize)))
            guard let chunk = try handle.read(upToCount: plainLength + tagSize), chunk.count == plainLength + tagSize else {
                throw BlobError.unreadable
            }
            let plain = try openSegment(chunk, index: UInt32(index), last: last, key: key, noncePrefix: noncePrefix, header: header)
            cached = (index, plain)
            return plain
        }

        /// Up to `maxLength` plaintext bytes from `offset`, never crossing a segment boundary
        /// (callers loop). Empty at or past the end.
        func bytes(at offset: Int64, maxLength: Int) throws -> Data {
            guard offset >= 0, offset < plaintextSize, maxLength > 0 else { return Data() }
            let index = offset / Int64(segmentSize)
            let plain = try segment(index)
            let start = Int(offset - index * Int64(segmentSize))
            let end = min(plain.count, start + maxLength)
            return plain.subdata(in: start ..< end)
        }
    }

    /// Reads, checks and hands on every segment after the header, then refuses trailing bytes.
    private static func openSegments(
        _ reader: FileHandle,
        header: Data,
        noncePrefix: Data,
        key: SymmetricKey,
        plaintextSize: Int64,
        onProgress: ((Double) -> Void)?,
        sink: (Data) throws -> Void
    ) throws {
        let count = segmentCount(plaintextSize)
        for i in 0 ..< count {
            try Task.checkCancellation()
            let last = i == count - 1
            let plainLength = last ? Int(plaintextSize - i * Int64(segmentSize)) : segmentSize
            try autoreleasepool {
                guard let chunk = try reader.read(upToCount: plainLength + tagSize),
                      chunk.count == plainLength + tagSize
                else { throw BlobError.unreadable }
                let plain = try openSegment(chunk, index: UInt32(i), last: last, key: key, noncePrefix: noncePrefix, header: header)
                try sink(plain)
            }
            if let onProgress, i % Int64(progressStride) == 0 || last {
                onProgress(Double(i + 1) / Double(count))
            }
        }
        // `sealedSize` already pinned the length; nothing may follow the last segment.
        if let trailing = try reader.read(upToCount: 1), !trailing.isEmpty { throw BlobError.wrongLength }
    }

    /// The file's size on disk now. Not `URL.resourceValues`: those are cached per URL value,
    /// and a blob checked once and rewritten would keep its old length.
    static func fileSize(at url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
    }

    /// True when the first bytes at `url` are an SHRF1 header — a cheap check on a download
    /// before it is kept.
    static func hasValidHeader(at url: URL) -> Bool {
        guard let reader = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? reader.close() }
        guard let header = try? reader.read(upToCount: headerSize) else { return false }
        return (try? parseHeader(header)) != nil
    }

    // MARK: - In memory (small inputs and tests)

    /// Whole-buffer seal, for vectors and tests. Production code streams through files.
    static func seal(_ plaintext: Data, key: SymmetricKey, noncePrefix: Data) throws -> Data {
        guard noncePrefix.count == noncePrefixSize else { throw BlobError.invalidKey }
        let header = header(noncePrefix: noncePrefix)
        var blob = header
        let count = Int(segmentCount(Int64(plaintext.count)))
        for i in 0 ..< count {
            let start = plaintext.startIndex + i * segmentSize
            let end = min(start + segmentSize, plaintext.endIndex)
            blob.append(try sealSegment(
                plaintext.subdata(in: start ..< end),
                index: UInt32(i),
                last: i == count - 1,
                key: key,
                noncePrefix: noncePrefix,
                header: header
            ))
        }
        return blob
    }

    /// Whole-buffer open with the same checks as the streaming one, for tests.
    static func open(_ blob: Data, key: SymmetricKey, plaintextSize: Int64) throws -> Data {
        guard Int64(blob.count) == sealedSize(plaintextSize) else { throw BlobError.wrongLength }
        let header = blob.prefix(headerSize)
        let noncePrefix = try parseHeader(Data(header))
        var plaintext = Data()
        var offset = blob.startIndex + headerSize
        let count = segmentCount(plaintextSize)
        for i in 0 ..< count {
            let last = i == count - 1
            let plainLength = last ? Int(plaintextSize - i * Int64(segmentSize)) : segmentSize
            let chunk = blob.subdata(in: offset ..< offset + plainLength + tagSize)
            plaintext.append(try openSegment(chunk, index: UInt32(i), last: last, key: key, noncePrefix: noncePrefix, header: Data(header)))
            offset += plainLength + tagSize
        }
        return plaintext
    }

    // MARK: - Segments

    private static func sealSegment(
        _ plaintext: Data,
        index: UInt32,
        last: Bool,
        key: SymmetricKey,
        noncePrefix: Data,
        header: Data
    ) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce(noncePrefix, index: index, last: last), authenticating: header)
        var out = box.ciphertext
        out.append(box.tag)
        return out
    }

    private static func openSegment(
        _ chunk: Data,
        index: UInt32,
        last: Bool,
        key: SymmetricKey,
        noncePrefix: Data,
        header: Data
    ) throws -> Data {
        do {
            let box = try AES.GCM.SealedBox(
                nonce: nonce(noncePrefix, index: index, last: last),
                ciphertext: chunk.prefix(chunk.count - tagSize),
                tag: chunk.suffix(tagSize)
            )
            return try AES.GCM.open(box, using: key, authenticating: header)
        } catch {
            throw BlobError.tampered
        }
    }

    private static func nonce(_ prefix: Data, index: UInt32, last: Bool) throws -> AES.GCM.Nonce {
        var bytes = prefix
        bytes.append(contentsOf: bigEndian(index))
        bytes.append(last ? 1 : 0)
        return try AES.GCM.Nonce(data: bytes)
    }

    private static func parseHeader(_ header: Data) throws -> Data {
        guard header.count == headerSize, header.prefix(magic.count) == magic else { throw BlobError.badHeader }
        let size = header.suffix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        guard size == UInt32(segmentSize) else { throw BlobError.badHeader }
        return Data(header.dropFirst(magic.count).prefix(noncePrefixSize))
    }

    private static func readSegment(_ reader: FileHandle) throws -> Data {
        var segment = Data()
        // A pipe or a provider-backed file can hand out short reads; fill the segment up.
        while segment.count < segmentSize {
            guard let more = try reader.read(upToCount: segmentSize - segment.count), !more.isEmpty else { break }
            segment.append(more)
        }
        return segment
    }

    private static func createOutput(_ url: URL) throws -> FileHandle {
        let files = FileManager.default
        try? files.removeItem(at: url)
        guard files.createFile(atPath: url.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete]) else {
            throw BlobError.unreadable
        }
        return try FileHandle(forWritingTo: url)
    }

    private static func bigEndian(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }
}

import CryptoKit
import Foundation
import Testing
@testable import shroud

/// The §4 type table and content check, the `t: "file"` payload, file replies, and how a file
/// message is stored (`docs/file-sharing.md`).
struct SharedFileTypeTests {
    @Test
    func theTableHasEveryExtensionOfTheSpec() {
        #expect(SharedFile.supportedExtensions.count == 45)
        #expect(Set(SharedFile.supportedExtensions).count == 45)
    }

    @Test(arguments: [
        ("report.pdf", "application/pdf", SharedFile.Category.pdf, SharedFile.Warning?.none),
        ("notes.TXT", "text/plain", .text, nil),
        ("data.csv", "text/csv", .text, nil),
        ("letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", .word, nil),
        ("letter.rtf", "application/rtf", .word, nil),
        ("old.doc", "application/msword", .word, .macros),
        ("old.dot", "application/msword", .word, .macros),
        ("macro.docm", "application/vnd.ms-word.document.macroEnabled.12", .word, .macros),
        ("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", .excel, nil),
        ("sheet.xls", "application/vnd.ms-excel", .excel, .macros),
        ("sheet.xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", .excel, .macros),
        ("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", .powerPoint, nil),
        ("deck.ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12", .powerPoint, .macros),
        ("deck.pot", "application/vnd.ms-powerpoint", .powerPoint, .macros),
        ("photo.JPG", "image/jpeg", .image, nil),
        ("photo.heic", "image/heic", .image, nil),
        ("scan.tif", "image/tiff", .image, nil),
        ("clip.mov", "video/quicktime", .video, nil),
        ("clip.3gp", "video/3gpp", .video, nil),
        ("clip.mkv", "video/x-matroska", .video, nil),
        ("app.apk", "application/vnd.android.package-archive", .app, .app),
    ])
    func typesComeFromTheExtension(
        name: String,
        mime: String,
        category: SharedFile.Category,
        warning: SharedFile.Warning?
    ) throws {
        let type = try #require(SharedFile.type(forName: name))
        #expect(type.mime == mime)
        #expect(type.category == category)
        #expect(type.warning == warning)
        #expect(type.label == type.ext.uppercased())
    }

    @Test(arguments: ["page.html", "logo.svg", "run.exe", "script.js", "archive.zip", "archive.tar.gz", "noext", "pdf", "weird.ext-with-dash"])
    func theRestIsUnsupported(name: String) {
        #expect(SharedFile.type(forName: name) == nil)
    }

    @Test
    func warningsCarryTheSpecCopy() {
        #expect(SharedFile.Warning.app.bubbleLine == "Installs an app")
        #expect(SharedFile.Warning.macros.bubbleLine == "May contain macros")
        #expect(SharedFile.Warning.app.dialogTitle == "This file can install an app")
        #expect(SharedFile.Warning.macros.dialogTitle == "This file may contain macros")
        #expect(SharedFile.Warning.app.dialogMessage(sender: "Jane") == "APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust Jane and expected this file.")
        #expect(SharedFile.Warning.macros.dialogMessage(sender: "Jane") == "Macros in Office files can run harmful code. Only continue if you trust Jane and expected this file, and don't turn on macros unless you're sure.")
    }

    @Test
    func refusalsUseCurlyQuotes() {
        #expect(SharedFile.unsupportedRefusal("a.exe") == "Shroud can't send \u{201C}a.exe\u{201D}: this file type isn't supported.")
        #expect(SharedFile.tooLargeRefusal("big.mov") == "\u{201C}big.mov\u{201D} is larger than 2 GB.")
        #expect(SharedFile.emptyRefusal("e.txt") == "\u{201C}e.txt\u{201D} is empty.")
        #expect(SharedFile.tooManyRefusal == "You can send up to 10 files at once.")
        #expect(SharedFile.composerTitle(count: 1) == "Send File")
        #expect(SharedFile.composerTitle(count: 3) == "Send 3 Files")
    }

    // MARK: - Content check

    private func type(_ ext: String) throws -> SharedFile.FileType {
        try #require(SharedFile.type(forName: "f.\(ext)"))
    }

    @Test
    func pdfNeedsItsMarkerInTheFirstKilobyte() throws {
        let pdf = try type("pdf")
        #expect(SharedFile.contentMatches(pdf, header: Data("%PDF-1.7\n".utf8)))
        #expect(SharedFile.contentMatches(pdf, header: Data(repeating: 0x20, count: 1000) + Data("%PDF-".utf8)))
        #expect(!SharedFile.contentMatches(pdf, header: Data(repeating: 0x20, count: 1020) + Data("%PDF-".utf8)))
        #expect(!SharedFile.contentMatches(pdf, header: Data("PK\u{3}\u{4}".utf8)))
    }

    @Test
    func officeAndApkNeedZipOrOle() throws {
        let zip = Data([0x50, 0x4B, 0x03, 0x04, 0x14, 0x00])
        let ole = Data([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0x00])
        for ext in ["docx", "xlsm", "pptx", "potm", "apk"] {
            #expect(SharedFile.contentMatches(try type(ext), header: zip))
            #expect(!SharedFile.contentMatches(try type(ext), header: ole))
        }
        for ext in ["doc", "dot", "xls", "xlt", "ppt", "pps", "pot"] {
            #expect(SharedFile.contentMatches(try type(ext), header: ole))
            #expect(!SharedFile.contentMatches(try type(ext), header: zip))
        }
    }

    @Test
    func rtfTextAndMedia() throws {
        #expect(SharedFile.contentMatches(try type("rtf"), header: Data("{\\rtf1\\ansi".utf8)))
        #expect(!SharedFile.contentMatches(try type("rtf"), header: Data("<html>".utf8)))
        #expect(SharedFile.contentMatches(try type("txt"), header: Data("plain words".utf8)))
        #expect(!SharedFile.contentMatches(try type("csv"), header: Data([0x61, 0x00, 0x62])))
        // A NUL past the first 8 KiB is not looked at.
        #expect(SharedFile.contentMatches(try type("txt"), header: Data(repeating: 0x61, count: 8192) + Data([0])))
        #expect(SharedFile.contentMatches(try type("png"), header: Data([0x00, 0x01])))
        #expect(SharedFile.contentMatches(try type("mp4"), header: Data()))
    }
}

struct FileMessagePayloadTests {
    @Test
    func aFilePayloadParsesWithItsName() throws {
        let json = #"{"t":"file","n":"Quarterly report 2026.pdf","mime":"application/pdf","k":"a2V5","s":2400000,"c":"Q3","w":0,"h":0}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isFile)
        #expect(payload.n == "Quarterly report 2026.pdf")
        #expect(payload.s == 2_400_000)
        #expect(payload.c == "Q3")
        #expect(!payload.isImage)
        #expect(!payload.isVideo)
        #expect(!payload.isVoice)
        #expect(!payload.isLink)
    }

    /// `t == "file"` wins over the MIME sniffing old payloads rely on.
    @Test(arguments: ["image/png", "video/mp4", "audio/mp4"])
    func mimeSniffingNeverTurnsAFileIntoMedia(mime: String) throws {
        let json = #"{"t":"file","n":"x","mime":"\#(mime)","k":"a2V5","s":1}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.isFile)
        #expect(!payload.isImage)
        #expect(!payload.isVideo)
        #expect(!payload.isVoice)
    }

    @Test
    func aFilePayloadEncodesItsName() throws {
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindFile,
            mime: "application/pdf",
            w: 0,
            h: 0,
            k: "a2V5",
            s: 12,
            n: "report.pdf"
        )
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["t"] as? String == "file")
        #expect(object["n"] as? String == "report.pdf")
        #expect(object["s"] as? Int == 12)
        #expect(MediaMessagePayload.parse(try payload.encoded()) == payload)
    }

    @Test
    func otherKindsCarryNoName() throws {
        let json = #"{"t":"image","mime":"image/jpeg","k":"a2V5"}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.n == nil)
        #expect(!payload.isFile)
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["n"] == nil)
    }

    @Test
    func aFileReplyQuotesTheName() throws {
        let reference = MessageReplyReference(messageID: UUID(), senderUserID: UUID(), kind: .file, snippet: "report.pdf")
        #expect(reference.wireObject["k"] as? String == "file")
        #expect(reference.wireObject["x"] as? String == "report.pdf")
        let parsed = try #require(MessageReplyReference.parse(wireObject: reference.wireObject))
        #expect(parsed.kind == .file)
        #expect(parsed.snippet == "report.pdf")
        #expect(MessageReplyReference.Kind.file.mediaLabel == "File")
    }

    @MainActor
    @Test
    func replyingToAFileSealsItsName() throws {
        let message = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: "the caption",
            createdAt: Date(),
            isMine: false,
            deleted: false,
            kind: .file,
            mediaObjectId: UUID(),
            mediaByteCount: 10,
            fileName: "report.pdf"
        )
        let quote = try #require(message.replyReference)
        #expect(quote.kind == .file)
        #expect(quote.snippet == "report.pdf")
        #expect(message.fileType?.ext == "pdf")
        #expect(message.needsMediaDownload)
        #expect(message.filePreviewText == "the caption")
    }

    @MainActor
    @Test
    func anUnsupportedFileIsNeverDownloaded() {
        let message = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: "",
            createdAt: Date(),
            isMine: false,
            deleted: false,
            kind: .file,
            mediaObjectId: UUID(),
            fileName: "page.html"
        )
        #expect(message.fileType == nil)
        #expect(!message.needsMediaDownload)
        #expect(message.filePreviewText == "page.html")
    }
}

@MainActor
struct FileMessageStorageTests {
    private func scratchStore() throws -> LocalFileStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("file-store-tests-\(UUID().uuidString)", isDirectory: true)
        return LocalFileStore(directory: dir)
    }

    @Test
    func aStoredFileMessageRoundTrips() throws {
        let store = try scratchStore()
        defer { store.clearAll() }
        let id = UUID()
        let message = MessagingController.ChatMessage(
            id: id,
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: "caption",
            createdAt: Date(timeIntervalSince1970: 1_800_000_000),
            isMine: true,
            deleted: false,
            receipt: .delivered,
            kind: .file,
            mediaObjectId: UUID(),
            mediaByteCount: 1234,
            fileName: "Budget 2026.XLSX",
            fileStored: true
        )
        let stored = LocalMessageStore.StoredMessage.from(message)
        #expect(stored.kind == "file")
        #expect(stored.fileName == "Budget 2026.XLSX")

        let data = try JSONEncoder.localStore.encode(stored)
        let decoded = try JSONDecoder.localStore.decode(LocalMessageStore.StoredMessage.self, from: data)
        #expect(decoded == stored)

        // No blob on this device: the message comes back as not downloaded.
        let missing = decoded.toChatMessage(media: LocalMediaCache(), files: store, historyKey: SealedTestKey.historyKey)
        #expect(missing.kind == .file)
        #expect(missing.fileName == "Budget 2026.XLSX")
        #expect(!missing.fileStored)

        let source = FileManager.default.temporaryDirectory.appendingPathComponent("blob-\(UUID().uuidString)")
        try Data("SHRF1".utf8).write(to: source)
        try store.adopt(source, as: id)
        let present = decoded.toChatMessage(media: LocalMediaCache(), files: store, historyKey: SealedTestKey.historyKey)
        #expect(present.fileStored)
        #expect(present.mediaByteCount == nil) // restored from the payload cache at hydrate
        #expect(MessagingController.ChatMessageKind(storageKey: "file") == .file)
    }

    @Test
    func aThreadWrittenBeforeFilesStillDecodes() throws {
        let json = #"{"id":"0F5C2C35-7B57-4D0C-8E6A-7D8A9B3C1D2E","peerUserID":"1F5C2C35-7B57-4D0C-8E6A-7D8A9B3C1D2E","senderUserID":"2F5C2C35-7B57-4D0C-8E6A-7D8A9B3C1D2E","text":"hi","createdAt":"2026-10-01T10:00:00.000Z","isMine":true,"deleted":false,"receipt":"sent","kind":"text"}"#
        let decoded = try JSONDecoder.localStore.decode(LocalMessageStore.StoredMessage.self, from: Data(json.utf8))
        #expect(decoded.fileName == nil)
    }

    @Test
    func theStoreMovesAndRemovesBlobs() throws {
        let store = try scratchStore()
        defer { store.clearAll() }
        let first = UUID()
        let second = UUID()
        let staging = store.makeStagingURL()
        try Data([1, 2, 3]).write(to: staging)
        try store.adopt(staging, as: first)
        #expect(store.hasBlob(first))
        #expect(!FileManager.default.fileExists(atPath: staging.path))
        #expect(store.url(for: first).lastPathComponent == first.uuidString.lowercased() + ".shrf")
        let values = try store.url(for: first).resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)

        store.rekey(from: first, to: second)
        #expect(!store.hasBlob(first))
        #expect(store.hasBlob(second))
        store.remove(messageIDs: [second])
        #expect(!store.hasBlob(second))
    }

    @Test
    func openedFilesLiveInASweptFolder() throws {
        let id = UUID()
        let staging = try FileOpenStaging.prepare(for: id)
        try Data("plain".utf8).write(to: staging)
        let committed = try FileOpenStaging.commit(staging, name: "report.pdf")
        #expect(committed.lastPathComponent == "report.pdf")
        #expect(committed.deletingLastPathComponent().lastPathComponent.hasPrefix(SensitiveTempFiles.prefix))
        FileOpenStaging.remove(for: id)
        #expect(!FileManager.default.fileExists(atPath: committed.path))
    }

    @Test
    func failedFilesWaitInTheOutbox() {
        let peer = UUID()
        let message = MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: peer,
            senderUserID: UUID(),
            text: "",
            createdAt: Date(),
            isMine: true,
            deleted: false,
            receipt: .failed,
            kind: .file,
            pendingSync: true,
            fileName: "a.pdf",
            fileStored: true
        )
        let items = OutboundPending.items(from: [peer: [message]])
        #expect(items == [.file(messageID: message.id, peerID: peer)])
    }
}

/// Opening a stored file into `tmp/` (`FileOpenStaging`): names that fit §5 but not APFS, and
/// which failures may cost the stored blob.
struct FileOpenStagingTests {
    private static let key = SymmetricKey(data: Data((0 ..< 32).map { UInt8($0) }))

    private func sealedBlob(_ plaintext: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("open-test-\(UUID().uuidString).shrf")
        try FileBlob.seal(plaintext, key: Self.key, noncePrefix: Data(repeating: 7, count: 7)).write(to: url)
        return url
    }

    @Test(arguments: [
        String(repeating: "報", count: 200) + ".pdf",
        String(repeating: "📄", count: 200) + ".pdf",
        String(repeating: "é", count: 90) + String(repeating: "漢", count: 60) + ".pdf",
    ])
    func longNamesStillOpen(raw: String) throws {
        let name = SharedFile.cleanName(raw)
        #expect(name.unicodeScalars.count <= SharedFile.maxNameLength)
        #expect(FileOpenStaging.diskBytes(name) > FileOpenStaging.maxDiskNameBytes)
        let type = try #require(SharedFile.type(forName: name))
        let plaintext = Data("%PDF-1.7\nbody".utf8)
        let blob = try sealedBlob(plaintext)
        defer { try? FileManager.default.removeItem(at: blob) }
        let id = UUID()
        defer { FileOpenStaging.remove(for: id) }

        let opened = try FileOpenStaging.open(
            blob: blob, key: Self.key, plaintextSize: Int64(plaintext.count),
            messageID: id, name: name, type: type
        )
        #expect(opened.contentMatches)
        #expect(FileOpenStaging.diskBytes(opened.url.lastPathComponent) <= FileOpenStaging.maxDiskNameBytes)
        #expect(opened.url.lastPathComponent.hasSuffix(".pdf"))
        #expect(name.hasPrefix(String(opened.url.lastPathComponent.precomposedStringWithCanonicalMapping.dropLast(4))))
        #expect(try Data(contentsOf: opened.url) == plaintext)
    }

    @Test
    func shortNamesAreKeptAsTheyAre() {
        #expect(FileOpenStaging.diskName(for: "Quarterly report 2026.pdf") == "Quarterly report 2026.pdf")
        // 62 × 4 + 4 = 252 bytes fits; 63 × 4 + 4 = 256 loses one emoji, never half of one.
        let fits = String(repeating: "📄", count: 62) + ".txt"
        #expect(FileOpenStaging.diskName(for: fits) == fits)
        let over = String(repeating: "📄", count: 63) + ".txt"
        #expect(FileOpenStaging.diskName(for: over) == fits)
    }

    @Test
    func onlyABlobThatFailsItsChecksCountsAsDamaged() {
        #expect(FileOpenStaging.isDamage(FileBlob.BlobError.tampered))
        #expect(FileOpenStaging.isDamage(FileBlob.BlobError.wrongLength))
        #expect(FileOpenStaging.isDamage(FileBlob.BlobError.badHeader))
        #expect(!FileOpenStaging.isDamage(FileBlob.BlobError.unreadable))
        #expect(!FileOpenStaging.isDamage(CocoaError(.fileWriteOutOfSpace)))
        #expect(!FileOpenStaging.isDamage(POSIXError(.ENAMETOOLONG)))
        #expect(!FileOpenStaging.isDamage(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))))
        #expect(!FileOpenStaging.isDamage(CancellationError()))
    }

    /// A file-system refusal (here: a folder where the opened file should go, as a stand-in for
    /// ENAMETOOLONG/ENOSPC) is not damage, so the caller keeps the blob; a tampered blob is.
    @Test
    func aFileSystemErrorIsNotDamage() throws {
        let plaintext = Data("plain words".utf8)
        let blob = try sealedBlob(plaintext)
        defer { try? FileManager.default.removeItem(at: blob) }
        let type = try #require(SharedFile.type(forName: "a.txt"))
        let id = UUID()
        defer { FileOpenStaging.remove(for: id) }

        // The move into place fails (a missing folder in the path), as ENAMETOOLONG or ENOSPC would.
        var thrown: Error?
        do {
            _ = try FileOpenStaging.open(blob: blob, key: Self.key, plaintextSize: Int64(plaintext.count), messageID: id, name: "a/b.txt", type: type)
        } catch {
            thrown = error
        }
        let error = try #require(thrown)
        #expect(!FileOpenStaging.isDamage(error))
        #expect(FileManager.default.fileExists(atPath: blob.path))
        #expect(!FileManager.default.fileExists(atPath: FileOpenStaging.folder(for: id).path))

        var tampered = try Data(contentsOf: blob)
        tampered[tampered.endIndex - 1] ^= 1
        try tampered.write(to: blob)
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileOpenStaging.open(blob: blob, key: Self.key, plaintextSize: Int64(plaintext.count), messageID: id, name: "a.txt", type: type)
        }
    }

    @Test
    func launchSweepsHalfSealedBlobs() throws {
        let store = LocalFileStore(directory: FileManager.default.temporaryDirectory
            .appendingPathComponent("file-store-sweep-\(UUID().uuidString)", isDirectory: true))
        defer { store.clearAll() }
        let staging = store.makeStagingURL()
        try Data([1]).write(to: staging)
        let kept = store.makeStagingURL()
        try Data([2]).write(to: kept)
        let id = UUID()
        try store.adopt(kept, as: id)

        store.sweepStaging()
        #expect(!FileManager.default.fileExists(atPath: staging.path))
        #expect(store.hasBlob(id))
    }
}

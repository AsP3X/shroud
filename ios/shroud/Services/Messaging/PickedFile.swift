import Foundation
import QuickLookThumbnailing
import Synchronization
import UIKit

/// A file picked to send: the document picker's original, read where it is.
///
/// Human: Nothing is copied when a file is picked, so the composer opens at once however large
/// the file is (up to 2 GB). The picker's security scope stays open until the file has been
/// sealed or the composer drops it, and the seal reads the original under a coordinated read
/// (a file provider downloads it then, behind the bubble's ring). A video hands its scope on
/// to the video compose (`PickedMovie.access`).
/// Agent: `name` is already cleaned (`SharedFile.cleanName`); `type` comes from its extension.
/// `url` is the user's own file: never delete or write it. Read the bytes through `read(_:)`.
nonisolated struct PickedFile: Identifiable, Equatable, Sendable {
    let id = UUID()
    /// The picked file itself, in Files or a file provider.
    let url: URL
    /// The picker's security scope on `url`, open until `cleanup()`.
    let access: ScopedAccess
    let name: String
    let byteCount: Int64
    let type: SharedFile.FileType
    /// An audio file's tags, duration and cover, read when it was picked (§11.2); nil for other
    /// types and when nothing could be read within 2 s.
    var audio: AudioFileMetadata? = nil

    /// Why a pick can't be sent; `message` is the toast.
    enum Refusal: Error, Equatable {
        case unsupported(String)
        case tooLarge(String)
        case empty(String)
        case unreadable(String)

        var message: String {
            switch self {
            case let .unsupported(name): SharedFile.unsupportedRefusal(name)
            case let .tooLarge(name): SharedFile.tooLargeRefusal(name)
            case let .empty(name): SharedFile.emptyRefusal(name)
            case .unreadable: "Could not load that file."
            }
        }
    }

    /// Closes the security scope; the file itself stays. Safe to call twice.
    func cleanup() {
        access.release()
    }

    /// The file couldn't be had: the provider couldn't download it (offline), or it is gone.
    enum ReadError: Error, Equatable {
        case unavailable
    }

    /// Runs `body` on a readable URL for the bytes. Blocking: run it off the main actor.
    ///
    /// Agent: Under `NSFileCoordinator`, so a file provider that still has to download the file
    /// does so first. THROWS `ReadError.unavailable` when the provider can't hand it over or it is
    /// gone, else what `body` throws.
    func read<T>(_ body: (URL) throws -> T) throws -> T {
        try Self.coordinatedRead(url, body)
    }

    /// `read(_:)` for any picked original (a video handed to `PickedMovie` uses it too).
    static func coordinatedRead<T>(_ url: URL, _ body: (URL) throws -> T) throws -> T {
        var result: Result<T, any Error>?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
            // Coordination goes ahead for a file that is gone (moved or deleted since the pick).
            guard FileManager.default.fileExists(atPath: readable.path) else {
                result = .failure(ReadError.unavailable)
                return
            }
            result = Result { try body(readable) }
        }
        guard let result else { throw ReadError.unavailable }
        return try result.get()
    }

    /// Checks one picked URL against §4/§2 and keeps it where it is, its security scope open.
    ///
    /// Agent: Cheap: the size comes from the file's metadata, so nothing is read or downloaded
    /// here (only a provider that reports no size, or zero, is asked for the file). That size
    /// is for the composer and the limits; the seal measures the bytes again. The caller owns
    /// the scope: `cleanup()` closes it.
    static func open(_ source: URL) -> Result<PickedFile, Refusal> {
        let name = SharedFile.cleanName(source.lastPathComponent)
        guard let type = SharedFile.type(forName: name) else { return .failure(.unsupported(name)) }

        let access = ScopedAccess(source)
        // Zero can mean a provider that doesn't know the size until it has the file.
        var size = [(try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init),
                    FileBlob.fileSize(at: source)]
            .compactMap(\.self).first { $0 > 0 }
        if size == nil {
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { readable in
                size = FileBlob.fileSize(at: readable)
            }
        }
        let refusal: Refusal? = switch size {
        case nil: .unreadable(name)
        case let size? where size <= 0: .empty(name)
        case let size? where size > SharedFile.maxPlaintextBytes: .tooLarge(name)
        default: nil
        }
        if let refusal {
            access.release()
            return .failure(refusal)
        }
        return .success(PickedFile(url: source, access: access, name: name, byteCount: size ?? 0, type: type))
    }
}

/// The document picker's security scope on one file, held until `release()` (or until nothing
/// holds it any more).
///
/// Agent: A class so every copy of a `PickedFile` shares one scope and `release()` stops it
/// exactly once, however many paths call `cleanup()`.
nonisolated final class ScopedAccess: Equatable, Sendable {
    private let stop: @Sendable () -> Void
    private let open: Mutex<Bool>

    convenience init(_ url: URL) {
        self.init(start: { url.startAccessingSecurityScopedResource() }, stop: { url.stopAccessingSecurityScopedResource() })
    }

    /// Agent: For tests; `start` runs once now, `stop` once at most (and only if `start` said yes).
    init(start: () -> Bool, stop: @escaping @Sendable () -> Void) {
        self.stop = stop
        open = Mutex(start())
    }

    func release() {
        let wasOpen = open.withLock { open in
            defer { open = false }
            return open
        }
        if wasOpen { stop() }
    }

    deinit { release() }

    static func == (lhs: ScopedAccess, rhs: ScopedAccess) -> Bool { lhs === rhs }
}

/// The optional `th` of a file message: a tiny JPEG for images, videos and PDFs.
///
/// Human: Only when it is cheap — Quick Look's thumbnailer reads what it needs (an embedded
/// thumbnail, the first frame) rather than the whole file. A PDF's is the top of its first
/// page in the bubble's 2:1 card, with the page count (`PDFPagePreview`). Anything that can't
/// be done quickly is sent without a preview.
nonisolated enum FilePreview {
    struct Thumbnail: Equatable, Sendable {
        let jpeg: Data
        /// Pixel size of what `jpeg` shows (`w` / `h` in the payload).
        let width: Int
        let height: Int
        /// A PDF's page count (`pg`).
        var pageCount: Int? = nil
    }

    static func thumbnail(for file: PickedFile) async -> Thumbnail? {
        if file.type.category == .pdf {
            guard let preview = await PDFPagePreview.senderPreview(for: file.url) else { return nil }
            var thumbnail = preview.thumbnail
            thumbnail.pageCount = preview.pageCount
            return thumbnail
        }
        guard [.image, .video].contains(file.type.category) else { return nil }
        let request = QLThumbnailGenerator.Request(
            fileAt: file.url,
            size: CGSize(width: 160, height: 160),
            scale: 1,
            representationTypes: .thumbnail
        )
        guard let representation = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request),
              let source = representation.uiImage.jpegData(compressionQuality: 0.85),
              let jpeg = MediaCrypto.chatPreviewJPEG(from: source),
              jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes,
              let size = MediaCrypto.pixelSize(for: jpeg)
        else { return nil }
        return Thumbnail(jpeg: jpeg, width: size.width, height: size.height)
    }
}

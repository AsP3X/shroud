import Foundation
import QuickLookThumbnailing
import UIKit

/// A file picked to send, copied out of the document picker's security scope into `tmp/`.
///
/// Human: The picker's URL is only readable while its security scope is open, and the composer
/// can stay up for a while — so the file is copied in once, under a `shroud-` name the sweep
/// knows. The copy is the plaintext, so it lives only until it has been sealed (or the composer
/// is cancelled).
/// Agent: `name` is already cleaned (`SharedFile.cleanName`); `type` comes from its extension.
nonisolated struct PickedFile: Identifiable, Equatable, Sendable {
    let id = UUID()
    /// The copy in `tmp/` (`shroud-pick-…`), with the original extension so previews work.
    let url: URL
    let name: String
    let byteCount: Int64
    let type: SharedFile.FileType

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

    func cleanup() {
        try? FileManager.default.removeItem(at: url)
    }

    /// Checks one picked URL against §4/§2 and copies it in. Blocking: run it off the main actor.
    ///
    /// Agent: Opens the security scope for the duration of the copy only. A file provider that
    /// still has to download the file does so under the coordinated read.
    static func copyIn(_ source: URL) -> Result<PickedFile, Refusal> {
        let name = SharedFile.cleanName(source.lastPathComponent)
        guard let type = SharedFile.type(forName: name) else { return .failure(.unsupported(name)) }

        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }

        var outcome: Result<PickedFile, Refusal> = .failure(.unreadable(name))
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { readable in
            let size = FileBlob.fileSize(at: readable) ?? 0
            guard size > 0 else {
                outcome = .failure(.empty(name))
                return
            }
            guard size <= SharedFile.maxPlaintextBytes else {
                outcome = .failure(.tooLarge(name))
                return
            }
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent("shroud-pick-\(UUID().uuidString.lowercased()).\(type.ext)")
            do {
                try FileManager.default.copyItem(at: readable, to: copy)
                // The copy is plaintext: it must not keep the source's (laxer) protection class.
                try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: copy.path)
            } catch {
                try? FileManager.default.removeItem(at: copy)
                return
            }
            // The copy is what gets sealed; its size is what the payload promises.
            let copied = FileBlob.fileSize(at: copy) ?? 0
            guard copied == size else {
                try? FileManager.default.removeItem(at: copy)
                return
            }
            outcome = .success(PickedFile(url: copy, name: name, byteCount: size, type: type))
        }
        return outcome
    }
}

/// The optional `th` of a file message: a tiny JPEG for images, videos and PDFs.
///
/// Human: Only when it is cheap — Quick Look's thumbnailer reads what it needs (an embedded
/// thumbnail, the first frame) rather than the whole file. A PDF's is the top of its first
/// page in the bubble's 2:1 card, with the page count (`PDFPagePreview`). Anything that can't
/// be done quickly is sent without a preview.
nonisolated enum FilePreview {
    struct Thumbnail: Sendable {
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

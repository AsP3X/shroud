import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// A movie file copied into a temp location from the photo library / Files.
///
/// Human: PhotosPicker hands us a security-scoped source; we copy once so export can run
/// after the picker dismisses without losing access.
struct PickedMovie: Transferable, Sendable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("shroud-pick-\(UUID().uuidString).\(received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension)")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
        FileRepresentation(contentType: .video) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("shroud-pick-\(UUID().uuidString).\(received.file.pathExtension.isEmpty ? "mp4" : received.file.pathExtension)")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }

    /// Best-effort cleanup after send (or cancel).
    func cleanup() {
        try? FileManager.default.removeItem(at: url)
    }
}

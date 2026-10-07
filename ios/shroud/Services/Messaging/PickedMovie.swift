import CoreTransferable
import Foundation
import UIKit
import UniformTypeIdentifiers

/// A movie to compose: a copy in a temp location (photo library, camera), or a video picked in
/// Files, read where it is.
///
/// Human: PhotosPicker hands us a file that is gone once its closure returns, so we copy once
/// so export can run after the picker dismisses. A video picked in Files stays where it is, its
/// security scope held until `cleanup()`, so a large clip opens without a copy.
/// Agent: `cleanup()` deletes `url` only when `access` is nil (our copy), never the original.
struct PickedMovie: Transferable, Sendable {
    let url: URL
    /// The document picker's scope on an original in Files; nil for a copy of our own.
    var access: ScopedAccess? = nil

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

    /// Makes sure an original from Files is on disk: a file provider may have evicted it since it
    /// was picked. True for a copy of our own. Blocking: run it off the main actor.
    nonisolated func ensureOnDisk() -> Bool {
        guard access != nil else { return true }
        return (try? PickedFile.coordinatedRead(url) { _ in true }) ?? false
    }

    /// Best-effort cleanup after send (or cancel): deletes our copy, or lets go of the original.
    func cleanup() {
        if let access {
            access.release()
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }
}

/// A picked movie plus the metadata the compose screen needs before it can draw anything.
///
/// Human: Probing is metadata-only (no frames decoded), so the host can do it while the
/// picker is still dismissing and the compose screen opens already populated.
struct PickedVideo: Identifiable {
    let id = UUID()
    let movie: PickedMovie
    let probe: VideoProbe
    /// First frame, so the strip and the optimistic bubble have something instantly.
    var poster: UIImage?

    var url: URL { movie.url }
}

/// Everything the compose screen decided about one outgoing video.
///
/// Human: The poster and geometry are captured *before* compression so the chat bubble can
/// appear the instant Send is tapped, then fill in its ring as the encode and upload run.
nonisolated struct VideoSendPlan: Sendable {
    let sourceURL: URL
    var caption: String = ""
    /// Handles from the trim strip; nil (or a full-range trim) sends the whole clip.
    var trim: VideoTrim?
    /// "Send without sound" — drops the audio track during export.
    var removeAudio: Bool = false
    /// Rung chosen in the compose sheet. High is at most 720p.
    var quality: VideoUploadQuality = .high
    /// First kept frame, for the optimistic bubble.
    var posterJPEG: Data?
    var width: Int?
    var height: Int?
    var durationMs: Int?
    /// Rough output size, only so the ring has a byte readout before the encode finishes.
    var estimatedBytes: Int?
}

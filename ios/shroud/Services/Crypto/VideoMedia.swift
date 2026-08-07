import AVFoundation
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Prepared video bytes for encrypt + upload (always MP4 after export).
nonisolated struct EncodedVideo: Sendable {
    let data: Data
    let width: Int
    let height: Int
    let durationMs: Int
    let mime: String
    /// First-frame JPEG for the chat bubble (not uploaded separately).
    let thumbnailJPEG: Data?
}

/// Compresses library / camera movies so the sealed blob fits the API media limit.
///
/// Human: Server caps encrypted media at 25 MiB. Phone-recorded 4K clips are often larger,
/// so we re-export to H.264 MP4 at a chat-friendly resolution before sealing.
nonisolated enum VideoMedia {
    enum VideoError: Error, Equatable {
        case unreadable
        case exportFailed
        case tooLarge
        case cancelled
    }

    /// Largest sealed payload the API accepts, with AES-GCM headroom.
    static let maxPlaintextBytes = 24 * 1024 * 1024

    /// Longest edge for chat export (1080p class).
    private static let maxExportEdge: CGFloat = 1280

    /// Prepares a file URL for sending: compress when needed, always produce MP4 when re-exporting.
    static func encode(sourceURL: URL) async throws -> EncodedVideo {
        let asset = AVURLAsset(url: sourceURL)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw VideoError.unreadable
        }

        let duration = try await asset.load(.duration)
        let durationMs = max(1, Int((CMTimeGetSeconds(duration) * 1000).rounded()))
        let naturalSize = try await videoTrack.load(.naturalSize)
        let transform = try await videoTrack.load(.preferredTransform)
        let display = Self.displaySize(natural: naturalSize, transform: transform)
        let width = max(1, Int(display.width.rounded()))
        let height = max(1, Int(display.height.rounded()))

        let thumbnail = await thumbnailJPEG(from: asset, maxEdge: 720)

        // Small enough already and already an MP4/MOV we can ship — prefer the original file.
        if let attrs = try? FileManager.default.attributesOfItem(atPath: sourceURL.path),
           let size = attrs[.size] as? NSNumber,
           size.intValue > 0,
           size.intValue <= maxPlaintextBytes,
           let data = try? Data(contentsOf: sourceURL, options: [.mappedIfSafe]),
           Self.isShipableContainer(url: sourceURL)
        {
            let mime = Self.mimeType(for: sourceURL) ?? "video/mp4"
            return EncodedVideo(
                data: data,
                width: width,
                height: height,
                durationMs: durationMs,
                mime: mime,
                thumbnailJPEG: thumbnail
            )
        }

        // Progressive quality until under the cap.
        let presets: [String] = [
            AVAssetExportPreset1280x720,
            AVAssetExportPreset960x540,
            AVAssetExportPreset640x480,
            AVAssetExportPresetMediumQuality,
            AVAssetExportPresetLowQuality,
        ]

        var lastError: Error = VideoError.exportFailed
        for preset in presets {
            guard AVAssetExportSession.allExportPresets().contains(preset) else { continue }
            do {
                let exportedURL = try await export(asset: asset, preset: preset)
                defer { try? FileManager.default.removeItem(at: exportedURL) }
                let data = try Data(contentsOf: exportedURL, options: [.mappedIfSafe])
                guard data.count <= maxPlaintextBytes else {
                    lastError = VideoError.tooLarge
                    continue
                }
                let exportedTrack = try await AVURLAsset(url: exportedURL)
                    .loadTracks(withMediaType: .video)
                    .first
                var outW = width
                var outH = height
                if let exportedTrack {
                    let n = try await exportedTrack.load(.naturalSize)
                    let t = try await exportedTrack.load(.preferredTransform)
                    let d = Self.displaySize(natural: n, transform: t)
                    outW = max(1, Int(d.width.rounded()))
                    outH = max(1, Int(d.height.rounded()))
                }
                return EncodedVideo(
                    data: data,
                    width: outW,
                    height: outH,
                    durationMs: durationMs,
                    mime: "video/mp4",
                    thumbnailJPEG: thumbnail
                )
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    /// First-frame thumbnail as JPEG (for bubble placeholder before full decode).
    static func thumbnailJPEG(from data: Data, maxEdge: CGFloat = 720) async -> Data? {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-thumb-\(UUID().uuidString).mp4")
        do {
            try data.write(to: url, options: .atomic)
            defer { try? FileManager.default.removeItem(at: url) }
            let asset = AVURLAsset(url: url)
            return await thumbnailJPEG(from: asset, maxEdge: maxEdge)
        } catch {
            return nil
        }
    }

    static func thumbnailJPEG(from asset: AVAsset, maxEdge: CGFloat) async -> Data? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        let maxPx = max(1, Int(maxEdge))
        generator.maximumSize = CGSize(width: maxPx, height: maxPx)
        do {
            let cg = try generator.copyCGImage(at: .zero, actualTime: nil)
            let image = UIImage(cgImage: cg)
            return image.jpegData(compressionQuality: 0.72)
        } catch {
            return nil
        }
    }

    // MARK: - Private

    private static func export(asset: AVAsset, preset: String) async throws -> URL {
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            throw VideoError.exportFailed
        }
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-export-\(UUID().uuidString).mp4")
        session.outputURL = out
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true

        await session.export()
        switch session.status {
        case .completed:
            return out
        case .cancelled:
            throw VideoError.cancelled
        default:
            throw session.error ?? VideoError.exportFailed
        }
    }

    private static func displaySize(natural: CGSize, transform: CGAffineTransform) -> CGSize {
        let rect = CGRect(origin: .zero, size: natural).applying(transform)
        return CGSize(width: abs(rect.width), height: abs(rect.height))
    }

    private static func isShipableContainer(url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        return ["mp4", "m4v", "mov"].contains(ext)
    }

    private static func mimeType(for url: URL) -> String? {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        default:
            if let type = UTType(filenameExtension: ext) {
                return type.preferredMIMEType
            }
            return nil
        }
    }
}

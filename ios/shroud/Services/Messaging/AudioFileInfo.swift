import AVFoundation
import Foundation
import ImageIO
import Synchronization
import UIKit

/// The words an audio file is shown with: its cleaned tags, its display title and its times
/// (`docs/file-sharing.md` §11.2).
///
/// Human: Tags come from whoever made the file, so they are cleaned like a name — no invisible
/// or bidi controls, spaces collapsed — on the sender before sealing and on the receiver again.
/// The display title is what the bubble, the reply quote, the chat list and the notification
/// call the file: "Title – Artist", else the title, else the file name.
/// Agent: Pure. `AudioFileTextTests` pins it against the `== audio … ==` blocks of
/// `scripts/gen_file_vectors.mjs`; change web/ and android/ together.
nonisolated enum AudioFileText {
    /// Longest cleaned tag, in code points.
    static let maxTagLength = 200

    /// `ti` / `ar` as sent and shown: NFC, the §5 step-3 code points dropped, runs of §5 spaces
    /// turned into one (none at the start), cut to 200 code points, trailing spaces trimmed.
    /// RETURNS nil for an absent or empty tag.
    static func cleanTag(_ raw: String?) -> String? {
        guard let raw else { return nil }
        var scalars: [UInt32] = []
        for scalar in raw.precomposedStringWithCanonicalMapping.unicodeScalars {
            let value = scalar.value
            if SharedFile.removed.contains(value) { continue }
            if SharedFile.spaces.contains(value) {
                if let last = scalars.last, last != 0x20 { scalars.append(0x20) }
            } else {
                scalars.append(value)
            }
        }
        if scalars.count > maxTagLength { scalars = Array(scalars.prefix(maxTagLength)) }
        while scalars.last == 0x20 { scalars.removeLast() }
        guard !scalars.isEmpty else { return nil }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars.compactMap(Unicode.Scalar.init))
        return String(view)
    }

    /// `{ti} – {ar}` when both are there, else `ti`, else the cleaned file name.
    static func displayTitle(title: String?, artist: String?, fileName: String) -> String {
        let title = cleanTag(title)
        let artist = cleanTag(artist)
        if let title, let artist { return "\(title) \u{2013} \(artist)" }
        return title ?? SharedFile.cleanName(fileName)
    }

    /// `m:ss`, from one hour `h:mm:ss`.
    static func format(seconds: Int) -> String {
        let total = max(0, seconds)
        let hours = total / 3600
        let minutes = total % 3600 / 60
        let rest = total % 60
        let ss = rest < 10 ? "0\(rest)" : "\(rest)"
        if hours > 0 {
            return "\(hours):\(minutes < 10 ? "0\(minutes)" : "\(minutes)"):\(ss)"
        }
        return "\(minutes):\(ss)"
    }

    /// A total (`d`): rounded to whole seconds.
    static func totalLabel(ms: Int) -> String {
        format(seconds: Int((Double(max(0, ms)) / 1000).rounded()))
    }

    /// An elapsed time: floored to whole seconds.
    static func elapsedLabel(seconds: Double) -> String {
        guard seconds.isFinite else { return format(seconds: 0) }
        return format(seconds: Int(max(0, seconds).rounded(.down)))
    }

    static func elapsedLabel(ms: Int) -> String {
        format(seconds: max(0, ms) / 1000)
    }

    /// Chat list and notification line of an audio file without a caption (§7 "Elsewhere").
    static func previewLine(displayTitle: String) -> String {
        "\u{1F3B5} \(displayTitle)"
    }
}

/// What the sender reads from a picked audio file: duration, title, artist and cover art
/// (`docs/file-sharing.md` §11.2).
///
/// Human: Only what can be read within 2 s goes into the payload; a file whose tags take longer
/// (or that AVFoundation can't open, like Ogg Vorbis) is sent without them. The cover becomes the
/// payload's `th`: centre-cropped square, 160 px, squeezed under 6 KB.
/// Agent: `read` never throws and never runs past `budget`; late work finishes in the
/// background and is dropped. Uses the async `load` APIs only.
nonisolated struct AudioFileMetadata: Equatable, Sendable {
    /// Rounded milliseconds, ≥ 1.
    var durationMs: Int?
    /// Cleaned tags (`AudioFileText.cleanTag`).
    var title: String?
    var artist: String?
    /// The cover as the payload's `th`.
    var cover: FilePreview.Thumbnail?

    static let budget: Duration = .seconds(2)
    /// The cover's side, and the smallest side worth sending.
    static let coverSide = 160
    static let coverMinSide = 64

    var isEmpty: Bool { durationMs == nil && title == nil && artist == nil && cover == nil }

    /// What the file's tags say, or nil when nothing could be read in time.
    static func read(url: URL) async -> AudioFileMetadata? {
        await Deadline.value(within: budget) {
            let metadata = await load(url: url)
            return metadata.isEmpty ? nil : metadata
        }
    }

    /// The reading itself, without the deadline.
    static func load(url: URL) async -> AudioFileMetadata {
        let asset = AVURLAsset(url: url)
        var metadata = AudioFileMetadata()
        if let duration = try? await asset.load(.duration) {
            let seconds = CMTimeGetSeconds(duration)
            if seconds.isFinite, seconds > 0 {
                metadata.durationMs = max(1, Int((seconds * 1000).rounded()))
            }
        }
        guard !Task.isCancelled, let items = try? await asset.load(.commonMetadata) else { return metadata }
        if let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierTitle).first {
            metadata.title = AudioFileText.cleanTag(try? await item.load(.stringValue))
        }
        if let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierArtist).first {
            metadata.artist = AudioFileText.cleanTag(try? await item.load(.stringValue))
        }
        if !Task.isCancelled,
           let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierArtwork).first,
           let data = try? await item.load(.dataValue)
        {
            metadata.cover = coverThumbnail(from: data)
        }
        return metadata
    }

    /// The cover art centre-cropped to a square, 160 px, as a JPEG within
    /// `MediaCrypto.maxEnvelopePreviewBytes`: lower quality first, then 0.8 of the side, never
    /// under `coverMinSide`.
    static func coverThumbnail(from data: Data) -> FilePreview.Thumbnail? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            // Enough to crop a sharp 160 px square from any aspect a cover comes in.
            kCGImageSourceThumbnailMaxPixelSize: coverSide * 4,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              image.width > 0, image.height > 0
        else { return nil }
        let edge = min(image.width, image.height)
        let crop = CGRect(x: (image.width - edge) / 2, y: (image.height - edge) / 2, width: edge, height: edge)
        guard let square = image.cropping(to: crop) else { return nil }

        var side = coverSide
        while side >= coverMinSide {
            guard let scaled = render(square, side: side) else { return nil }
            let uiImage = UIImage(cgImage: scaled)
            for quality in [0.7, 0.55, 0.42, 0.32] as [CGFloat] {
                if let jpeg = uiImage.jpegData(compressionQuality: quality), jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes {
                    return FilePreview.Thumbnail(jpeg: jpeg, width: side, height: side)
                }
            }
            side = Int(CGFloat(side) * 0.8)
        }
        return nil
    }

    private static func render(_ image: CGImage, side: Int) -> CGImage? {
        guard let context = CGContext(
            data: nil,
            width: side,
            height: side,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.interpolationQuality = .high
        // A transparent cover reads as black in a JPEG; white is what a player shows under it.
        context.setFillColor(UIColor.white.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
        return context.makeImage()
    }
}

/// Runs work against a deadline without waiting for work that ignores cancellation.
///
/// Agent: RETURNS the work's result, or nil once `budget` has passed; the late work is
/// cancelled and its result dropped. A task group would wait for it.
nonisolated enum Deadline {
    static func value<T: Sendable>(
        within budget: Duration,
        _ work: @escaping @Sendable () async -> T?
    ) async -> T? {
        let task = Task.detached(priority: .userInitiated) { await work() }
        let once = OnceFlag()
        return await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            Task.detached {
                let result = await task.value
                if once.claim() { continuation.resume(returning: result) }
            }
            Task.detached {
                try? await Task.sleep(for: budget)
                if once.claim() {
                    task.cancel()
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    /// True for the first caller only.
    private final class OnceFlag: Sendable {
        private let taken = Mutex(false)

        func claim() -> Bool {
            taken.withLock { taken in
                if taken { return false }
                taken = true
                return true
            }
        }
    }
}

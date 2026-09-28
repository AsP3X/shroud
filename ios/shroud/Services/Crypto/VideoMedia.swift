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

/// The slice of a source movie the user kept in the compose screen (seconds).
nonisolated struct VideoTrim: Equatable, Sendable {
    var start: Double
    var end: Double

    var duration: Double { max(0, end - start) }

    /// True when the handles still cover (effectively) the whole clip — nothing to cut.
    func isFullRange(of duration: Double) -> Bool {
        start <= 0.05 && end >= duration - 0.05
    }

    var timeRange: CMTimeRange {
        CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            end: CMTime(seconds: max(start + 0.1, end), preferredTimescale: 600)
        )
    }
}

/// What the compose screen needs to know about a picked movie before any work is done.
nonisolated struct VideoProbe: Sendable {
    let durationSeconds: Double
    let width: Int
    let height: Int
    let fileSizeBytes: Int
    let hasAudio: Bool

    var aspect: CGFloat {
        guard width > 0, height > 0 else { return 16.0 / 9.0 }
        return CGFloat(width) / CGFloat(height)
    }
}

/// What the sender picks in the compose sheet. `high` is the default (at most 720p).
///
/// `original` sends an MP4 that already fits unchanged. Anything else is re-encoded,
/// and the highest H.264 preset is 1080p, so a larger movie comes back at 1080p.
/// The web client can re-encode up to 4K; both still share High, Medium and Small.
enum VideoUploadQuality: String, CaseIterable, Identifiable, Sendable {
    case original
    case high
    case medium
    case small

    var id: String { rawValue }

    var label: String {
        switch self {
        case .original: return "Original"
        case .high: return "High"
        case .medium: return "Medium"
        case .small: return "Small"
        }
    }

    /// Nominal rung shown before a long clip has to step down.
    var hint: String {
        switch self {
        case .original: return "Full size"
        case .high: return "720p"
        case .medium: return "540p"
        case .small: return "360p"
        }
    }
}

/// What the compose sheet can promise before an export starts.
struct VideoOutgoingPlan: Equatable, Sendable {
    var width: Int
    var height: Int
    var estimatedBytes: Int
    /// "720p", or "Original" when that choice keeps the source frame.
    var resolutionLabel: String
    var passthrough: Bool
    var videoBitrate: Int
    var audioBitrate: Int
    /// 0 keeps the source frame rate.
    var frameRate: Int
}

/// The chosen quality cannot fit under the media cap.
struct VideoPlanError: Error, Equatable {
    var message: String
    var maxSeconds: Int
}

/// Compresses library / camera movies so the sealed blob fits the API media limit.
///
/// Human: The server accepts an encrypted media file up to 2 GiB. A clip that would
/// land over that is re-exported smaller. Trim and mute come from the compose screen
/// and are applied here, in the same export.
nonisolated enum VideoMedia {
    enum VideoError: Error, Equatable {
        case unreadable
        case exportFailed
        case tooLarge
        case cancelled
    }

    /// Largest file before encryption. The API accepts 2 GiB sealed; a mebibyte covers
    /// the AES-GCM tag and a little encoder overshoot.
    static let maxPlaintextBytes = 2 * 1024 * 1024 * 1024 - 1024 * 1024
    /// What `PUT /media/{id}/content` will accept, ciphertext included.
    static let maxSealedBytes = 2 * 1024 * 1024 * 1024

    /// Longest edge for chat export (1080p class).
    private static let maxExportEdge: CGFloat = 1280

    /// Reads duration, pixel size and audio presence without decoding any frames.
    static func probe(url: URL) async -> VideoProbe? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let duration = try? await asset.load(.duration),
              let natural = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return nil }

        let display = displaySize(natural: natural, transform: transform)
        let hasAudio = ((try? await asset.loadTracks(withMediaType: .audio)) ?? []).isEmpty == false
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)??.intValue

        return VideoProbe(
            durationSeconds: max(0.1, CMTimeGetSeconds(duration)),
            width: max(1, Int(display.width.rounded())),
            height: max(1, Int(display.height.rounded())),
            fileSizeBytes: size ?? 0,
            hasAudio: hasAudio
        )
    }

    /// First frame as an image, for compose thumbnails and the optimistic bubble.
    static func posterImage(url: URL, maxEdge: CGFloat = 640) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        guard let jpeg = await thumbnailJPEG(from: asset, maxEdge: maxEdge) else { return nil }
        return UIImage(data: jpeg)
    }

    /// Evenly spaced stills for the compose-screen trim strip.
    static func filmstrip(url: URL, count: Int, maxEdge: CGFloat = 160) async -> [UIImage] {
        guard count > 0 else { return [] }
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return [] }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return [] }

        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxEdge, height: maxEdge)
        // A filmstrip is a rough map of the clip, so let the generator snap to the nearest
        // sync frame instead of decoding to an exact time for every tile.
        generator.requestedTimeToleranceBefore = CMTime(seconds: 0.6, preferredTimescale: 600)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 0.6, preferredTimescale: 600)

        var frames: [UIImage] = []
        frames.reserveCapacity(count)
        for index in 0 ..< count {
            if Task.isCancelled { return frames }
            let fraction = (Double(index) + 0.5) / Double(count)
            let time = CMTime(seconds: seconds * fraction, preferredTimescale: 600)
            guard let cg = try? await generator.image(at: time).image else { continue }
            frames.append(UIImage(cgImage: cg))
        }
        return frames
    }

    /// Prepares a file URL for sending: apply the compose trim/mute, compress when needed,
    /// and always produce MP4 when re-exporting.
    ///
    /// `onProgress` reports 0…1 across the export. The passthrough path copies the streams into
    /// a fresh MP4 without re-encoding, which only drops the source's metadata (location, device,
    /// dates) and takes about as long as reading the file.
    ///
    /// Human: No path sends the source file itself. Every output is written by an export session
    /// told to carry none of the source's metadata (`MediaMetadataScrubber.stripMetadata`), or by
    /// `AVAssetWriter`, which starts empty.
    static func encode(
        sourceURL: URL,
        trim: VideoTrim? = nil,
        removeAudio: Bool = false,
        quality: VideoUploadQuality = .high,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> EncodedVideo {
        let asset = AVURLAsset(url: sourceURL)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw VideoError.unreadable
        }

        let duration = try await asset.load(.duration)
        let fullSeconds = CMTimeGetSeconds(duration)
        let naturalSize = try await videoTrack.load(.naturalSize)
        let transform = try await videoTrack.load(.preferredTransform)
        let display = Self.displaySize(natural: naturalSize, transform: transform)
        let width = max(1, Int(display.width.rounded()))
        let height = max(1, Int(display.height.rounded()))

        // Only treat the trim as real when it actually removes something.
        let effectiveTrim = trim.flatMap { $0.isFullRange(of: fullSeconds) ? nil : $0 }
        let keptSeconds = effectiveTrim?.duration ?? fullSeconds
        let durationMs = max(1, Int((keptSeconds * 1000).rounded()))
        let mustRewrite = effectiveTrim != nil || removeAudio

        // Poster comes from the first frame the recipient will actually see.
        let thumbnail = await thumbnailJPEG(
            from: asset,
            maxEdge: 720,
            at: effectiveTrim.map { CMTime(seconds: $0.start, preferredTimescale: 600) } ?? .zero
        )

        // Only pass through a real MP4 that already fits the chosen rung and the size cap.
        // Never ship raw .mov / HEVC camera containers — recipients write a temp `.mp4`
        // for playback and those formats fail to open. "Pass through" keeps the encoded
        // streams; the container is rewritten so the file's metadata stays behind. If that
        // rewrite fails, the clip is compressed like any other rather than sent as it is.
        if let attrs = try? FileManager.default.attributesOfItem(atPath: sourceURL.path),
           let size = attrs[.size] as? NSNumber,
           canPassthrough(
               fileExtension: sourceURL.pathExtension,
               fileSize: size.intValue,
               width: width,
               height: height,
               mustRewrite: mustRewrite,
               quality: quality
           ),
           let remuxed = try? await export(asset: asset, preset: AVAssetExportPresetPassthrough, onProgress: onProgress)
        {
            defer { try? FileManager.default.removeItem(at: remuxed) }
            if let data = try? Data(contentsOf: remuxed, options: [.mappedIfSafe]), data.count <= maxPlaintextBytes {
                onProgress?(1)
                return EncodedVideo(
                    data: data,
                    width: width,
                    height: height,
                    durationMs: durationMs,
                    mime: "video/mp4",
                    thumbnailJPEG: thumbnail
                )
            }
        }

        // Trim and mute are structural, so they go through a composition; a plain compress
        // exports the original asset (cheaper, and keeps the source's own track layout).
        let exportAsset: AVAsset
        if mustRewrite {
            exportAsset = try await composition(
                asset: asset,
                range: effectiveTrim?.timeRange ?? CMTimeRange(start: .zero, duration: duration),
                includeAudio: !removeAudio
            )
        } else {
            exportAsset = asset
        }

        // The chosen rung first, then smaller presets until the file fits (H.264/AAC MP4).
        // Original stops at 1080p: that is the highest preset that re-encodes to H.264.
        let presets = exportPresets(for: quality)

        // Human: Every preset used to be tried by running a full export and checking the size
        // afterwards. A 40 s clip ran 720p, then 540p, then 480p: three full encodes, with the
        // ring dropping back to zero each time, so the bubble looked stuck on "Compressing" for
        // minutes. The session's size estimate costs nothing, so only presets it expects to
        // fit get exported. The estimate can be about 10% low, hence the margin. The fallback
        // loop stays in case an export still comes out too big.
        let available = presets.filter { AVAssetExportSession.allExportPresets().contains($0) }
        guard !available.isEmpty else { throw VideoError.exportFailed }
        var estimates: [(preset: String, bytes: Int64?)] = []
        for preset in available {
            estimates.append((preset, await estimatedBytes(asset: exportAsset, preset: preset)))
        }
        let candidates = exportCandidates(estimates)
        // A preset whose estimate is over the cap is skipped. What remains, if nothing
        // named will fit, is written at the bitrate the plan picked for this quality.
        guard !candidates.isEmpty else {
            return try await exportAtPlanBitrate(
                source: exportAsset,
                width: width,
                height: height,
                durationMs: durationMs,
                thumbnail: thumbnail,
                quality: quality,
                onProgress: onProgress
            )
        }

        var lastError: Error = VideoError.exportFailed
        for (attempt, preset) in candidates.enumerated() {
            try Task.checkCancellation()
            do {
                // A fallback export continues the ring from where the last one stopped
                // instead of sending it back to 0.
                let window = progressWindow(attempt: attempt)
                let exportedURL = try await export(
                    asset: exportAsset,
                    preset: preset,
                    onProgress: onProgress.map { report in
                        { @Sendable fraction in
                            report(window.lowerBound + fraction * (window.upperBound - window.lowerBound))
                        }
                    }
                )
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
                onProgress?(1)
                return EncodedVideo(
                    data: data,
                    width: outW,
                    height: outH,
                    durationMs: durationMs,
                    mime: "video/mp4",
                    thumbnailJPEG: thumbnail
                )
            } catch is CancellationError {
                throw VideoError.cancelled
            } catch VideoError.cancelled {
                throw VideoError.cancelled
            } catch {
                lastError = error
            }
        }
        if let tooBig = lastError as? VideoError, tooBig == .tooLarge {
            return try await exportAtPlanBitrate(
                source: exportAsset,
                width: width,
                height: height,
                durationMs: durationMs,
                thumbnail: thumbnail,
                quality: quality,
                onProgress: onProgress
            )
        }
        throw lastError
    }

    /// Writes H.264 + AAC at the size and bitrate `previewPlan` would pick for this asset
    /// and `quality`. Used when every named preset is larger than the server cap. Original
    /// does not step down here: if 1080p will not fit, this fails the same way the sheet does.
    private static func exportAtPlanBitrate(
        source: AVAsset,
        width: Int,
        height: Int,
        durationMs: Int,
        thumbnail: Data?,
        quality: VideoUploadQuality,
        onProgress: (@Sendable (Double) -> Void)?
    ) async throws -> EncodedVideo {
        let seconds = Double(durationMs) / 1000
        let hasAudio = (try? await source.loadTracks(withMediaType: .audio).first) != nil
        let probe = VideoProbe(
            durationSeconds: max(0.1, seconds),
            width: width,
            height: height,
            fileSizeBytes: maxPlaintextBytes + 1,
            hasAudio: hasAudio
        )
        // Over the cap and not an mp4 passthrough, so this takes the chosen quality's ladder.
        // The duration picks how far down that ladder goes.
        let plan: VideoOutgoingPlan
        do {
            plan = try previewPlan(
                probe: probe,
                fileExtension: "mov",
                trim: nil,
                removeAudio: !hasAudio,
                quality: quality
            )
        } catch {
            throw VideoError.tooLarge
        }
        let url = try await writeBudget(
            asset: source,
            width: plan.width,
            height: plan.height,
            videoBitrate: plan.videoBitrate,
            audioBitrate: plan.audioBitrate,
            frameRate: plan.frameRate == 0 ? 30 : plan.frameRate,
            onProgress: onProgress
        )
        defer { try? FileManager.default.removeItem(at: url) }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        guard data.count <= maxPlaintextBytes else { throw VideoError.tooLarge }
        onProgress?(1)
        return EncodedVideo(
            data: data,
            width: plan.width,
            height: plan.height,
            durationMs: durationMs,
            mime: "video/mp4",
            thumbnailJPEG: thumbnail
        )
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

    static func thumbnailJPEG(from asset: AVAsset, maxEdge: CGFloat, at time: CMTime = .zero) async -> Data? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        let maxPx = max(1, Int(maxEdge))
        generator.maximumSize = CGSize(width: maxPx, height: maxPx)
        generator.requestedTimeToleranceBefore = CMTime(seconds: 0.3, preferredTimescale: 600)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 0.3, preferredTimescale: 600)
        guard let cg = try? await generator.image(at: time).image else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.72)
    }

    /// Presets for `quality`, best first. Original is the 1080p H.264 preset.
    static func exportPresets(for quality: VideoUploadQuality) -> [String] {
        switch quality {
        case .original:
            return [AVAssetExportPreset1920x1080]
        case .high:
            return [
                AVAssetExportPreset1280x720,
                AVAssetExportPreset960x540,
                AVAssetExportPreset640x480,
                AVAssetExportPresetMediumQuality,
                AVAssetExportPresetLowQuality,
            ]
        case .medium:
            return [
                AVAssetExportPreset960x540,
                AVAssetExportPreset640x480,
                AVAssetExportPresetMediumQuality,
                AVAssetExportPresetLowQuality,
            ]
        case .small:
            return [
                AVAssetExportPreset640x480,
                AVAssetExportPresetMediumQuality,
                AVAssetExportPresetLowQuality,
            ]
        }
    }

    /// Size and resolution the compose sheet shows. Same rungs as web `planVideo`.
    /// Original re-encodes stop at 1080p because that is the highest H.264 preset.
    static func previewPlan(
        probe: VideoProbe,
        fileExtension: String,
        trim: VideoTrim?,
        removeAudio: Bool,
        quality: VideoUploadQuality
    ) throws -> VideoOutgoingPlan {
        let full = trim?.isFullRange(of: probe.durationSeconds) ?? true
        let mustRewrite = !full || removeAudio
        let duration = max(0.1, full ? probe.durationSeconds : (trim?.duration ?? probe.durationSeconds))
        let width = max(1, probe.width)
        let height = max(1, probe.height)

        if canPassthrough(
            fileExtension: fileExtension,
            fileSize: probe.fileSizeBytes,
            width: width,
            height: height,
            mustRewrite: mustRewrite,
            quality: quality
        ) {
            return VideoOutgoingPlan(
                width: width,
                height: height,
                estimatedBytes: max(1, probe.fileSizeBytes),
                resolutionLabel: resolutionLabel(width: width, height: height, keptSource: quality == .original),
                passthrough: true,
                videoBitrate: 0,
                audioBitrate: 0,
                frameRate: 0
            )
        }

        let hasSound = probe.hasAudio && !removeAudio
        let budgetBits = (Double(maxPlaintextBytes) * 8 * headroom) / duration
        let bitrateCap = quality == .original ? originalMaxVideoBitrate : maxVideoBitrate
        var fallback = (floor: minVideoBitrate, audio: 0.0)
        for box in encodeBoxes(for: quality) {
            let longForm = box.tier >= 4
            let fitted = fit(width: width, height: height, box: box)
            let rateCap = longForm ? 15.0 : (box.tier >= 2 ? 30.0 : 60.0)
            let rate = min(assumedFps, rateCap)
            let pixels = Double(fitted.width * fitted.height) * rate
            let minRate = longForm ? longMinVideoBitrate : minVideoBitrate
            let audioBitrate = hasSound ? (longForm ? longAudioBitrate : (box.tier >= 2 ? 96_000.0 : 128_000.0)) : 0
            let target = min(bitrateCap, max(minRate, pixels * targetBpp))
            let floor = max(minRate, pixels * floorBpp)
            let budget = budgetBits - audioBitrate
            fallback = (floor, audioBitrate)
            if budget < floor { continue }
            let videoBitrate = min(target, budget)
            let bytes = Int((((videoBitrate + audioBitrate) * duration) / 8 * 1.02).rounded())
            let keptSource = quality == .original
                && abs(max(fitted.width, fitted.height) - max(width, height)) <= 4
                && abs(min(fitted.width, fitted.height) - min(width, height)) <= 4
            return VideoOutgoingPlan(
                width: fitted.width,
                height: fitted.height,
                estimatedBytes: max(1, bytes),
                resolutionLabel: resolutionLabel(width: fitted.width, height: fitted.height, keptSource: keptSource),
                passthrough: false,
                videoBitrate: Int(videoBitrate.rounded()),
                audioBitrate: Int(audioBitrate.rounded()),
                frameRate: assumedFps > rate + 1 ? Int(rate) : 0
            )
        }
        let maxSeconds = Int((Double(maxPlaintextBytes) * 8 * headroom) / (fallback.floor + fallback.audio))
        let seconds = max(1, maxSeconds)
        let clock = Self.clock(seconds)
        throw VideoPlanError(
            message: quality == .original
                ? "Original quality won’t fit. Trim it to \(clock) or choose a lower quality."
                : "This video is too long to send. Trim it to \(clock) or less.",
            maxSeconds: seconds
        )
    }

    // MARK: - Private

    /// Presets whose estimated output is under this share of the cap get exported.
    static let estimateMargin = 0.85

    /// Same bitrate model as web `videoPlan.ts`, at an assumed 30 fps (the probe has no frame rate).
    private static let targetBpp = 0.085
    private static let floorBpp = 0.028
    private static let minVideoBitrate = 120_000.0
    /// Same as web `LONG_MIN_VIDEO_BITRATE` / `LONG_AUDIO_BITRATE`.
    private static let longMinVideoBitrate = 40_000.0
    private static let longAudioBitrate = 32_000.0
    private static let maxVideoBitrate = 3_200_000.0
    private static let originalMaxVideoBitrate = 12_000_000.0
    private static let headroom = 0.9
    private static let assumedFps = 30.0

    private struct PlanBox {
        var long: Double
        var short: Double
        var tier: Int
    }

    /// Boxes the sheet may promise, best first. The 640-wide rung matches
    /// `AVAssetExportPreset640x480`: 16:9 lands at 360p, and a squarer frame stays inside
    /// 640×480. A 360-tall box would promise a size that preset does not make.
    private static func encodeBoxes(for quality: VideoUploadQuality) -> [PlanBox] {
        switch quality {
        case .original:
            return [PlanBox(long: 1920, short: 1080, tier: 0)]
        case .high:
            return [
                PlanBox(long: 1280, short: 720, tier: 0),
                PlanBox(long: 960, short: 540, tier: 1),
                PlanBox(long: 640, short: 480, tier: 2),
                PlanBox(long: 480, short: 270, tier: 3),
                PlanBox(long: 480, short: 270, tier: 4),
            ]
        case .medium:
            return [
                PlanBox(long: 960, short: 540, tier: 1),
                PlanBox(long: 640, short: 480, tier: 2),
                PlanBox(long: 480, short: 270, tier: 3),
                PlanBox(long: 480, short: 270, tier: 4),
            ]
        case .small:
            return [
                PlanBox(long: 640, short: 480, tier: 2),
                PlanBox(long: 480, short: 270, tier: 3),
                PlanBox(long: 480, short: 270, tier: 4),
            ]
        }
    }

    /// Largest frame that may be sent unchanged. Nil means any size (original).
    private static func passthroughLimit(_ quality: VideoUploadQuality) -> (long: Int, short: Int)? {
        switch quality {
        case .original: return nil
        case .high: return (1280, 720)
        case .medium: return (960, 540)
        case .small: return (640, 480)
        }
    }

    private static func canPassthrough(
        fileExtension: String,
        fileSize: Int,
        width: Int,
        height: Int,
        mustRewrite: Bool,
        quality: VideoUploadQuality
    ) -> Bool {
        if mustRewrite || fileSize <= 0 || fileSize > maxPlaintextBytes { return false }
        let ext = fileExtension.lowercased()
        guard ext == "mp4" || ext == "m4v" else { return false }
        if let limit = passthroughLimit(quality) {
            if max(width, height) > limit.long || min(width, height) > limit.short { return false }
        }
        return true
    }

    private static func fit(width: Int, height: Int, box: PlanBox) -> (width: Int, height: Int) {
        let w = Double(max(width, 1))
        let h = Double(max(height, 1))
        let landscape = w >= h
        let long = landscape ? w : h
        let short = landscape ? h : w
        let scale = min(1, box.long / max(long, 1), box.short / max(short, 1))
        return (evenDimension(w * scale), evenDimension(h * scale))
    }

    private static func evenDimension(_ n: Double) -> Int {
        max(2, Int((n / 2).rounded()) * 2)
    }

    private static func resolutionLabel(width: Int, height: Int, keptSource: Bool) -> String {
        if keptSource { return "Original" }
        let short = min(width, height)
        let named = [(1080, "1080p"), (720, "720p"), (540, "540p"), (480, "480p"), (360, "360p"), (270, "270p")]
        for (edge, label) in named where abs(short - edge) <= 16 { return label }
        return "\(short)p"
    }

    private static func clock(_ seconds: Int) -> String {
        let total = max(0, seconds)
        let padded = total % 60
        return "\(total / 60):\(padded < 10 ? "0" : "")\(padded)"
    }

    /// The presets worth exporting, best first, given each one's size estimate.
    ///
    /// A preset with no estimate is kept, since only a real export can tell. When nothing
    /// clears the margin, the smallest preset still gets one try if its estimate is under
    /// the cap itself: the margin exists to avoid wasted encodes, not to refuse clips that
    /// would fit.
    static func exportCandidates(
        _ estimates: [(preset: String, bytes: Int64?)],
        cap: Int = maxPlaintextBytes
    ) -> [String] {
        let fits = estimates.filter { entry in
            guard let bytes = entry.bytes else { return true }
            return Double(bytes) <= Double(cap) * estimateMargin
        }
        if !fits.isEmpty { return fits.map(\.preset) }
        guard let smallest = estimates.compactMap({ entry in entry.bytes.map { (entry.preset, $0) } })
            .min(by: { $0.1 < $1.1 }),
            smallest.1 <= Int64(cap)
        else { return [] }
        return [smallest.0]
    }

    /// The share of the 0…1 ring that export attempt `attempt` fills.
    ///
    /// The first export gets 0…0.9. Each fallback gets 90% of what is left, so the ring
    /// keeps moving forward and only reaches 1 once an export is accepted.
    static func progressWindow(attempt: Int) -> ClosedRange<Double> {
        var lower = 0.0
        var span = 0.9
        for _ in 0 ..< max(0, attempt) {
            lower += span
            span = (1 - lower) * 0.9
        }
        return lower ... (lower + span)
    }

    /// The export session's own size prediction for `preset`; nil when it cannot tell.
    private static func estimatedBytes(asset: AVAsset, preset: String) async -> Int64? {
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { return nil }
        session.outputFileType = .mp4
        guard let bytes = try? await session.estimatedOutputFileLengthInBytes, bytes > 0 else { return nil }
        return bytes
    }

    /// Video (+ optional audio) rewritten over `range` — how trim and mute are applied.
    private static func composition(
        asset: AVAsset,
        range: CMTimeRange,
        includeAudio: Bool
    ) async throws -> AVComposition {
        let composition = AVMutableComposition()
        guard let sourceVideo = try await asset.loadTracks(withMediaType: .video).first,
              let videoTrack = composition.addMutableTrack(
                  withMediaType: .video,
                  preferredTrackID: kCMPersistentTrackID_Invalid
              )
        else { throw VideoError.unreadable }

        try videoTrack.insertTimeRange(range, of: sourceVideo, at: .zero)
        // Without this a portrait clip comes back rotated — the composition track does not
        // inherit the source's display transform.
        videoTrack.preferredTransform = try await sourceVideo.load(.preferredTransform)

        if includeAudio,
           let sourceAudio = try await asset.loadTracks(withMediaType: .audio).first,
           let audioTrack = composition.addMutableTrack(
               withMediaType: .audio,
               preferredTrackID: kCMPersistentTrackID_Invalid
           )
        {
            // A missing/short audio track must not fail the whole send.
            try? audioTrack.insertTimeRange(range, of: sourceAudio, at: .zero)
        }
        return composition
    }

    private static func export(
        asset: AVAsset,
        preset: String,
        onProgress: (@Sendable (Double) -> Void)?
    ) async throws -> URL {
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            throw VideoError.exportFailed
        }
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-export-\(UUID().uuidString).mp4")
        session.shouldOptimizeForNetworkUse = true
        MediaMetadataScrubber.stripMetadata(from: session)

        // The states sequence ends with the export, so the monitor is bounded; cancelling it
        // in `defer` covers the throwing paths.
        let monitor: Task<Void, Never>? = onProgress.map { report in
            Task {
                for await state in session.states(updateInterval: 0.15) {
                    if case let .exporting(progress) = state {
                        report(min(1, max(0, progress.fractionCompleted)))
                    }
                }
            }
        }
        defer { monitor?.cancel() }

        do {
            try await session.export(to: out, as: .mp4)
            onProgress?(1)
            return out
        } catch is CancellationError {
            try? FileManager.default.removeItem(at: out)
            throw VideoError.cancelled
        } catch {
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }

    /// H.264 + AAC at an explicit bitrate. Named export presets cannot go low enough once
    /// every one of them estimates over the server cap.
    static func writeBudget(
        asset: AVAsset,
        width: Int,
        height: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        frameRate: Int,
        onProgress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        let renderWidth = max(2, width - width % 2)
        let renderHeight = max(2, height - height % 2)
        let render = CGSize(width: renderWidth, height: renderHeight)
        let duration = try await asset.load(.duration)
        let seconds = max(0.1, CMTimeGetSeconds(duration))
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw VideoError.unreadable
        }
        let natural = try await videoTrack.load(.naturalSize)
        let preferred = try await videoTrack.load(.preferredTransform)

        let composition = AVMutableVideoComposition()
        composition.renderSize = render
        composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(max(1, frameRate)))
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(fittedTransform(natural: natural, preferred: preferred, render: render), at: .zero)
        instruction.layerInstructions = [layer]
        composition.instructions = [instruction]

        let reader = try AVAssetReader(asset: asset)
        let videoOutput = AVAssetReaderVideoCompositionOutput(
            videoTracks: [videoTrack],
            videoSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            ]
        )
        videoOutput.videoComposition = composition
        videoOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput) else { throw VideoError.exportFailed }
        reader.add(videoOutput)

        let audioTrack = audioBitrate > 0
            ? try await asset.loadTracks(withMediaType: .audio).first
            : nil
        let audioOutput: AVAssetReaderTrackOutput? = audioTrack.map { track in
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
            ])
            output.alwaysCopiesSampleData = false
            return output
        }
        if let audioOutput {
            guard reader.canAdd(audioOutput) else { throw VideoError.exportFailed }
            reader.add(audioOutput)
        }

        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("shroud-budget-\(UUID().uuidString).mp4")
        var keep = false
        defer {
            if !keep { try? FileManager.default.removeItem(at: out) }
        }
        let writer = try AVAssetWriter(outputURL: out, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: renderWidth,
            AVVideoHeightKey: renderHeight,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: max(40_000, videoBitrate),
                AVVideoExpectedSourceFrameRateKey: max(1, frameRate),
                AVVideoMaxKeyFrameIntervalKey: max(1, frameRate) * 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput) else { throw VideoError.exportFailed }
        writer.add(videoInput)
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: renderWidth,
                kCVPixelBufferHeightKey as String: renderHeight,
            ]
        )
        let audioInput: AVAssetWriterInput? = audioOutput == nil ? nil : AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: max(32_000, audioBitrate),
            ]
        )
        if let audioInput {
            audioInput.expectsMediaDataInRealTime = false
            guard writer.canAdd(audioInput) else { throw VideoError.exportFailed }
            writer.add(audioInput)
        }

        guard reader.startReading() else { throw VideoError.exportFailed }
        writer.startWriting()
        guard writer.status == .writing else { throw VideoError.exportFailed }
        writer.startSession(atSourceTime: .zero)

        var videoBuffer = videoOutput.copyNextSampleBuffer()
        var audioBuffer = audioOutput?.copyNextSampleBuffer()
        while videoBuffer != nil || audioBuffer != nil {
            try Task.checkCancellation()
            let videoTime = videoBuffer.map { CMSampleBufferGetPresentationTimeStamp($0).seconds } ?? .infinity
            let audioTime = audioBuffer.map { CMSampleBufferGetPresentationTimeStamp($0).seconds } ?? .infinity
            if videoTime <= audioTime, let sample = videoBuffer {
                try await waitUntilReady(videoInput)
                if let image = CMSampleBufferGetImageBuffer(sample) {
                    let time = CMSampleBufferGetPresentationTimeStamp(sample)
                    if !adaptor.append(image, withPresentationTime: time) {
                        throw VideoError.exportFailed
                    }
                    onProgress?(min(0.99, max(0, time.seconds / seconds)))
                }
                videoBuffer = videoOutput.copyNextSampleBuffer()
            } else if let sample = audioBuffer, let audioInput {
                try await waitUntilReady(audioInput)
                if !audioInput.append(sample) { throw VideoError.exportFailed }
                audioBuffer = audioOutput?.copyNextSampleBuffer()
            } else {
                break
            }
        }
        if reader.status == .failed { throw VideoError.exportFailed }
        videoInput.markAsFinished()
        audioInput?.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else { throw VideoError.exportFailed }
        keep = true
        return out
    }

    private static func waitUntilReady(_ input: AVAssetWriterInput) async throws {
        var spins = 0
        while !input.isReadyForMoreMediaData {
            try Task.checkCancellation()
            if spins > 500 { throw VideoError.exportFailed }
            spins += 1
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    /// `preferred` maps the track's stored pixels onto its display rect. Scale that into
    /// `render` without stretching, and shift a rotated frame back onto the canvas.
    private static func fittedTransform(natural: CGSize, preferred: CGAffineTransform, render: CGSize) -> CGAffineTransform {
        let bounds = CGRect(origin: .zero, size: natural).applying(preferred).standardized
        let display = CGSize(width: max(bounds.width, 1), height: max(bounds.height, 1))
        let scale = min(render.width / display.width, render.height / display.height)
        let scaled = CGSize(width: display.width * scale, height: display.height * scale)
        let tx = (render.width - scaled.width) / 2 - bounds.origin.x * scale
        let ty = (render.height - scaled.height) / 2 - bounds.origin.y * scale
        return preferred
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
            .concatenating(CGAffineTransform(translationX: tx, y: ty))
    }

    private static func displaySize(natural: CGSize, transform: CGAffineTransform) -> CGSize {
        let rect = CGRect(origin: .zero, size: natural).applying(transform)
        return CGSize(width: abs(rect.width), height: abs(rect.height))
    }
}

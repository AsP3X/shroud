import AVFoundation
import CoreVideo
import Foundation
import Testing
@testable import shroud

struct VideoMediaEncodeTests {
    private let cap = 100_000_000

    // MARK: - Preset choice

    @Test func skipsPresetsEstimatedOverTheMargin() {
        let chosen = VideoMedia.exportCandidates(
            [("720", 120_000_000), ("540", 90_000_000), ("480", 50_000_000), ("low", 5_000_000)],
            cap: cap
        )
        #expect(chosen == ["480", "low"])
    }

    @Test func keepsPresetsWithoutAnEstimate() {
        let chosen = VideoMedia.exportCandidates([("720", nil), ("540", 200_000_000)], cap: cap)
        #expect(chosen == ["720"])
    }

    @Test func triesTheSmallestPresetWhenOnlyTheMarginRulesItOut() {
        // 90 MB is over 85% of the cap but under the cap itself: worth one real export.
        let chosen = VideoMedia.exportCandidates(
            [("720", 300_000_000), ("low", 90_000_000), ("540", 150_000_000)],
            cap: cap
        )
        #expect(chosen == ["low"])
    }

    @Test func presetsFollowTheChosenQuality() {
        #expect(VideoMedia.exportPresets(for: .high).first == AVAssetExportPreset1280x720)
        #expect(VideoMedia.exportPresets(for: .medium).first == AVAssetExportPreset960x540)
        #expect(!VideoMedia.exportPresets(for: .medium).contains(AVAssetExportPreset1280x720))
        #expect(VideoMedia.exportPresets(for: .small).first == AVAssetExportPreset640x480)
        #expect(VideoMedia.exportPresets(for: .original) == [AVAssetExportPreset1920x1080])
    }

    @Test func originalKeepsAFittingMp4() throws {
        let plan = try VideoMedia.previewPlan(
            probe: VideoProbe(durationSeconds: 8, width: 1920, height: 1080, fileSizeBytes: 2_000_000, hasAudio: true),
            fileExtension: "mp4",
            trim: nil,
            removeAudio: false,
            quality: .original
        )
        #expect(plan.passthrough)
        #expect(plan.resolutionLabel == "Original")
        #expect(plan.width == 1920)
        #expect(plan.estimatedBytes == 2_000_000)
    }

    @Test func highScalesA1080pFileDownTo720() throws {
        let plan = try VideoMedia.previewPlan(
            probe: VideoProbe(durationSeconds: 8, width: 1920, height: 1080, fileSizeBytes: 2_000_000, hasAudio: true),
            fileExtension: "mp4",
            trim: nil,
            removeAudio: false,
            quality: .high
        )
        #expect(!plan.passthrough)
        #expect(plan.width == 1280)
        #expect(plan.height == 720)
        #expect(plan.resolutionLabel == "720p")
    }

    @Test func mediumAndSmallUseTheirBoxes() throws {
        let probe = VideoProbe(durationSeconds: 8, width: 1280, height: 720, fileSizeBytes: 4_000_000, hasAudio: true)
        let medium = try VideoMedia.previewPlan(
            probe: probe, fileExtension: "mov", trim: nil, removeAudio: false, quality: .medium
        )
        #expect(medium.width == 960)
        #expect(medium.height == 540)
        #expect(medium.resolutionLabel == "540p")
        let small = try VideoMedia.previewPlan(
            probe: probe, fileExtension: "mov", trim: nil, removeAudio: false, quality: .small
        )
        #expect(small.width == 640)
        #expect(small.height == 360)
        #expect(small.resolutionLabel == "360p")
        // 4:3 cannot become 360p: the preset that Small exports with fits inside 640×480.
        let fourByThree = try VideoMedia.previewPlan(
            probe: VideoProbe(durationSeconds: 8, width: 1440, height: 1080, fileSizeBytes: 4_000_000, hasAudio: true),
            fileExtension: "mov",
            trim: nil,
            removeAudio: false,
            quality: .small
        )
        #expect(fourByThree.width == 640)
        #expect(fourByThree.height == 480)
        #expect(fourByThree.resolutionLabel == "480p")
        let kept480 = try VideoMedia.previewPlan(
            probe: VideoProbe(durationSeconds: 8, width: 640, height: 480, fileSizeBytes: 1_000_000, hasAudio: true),
            fileExtension: "mp4",
            trim: nil,
            removeAudio: false,
            quality: .small
        )
        #expect(kept480.passthrough)
        #expect(kept480.resolutionLabel == "480p")
    }

    @Test func originalReencodeOf4KStopsAt1080() throws {
        let plan = try VideoMedia.previewPlan(
            probe: VideoProbe(durationSeconds: 8, width: 3840, height: 2160, fileSizeBytes: 20_000_000, hasAudio: true),
            fileExtension: "mov",
            trim: nil,
            removeAudio: false,
            quality: .original
        )
        #expect(!plan.passthrough)
        #expect(plan.width == 1920)
        #expect(plan.height == 1080)
        #expect(plan.resolutionLabel == "1080p")
    }

    @Test func muteAndTrimStopPassthrough() throws {
        let probe = VideoProbe(durationSeconds: 8, width: 1280, height: 720, fileSizeBytes: 1_000_000, hasAudio: true)
        let muted = try VideoMedia.previewPlan(
            probe: probe, fileExtension: "mp4", trim: nil, removeAudio: true, quality: .high
        )
        #expect(!muted.passthrough)
        let trimmed = try VideoMedia.previewPlan(
            probe: probe,
            fileExtension: "mp4",
            trim: VideoTrim(start: 1, end: 4),
            removeAudio: false,
            quality: .original
        )
        #expect(!trimmed.passthrough)
        #expect(trimmed.width == 1280)
        #expect(trimmed.height == 720)
    }

    @Test func halfHourFitsUnderTheCap() throws {
        let plan = try VideoMedia.previewPlan(
            probe: VideoProbe(
                durationSeconds: 30 * 60,
                width: 1920,
                height: 1080,
                fileSizeBytes: 200_000_000,
                hasAudio: true
            ),
            fileExtension: "mov",
            trim: nil,
            removeAudio: false,
            quality: .high
        )
        #expect(!plan.passthrough)
        #expect(plan.width == 1280)
        #expect(plan.height == 720)
        #expect(plan.resolutionLabel == "720p")
        #expect(plan.estimatedBytes <= VideoMedia.maxPlaintextBytes)
    }

    @Test func aFourMinuteClipAtSmallStaysUnderHigh() throws {
        let probe = VideoProbe(
            durationSeconds: 240,
            width: 1920,
            height: 1080,
            fileSizeBytes: 80_000_000,
            hasAudio: true
        )
        let small = try VideoMedia.previewPlan(
            probe: probe, fileExtension: "mov", trim: nil, removeAudio: false, quality: .small
        )
        let high = try VideoMedia.previewPlan(
            probe: probe, fileExtension: "mov", trim: nil, removeAudio: false, quality: .high
        )
        #expect(max(small.width, small.height) <= 640)
        #expect(max(high.width, high.height) > 640)
        #expect(small.estimatedBytes <= VideoMedia.maxPlaintextBytes)
        #expect(high.estimatedBytes <= VideoMedia.maxPlaintextBytes)
    }

    @Test func budgetEncodeWritesAnMp4() async throws {
        let url = try await Self.makeMovie(seconds: 1)
        defer { try? FileManager.default.removeItem(at: url) }
        let written = try await VideoMedia.writeBudget(
            asset: AVURLAsset(url: url),
            width: 320,
            height: 180,
            videoBitrate: 80_000,
            audioBitrate: 0,
            frameRate: 15
        )
        defer { try? FileManager.default.removeItem(at: written) }
        let data = try Data(contentsOf: written)
        #expect(data.count > 0)
        #expect(data.count <= VideoMedia.maxPlaintextBytes)
        let track = try await AVURLAsset(url: written).loadTracks(withMediaType: .video).first
        let natural = try #require(try await track?.load(.naturalSize))
        #expect(Int(natural.width.rounded()) == 320)
        #expect(Int(natural.height.rounded()) == 180)
    }

    @Test func originalRefusesAClipThatCannotStayFullSize() {
        #expect(throws: VideoPlanError.self) {
            try VideoMedia.previewPlan(
                probe: VideoProbe(durationSeconds: 4 * 3600, width: 3840, height: 2160, fileSizeBytes: 80_000_000, hasAudio: true),
                fileExtension: "mov",
                trim: nil,
                removeAudio: false,
                quality: .original
            )
        }
    }

    @Test func givesUpWhenEvenTheSmallestEstimateIsOverTheCap() {
        let chosen = VideoMedia.exportCandidates([("720", 400_000_000), ("low", 101_000_000)], cap: cap)
        #expect(chosen.isEmpty)
    }

    // MARK: - Progress

    @Test func progressWindowsMoveForwardAndStayUnderOne() {
        var previousUpper = 0.0
        for attempt in 0 ..< 5 {
            let window = VideoMedia.progressWindow(attempt: attempt)
            #expect(window.lowerBound >= previousUpper - 1e-9)
            #expect(window.upperBound > window.lowerBound)
            #expect(window.upperBound < 1)
            previousUpper = window.upperBound
        }
        #expect(VideoMedia.progressWindow(attempt: 0) == 0.0 ... 0.9)
    }

    @Test func encodeReportsProgressThatNeverGoesBackwards() async throws {
        let url = try await Self.makeMovie(seconds: 2)
        defer { try? FileManager.default.removeItem(at: url) }
        let recorder = ProgressRecorder()
        let out = try await VideoMedia.encode(sourceURL: url, onProgress: { recorder.add($0) })

        #expect(out.data.count > 0)
        #expect(out.data.count <= VideoMedia.maxPlaintextBytes)
        #expect(out.mime == "video/mp4")
        let values = recorder.values
        #expect(values.last == 1)
        #expect(zip(values, values.dropFirst()).allSatisfy { $0 <= $1 })
    }

    @Test func smallQualityExportStaysInsideTheSmallBox() async throws {
        let url = try await Self.makeMovie(seconds: 1)
        defer { try? FileManager.default.removeItem(at: url) }
        let out = try await VideoMedia.encode(sourceURL: url, quality: .small)
        #expect(max(out.width, out.height) <= 640)
        #expect(min(out.width, out.height) <= 360)
    }

    @Test func smallQualityExportOfA4x3ClipFitsThe640x480Preset() async throws {
        let url = try await Self.makeMovie(seconds: 1, width: 1440, height: 1080)
        defer { try? FileManager.default.removeItem(at: url) }
        let out = try await VideoMedia.encode(sourceURL: url, quality: .small)
        #expect(out.width == 640)
        #expect(out.height == 480)
    }

    @Test func trimmedAndMutedEncodeFinishes() async throws {
        let url = try await Self.makeMovie(seconds: 3)
        defer { try? FileManager.default.removeItem(at: url) }
        let out = try await VideoMedia.encode(
            sourceURL: url,
            trim: VideoTrim(start: 0.5, end: 2.0),
            removeAudio: true
        )
        #expect(out.data.count > 0)
        #expect(out.durationMs == 1500)
    }

    // MARK: - Helpers

    private final class ProgressRecorder: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: [Double] = []

        func add(_ value: Double) {
            lock.lock()
            stored.append(value)
            lock.unlock()
        }

        var values: [Double] {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
    }

    /// A small H.264 .mov, so the encode takes the real export path rather than passthrough.
    private static func makeMovie(seconds: Int, fps: Int32 = 30, width: Int = 640, height: Int = 360) async throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("video-media-test-\(UUID().uuidString).mov")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        for frame in 0 ..< Int(fps) * seconds {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 1_000_000) }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, try #require(adaptor.pixelBufferPool), &buffer)
            let pixels = try #require(buffer)
            CVPixelBufferLockBaseAddress(pixels, [])
            memset(CVPixelBufferGetBaseAddress(pixels), Int32(frame * 4 % 255), CVPixelBufferGetDataSize(pixels))
            CVPixelBufferUnlockBaseAddress(pixels, [])
            adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps))
        }
        input.markAsFinished()
        await writer.finishWriting()
        try #require(writer.status == .completed)
        return url
    }
}

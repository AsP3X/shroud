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
    private static func makeMovie(seconds: Int, fps: Int32 = 30) async throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("video-media-test-\(UUID().uuidString).mov")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let width = 640, height = 360
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

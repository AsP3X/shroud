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

    /// A clip whose sound stops before its picture (a phone video with a short sound track). The
    /// writer interleaves its inputs and held the picture back for sound that never came: the
    /// encode failed after 5 s on dev, and hung once that limit went. It must finish, sound kept.
    @Test(.timeLimit(.minutes(10)))
    func budgetEncodeOfAClipWhoseSoundEndsEarlyFinishes() async throws {
        let url = try await Self.makeMovie(seconds: 10, audioSeconds: 2)
        defer { try? FileManager.default.removeItem(at: url) }
        let written = try await VideoMedia.writeBudget(
            asset: AVURLAsset(url: url),
            width: 320,
            height: 180,
            videoBitrate: 80_000,
            audioBitrate: 32_000,
            frameRate: 15
        )
        defer { try? FileManager.default.removeItem(at: written) }
        let encoded = AVURLAsset(url: written)
        #expect(try await encoded.load(.duration).seconds > 9.5)
        let sound = try #require(try await encoded.loadTracks(withMediaType: .audio).first)
        #expect(try await sound.load(.timeRange).duration.seconds > 1.5)
    }

    /// A writer that stopped ends the wait at its first look, not after a time limit (the old
    /// limit failed it too, but only 5 s later).
    @Test func aWriterThatStoppedEndsTheWaitAtOnce() async throws {
        let (writer, input, url) = try Self.idleWriter()
        defer { try? FileManager.default.removeItem(at: url) }
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        writer.cancelWriting()
        // An input that still took samples would return at once and prove nothing.
        #expect(!input.isReadyForMoreMediaData)
        let start = ContinuousClock.now
        await #expect(throws: VideoMedia.VideoError.exportFailed) {
            try await VideoMedia.nextBudgetInput(video: (input, 0), audio: nil, writer: writer, stallLimit: .seconds(30))
        }
        #expect(ContinuousClock.now - start < .seconds(5))
    }

    /// Neither input taking a sample for the whole stall limit ends the encode instead of leaving
    /// the send on Compressing (nothing cancels a send's encode from outside). Readiness is held
    /// fixed: a real encoder under load can pause and resume inside any limit a test could pick.
    @Test func neitherInputTakingASampleEndsTheWaitAfterTheLimit() async throws {
        let start = ContinuousClock.now
        await #expect(throws: VideoMedia.VideoError.exportFailed) {
            try await VideoMedia.nextBudgetInput(
                video: (ready: { false }, time: 1),
                audio: (ready: { false }, time: 2),
                isWriting: { true },
                stallLimit: .milliseconds(300)
            )
        }
        #expect(ContinuousClock.now - start >= .milliseconds(300))
    }

    /// The writer may hold the earlier sample's input until the other catches up: the other one
    /// is fed meanwhile instead of waiting on the earlier one for good.
    @Test func theInputTheWriterWillTakeGoesNextEvenIfItsSampleIsLater() async throws {
        let next = try await VideoMedia.nextBudgetInput(
            video: (ready: { false }, time: 1),
            audio: (ready: { true }, time: 2),
            isWriting: { true },
            stallLimit: .seconds(30)
        )
        #expect(next == .audio)
    }

    /// With both inputs willing, the earlier sample goes first.
    @Test func theEarlierSampleGoesFirstWhenBothInputsWillTakeOne() async throws {
        let video = try await VideoMedia.nextBudgetInput(
            video: (ready: { true }, time: 1),
            audio: (ready: { true }, time: 2),
            isWriting: { true },
            stallLimit: .seconds(30)
        )
        #expect(video == .video)
        let audio = try await VideoMedia.nextBudgetInput(
            video: (ready: { true }, time: 3),
            audio: (ready: { true }, time: 2),
            isWriting: { true },
            stallLimit: .seconds(30)
        )
        #expect(audio == .audio)
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
    /// With `audioSeconds`, a mono AAC track of silence that long starts with the picture.
    private static func makeMovie(
        seconds: Int,
        fps: Int32 = 30,
        width: Int = 640,
        height: Int = 360,
        audioSeconds: Int? = nil
    ) async throws -> URL {
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
        let sound: AVAssetWriterInput? = audioSeconds == nil ? nil : AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: audioRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64_000,
        ])
        if let sound {
            sound.expectsMediaDataInRealTime = false
            writer.add(sound)
        }
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        let frames = Int(fps) * seconds
        let chunks = audioRate * (audioSeconds ?? 0) / audioChunk
        var frame = 0
        var chunk = 0
        // The writer interleaves its inputs and holds one back until the other catches up, so
        // whichever will take a sample gets the next one.
        while frame < frames || chunk < chunks {
            if frame < frames, input.isReadyForMoreMediaData {
                var buffer: CVPixelBuffer?
                CVPixelBufferPoolCreatePixelBuffer(nil, try #require(adaptor.pixelBufferPool), &buffer)
                let pixels = try #require(buffer)
                CVPixelBufferLockBaseAddress(pixels, [])
                memset(CVPixelBufferGetBaseAddress(pixels), Int32(frame * 4 % 255), CVPixelBufferGetDataSize(pixels))
                CVPixelBufferUnlockBaseAddress(pixels, [])
                adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps))
                frame += 1
            } else if chunk < chunks, let sound, sound.isReadyForMoreMediaData {
                sound.append(try silence(startingAt: chunk * audioChunk))
                chunk += 1
                // Finished at once, or the writer holds the picture back for more of it.
                if chunk == chunks { sound.markAsFinished() }
            } else {
                try await Task.sleep(nanoseconds: 1_000_000)
            }
        }
        input.markAsFinished()
        if chunks == 0 { sound?.markAsFinished() }
        await writer.finishWriting()
        try #require(writer.status == .completed)
        return url
    }

    /// A writer with one video input, not yet started.
    private static func idleWriter() throws -> (AVAssetWriter, AVAssetWriterInput, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("video-media-test-\(UUID().uuidString).mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 320,
            AVVideoHeightKey: 180,
        ])
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        return (writer, input, url)
    }

    private static let audioRate = 44_100
    /// Samples per audio buffer: about 23 ms, the size a microphone delivers.
    private static let audioChunk = 1024

    /// `audioChunk` samples of 16-bit mono silence at sample `start`.
    private static func silence(startingAt start: Int) throws -> CMSampleBuffer {
        var description = AudioStreamBasicDescription(
            mSampleRate: Float64(audioRate),
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 2,
            mFramesPerPacket: 1,
            mBytesPerFrame: 2,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 16,
            mReserved: 0
        )
        var format: CMAudioFormatDescription?
        CMAudioFormatDescriptionCreate(
            allocator: nil, asbd: &description, layoutSize: 0, layout: nil,
            magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format
        )
        let length = audioChunk * 2
        var block: CMBlockBuffer?
        CMBlockBufferCreateWithMemoryBlock(
            allocator: nil, memoryBlock: nil, blockLength: length, blockAllocator: nil,
            customBlockSource: nil, offsetToData: 0, dataLength: length,
            flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block
        )
        let data = try #require(block)
        CMBlockBufferFillDataBytes(with: 0, blockBuffer: data, offsetIntoDestination: 0, dataLength: length)
        var sample: CMSampleBuffer?
        CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            allocator: nil, dataBuffer: data, formatDescription: try #require(format),
            sampleCount: audioChunk,
            presentationTimeStamp: CMTime(value: CMTimeValue(start), timescale: CMTimeScale(audioRate)),
            packetDescriptions: nil, sampleBufferOut: &sample
        )
        return try #require(sample)
    }
}

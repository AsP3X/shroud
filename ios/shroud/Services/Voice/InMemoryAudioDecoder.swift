import AudioToolbox
import AVFoundation
import Foundation

/// Decodes a voice note's bytes (AAC in M4A, WAV, CAF, …) to mono Float32 PCM without a file.
///
/// Human: A voice note is plaintext audio once it is decrypted or recorded, and nothing plaintext
/// may be written to disk (`docs/architecture.md`, security invariants 1 and 4). `AVAudioFile` only
/// opens URLs, so this hands Core Audio the bytes through read callbacks instead and lets
/// `ExtAudioFile`, the decoder `AVAudioFile` itself wraps, turn them into PCM. The samples match
/// what `AVAudioFile` reads from the same bytes on disk, priming and remainder frames included.
/// Agent: READS `data` only; WRITES nothing to disk; RETURNS PCM at the note's own sample rate
/// (callers resample). Throws `DecodeError.unreadable` when Core Audio cannot parse or decode it.
nonisolated enum InMemoryAudioDecoder {
    struct PCM: Sendable {
        /// Mono samples in [-1, 1].
        let samples: [Float]
        let sampleRate: Double

        var seconds: Double { Double(samples.count) / sampleRate }
    }

    enum DecodeError: Error, LocalizedError {
        case unreadable(OSStatus)

        var errorDescription: String? { "Couldn't convert the audio for transcription." }
    }

    /// Frames decoded per `ExtAudioFileRead`; about 0.7 s at 44.1 kHz.
    private static let chunkFrames: AVAudioFrameCount = 32_768

    static func decode(_ data: Data) throws -> PCM {
        let source = Source(data)
        // Human: Core Audio keeps the context pointer, not the object; `source` must outlive the
        // AudioFileID, which is closed before this scope ends.
        return try withExtendedLifetime(source) {
            var fileID: AudioFileID?
            // Hint 0: Core Audio detects the container from the bytes, as it does for a file.
            try check(AudioFileOpenWithCallbacks(
                Unmanaged.passUnretained(source).toOpaque(),
                Source.read,
                nil,
                Source.size,
                nil,
                0,
                &fileID
            ))
            guard let fileID else { throw DecodeError.unreadable(kAudioFileUnspecifiedError) }
            defer { AudioFileClose(fileID) }

            var extFile: ExtAudioFileRef?
            try check(ExtAudioFileWrapAudioFileID(fileID, false, &extFile))
            guard let extFile else { throw DecodeError.unreadable(kAudioFileUnspecifiedError) }
            defer { ExtAudioFileDispose(extFile) }

            return try readMono(extFile)
        }
    }

    /// Reads every frame of `extFile` as mono Float32 at the file's own sample rate.
    ///
    /// Human: Every Shroud client records mono, so the converter's downmix only matters for a
    /// stereo file; it never resamples, which stays with the engine's band-limited resampler.
    private static func readMono(_ extFile: ExtAudioFileRef) throws -> PCM {
        var fileFormat = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        try check(ExtAudioFileGetProperty(extFile, kExtAudioFileProperty_FileDataFormat, &size, &fileFormat))
        guard fileFormat.mSampleRate > 0,
              let clientFormat = AVAudioFormat(
                  commonFormat: .pcmFormatFloat32,
                  sampleRate: fileFormat.mSampleRate,
                  channels: 1,
                  interleaved: false
              ),
              let buffer = AVAudioPCMBuffer(pcmFormat: clientFormat, frameCapacity: chunkFrames),
              let channel = buffer.floatChannelData?[0]
        else { throw DecodeError.unreadable(kAudioFileUnsupportedDataFormatError) }

        var client = clientFormat.streamDescription.pointee
        try check(ExtAudioFileSetProperty(
            extFile,
            kExtAudioFileProperty_ClientDataFormat,
            UInt32(MemoryLayout<AudioStreamBasicDescription>.size),
            &client
        ))

        var samples: [Float] = []
        var fileFrames: Int64 = 0
        size = UInt32(MemoryLayout<Int64>.size)
        if ExtAudioFileGetProperty(extFile, kExtAudioFileProperty_FileLengthFrames, &size, &fileFrames) == noErr,
           fileFrames > 0
        {
            samples.reserveCapacity(Int(fileFrames))
        }

        while true {
            var frames = UInt32(chunkFrames)
            buffer.frameLength = chunkFrames
            try check(ExtAudioFileRead(extFile, &frames, buffer.mutableAudioBufferList))
            if frames == 0 { break }
            samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(frames)))
        }
        return PCM(samples: samples, sampleRate: fileFormat.mSampleRate)
    }

    private static func check(_ status: OSStatus) throws {
        guard status == noErr else { throw DecodeError.unreadable(status) }
    }

    /// The bytes Core Audio reads through `read`/`size`; its address is the callbacks' context.
    private final class Source {
        let data: Data

        init(_ data: Data) {
            self.data = data
        }

        /// Copies up to `count` bytes from `position`; a short read at the end is how the file ends.
        static let read: AudioFile_ReadProc = { context, position, count, destination, actualCount in
            let data = Unmanaged<Source>.fromOpaque(context).takeUnretainedValue().data
            guard position >= 0, position <= Int64(data.count) else {
                actualCount.pointee = 0
                return kAudioFilePositionError
            }
            // A `Data` slice need not start at index 0.
            let start = data.startIndex + Int(position)
            let length = min(Int(count), data.endIndex - start)
            data.copyBytes(to: destination.assumingMemoryBound(to: UInt8.self), from: start ..< start + length)
            actualCount.pointee = UInt32(length)
            return noErr
        }

        static let size: AudioFile_GetSizeProc = { context in
            Int64(Unmanaged<Source>.fromOpaque(context).takeUnretainedValue().data.count)
        }
    }
}

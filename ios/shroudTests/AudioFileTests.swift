import AVFoundation
import CryptoKit
import Foundation
import Testing
import UIKit
@testable import shroud

/// Audio files (`docs/file-sharing.md` §11): every row of the `== audio tags ==`,
/// `== audio titles ==`, `== audio durations ==` and `== audio sniff ==` blocks of
/// `node scripts/gen_file_vectors.mjs`, with the script's own inputs.
struct AudioFileTextTests {
    static let tags: [(String, String?)] = [
        ("Midnight City", "Midnight City"),
        ("  Holocene  ", "Holocene"),
        ("Bon  Iver", "Bon Iver"),
        ("line\nbreak\ttab", "line break tab"),
        ("rtl\u{202E}override", "rtloverride"),
        ("zero\u{200B}width\u{FEFF}", "zerowidth"),
        ("Beyonce\u{0301}", "Beyonc\u{00E9}"),
        ("   ", nil),
        ("", nil),
        ("\u{0000}\u{0007}", nil),
        (String(repeating: "T", count: 199) + " x", String(repeating: "T", count: 199)),
        (String(repeating: "y", count: 250), String(repeating: "y", count: 200)),
        ("\u{1F3B5} emoji title", "\u{1F3B5} emoji title"),
    ]

    static let titles: [(String?, String?, String, String)] = [
        ("Midnight City", "M83", "track01.mp3", "Midnight City \u{2013} M83"),
        ("Midnight City", nil, "track01.mp3", "Midnight City"),
        (nil, "M83", "track01.mp3", "track01.mp3"),
        ("  ", "  ", "Interview raw take.flac", "Interview raw take.flac"),
        (nil, nil, "../x/Demo v3 (final mix).wav", "Demo v3 (final mix).wav"),
    ]

    /// ms → (total, elapsed).
    static let durations: [(Int, String, String)] = [
        (0, "0:00", "0:00"),
        (499, "0:00", "0:00"),
        (500, "0:01", "0:00"),
        (999, "0:01", "0:00"),
        (59499, "0:59", "0:59"),
        (59500, "1:00", "0:59"),
        (243_400, "4:03", "4:03"),
        (3_599_499, "59:59", "59:59"),
        (3_599_500, "1:00:00", "59:59"),
        (3_600_000, "1:00:00", "1:00:00"),
        (45_296_000, "12:34:56", "12:34:56"),
    ]

    static let sniffs: [(String, String, Bool)] = [
        ("mp3", "494433040000", true), ("mp3", "fffb9064", true), ("mp3", "fff15080", true), ("mp3", "00000020", false),
        ("aac", "fff15080", true), ("aac", "fff95080", true), ("aac", "fffb9064", false), ("aac", "494433", true),
        ("m4a", "0000002066747970", true), ("m4a", "0000002066726565", false),
        ("wav", "52494646244200005741564566", true), ("wav", "524946462442000041564920", false),
        ("flac", "664c614300000022", true), ("flac", "4944330300", true), ("flac", "4f676753", false),
        ("ogg", "4f67675300020000", true), ("opus", "4f67675300020000", true), ("ogg", "664c6143", false),
        ("aiff", "464f524d0000a0c641494646", true), ("aif", "464f524d0000a0c641494643", true),
        ("aiff", "464f524d0000a0c64d415220", false),
        ("mp3", "", false),
    ]

    @Test
    func everyVectorIsPinned() {
        #expect(Self.tags.count == 13)
        #expect(Self.titles.count == 5)
        #expect(Self.durations.count == 11)
        #expect(Self.sniffs.count == 22)
    }

    @Test(arguments: AudioFileTextTests.tags)
    func tagsCleanLikeTheScript(input: String, expected: String?) {
        let cleaned = AudioFileText.cleanTag(input)
        #expect(cleaned.map { Array($0.unicodeScalars) } == expected.map { Array($0.unicodeScalars) })
    }

    @Test
    func anAbsentTagStaysAbsent() {
        #expect(AudioFileText.cleanTag(nil) == nil)
    }

    @Test(arguments: AudioFileTextTests.titles)
    func displayTitlesFollowTheScript(title: String?, artist: String?, name: String, expected: String) {
        #expect(AudioFileText.displayTitle(title: title, artist: artist, fileName: name) == expected)
    }

    @Test(arguments: AudioFileTextTests.durations)
    func durationsRoundTotalsAndFloorElapsed(ms: Int, total: String, elapsed: String) {
        #expect(AudioFileText.totalLabel(ms: ms) == total)
        #expect(AudioFileText.elapsedLabel(ms: ms) == elapsed)
        #expect(AudioFileText.elapsedLabel(seconds: Double(ms) / 1000) == elapsed)
    }

    @Test(arguments: AudioFileTextTests.sniffs)
    func contentChecksMatchTheScript(ext: String, hex: String, matches: Bool) throws {
        let type = try #require(SharedFile.type(forName: "f.\(ext)"))
        #expect(SharedFile.contentMatches(type, header: Self.bytes(hex)) == matches)
    }

    @Test
    func previewLineLeadsWithANote() {
        #expect(AudioFileText.previewLine(displayTitle: "Midnight City \u{2013} M83") == "\u{1F3B5} Midnight City \u{2013} M83")
    }

    static func bytes(_ hex: String) -> Data {
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            data.append(UInt8(hex[index ..< next], radix: 16)!)
            index = next
        }
        return data
    }
}

/// The §4 Audio row, the §11.2 payload keys, quotes and previews.
struct AudioFileMessageTests {
    @Test(arguments: [
        ("song.mp3", "audio/mpeg"), ("song.M4A", "audio/mp4"), ("song.aac", "audio/aac"),
        ("take.wav", "audio/wav"), ("take.flac", "audio/flac"), ("song.ogg", "audio/ogg"),
        ("note.opus", "audio/ogg"), ("take.aif", "audio/aiff"), ("take.aiff", "audio/aiff"),
    ])
    func audioTypesComeFromTheExtension(name: String, mime: String) throws {
        let type = try #require(SharedFile.type(forName: name))
        #expect(type.mime == mime)
        #expect(type.category == .audio)
        #expect(type.warning == nil)
        #expect(SharedFile.pickerTypes.contains { $0.preferredFilenameExtension == type.ext || $0.tags[.filenameExtension]?.contains(type.ext) == true })
    }

    @Test
    func thePayloadCarriesTitleArtistAndDuration() throws {
        let payload = MediaMessagePayload(
            t: MediaMessagePayload.kindFile,
            mime: "audio/mpeg",
            w: 160,
            h: 160,
            k: "a2V5",
            d: 243_400,
            th: "AAAA",
            s: 4_000_000,
            n: "track01.mp3",
            ti: "Midnight City",
            ar: "M83"
        )
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["ti"] as? String == "Midnight City")
        #expect(object["ar"] as? String == "M83")
        #expect(object["d"] as? Int == 243_400)
        let parsed = try #require(MediaMessagePayload.parse(try payload.encoded()))
        #expect(parsed == payload)
        #expect(parsed.audioDurationMs == 243_400)
        #expect(!parsed.isVoice)
    }

    @Test
    func aMissingOrBogusDurationIsAbsent() throws {
        let json = #"{"t":"file","n":"a.mp3","mime":"audio/mpeg","k":"a2V5","s":1,"d":0}"#
        let payload = try #require(MediaMessagePayload.parse(Data(json.utf8)))
        #expect(payload.audioDurationMs == nil)
        #expect(payload.ti == nil)
        #expect(payload.ar == nil)
        let object = try #require(try JSONSerialization.jsonObject(with: payload.encoded()) as? [String: Any])
        #expect(object["ti"] == nil)
        #expect(object["ar"] == nil)
    }

    @MainActor
    private func audioMessage(caption: String = "", title: String?, artist: String?) -> MessagingController.ChatMessage {
        MessagingController.ChatMessage(
            id: UUID(),
            peerUserID: UUID(),
            senderUserID: UUID(),
            text: caption,
            createdAt: Date(),
            isMine: false,
            deleted: false,
            kind: .file,
            mediaObjectId: UUID(),
            mediaByteCount: 4_000_000,
            voiceDurationMs: 243_400,
            fileName: "track01.mp3",
            fileTitle: title,
            fileArtist: artist
        )
    }

    @MainActor
    @Test
    func anAudioFileQuotesAsAudioWithItsDisplayTitle() throws {
        let message = audioMessage(title: "Midnight City", artist: "M83")
        #expect(message.isAudioFile)
        let quote = try #require(message.replyReference)
        #expect(quote.kind == .audio)
        #expect(quote.snippet == "Midnight City \u{2013} M83")
        #expect(quote.wireObject["k"] as? String == "audio")
        #expect(MessageReplyReference.Kind.audio.mediaLabel == "Audio")
        let parsed = try #require(MessageReplyReference.parse(wireObject: quote.wireObject))
        #expect(parsed.kind == .audio)
    }

    @MainActor
    @Test
    func previewsUseTheNoteAndTheDisplayTitle() {
        #expect(audioMessage(title: "Midnight City", artist: "M83").filePreviewText == "\u{1F3B5} Midnight City \u{2013} M83")
        #expect(audioMessage(title: nil, artist: "M83").filePreviewText == "\u{1F3B5} track01.mp3")
        #expect(audioMessage(caption: "listen", title: "Midnight City", artist: nil).filePreviewText == "listen")
    }

    @Test
    func aCoverBecomesASmallSquareJPEG() throws {
        let size = CGSize(width: 600, height: 400)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            for i in 0 ..< 40 {
                UIColor(hue: CGFloat(i) / 40, saturation: 0.8, brightness: 0.9, alpha: 1).setFill()
                context.fill(CGRect(x: CGFloat(i) * 15, y: 0, width: 15, height: 400))
            }
        }
        let data = try #require(image.pngData())
        let cover = try #require(AudioFileMetadata.coverThumbnail(from: data))
        #expect(cover.width == cover.height)
        #expect(cover.width <= AudioFileMetadata.coverSide)
        #expect(cover.width >= AudioFileMetadata.coverMinSide)
        #expect(cover.jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes)
        #expect(MediaCrypto.pixelSize(for: cover.jpeg)?.width == cover.width)
    }

    @Test
    func garbageIsNoCover() {
        #expect(AudioFileMetadata.coverThumbnail(from: Data("not an image".utf8)) == nil)
    }
}

/// The sealed audio path (§11.5): a WAV sealed with SHRF1 plays through
/// `SealedAudioResourceLoader` without its plaintext touching the disk. Serialized: the player is
/// one shared instance.
@MainActor
@Suite(.serialized)
struct SealedAudioPlaybackTests {
    private static let key = SymmetricKey(data: Data((0 ..< 32).map { UInt8($0) }))

    /// 16-bit mono PCM, a 440 Hz tone, as a RIFF/WAVE file.
    static func wav(seconds: Double, sampleRate: Int = 22050) -> Data {
        let count = Int(Double(sampleRate) * seconds)
        var pcm = Data(capacity: count * 2)
        for i in 0 ..< count {
            let value = Int16(sin(Double(i) * 2 * .pi * 440 / Double(sampleRate)) * 12000)
            withUnsafeBytes(of: value.littleEndian) { pcm.append(contentsOf: $0) }
        }
        func le32(_ v: UInt32) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }
        func le16(_ v: UInt16) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }
        var data = Data("RIFF".utf8)
        data.append(contentsOf: le32(UInt32(36 + pcm.count)))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        data.append(contentsOf: le32(16))
        data.append(contentsOf: le16(1))
        data.append(contentsOf: le16(1))
        data.append(contentsOf: le32(UInt32(sampleRate)))
        data.append(contentsOf: le32(UInt32(sampleRate * 2)))
        data.append(contentsOf: le16(2))
        data.append(contentsOf: le16(16))
        data.append(contentsOf: Array("data".utf8))
        data.append(contentsOf: le32(UInt32(pcm.count)))
        data.append(pcm)
        return data
    }

    private static func scratch() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sealed-audio-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Seals `plain` into `dir/blob.shrf` with the streaming sealer.
    private static func sealedBlob(_ plain: Data, in dir: URL) throws -> URL {
        let source = dir.appendingPathComponent("plain.wav")
        try plain.write(to: source)
        let blob = dir.appendingPathComponent("blob.shrf")
        try FileBlob.seal(from: source, to: blob, key: key)
        try FileManager.default.removeItem(at: source)
        return blob
    }

    @Test
    func verifyChecksTheWholeBlobAndReturnsItsHeader() throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = Self.wav(seconds: 2)
        let blob = try Self.sealedBlob(plain, in: dir)
        let prefix = try FileBlob.verify(at: blob, key: Self.key, plaintextSize: Int64(plain.count), prefixBytes: SharedFile.contentCheckBytes)
        #expect(prefix == plain.prefix(SharedFile.contentCheckBytes))
        let wav = try #require(SharedFile.type(forName: "take.wav"))
        #expect(SharedFile.contentMatches(wav, header: prefix))

        // One flipped byte in the last segment fails the check.
        var bytes = try Data(contentsOf: blob)
        bytes[bytes.count - 20] ^= 0x01
        try bytes.write(to: blob)
        #expect(throws: FileBlob.BlobError.tampered) {
            try FileBlob.verify(at: blob, key: Self.key, plaintextSize: Int64(plain.count), prefixBytes: 16)
        }
        #expect(throws: FileBlob.BlobError.wrongLength) {
            try FileBlob.verify(at: blob, key: Self.key, plaintextSize: Int64(plain.count) + 1, prefixBytes: 16)
        }
    }

    @Test
    func theSegmentReaderServesAnyRange() throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = Data((0 ..< 200_000).map { UInt8($0 % 251) })
        let blob = try Self.sealedBlob(plain, in: dir)
        let reader = try FileBlob.SegmentReader(url: blob, key: Self.key, plaintextSize: Int64(plain.count))
        for (offset, length) in [(0, 10), (65530, 100), (65536, 65536), (199_990, 100), (131_072, 1)] {
            var out = Data()
            var at = Int64(offset)
            while out.count < length {
                let chunk = try reader.bytes(at: at, maxLength: length - out.count)
                if chunk.isEmpty { break }
                out.append(chunk)
                at += Int64(chunk.count)
            }
            let end = min(plain.count, offset + length)
            #expect(out == plain.subdata(in: offset ..< end))
        }
        #expect(try reader.bytes(at: Int64(plain.count), maxLength: 10).isEmpty)
    }

    @Test
    func aSealedWavPlaysThroughTheResourceLoader() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = Self.wav(seconds: 3)
        #expect(plain.count > FileBlob.segmentSize)
        let blob = try Self.sealedBlob(plain, in: dir)

        let loader = try SealedAudioResourceLoader(blob: blob, key: Self.key, plaintextSize: Int64(plain.count), ext: "wav")
        let asset = AVURLAsset(url: SealedAudioResourceLoader.url(messageID: UUID(), ext: "wav"))
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        #expect(try await asset.load(.isPlayable))
        let duration = CMTimeGetSeconds(try await asset.load(.duration))
        #expect(abs(duration - 3) < 0.05)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        #expect(tracks.count == 1)

        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        #expect(await ChatVideoPlayer.waitUntilReady(item))
        _ = player
        // Nothing decrypted was written next to the blob.
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        #expect(leftovers == ["blob.shrf"])
        withExtendedLifetime(loader) {}
    }

    @Test
    func theFilePlayerPlaysAndStops() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = Self.wav(seconds: 2)
        let blob = try Self.sealedBlob(plain, in: dir)
        let id = UUID()
        let player = AudioFilePlayer.shared
        let result = await player.play(AudioFilePlayer.Source(
            messageID: id,
            peerUserID: UUID(),
            blob: blob,
            key: Self.key,
            plaintextSize: Int64(plain.count),
            ext: "wav",
            title: "take.wav",
            artist: nil,
            durationMs: 2000
        ))
        #expect(result == .started)
        #expect(player.isActive(id))
        #expect(player.isPlaying(id))
        #expect(abs(player.duration - 2) < 0.05)
        #expect(!player.offersSpeed)
        // The playhead moves: AVPlayer is really reading through the loader.
        for _ in 0 ..< 30 where player.currentTime < 0.2 {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(player.currentTime >= 0.2)
        player.seek(to: 0.5)
        #expect(abs(player.currentTime - 1) < 0.01)
        player.pause()
        #expect(!player.isPlaying)
        #expect(player.isActive(id))
        player.stop()
        #expect(player.track == nil)
    }

    @Test
    func theEndRewindsUnloadsAndHandsOver() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = Self.wav(seconds: 0.4)
        let blob = try Self.sealedBlob(plain, in: dir)
        let id = UUID()
        let player = AudioFilePlayer.shared
        var finished: AudioFilePlayer.Track?
        player.onFinished = { finished = $0 }
        defer { player.onFinished = nil }
        let result = await player.play(AudioFilePlayer.Source(
            messageID: id,
            peerUserID: UUID(),
            blob: blob,
            key: Self.key,
            plaintextSize: Int64(plain.count),
            ext: "wav",
            title: "short.wav",
            artist: "Someone",
            durationMs: 400
        ))
        #expect(result == .started)
        for _ in 0 ..< 60 where finished == nil {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(finished?.messageID == id)
        #expect(finished?.artist == "Someone")
        #expect(player.track == nil)
        #expect(!player.isPlaying)
    }

    @Test
    func aFileTheCodecsCantReadIsUnplayable() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        // An Ogg page header and noise: AVFoundation has no Ogg Vorbis reader.
        var plain = Data("OggS".utf8) + Data([0, 2]) + Data(repeating: 0, count: 20)
        plain.append(Data((0 ..< 70_000).map { UInt8(truncatingIfNeeded: $0 &* 31) }))
        let blob = try Self.sealedBlob(plain, in: dir)
        let id = UUID()
        let player = AudioFilePlayer.shared
        let result = await player.play(AudioFilePlayer.Source(
            messageID: id,
            peerUserID: UUID(),
            blob: blob,
            key: Self.key,
            plaintextSize: Int64(plain.count),
            ext: "ogg",
            title: "song.ogg",
            artist: nil,
            durationMs: nil
        ))
        #expect(result == .unplayable)
        #expect(player.isUnplayable(id))
        #expect(player.track == nil)
        player.forget([id])
        #expect(!player.isUnplayable(id))
    }

    @Test
    func rateCyclesThroughTheChip() {
        let player = AudioFilePlayer.shared
        let start = player.rate
        var seen: [Float] = []
        for _ in 0 ..< 3 {
            player.cycleRate()
            seen.append(player.rate)
        }
        #expect(Set(seen) == Set(AudioFilePlayer.rates))
        #expect(player.rate == start)
        #expect(AudioFilePlayer.rateLabel(1) == "1\u{00D7}")
        #expect(AudioFilePlayer.rateLabel(1.5) == "1.5\u{00D7}")
        #expect(AudioFilePlayer.rateLabel(2) == "2\u{00D7}")
    }

    @Test
    func theSenderReadsTagsFromAnM4A() async throws {
        let dir = try Self.scratch()
        defer { try? FileManager.default.removeItem(at: dir) }
        // AAC in M4A, then tagged by a passthrough export.
        let raw = dir.appendingPathComponent("raw.m4a")
        do {
            let format = try #require(AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1))
            let file = try AVAudioFile(forWriting: raw, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44100,
                AVNumberOfChannelsKey: 1,
            ])
            let frames = AVAudioFrameCount(44100 * 3)
            let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
            buffer.frameLength = frames
            let samples = try #require(buffer.floatChannelData?[0])
            for i in 0 ..< Int(frames) { samples[i] = Float(sin(Double(i) * 2 * .pi * 440 / 44100) * 0.3) }
            try file.write(from: buffer)
        }
        let cover = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 300)).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 300, height: 300))
        }
        func item(_ identifier: AVMetadataIdentifier, _ value: NSCopying & NSObjectProtocol) -> AVMetadataItem {
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value
            item.extendedLanguageTag = "und"
            return item
        }
        let tagged = dir.appendingPathComponent("tagged.m4a")
        let session = try #require(AVAssetExportSession(asset: AVURLAsset(url: raw), presetName: AVAssetExportPresetPassthrough))
        session.metadata = [
            item(.commonIdentifierTitle, "Midnight  City" as NSString),
            item(.commonIdentifierArtist, "M83" as NSString),
            item(.commonIdentifierArtwork, try #require(cover.jpegData(compressionQuality: 0.9)) as NSData),
        ]
        try await session.export(to: tagged, as: .m4a)

        let metadata = try #require(await AudioFileMetadata.read(url: tagged))
        #expect(metadata.title == "Midnight City")
        #expect(metadata.artist == "M83")
        let ms = try #require(metadata.durationMs)
        #expect(abs(ms - 3000) < 100)
        let thumbnail = try #require(metadata.cover)
        #expect(thumbnail.width == thumbnail.height)
        #expect(thumbnail.jpeg.count <= MediaCrypto.maxEnvelopePreviewBytes)
    }
}

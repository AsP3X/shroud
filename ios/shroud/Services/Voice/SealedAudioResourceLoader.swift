import AVFoundation
import CryptoKit
import Foundation
import UniformTypeIdentifiers

/// Serves an audio file's plaintext to `AVPlayer` straight out of its stored SHRF1 blob
/// (`docs/file-sharing.md` §11.5).
///
/// Human: A shared audio file can be a 2 GB recording, and no decrypted audio may touch the
/// disk. `AVURLAsset` gets a made-up `shroud-sealed-audio:` URL instead of a file, and asks this
/// delegate for byte ranges; each range is answered by opening just the 64 KiB segments it
/// touches, every one a full AES-GCM open with its index and last flag. So memory stays at a
/// segment or two, and a byte AVFoundation sees has always passed its tag. The whole blob was
/// checked once before the first play (`FileBlob.verify`), so §3's "nothing before the last tag"
/// holds for the file as a whole too.
/// Agent: Every delegate call and every read happens on `queue`; the segment reader is not
/// thread-safe. The delegate methods are the synchronous Objective-C forms on purpose: async
/// `@objc` delegate thunks crash swift-frontend in this target (`While silgen …`). A long range
/// is served one segment per hop on `queue`, so a cancel or a seek's new request gets in between.
nonisolated final class SealedAudioResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let scheme = "shroud-sealed-audio"

    /// The delegate queue; `AVAssetResourceLoader.setDelegate(_:queue:)` gets this one.
    let queue = DispatchQueue(label: "de.corespace.shroud.sealed-audio", qos: .userInitiated)
    private let reader: FileBlob.SegmentReader
    private let contentType: String

    /// `ext` picks the type AVFoundation parses the bytes as (§4 decides it by the extension).
    init(blob: URL, key: SymmetricKey, plaintextSize: Int64, ext: String) throws {
        reader = try FileBlob.SegmentReader(url: blob, key: key, plaintextSize: plaintextSize)
        contentType = UTType(filenameExtension: ext.lowercased())?.identifier ?? UTType.audio.identifier
    }

    /// The URL an asset for `messageID` is opened with; the extension helps AVFoundation's
    /// format guess, the type it really gets comes from `contentInformationRequest`.
    static func url(messageID: UUID, ext: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "audio"
        components.path = "/\(messageID.uuidString.lowercased()).\(ext.lowercased())"
        return components.url ?? URL(fileURLWithPath: "/dev/null")
    }

    // MARK: - AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard loadingRequest.request.url?.scheme == Self.scheme else { return false }
        if let info = loadingRequest.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = reader.plaintextSize
            info.isByteRangeAccessSupported = true
        }
        guard let dataRequest = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return true
        }
        // AVFoundation asks for everything up to the end and cancels once its buffer is full
        // (measured: a 3-hour WAV held the footprint at about +25 MB), so a range is served whole.
        let end: Int64 = dataRequest.requestsAllDataToEndOfResource
            ? reader.plaintextSize
            : min(reader.plaintextSize, dataRequest.requestedOffset + Int64(dataRequest.requestedLength))
        queue.async { [self] in serve(loadingRequest, until: end) }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        // `serve` checks `isCancelled` before every segment.
    }

    // MARK: - Serving

    /// Hands out the next segment's worth of the request's range, then queues the rest.
    private func serve(_ loadingRequest: AVAssetResourceLoadingRequest, until end: Int64) {
        guard !loadingRequest.isCancelled, !loadingRequest.isFinished else { return }
        guard let dataRequest = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return
        }
        let offset = dataRequest.currentOffset
        guard offset < end else {
            loadingRequest.finishLoading()
            return
        }
        do {
            let chunk = try reader.bytes(at: offset, maxLength: Int(min(Int64(FileBlob.segmentSize), end - offset)))
            guard !chunk.isEmpty else {
                loadingRequest.finishLoading()
                return
            }
            dataRequest.respond(with: chunk)
        } catch {
            // A tag that fails now means the blob changed under the player: nothing more of it.
            loadingRequest.finishLoading(with: error)
            return
        }
        queue.async { [self] in serve(loadingRequest, until: end) }
    }
}

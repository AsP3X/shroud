import CoreMedia
import Foundation
import ReplayKit

/// The screen broadcast Shroud's Share starts: the phone's whole screen, for the call that is
/// running (docs/calls.md, "Screen sharing").
///
/// Human: The system hands this extension every frame of the screen. Each goes to the Shroud app
/// over the app group's socket (`ScreenShareUploader`); the app puts it on the call. Nothing here
/// touches the network and nothing is kept. Without a call to share into, the broadcast ends at
/// once and says why; Stop in Shroud, or the call ending, ends it too. The system then shows its
/// own alert with the reason given here.
/// Agent: the extension may use about 50 MB. Frames are dropped, never queued, while the previous
/// one is still on its way; microphone and app audio are ignored (the call has its own microphone,
/// and the app's sound is not sent yet).
final class SampleHandler: RPBroadcastSampleHandler {
    private let uploader = ScreenShareUploader()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        do {
            try uploader.connect { [weak self] in
                self?.finish("Screen sharing in Shroud has ended.")
            }
        } catch {
            finish("Share your screen from a call in Shroud.")
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let orientation = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)?
            .uint32Value ?? CGImagePropertyOrientation.up.rawValue
        uploader.send(pixels, orientation: orientation)
    }

    override func broadcastFinished() {
        uploader.close()
    }

    /// Ends the broadcast. The system shows `reason` in its alert.
    private func finish(_ reason: String) {
        uploader.close()
        finishBroadcastWithError(NSError(domain: "de.corespace.shroud.screen-share", code: 1, userInfo: [
            NSLocalizedDescriptionKey: reason,
        ]))
    }
}

import Foundation
import Testing
@testable import shroud

/// The camera's ladder: where a call starts, when it steps down and up, and what the encoder is
/// told. The web's `videoQuality.selftest.ts` and Android's `CallVideoQualityTest` check the same
/// cases.
struct CallVideoQualityTests {
    private static let good = CameraSample(estimate: 6_000_000, limitation: CameraLimitation.none, loss: 0)
    private static let none = CameraSample(estimate: nil, limitation: nil, loss: nil)
    private static let tight = CameraSample(estimate: 500_000, limitation: .bandwidth, loss: 0)

    /// Feeds `sample` `times` times; the rung names it passed through.
    @discardableResult
    private func feed(_ quality: inout CameraQuality, _ sample: CameraSample, _ times: Int) -> [String] {
        var seen: [String] = []
        for _ in 0..<times where quality.sample(sample) {
            seen.append(quality.rung.name)
        }
        return seen
    }

    private func with(_ sample: CameraSample, estimate: Double?? = nil, limitation: CameraLimitation?? = nil, loss: Double?? = nil) -> CameraSample {
        var copy = sample
        if let estimate { copy.estimate = estimate }
        if let limitation { copy.limitation = limitation }
        if let loss { copy.loss = loss }
        return copy
    }

    // MARK: - Where a call starts

    @Test
    func whereACallStarts() {
        #expect(CameraQuality(captureLong: 1920).rung.name == "720p")
        #expect(CameraQuality(captureLong: 1280).rung.name == "720p")
        #expect(CameraQuality(captureLong: 640).rung.name == "360p")
        #expect(CameraQuality(captureLong: nil).rung.name == "720p")
        #expect(CallVideoQuality.ceiling(for: 1920) == 5)
        #expect(CallVideoQuality.ceiling(for: 1280) == 4)
        #expect(CallVideoQuality.ceiling(for: 1300) == 4)
        // A capture a little under 1080p still reaches it.
        #expect(CallVideoQuality.ceiling(for: 1880) == 5)
    }

    // MARK: - Stepping up

    @Test
    func cleanReadingsStepUpAfterTheStartSettles() {
        var quality = CameraQuality(captureLong: 1920)
        #expect(feed(&quality, Self.good, 3).isEmpty, "the first three readings settle")
        #expect(feed(&quality, Self.good, 3).isEmpty, "three clean readings are not yet enough")
        #expect(feed(&quality, Self.good, 1).first == "1080p", "the fourth goes up to 1080p")
        #expect(feed(&quality, Self.good, 20).isEmpty, "1080p is the top")
    }

    @Test
    func a720pCameraNeverGoesAbove720p() {
        var quality = CameraQuality(captureLong: 1280)
        #expect(feed(&quality, Self.good, 30).isEmpty)
    }

    @Test
    func goingUpDoesNotWaitForTheEstimate() {
        // A camera sending less than the link could carry: the estimate stays near what it sends.
        var quality = CameraQuality(captureLong: 1920)
        let low = CameraSample(estimate: 400_000, limitation: CameraLimitation.none, loss: 0)
        #expect(feed(&quality, low, 7).first == "1080p")
    }

    @Test
    func withoutAnEstimateOrALimitationCleanReadingsStillGoUp() {
        var quality = CameraQuality(captureLong: 1920)
        #expect(feed(&quality, Self.none, 7).first == "1080p")
    }

    @Test
    func aBusyEncoderOrALossyLinkDoesNotGoUp() {
        var quality = CameraQuality(captureLong: 1920)
        #expect(feed(&quality, with(Self.good, limitation: CameraLimitation.cpu), 30).isEmpty, "a busy processor does not go up")
        #expect(feed(&quality, with(Self.good, limitation: CameraLimitation.bandwidth), 30).isEmpty, "an encoder short of bits does not go up")
        #expect(quality.rung.name == "720p", "nor down while the estimate has room")
        #expect(feed(&quality, with(Self.good, loss: 0.05), 30).isEmpty, "a lossy link does not go up")
        _ = quality.sample(Self.good)
        _ = quality.sample(Self.good)
        _ = quality.sample(with(Self.good, loss: 0.05))
        #expect(feed(&quality, Self.good, 3).isEmpty, "a bad reading starts the count again")
        #expect(feed(&quality, Self.good, 1).first == "1080p", "four clean ones in a row go up")
    }

    // MARK: - Stepping down

    @Test
    func twoStarvedReadingsGoDownAsFarAsTheEstimateNeeds() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 3)
        #expect(feed(&quality, Self.tight, 1).isEmpty, "one starved reading is not enough")
        #expect(feed(&quality, Self.tight, 1).first == "360p", "two go down as far as the estimate needs at once")
        #expect(feed(&quality, Self.tight, 10).isEmpty, "and stay where the estimate fits")
    }

    @Test
    func aLowEstimateAloneDoesNotStepDown() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 3)
        #expect(feed(&quality, with(Self.tight, limitation: CameraLimitation.none), 2).isEmpty)
        #expect(quality.rung.name == "720p", "the encoder is not short of bits")
    }

    @Test
    func starvedReadingsMustComeInARow() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 3)
        _ = quality.sample(Self.tight)
        _ = quality.sample(Self.good)
        #expect(feed(&quality, Self.tight, 1).isEmpty)
    }

    @Test
    func lossStepsDownOneRungAtATime() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 3)
        let lossy = with(Self.good, loss: 0.15)
        #expect(feed(&quality, lossy, 2).first == "540p", "loss steps down one rung")
        #expect(feed(&quality, lossy, 2).isEmpty, "and settles before the next")
        #expect(feed(&quality, lossy, 2).first == "360p", "then steps again")
    }

    @Test
    func aStarvedLinkGoesToTheBottom() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 3)
        let starved = CameraSample(estimate: 40_000, limitation: .bandwidth, loss: 0.5)
        #expect(feed(&quality, starved, 2).first == "180p", "a starved link goes to the bottom")
        #expect(feed(&quality, starved, 20).isEmpty, "the bottom is the bottom")
        #expect(quality.rungIndex == 0, "and stays there")
    }

    // MARK: - An upgrade that does not hold waits longer next time

    @Test
    func anUpgradeThatDoesNotHoldWaitsLongerNextTime() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 7)
        #expect(quality.rung.name == "1080p", "up to 1080p")
        let short = CameraSample(estimate: 1_500_000, limitation: .bandwidth, loss: 0)
        feed(&quality, short, 2 + 2)
        #expect(quality.rung.name == "720p", "the link could not carry it")
        #expect(feed(&quality, Self.good, 2 + 7).isEmpty, "the next try waits twice as long")
        #expect(feed(&quality, Self.good, 1).first == "1080p", "eight clean readings")
        feed(&quality, Self.good, 16)
        feed(&quality, short, 2)
        #expect(quality.rung.name == "720p", "down again after it held")
        let waited = feed(&quality, Self.good, 2 + 3)
        #expect(waited.isEmpty && feed(&quality, Self.good, 1).first == "1080p", "a held upgrade resets the wait")
    }

    // MARK: - The camera's size

    @Test
    func theCamerasSizeIsTheCeiling() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 7)
        let smaller = quality.setCapture(1280)
        #expect(smaller && quality.rung.name == "720p", "a smaller camera brings the rung down to it")
        let larger = quality.setCapture(1920)
        #expect(!larger && quality.rung.name == "720p", "a larger one only lifts the ceiling")
        let unknown = quality.setCapture(0)
        #expect(!unknown, "an unknown size changes nothing")
    }

    @Test
    func afterAPauseTheCountStartsAgain() {
        var quality = CameraQuality(captureLong: 1920)
        feed(&quality, Self.good, 6)
        quality.pause()
        #expect(feed(&quality, Self.good, 5).isEmpty, "after a pause the count starts again")
        #expect(feed(&quality, Self.good, 1).first == "1080p", "and finishes")
    }

    @Test
    func aPauseBeforeTheFirstReadingKeepsTheOpeningSettle() {
        // A voice call whose camera comes on later: the ladder was paused from the start.
        var quality = CameraQuality(captureLong: 1920)
        quality.pause()
        quality.pause()
        #expect(feed(&quality, Self.good, 6).isEmpty, "a pause before the first reading keeps the opening settle")
        #expect(feed(&quality, Self.good, 1).first == "1080p", "seven readings in")
    }

    // MARK: - What the encoder is told

    @Test
    func whatTheEncoderIsTold() {
        let ladder = CallVideoQuality.ladder
        let at720 = CallVideoQuality.cameraEncoding(ladder[4], width: 1920, height: 1080)
        #expect(at720 == CameraEncoding(maxBitrate: 2_200_000, maxFramerate: 30, scaleResolutionDownBy: Double(1.5)))
        // A portrait camera too.
        #expect(CallVideoQuality.cameraEncoding(ladder[4], width: 1080, height: 1920).scaleResolutionDownBy == Double(1.5))
        // Never enlarged.
        #expect(CallVideoQuality.cameraEncoding(ladder[5], width: 1280, height: 720).scaleResolutionDownBy == Double(1))
        // An unknown size is sent as it is.
        #expect(CallVideoQuality.cameraEncoding(ladder[0], width: nil, height: nil).scaleResolutionDownBy == Double(1))
        let tile = CallVideoQuality.cameraEncoding(CallVideoQuality.tileOf(ladder[4]), width: 1920, height: 1080)
        #expect(tile == CameraEncoding(maxBitrate: 350_000, maxFramerate: 15, scaleResolutionDownBy: Double(3)), "the tile while sharing")
        #expect(CallVideoQuality.tileOf(ladder[5]) == CallVideoQuality.tile, "the tile from the top rung is the tile")
        let low = CallVideoQuality.cameraEncoding(CallVideoQuality.tileOf(ladder[1]), width: 1920, height: 1080)
        #expect(low == CameraEncoding(maxBitrate: 300_000, maxFramerate: 15, scaleResolutionDownBy: Double(4)), "a camera below the tile stays below it")
    }

    // MARK: - Reading the stats

    private static let report: [String: [String: Any]] = [
        "OT1": ["type": "outbound-rtp", "kind": "video", "qualityLimitationReason": "bandwidth"],
        "RI1": ["type": "remote-inbound-rtp", "kind": "video", "fractionLost": 0.0625],
        "T1": ["type": "transport", "selectedCandidatePairId": "CP2"],
        "CP1": ["type": "candidate-pair", "nominated": false, "availableOutgoingBitrate": 99],
        "CP2": ["type": "candidate-pair", "nominated": true, "state": "succeeded", "availableOutgoingBitrate": 1_234_567],
    ]

    @Test
    func readsTheSendersStats() throws {
        let sample = try #require(CallVideoQuality.readCameraSample(Self.report))
        #expect(sample.estimate == Double(1_234_567), "the selected pair's estimate")
        #expect(sample.limitation == .bandwidth, "the encoder's limitation")
        #expect(sample.loss == Double(0.0625), "the reported loss")
    }

    @Test
    func statsWithoutThoseValuesReadAsNil() throws {
        let bare = try #require(CallVideoQuality.readCameraSample(["OT1": ["type": "outbound-rtp", "kind": "video"]]))
        #expect(bare == CameraSample(estimate: nil, limitation: nil, loss: nil))
    }

    @Test
    func withoutATransportTheNominatedPair() {
        let nominated = CallVideoQuality.readCameraSample(Self.report.filter { $0.value["type"] as? String != "transport" })
        #expect(nominated?.estimate == Double(1_234_567))
    }

    @Test
    func nothingSentYetIsNoReading() {
        let unsent = CallVideoQuality.readCameraSample(Self.report.filter { $0.value["type"] as? String != "outbound-rtp" })
        #expect(unsent == nil)
    }

    /// As WebRTC hands them over: numbers and flags as `NSNumber`.
    @Test
    func readsWebRTCsNumbers() {
        let report: [String: [String: Any]] = [
            "OT1": ["type": "outbound-rtp", "kind": "video", "qualityLimitationReason": "none"],
            "RI1": ["type": "remote-inbound-rtp", "kind": "video", "fractionLost": NSNumber(value: 0)],
            "CP1": ["type": "candidate-pair", "nominated": NSNumber(value: true), "state": "succeeded",
                    "availableOutgoingBitrate": NSNumber(value: 2_000_000.0)],
        ]
        let sample = CallVideoQuality.readCameraSample(report)
        #expect(sample == CameraSample(estimate: 2_000_000, limitation: CameraLimitation.none, loss: 0))
    }
}

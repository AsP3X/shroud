import Foundation

/// How sharp our camera goes out, step by step as the link allows (docs/calls.md, "Camera
/// quality").
///
/// Human: The camera is opened at up to 1080p; the encoder sends one rung of the ladder below,
/// picked every two seconds from the sender's stats. An encoder short of bits on a link whose
/// bandwidth estimate is below the rung steps down after two readings in a row, as far as the
/// estimate needs at once; so does loss, one rung. Clean readings step up one rung at a time after
/// a stretch of them, a longer one each time an upgrade did not hold. WebRTC keeps adapting within
/// a rung on its own.
///
/// Going up does not wait for the estimate to show room: while the camera sends less than the
/// link could carry, the estimate only grows as far as what is sent, so it would never show it. A
/// higher cap makes WebRTC probe the link instead, and a try the link cannot carry steps back down.
/// Agent: the web (`videoQuality.ts`) and Android (`CallVideoQuality.kt`) run the same ladder with
/// the same numbers; each side decides only what it sends, so nothing goes on the wire.
nonisolated enum CallVideoQuality {
    static let ladder: [CameraRung] = [
        CameraRung(name: "180p", long: 320, fps: 15, maxBitrate: 150_000, minBitrate: 0),
        CameraRung(name: "270p", long: 480, fps: 20, maxBitrate: 300_000, minBitrate: 150_000),
        CameraRung(name: "360p", long: 640, fps: 30, maxBitrate: 600_000, minBitrate: 300_000),
        CameraRung(name: "540p", long: 960, fps: 30, maxBitrate: 1_200_000, minBitrate: 600_000),
        CameraRung(name: "720p", long: 1280, fps: 30, maxBitrate: 2_200_000, minBitrate: 1_100_000),
        CameraRung(name: "1080p", long: 1920, fps: 30, maxBitrate: 3_800_000, minBitrate: 2_000_000),
    ]

    /// Where a call starts: 720p, until the link has shown what it can carry.
    static let startRung = 4

    /// Our camera while our screen is shared: they show it as a small tile, so a thumbnail's worth.
    static let tile = CameraRung(name: "tile", long: 640, fps: 15, maxBitrate: 350_000, minBitrate: 0)

    /// The camera as a tile while our screen is shared: a thumbnail's worth, and never more than
    /// its rung, so a camera the link had pushed below the tile stays there while the screen
    /// competes.
    static func tileOf(_ rung: CameraRung) -> CameraRung {
        CameraRung(
            name: tile.name,
            long: min(tile.long, rung.long),
            fps: min(tile.fps, rung.fps),
            maxBitrate: min(tile.maxBitrate, rung.maxBitrate),
            minBitrate: 0
        )
    }

    /// How often the stats are read.
    static let sampleInterval: Duration = .seconds(2)
    /// Kept off the camera's share of the link: speech, and RTCP.
    static let audioReserveBps = 50_000.0
    /// Readings in a row that step down.
    static let downAfter = 2
    /// Readings in a row that step up, at first and after an upgrade held.
    static let upAfter = 4
    /// The most an upgrade that keeps failing waits (about a minute).
    static let upAfterMax = 32
    /// An upgrade held this many readings: the next one waits `upAfter` again.
    static let upProven = 15
    /// Readings ignored at the start, while the estimate ramps up from where it began.
    static let startSettle = 3
    /// Readings ignored after a change or a pause, while the link and the encoder settle.
    static let settle = 2
    /// Loss that steps down, and the most that lets a step up.
    static let lossDown = 0.10
    static let lossUp = 0.03

    /// A rung's pixels: its longer side by its shorter, 16:9 (1920×1080 for 1080p).
    static func rungPixels(_ rung: CameraRung) -> Int {
        rung.long * Int((Double(rung.long) * 9 / 16).rounded())
    }

    /// The top rung for a picture of `width` × `height`: the highest whose pixels it has, give or
    /// take a quarter; unknown (or empty): `startRung`.
    ///
    /// By pixels rather than by the longer side, since a framed picture takes the other side's
    /// shape ("Framing and Center Stage"): a tall 886×1920 cut has 1080p's pixels near enough, and
    /// a squat 1080×810 one 720p's, though its longer side is only 1080.
    static func ceiling(width: Int?, height: Int?) -> Int {
        let pixels = (width ?? 0) * (height ?? 0)
        guard pixels > 0 else { return startRung }
        var top = 0
        for (index, rung) in ladder.enumerated() where Double(rungPixels(rung)) <= Double(pixels) * 1.25 {
            top = index
        }
        return top
    }

    /// The highest rung whose minimum fits `budget`.
    static func fitting(_ budget: Double) -> Int {
        var top = 0
        for (index, rung) in ladder.enumerated() where Double(rung.minBitrate) <= budget {
            top = index
        }
        return top
    }

    /// What the encoder is told for a rung: its bitrate, its frame rate, and how far to shrink a
    /// picture of `width` × `height` to the rung's pixels (any shape keeps its shape and gets the
    /// rung's pixel count; unknown: sent as it is).
    static func cameraEncoding(_ rung: CameraRung, width: Int?, height: Int?) -> CameraEncoding {
        let pixels = (width ?? 0) * (height ?? 0)
        return CameraEncoding(
            maxBitrate: rung.maxBitrate,
            maxFramerate: rung.fps,
            scaleResolutionDownBy: pixels > 0 ? max(1, (Double(pixels) / Double(rungPixels(rung))).squareRoot()) : 1
        )
    }

    /// The camera's reading from its sender's stats (`statistics(for: sender)`): its
    /// `outbound-rtp`, the `remote-inbound-rtp` the other side reports for it, and the link's
    /// selected candidate pair. Nil while nothing goes out yet (no `outbound-rtp`).
    ///
    /// Agent: `stats` maps each stat's id to its values plus its `type`, as
    /// `RTCStatisticsReport.statistics` has them. Walked in id order, so "the first" of a kind is
    /// the same on every read.
    static func readCameraSample(_ stats: [String: [String: Any]]) -> CameraSample? {
        let all = stats.sorted { $0.key < $1.key }.map(\.value)
        guard let outbound = all.first(where: { $0["type"] as? String == "outbound-rtp" && $0["kind"] as? String != "audio" })
        else { return nil }
        let remote = all.first { $0["type"] as? String == "remote-inbound-rtp" && $0["kind"] as? String != "audio" }
        let transport = all.first { $0["type"] as? String == "transport" }
        let selected = (transport?["selectedCandidatePairId"] as? String).flatMap { stats[$0] }
        let pair = selected
            ?? all.first { $0["type"] as? String == "candidate-pair" && flag($0["nominated"]) && $0["state"] as? String == "succeeded" }
            ?? all.first { $0["type"] as? String == "candidate-pair" && flag($0["selected"]) }
        return CameraSample(
            estimate: number(pair?["availableOutgoingBitrate"]),
            limitation: (outbound["qualityLimitationReason"] as? String).flatMap(CameraLimitation.init(rawValue:)),
            loss: number(remote?["fractionLost"])
        )
    }

    /// A finite number (WebRTC hands numbers over as `NSNumber`); a string is not one.
    private static func number(_ value: Any?) -> Double? {
        guard let double = (value as? NSNumber)?.doubleValue, double.isFinite else { return nil }
        return double
    }

    private static func flag(_ value: Any?) -> Bool {
        (value as? Bool) == true
    }
}

nonisolated struct CameraRung: Equatable, Sendable {
    let name: String
    /// The picture's longer side, in pixels (1920 for 1080p, portrait or landscape).
    let long: Int
    let fps: Int
    /// The most the encoder may use, bits per second.
    let maxBitrate: Int
    /// The least the link must have room for to keep this rung.
    let minBitrate: Int
}

/// What the camera's encoding gets for a rung.
nonisolated struct CameraEncoding: Equatable, Sendable {
    let maxBitrate: Int
    let maxFramerate: Int
    let scaleResolutionDownBy: Double
}

/// What the encoder says holds it back (`qualityLimitationReason`).
nonisolated enum CameraLimitation: String, Sendable {
    case none
    case cpu
    case bandwidth
    case other
}

/// One reading of the camera's sender. Nil where the stats have no such value.
nonisolated struct CameraSample: Equatable, Sendable {
    /// The bandwidth estimate for the whole link (`availableOutgoingBitrate`), bits per second.
    var estimate: Double?
    var limitation: CameraLimitation?
    /// The share of our packets the other side lost lately (`fractionLost`), 0…1.
    var loss: Double?
}

/// The camera's rung for one call; `sample` moves it. The link's rung (`index`) is kept apart
/// from the picture's top rung (`ceiling`): a picture that turns smaller for a while (their view
/// turned sideways) holds the rung down only while it lasts, and the link's rung comes back with it.
nonisolated struct CameraQuality: Sendable {
    /// The link's rung.
    private var index: Int
    /// The picture's top rung.
    private var ceiling: Int
    private var low = 0
    private var high = 0
    private var settle = CallVideoQuality.startSettle
    private var upAfter = CallVideoQuality.upAfter
    /// Readings since the last step up, until it has held.
    private var sinceUp: Int?

    /// A call's ladder, for a picture of `width` × `height` (unknown: 720p at the top).
    init(width: Int? = nil, height: Int? = nil) {
        ceiling = CallVideoQuality.ceiling(width: width, height: height)
        index = CallVideoQuality.startRung
    }

    /// The rung that goes out: the link's, no higher than the picture's top rung.
    var rung: CameraRung {
        CallVideoQuality.ladder[rungIndex]
    }

    var rungIndex: Int {
        min(index, ceiling)
    }

    /// The picture going out has a new size (another camera, their view): its top rung. True when
    /// the rung that goes out changed. An unknown or empty size changes nothing.
    @discardableResult
    mutating func setCapture(width: Int?, height: Int?) -> Bool {
        guard (width ?? 0) * (height ?? 0) > 0 else { return false }
        let before = rungIndex
        ceiling = CallVideoQuality.ceiling(width: width, height: height)
        return rungIndex != before
    }

    /// The camera went off, out as a tile, or the system paused it: the next readings start
    /// counting afresh. A call that has not been read yet keeps its longer opening settle.
    mutating func pause() {
        low = 0
        high = 0
        settle = max(settle, CallVideoQuality.settle)
    }

    /// One reading; true when the rung changed.
    mutating func sample(_ sample: CameraSample) -> Bool {
        if let since = sinceUp {
            if since + 1 > CallVideoQuality.upProven {
                sinceUp = nil
                upAfter = CallVideoQuality.upAfter
            } else {
                sinceUp = since + 1
            }
        }
        if settle > 0 {
            settle -= 1
            return false
        }
        let current = rungIndex
        let budget = sample.estimate.map { $0 - CallVideoQuality.audioReserveBps }
        let starved = sample.limitation == .bandwidth && budget.map { $0 < Double(rung.minBitrate) } == true
        let lossy = sample.loss.map { $0 >= CallVideoQuality.lossDown } ?? false

        var down: Int?
        // As far down as the estimate needs, at once; loss steps down one.
        if starved, let budget { down = CallVideoQuality.fitting(budget) }
        if lossy { down = max(0, min(down ?? current, current - 1)) }
        if let down, down < current {
            high = 0
            low += 1
            if low < CallVideoQuality.downAfter { return false }
            // An upgrade that did not hold: the next one waits twice as long.
            if sinceUp != nil { upAfter = min(upAfter * 2, CallVideoQuality.upAfterMax) }
            sinceUp = nil
            move(to: down)
            return true
        }
        low = 0

        // Not the estimate: it grows only as far as what is sent (see the type's comment).
        let clean = current < ceiling
            && sample.limitation != .cpu
            && sample.limitation != .bandwidth
            && (sample.loss.map { $0 < CallVideoQuality.lossUp } ?? true)
        guard clean else {
            high = 0
            return false
        }
        high += 1
        if high < upAfter { return false }
        move(to: current + 1)
        sinceUp = 0
        return true
    }

    private mutating func move(to index: Int) {
        self.index = index
        low = 0
        high = 0
        settle = CallVideoQuality.settle
    }
}

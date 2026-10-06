import Foundation

/// Opus settings for a voice call. An SDP with no Opus line is unchanged.
///
/// Human: Error correction keeps words intelligible on a lossy network, and silence
/// is not sent, so a congested link has room for the video.
/// Agent: `useinbandfec=1`, `usedtx=1`, mono, `maxaveragebitrate=32000`. Line endings kept.
/// A parameter is replaced only when it is its own key, so `stereo` does not rewrite `sprop-stereo`.
nonisolated enum CallSdp {
    private static let extras = [
        "useinbandfec=1",
        "usedtx=1",
        "stereo=0",
        "sprop-stereo=0",
        "maxaveragebitrate=32000",
    ]

    /// ICE addresses leave the description. They travel later, under the per-call key.
    static func withoutCandidates(_ sdp: String) -> String {
        let eol = sdp.contains("\r\n") ? "\r\n" : "\n"
        return sdp
            .components(separatedBy: eol)
            .filter { !$0.lowercased().hasPrefix("a=candidate:") }
            .joined(separator: eol)
    }

    /// The SHA-256 DTLS fingerprint, lowercase, or nil when the description has none.
    static func fingerprint(_ sdp: String) -> String? {
        let prefix = "a=fingerprint:sha-256"
        for line in splitLines(sdp) {
            let text = line.trimmingCharacters(in: .whitespaces)
            guard text.lowercased().hasPrefix(prefix) else { continue }
            let value = text.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
            guard !value.isEmpty else { continue }
            return value.lowercased()
        }
        return nil
    }

    /// The certificate fingerprint and the sealed one name the same certificate.
    static func matches(_ sdpPrint: String, _ certPrint: String) -> Bool {
        let left = normalize(sdpPrint)
        let right = normalize(certPrint)
        return !left.isEmpty && left == right
    }

    private static func normalize(_ value: String) -> String {
        value.filter { !$0.isWhitespace && $0 != ":" }.lowercased()
    }

    static func withVoiceResilience(_ sdp: String) -> String {
        let eol = sdp.contains("\r\n") ? "\r\n" : "\n"
        var lines = splitLines(sdp)
        guard let payload = lines.lazy.compactMap(opusPayload).first else {
            return lines.joined(separator: eol)
        }
        if let index = lines.firstIndex(where: { isFmtp($0, payload: payload) }) {
            lines[index] = applying(extras, to: lines[index])
        } else if let map = lines.firstIndex(where: { isRtpmap($0, payload: payload) }) {
            lines.insert("a=fmtp:\(payload) \(extras.joined(separator: ";"))", at: lines.index(after: map))
        }
        return lines.joined(separator: eol)
    }

    /// Opus for a shared screen's sound: the second audio section (the first is the microphone),
    /// stereo, never silenced, about 128 kbps. Each section has its own `a=fmtp`, so the
    /// microphone keeps its speech settings. An SDP without that section is unchanged.
    static func withScreenSound(_ sdp: String) -> String {
        let eol = sdp.contains("\r\n") ? "\r\n" : "\n"
        var lines = splitLines(sdp)
        let starts = lines.indices.filter { lines[$0].lowercased().hasPrefix("m=audio ") }
        guard starts.count >= 2 else { return lines.joined(separator: eol) }
        let from = starts[1]
        let to = lines.indices.first { $0 > from && lines[$0].lowercased().hasPrefix("m=") } ?? lines.endIndex
        let section = from..<to
        guard let payload = lines[section].lazy.compactMap(opusPayload).first else {
            return lines.joined(separator: eol)
        }
        if let index = section.first(where: { isFmtp(lines[$0], payload: payload) }) {
            lines[index] = applying(screenSoundExtras, to: lines[index])
        } else if let map = section.first(where: { isRtpmap(lines[$0], payload: payload) }) {
            lines.insert("a=fmtp:\(payload) \(screenSoundExtras.joined(separator: ";"))", at: lines.index(after: map))
        }
        return lines.joined(separator: eol)
    }

    /// VP8 first on the screen's picture (the second video section; the first is the camera).
    /// A phone shares its screen from the background, where its hardware H.264 encoder may not
    /// run; VP8 is encoded in software everywhere. Both sides put it first in their own
    /// descriptions, so it is what either one sends there, whoever offers. Unchanged without
    /// that section or without VP8. The web does the same (`screenVideoSdp`).
    static func withScreenVideo(_ sdp: String) -> String {
        codecFirst(sdp, place: 1, codec: "vp8")
    }

    /// H.264 first on the camera's picture (the first video section). Phones and Macs encode it
    /// in hardware; VP8, which a browser lists first, is encoded in software there and runs out of
    /// processor at the sizes the camera now goes out at. Both sides put it first in their own
    /// descriptions, so it is what either one sends, whoever offers; the H.264 profiles keep their
    /// own order. Each H.264 entry declares at least level 4.0 (`h264Level`), so 1080p goes out at
    /// 30 fps. Unchanged without that section or without H.264. The web does the same
    /// (`cameraVideoSdp`).
    static func withCameraVideo(_ sdp: String) -> String {
        let ordered = codecFirst(sdp, place: 0, codec: "h264")
        let eol = ordered.contains("\r\n") ? "\r\n" : "\n"
        var lines = splitLines(ordered)
        guard let from = lines.firstIndex(where: isVideoSection) else { return ordered }
        let to = lines.indices.first { $0 > from && lines[$0].lowercased().hasPrefix("m=") } ?? lines.endIndex
        let h264 = Set(lines[from..<to].compactMap { rtpmapPayload($0, codec: "h264") })
        for index in (from + 1)..<to {
            guard let payload = fmtpPayload(lines[index]), h264.contains(payload) else { continue }
            lines[index] = raisingH264Level(lines[index])
        }
        return lines.joined(separator: eol)
    }

    /// H.264 level 4.0 (`level_idc` 0x28): 1080p at 30 fps.
    private static let h264Level1080p = 0x28

    /// A `profile-level-id` (six hex digits: profile, constraints, level) raised to at least level
    /// 4.0; anything else is unchanged.
    ///
    /// Human: A sender may hold its frame rate to the level the receiver declares (the iPhone's
    /// H.264 encoder does), and browsers declare 3.1, which carries 1080p at only about 13 fps.
    /// Every client here decodes 1080p30, so declaring 4.0 is true; it says what this side can
    /// receive and changes nothing about what it sends. The web does the same (`h264Level`).
    static func h264Level(_ profileLevelId: String) -> String {
        guard profileLevelId.count == 6, profileLevelId.allSatisfy(isHex),
              let level = Int(profileLevelId.suffix(2), radix: 16)
        else { return profileLevelId }
        return level >= h264Level1080p
            ? profileLevelId
            : String(profileLevelId.prefix(4)) + String(h264Level1080p, radix: 16)
    }

    /// The first `profile-level-id=` that is its own parameter and has a hex value, with that value
    /// through `h264Level`; the rest of the line as it was.
    private static func raisingH264Level(_ line: String) -> String {
        let key = "profile-level-id="
        var search = line.startIndex
        while let found = line.range(of: key, options: .caseInsensitive, range: search..<line.endIndex) {
            search = found.upperBound
            if found.lowerBound != line.startIndex {
                let previous = line[line.index(before: found.lowerBound)]
                guard previous == " " || previous == "\t" || previous == ";" else { continue }
            }
            let end = line[found.upperBound...].firstIndex { !isHex($0) } ?? line.endIndex
            guard end > found.upperBound else { continue }
            var updated = line
            updated.replaceSubrange(found.upperBound..<end, with: h264Level(String(line[found.upperBound..<end])))
            return updated
        }
        return line
    }

    /// One codec first in the `place`-th video section (0 the camera's, 1 the screen's), keeping
    /// the order of everything else. Unchanged without that section or without that codec.
    /// Agent: `codec` is lowercase; rtpmap lines are matched case-insensitively.
    private static func codecFirst(_ sdp: String, place: Int, codec: String) -> String {
        let eol = sdp.contains("\r\n") ? "\r\n" : "\n"
        var lines = splitLines(sdp)
        let starts = lines.indices.filter { isVideoSection(lines[$0]) }
        guard starts.count > place else { return lines.joined(separator: eol) }
        let from = starts[place]
        let to = lines.indices.first { $0 > from && lines[$0].lowercased().hasPrefix("m=") } ?? lines.endIndex
        let wanted = Set(lines[from..<to].compactMap { rtpmapPayload($0, codec: codec) })
        guard !wanted.isEmpty else { return lines.joined(separator: eol) }
        let parts = lines[from].split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard parts.count > 3 else { return lines.joined(separator: eol) }
        // m=video <port> <proto> <payload types…>
        let types = parts.dropFirst(3)
        lines[from] = (parts.prefix(3) + types.filter { wanted.contains($0) } + types.filter { !wanted.contains($0) })
            .joined(separator: " ")
        return lines.joined(separator: eol)
    }

    private static let screenSoundExtras = [
        "useinbandfec=1",
        "usedtx=0",
        "stereo=1",
        "sprop-stereo=1",
        "maxaveragebitrate=128000",
    ]

    private static func splitLines(_ sdp: String) -> [String] {
        sdp.replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
    }

    /// `m=video` followed by a space or a tab.
    private static func isVideoSection(_ line: String) -> Bool {
        let lower = line.lowercased()
        return lower.hasPrefix("m=video ") || lower.hasPrefix("m=video\t")
    }

    /// The payload type of an `a=rtpmap:<pt> <codec>/90000` line; `codec` is lowercase.
    private static func rtpmapPayload(_ line: String, codec: String) -> String? {
        let lower = line.lowercased()
        guard lower.hasPrefix("a=rtpmap:") else { return nil }
        let payload = lower.dropFirst("a=rtpmap:".count).prefix(while: isDigit)
        guard !payload.isEmpty,
              lower.dropFirst("a=rtpmap:".count + payload.count).hasPrefix(" \(codec)/90000")
        else { return nil }
        return String(payload)
    }

    /// The payload type of an `a=fmtp:<pt> …` line.
    private static func fmtpPayload(_ line: String) -> String? {
        let lower = line.lowercased()
        guard lower.hasPrefix("a=fmtp:") else { return nil }
        let rest = lower.dropFirst("a=fmtp:".count)
        let payload = rest.prefix(while: isDigit)
        guard !payload.isEmpty, let next = rest.dropFirst(payload.count).first, next == " " || next == "\t"
        else { return nil }
        return String(payload)
    }

    private static func isDigit(_ char: Character) -> Bool {
        char.isASCII && char.isNumber
    }

    private static func isHex(_ char: Character) -> Bool {
        char.isASCII && char.isHexDigit
    }

    private static func opusPayload(_ line: String) -> String? {
        let lower = line.lowercased()
        guard lower.hasPrefix("a=rtpmap:"), lower.contains(" opus/48000") else { return nil }
        let rest = line.dropFirst("a=rtpmap:".count)
        let payload = rest.prefix { $0.isNumber }
        return payload.isEmpty ? nil : String(payload)
    }

    private static func isRtpmap(_ line: String, payload: String) -> Bool {
        let lower = line.lowercased()
        return lower.hasPrefix("a=rtpmap:\(payload) ") || lower.hasPrefix("a=rtpmap:\(payload)\t")
    }

    private static func isFmtp(_ line: String, payload: String) -> Bool {
        let prefix = "a=fmtp:\(payload)"
        guard line.lowercased().hasPrefix(prefix.lowercased()) else { return false }
        guard line.count > prefix.count else { return line.count == prefix.count }
        let next = line[line.index(line.startIndex, offsetBy: prefix.count)]
        return next == " " || next == ";" || next == "\t"
    }

    private static func applying(_ extras: [String], to line: String) -> String {
        extras.reduce(line) { partial, extra in
            guard let eq = extra.firstIndex(of: "=") else { return partial }
            let key = String(extra[..<eq])
            if let range = parameterRange(key, in: partial) {
                var updated = partial
                updated.replaceSubrange(range, with: extra)
                return updated
            }
            return partial + ";" + extra
        }
    }

    /// The `key=value` span when `key` is its own fmtp parameter, not a suffix of another.
    private static func parameterRange(_ key: String, in line: String) -> Range<String.Index>? {
        let token = key + "="
        var search = line.startIndex
        while let found = line.range(of: token, range: search..<line.endIndex) {
            let atBoundary: Bool
            if found.lowerBound == line.startIndex {
                atBoundary = true
            } else {
                let previous = line[line.index(before: found.lowerBound)]
                atBoundary = previous == ";" || previous == " " || previous == "\t"
            }
            if atBoundary {
                var end = found.upperBound
                while end < line.endIndex {
                    let char = line[end]
                    if char == ";" || char == " " || char == "\t" { break }
                    end = line.index(after: end)
                }
                return found.lowerBound..<end
            }
            search = found.upperBound
        }
        return nil
    }
}

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
        let eol = sdp.contains("\r\n") ? "\r\n" : "\n"
        var lines = splitLines(sdp)
        let starts = lines.indices.filter { lines[$0].lowercased().hasPrefix("m=video ") }
        guard starts.count >= 2 else { return lines.joined(separator: eol) }
        let from = starts[1]
        let to = lines.indices.first { $0 > from && lines[$0].lowercased().hasPrefix("m=") } ?? lines.endIndex
        let vp8 = Set(lines[from..<to].compactMap { line -> String? in
            let lower = line.lowercased()
            guard lower.hasPrefix("a=rtpmap:"), lower.contains(" vp8/90000") else { return nil }
            let payload = line.dropFirst("a=rtpmap:".count).prefix { $0.isNumber }
            return payload.isEmpty ? nil : String(payload)
        })
        guard !vp8.isEmpty else { return lines.joined(separator: eol) }
        let parts = lines[from].split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard parts.count > 3 else { return lines.joined(separator: eol) }
        // m=video <port> <proto> <payload types…>
        let types = parts.dropFirst(3)
        lines[from] = (parts.prefix(3) + types.filter { vp8.contains($0) } + types.filter { !vp8.contains($0) })
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

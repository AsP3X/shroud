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

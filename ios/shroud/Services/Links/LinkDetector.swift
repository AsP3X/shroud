import Foundation

/// A link found inside message text.
nonisolated struct DetectedLink: Equatable, Sendable {
    /// UTF-16 range of the link in the scanned text — the same offsets the web client uses.
    let range: NSRange
    /// What a tap opens: the URL as typed, `https://` + the host for a bare domain, or
    /// `mailto:` + the address for an e-mail.
    let url: URL
    /// E-mail addresses are tappable but never get a preview.
    let isEmail: Bool
}

/// Finds web links and e-mail addresses in plain message text.
///
/// Human: Telegram gets its link ranges from the server; Shroud's server never sees the text, so
/// each client finds links itself. Both clients must underline the *same* characters, or a
/// message reads differently on the phone and in the browser — which is why this is a small
/// hand-written rule set instead of `NSDataDetector` (whose results change between iOS releases).
/// `web/src/links.ts` is a line-for-line port; the test vectors in `LinkDetectorTests` and
/// `links.selftest.ts` are the same strings.
/// Agent: Pure and deterministic. Offsets are UTF-16 (NSString) units. Only `http`, `https`
/// and `mailto` URLs are ever produced — nothing else is tappable.
nonisolated enum LinkDetector {
    /// Candidates: a scheme URL (group 1), an e-mail (group 2) or a `www.`/bare host (group 3).
    ///
    /// Human: The look-behind keeps a match from starting in the middle of a word, a path or an
    /// address (`foo@bar.com` must not also yield `bar.com`). Trailing punctuation is trimmed
    /// afterwards, because a regex cannot know whether a closing bracket belongs to the URL.
    private static let pattern = #"(?<![\p{L}\p{N}@._\-/#%+~=&])(?:(https?://[^\s<>"]+)|([\p{L}\p{N}._%+\-]+@(?:[\p{L}\p{N}](?:[\p{L}\p{N}\-]{0,61}[\p{L}\p{N}])?\.)+(?:xn--[a-z0-9\-]{1,59}|\p{L}{2,63}))|((?:www\.)?(?:[\p{L}\p{N}](?:[\p{L}\p{N}\-]{0,61}[\p{L}\p{N}])?\.)+(?:xn--[a-z0-9\-]{1,59}|\p{L}{2,63})(?::\d{1,5})?(?:[/?#][^\s<>"]*)?))"#

    private static let regex: NSRegularExpression? = try? NSRegularExpression(
        pattern: pattern,
        options: [.caseInsensitive]
    )

    /// Characters that end a sentence rather than a URL.
    private static let trailingPunctuation: Set<unichar> = Set(".,:;!?'\"‘’“”»›…".utf16)

    /// Two-letter country codes. Bare hosts must end in one of these or in `genericTLDs`, so
    /// sentences without a space after the full stop ("Ende.Da") are not turned into links.
    private static let countryTLDs: Set<String> = Set("""
    ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs \
    bt bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee \
    eg er es et eu fi fj fk fm fo fr ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk \
    hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la \
    lb lc li lk lr ls lt lu lv ly ma mc md me mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my \
    mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro \
    rs ru rw sa sb sc sd se sg sh si sk sl sm sn so sr ss st su sv sx sy sz tc td tf tg th tj tk \
    tl tm tn to tr tt tv tw tz ua ug uk us uy uz va vc ve vg vi vn vu wf ws ye yt za zm zw
    """.split(separator: " ").map(String.init))

    /// The generic TLDs people actually type without a scheme.
    private static let genericTLDs: Set<String> = Set("""
    com org net edu gov mil int info biz name pro app dev xyz online site website tech store shop \
    blog news cloud club live page link wiki email social media art design studio travel one top \
    mobi museum coop aero asia jobs tel cat post zone network digital agency company services \
    solutions systems software team tools works today space fun games music video photo photos \
    gallery host codes support help guide center school academy education university health care \
    law finance money bank capital fund market events community foundation family life city \
    berlin hamburg bayern koeln wien london paris nyc tokyo amsterdam swiss gmbh ltd inc llc eco \
    energy bio garden house reisen restaurant cafe coffee bar wine rocks guru expert consulting \
    partners tips fyi run chat ninja social
    """.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init))

    /// Every link in `text`, in order.
    ///
    /// Agent: RETURNS UTF-16 ranges into `text`; never throws; empty for plain prose.
    static func links(in text: String) -> [DetectedLink] {
        guard let regex, !text.isEmpty else { return [] }
        let ns = text as NSString
        var found: [DetectedLink] = []
        for match in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            if let link = link(from: match, in: ns) {
                found.append(link)
            }
        }
        return found
    }

    /// The link a preview is built for: the first one that is not an e-mail address
    /// (Telegram previews the first link in a message too).
    static func firstPreviewableURL(in text: String) -> URL? {
        links(in: text).first(where: { !$0.isEmail })?.url
    }

    // MARK: - Match → link

    private static func link(from match: NSTextCheckingResult, in text: NSString) -> DetectedLink? {
        let scheme = match.range(at: 1)
        let email = match.range(at: 2)
        let host = match.range(at: 3)

        if scheme.location != NSNotFound {
            let trimmed = trimTrailing(scheme, in: text)
            let raw = text.substring(with: trimmed)
            // "https://" on its own, or a scheme followed only by punctuation, is not a link.
            guard let url = URL(string: raw), let hostName = url.host, !hostName.isEmpty else {
                return nil
            }
            return DetectedLink(range: trimmed, url: url, isEmail: false)
        }

        if email.location != NSNotFound {
            let raw = text.substring(with: email)
            guard let domain = raw.split(separator: "@").last,
                  isAllowedTLD(of: String(domain)),
                  let url = URL(string: "mailto:" + raw)
            else { return nil }
            return DetectedLink(range: email, url: url, isEmail: true)
        }

        guard host.location != NSNotFound else { return nil }
        let trimmed = trimTrailing(host, in: text)
        let raw = text.substring(with: trimmed)
        let hostName = raw.prefix { !":/?#".contains($0) }
        // `www.` says "this is a web address" on its own; a bare name needs a real TLD.
        let hasWWW = raw.lowercased().hasPrefix("www.")
        guard hasWWW || isAllowedTLD(of: String(hostName)),
              let url = URL(string: "https://" + raw)
        else { return nil }
        return DetectedLink(range: trimmed, url: url, isEmail: false)
    }

    /// Drops sentence punctuation and unbalanced closing brackets from the end of a match.
    ///
    /// Human: "(see example.com/a_(b))." keeps the `)` that closes `(b` but loses the one that
    /// closes the sentence's bracket and the full stop.
    private static func trimTrailing(_ range: NSRange, in text: NSString) -> NSRange {
        var length = range.length
        while length > 0 {
            let last = text.character(at: range.location + length - 1)
            if trailingPunctuation.contains(last) {
                length -= 1
                continue
            }
            let body = text.substring(with: NSRange(location: range.location, length: length))
            if let opener = opening(for: last), count(opener, in: body) < count(last, in: body) {
                length -= 1
                continue
            }
            break
        }
        return NSRange(location: range.location, length: length)
    }

    private static func opening(for closer: unichar) -> unichar? {
        switch closer {
        case 0x29: 0x28 // ) (
        case 0x5D: 0x5B // ] [
        case 0x7D: 0x7B // } {
        default: nil
        }
    }

    private static func count(_ unit: unichar, in string: String) -> Int {
        string.utf16.reduce(0) { $0 + ($1 == unit ? 1 : 0) }
    }

    /// True when the host's last label is a real top-level domain.
    private static func isAllowedTLD(of host: String) -> Bool {
        guard let last = host.split(separator: ".").last else { return false }
        let tld = last.lowercased()
        if tld.hasPrefix("xn--") { return tld.count > 4 }
        // A TLD in its own script (e.g. `.рф`) — the regex already required letters.
        if tld.unicodeScalars.allSatisfy({ !$0.isASCII }) { return true }
        if tld.count == 2 { return countryTLDs.contains(tld) }
        return genericTLDs.contains(tld)
    }
}

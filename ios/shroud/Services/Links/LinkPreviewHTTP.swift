import Foundation

/// Why the link-preview HTTP client gave up.
nonisolated enum LinkPreviewHTTPError: Error, Equatable {
    /// The URL, its host name or its addresses fail the fetch rules.
    case notAllowed
    /// The response is malformed, truncated, or a redirect was refused.
    case badResponse
    /// A transfer or content coding other than `chunked`/`identity`.
    case unsupportedEncoding
    case tooManyRedirects
    case timedOut
    case closed
}

/// The HTTP/1.1 the link-preview fetcher speaks: building one GET, reading the response head,
/// framing the body, and resolving redirects.
///
/// Human: The fetcher connects to the very address it checked (see `LinkPreviewConnection`),
/// which URLSession cannot do, so it speaks HTTP itself. It only needs a sliver of the protocol:
/// one GET per connection (`Connection: close`), no compression (`Accept-Encoding: identity`),
/// bodies framed by `Content-Length`, `chunked`, or the server closing the connection. Anything
/// else is refused rather than guessed at.
/// Agent: Pure functions, no I/O; covered by `LinkPreviewHTTPTests`. Header names are compared
/// lowercased. Bytes outside ASCII in header values are read as Latin-1.
nonisolated enum LinkPreviewHTTP {
    /// Largest response head (status line plus headers) read before giving up.
    static let maxHeadBytes = 64 * 1024
    static let maxHeaderCount = 100
    static let redirectStatuses: Set<Int> = [301, 302, 303, 307, 308]

    // MARK: - Request

    /// The host name to resolve, send as SNI and `Host`, and check the certificate against:
    /// lowercased ASCII (IDNA A-labels), without a trailing dot.
    ///
    /// Agent: RETURNS nil for anything but letters, digits, `-`, `.` and `_`, so nothing a
    /// header or the TLS layer would misread gets through.
    static func hostName(of url: URL) -> String? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true),
              var host = components.encodedHost?.lowercased()
        else { return nil }
        if host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty, host.utf8.count <= 253, host.utf8.allSatisfy(isHostByte) else { return nil }
        return host
    }

    /// Path and query as they go on the request line (`/a/b?c=d`); the fragment is dropped.
    ///
    /// Agent: RETURNS nil when the target holds a space, a control or non-ASCII byte, or `#`.
    static func requestTarget(of url: URL) -> String? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { return nil }
        var target = components.percentEncodedPath
        if target.isEmpty { target = "/" }
        guard target.hasPrefix("/") else { return nil }
        if let query = components.percentEncodedQuery { target += "?" + query }
        guard target.utf8.allSatisfy({ $0 > 0x20 && $0 < 0x7F && $0 != UInt8(ascii: "#") }) else { return nil }
        return target
    }

    /// The bytes of one `GET` for `url`, with `headers` after `Host` and before the fixed
    /// `Accept-Encoding: identity` and `Connection: close`.
    ///
    /// Agent: RETURNS nil when the URL, a header name or a header value could break the request
    /// framing (CR, LF, other control bytes, non-ASCII).
    static func request(for url: URL, headers: [(name: String, value: String)]) -> Data? {
        guard let host = hostName(of: url), let target = requestTarget(of: url) else { return nil }
        var text = "GET \(target) HTTP/1.1\r\nHost: \(host)\r\n"
        for (name, value) in headers {
            guard !name.isEmpty,
                  name.utf8.allSatisfy(isTokenByte),
                  value.utf8.allSatisfy({ $0 == 0x09 || (0x20 ... 0x7E).contains($0) })
            else { return nil }
            text += "\(name): \(value)\r\n"
        }
        text += "Accept-Encoding: identity\r\nConnection: close\r\n\r\n"
        return Data(text.utf8)
    }

    // MARK: - Response head

    nonisolated struct Header: Equatable, Sendable {
        /// Lowercased.
        let name: String
        /// Without leading or trailing spaces and tabs.
        let value: String
    }

    nonisolated struct ResponseHead: Equatable, Sendable {
        let status: Int
        let headers: [Header]

        /// Every value of the header `name`, in order.
        func values(named name: String) -> [String] {
            let name = name.lowercased()
            return headers.filter { $0.name == name }.map(\.value)
        }

        /// The first value of the header `name`.
        func value(named name: String) -> String? {
            values(named: name).first
        }

        /// The comma-separated list elements of every `name` header, trimmed and lowercased,
        /// empty elements dropped (`Transfer-Encoding: gzip, chunked` → `["gzip", "chunked"]`).
        func listValues(named name: String) -> [String] {
            values(named: name).flatMap { value in
                value.split(separator: ",", omittingEmptySubsequences: true)
                    .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                    .filter { !$0.isEmpty }
            }
        }
    }

    /// Where the head ends in `bytes`: the head is `bytes[..<headLength]` and the body starts at
    /// `bodyStart`. Accepts `\r\n\r\n` and, leniently, bare `\n` line ends.
    ///
    /// Agent: RETURNS nil while the blank line that ends the head has not arrived.
    static func headEnd(in bytes: [UInt8]) -> (headLength: Int, bodyStart: Int)? {
        var index = 0
        while index < bytes.count {
            if bytes[index] == 0x0A {
                if index + 1 < bytes.count, bytes[index + 1] == 0x0A {
                    return (index, index + 2)
                }
                if index + 2 < bytes.count, bytes[index + 1] == 0x0D, bytes[index + 2] == 0x0A {
                    return (index, index + 3)
                }
            }
            index += 1
        }
        return nil
    }

    /// The status code of `HTTP/1.x NNN [reason]`.
    ///
    /// Agent: RETURNS nil for anything but HTTP/1.x and codes outside 100–599.
    static func statusCode(ofStatusLine line: String) -> Int? {
        let bytes = Array(line.utf8)
        guard bytes.count >= 12,
              bytes[0 ..< 7].elementsEqual("HTTP/1.".utf8),
              isDigit(bytes[7]),
              bytes[8] == 0x20,
              bytes[9 ..< 12].allSatisfy(isDigit),
              bytes.count == 12 || bytes[12] == 0x20
        else { return nil }
        let status = bytes[9 ..< 12].reduce(0) { $0 * 10 + Int($1 - 0x30) }
        guard (100 ... 599).contains(status) else { return nil }
        return status
    }

    /// Parses a response head: the status line and header lines, without the blank line that
    /// ends it (`bytes[..<headLength]` from `headEnd`).
    ///
    /// Agent: RETURNS nil for a bad status line, folded (`obs-fold`) or nameless header lines,
    /// header names that are not tokens, CR or NUL inside a value, or more than
    /// `maxHeaderCount` headers.
    static func parseHead(_ bytes: [UInt8]) -> ResponseHead? {
        let lines = bytes.split(separator: 0x0A, omittingEmptySubsequences: false).map { line in
            line.last == 0x0D ? line.dropLast() : line
        }
        guard let statusLine = lines.first,
              let status = statusCode(ofStatusLine: String(decoding: statusLine, as: UTF8.self)),
              lines.count - 1 <= maxHeaderCount
        else { return nil }
        var headers: [Header] = []
        for line in lines.dropFirst() {
            guard let first = line.first, first != 0x20, first != 0x09,
                  let colon = line.firstIndex(of: UInt8(ascii: ":"))
            else { return nil }
            let name = line[line.startIndex ..< colon]
            guard !name.isEmpty, name.allSatisfy(isTokenByte) else { return nil }
            var value = line[line.index(after: colon)...]
            while let byte = value.first, byte == 0x20 || byte == 0x09 { value = value.dropFirst() }
            while let byte = value.last, byte == 0x20 || byte == 0x09 { value = value.dropLast() }
            guard !value.contains(where: { $0 == 0x0D || $0 == 0x00 }) else { return nil }
            headers.append(Header(
                name: String(decoding: name, as: UTF8.self).lowercased(),
                value: String(bytes: value, encoding: .isoLatin1) ?? ""
            ))
        }
        return ResponseHead(status: status, headers: headers)
    }

    // MARK: - Body

    nonisolated enum BodyFraming: Equatable, Sendable {
        /// No body (1xx, 204, 304).
        case empty
        /// Exactly this many bytes.
        case length(Int)
        /// `Transfer-Encoding: chunked`.
        case chunked
        /// Everything until the server closes the connection.
        case untilClose
    }

    /// How the body of a response to a GET is delimited (RFC 9112 §6.3).
    ///
    /// Agent: RETURNS nil (refuse the response) for a `Content-Encoding` other than `identity`,
    /// a `Transfer-Encoding` other than exactly `chunked`, or a missing, malformed or
    /// conflicting `Content-Length` value. `Transfer-Encoding` wins over `Content-Length`.
    static func bodyFraming(of head: ResponseHead) -> BodyFraming? {
        if (100 ..< 200).contains(head.status) || head.status == 204 || head.status == 304 {
            return .empty
        }
        guard head.listValues(named: "content-encoding").allSatisfy({ $0 == "identity" }) else { return nil }
        if !head.values(named: "transfer-encoding").isEmpty {
            return head.listValues(named: "transfer-encoding") == ["chunked"] ? .chunked : nil
        }
        guard !head.values(named: "content-length").isEmpty else { return .untilClose }
        let lengths = head.listValues(named: "content-length")
        guard let first = lengths.first,
              lengths.allSatisfy({ $0 == first }),
              (1 ... 18).contains(first.utf8.count),
              first.utf8.allSatisfy(isDigit),
              let length = Int(first)
        else { return nil }
        return .length(length)
    }

    /// Decodes a `chunked` body as it arrives, in pieces of any size.
    ///
    /// Agent: `decode` returns the payload bytes the input completed (possibly none) and throws
    /// `badResponse` for a malformed chunk size, a chunk not followed by CRLF, or an over-long
    /// size or trailer line. Chunk extensions and trailers are skipped. After `isFinished`,
    /// further input is ignored.
    nonisolated struct ChunkedDecoder {
        static let maxLineBytes = 4096
        static let maxTrailerBytes = 16 * 1024

        private nonisolated enum State: Equatable {
            case size
            case data(remaining: Int)
            case dataEnd
            case trailer
            case done
        }

        private var state = State.size
        /// Input not yet consumed (a partial size line, CRLF or trailer line).
        private var pending: [UInt8] = []
        private var trailerBytes = 0

        var isFinished: Bool { state == .done }

        mutating func decode(_ input: Data) throws -> Data {
            guard state != .done else { return Data() }
            pending.append(contentsOf: input)
            var output = Data()
            var index = 0
            parsing: while index < pending.count {
                switch state {
                case .size:
                    guard let line = Self.line(in: pending, from: index) else {
                        guard pending.count - index <= Self.maxLineBytes else { throw LinkPreviewHTTPError.badResponse }
                        break parsing
                    }
                    guard line.length <= Self.maxLineBytes,
                          let size = Self.chunkSize(pending[index ..< index + line.length])
                    else { throw LinkPreviewHTTPError.badResponse }
                    index = line.next
                    state = size == 0 ? .trailer : .data(remaining: size)
                case let .data(remaining):
                    let count = min(remaining, pending.count - index)
                    output.append(contentsOf: pending[index ..< index + count])
                    index += count
                    state = count == remaining ? .dataEnd : .data(remaining: remaining - count)
                case .dataEnd:
                    guard let line = Self.line(in: pending, from: index) else {
                        // Only a lone CR may wait for its LF.
                        guard pending.count - index == 1, pending[index] == 0x0D else {
                            throw LinkPreviewHTTPError.badResponse
                        }
                        break parsing
                    }
                    guard line.length == 0 else { throw LinkPreviewHTTPError.badResponse }
                    index = line.next
                    state = .size
                case .trailer:
                    guard let line = Self.line(in: pending, from: index) else {
                        guard pending.count - index <= Self.maxLineBytes else { throw LinkPreviewHTTPError.badResponse }
                        break parsing
                    }
                    index = line.next
                    if line.length == 0 {
                        state = .done
                    } else {
                        trailerBytes += line.length
                        guard line.length <= Self.maxLineBytes, trailerBytes <= Self.maxTrailerBytes else {
                            throw LinkPreviewHTTPError.badResponse
                        }
                    }
                case .done:
                    break parsing
                }
            }
            if state == .done {
                pending = []
            } else {
                pending.removeFirst(index)
            }
            return output
        }

        /// The line starting at `start`: its length without CR LF (or bare LF), and where the
        /// next line starts. Nil while its LF has not arrived.
        private static func line(in bytes: [UInt8], from start: Int) -> (length: Int, next: Int)? {
            guard let newline = bytes[start...].firstIndex(of: 0x0A) else { return nil }
            var end = newline
            if end > start, bytes[end - 1] == 0x0D { end -= 1 }
            return (end - start, newline + 1)
        }

        /// The hexadecimal size before any `;extension`, with trailing spaces or tabs allowed.
        private static func chunkSize(_ line: ArraySlice<UInt8>) -> Int? {
            var digits = line
            if let semicolon = line.firstIndex(of: UInt8(ascii: ";")) {
                digits = line[line.startIndex ..< semicolon]
            }
            while let byte = digits.last, byte == 0x20 || byte == 0x09 { digits = digits.dropLast() }
            guard (1 ... 15).contains(digits.count), digits.allSatisfy(LinkPreviewHTTP.isHexDigit) else { return nil }
            return Int(String(decoding: digits, as: UTF8.self), radix: 16)
        }
    }

    // MARK: - Redirects

    /// The absolute URL a redirect points to, resolved against the URL that answered.
    ///
    /// Agent: RETURNS nil when `head` is not a 301/302/303/307/308 or has no single `Location`.
    /// The caller still holds the result to the fetch rules (`allowedTarget`, address check).
    static func redirectTarget(of head: ResponseHead, from url: URL) -> URL? {
        guard redirectStatuses.contains(head.status) else { return nil }
        let locations = Set(head.values(named: "location"))
        guard locations.count == 1,
              let location = locations.first?.trimmingCharacters(in: .whitespaces),
              !location.isEmpty
        else { return nil }
        return URL(string: location, relativeTo: url)?.absoluteURL
    }

    // MARK: - Bytes

    private static func isDigit(_ byte: UInt8) -> Bool {
        (0x30 ... 0x39).contains(byte)
    }

    private static func isHexDigit(_ byte: UInt8) -> Bool {
        isDigit(byte) || (0x41 ... 0x46).contains(byte) || (0x61 ... 0x66).contains(byte)
    }

    /// `tchar` of RFC 9110 §5.6.2.
    private static func isTokenByte(_ byte: UInt8) -> Bool {
        if isDigit(byte) || (0x41 ... 0x5A).contains(byte) || (0x61 ... 0x7A).contains(byte) { return true }
        return "!#$%&'*+-.^_`|~".utf8.contains(byte)
    }

    private static func isHostByte(_ byte: UInt8) -> Bool {
        isDigit(byte) || (0x61 ... 0x7A).contains(byte) || byte == UInt8(ascii: "-")
            || byte == UInt8(ascii: ".") || byte == UInt8(ascii: "_")
    }
}

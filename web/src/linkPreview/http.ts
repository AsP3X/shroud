/**
 * A small HTTP/1.1 response reader for link previews.
 *
 * The browser talks to websites through Shroud's link relay with its own TLS (see `tls.ts`), so
 * it also has to read the HTTP response itself. Only what previews need: a status line,
 * headers, and a body framed by `Content-Length`, `chunked`, or the connection closing — capped
 * at `maxBodyBytes`, because a preview never needs more than a page's head or one image.
 * Pure: bytes in, response out; no I/O.
 */

export type HttpResponse = {
  status: number;
  /** Lowercased names; repeated headers joined with ", ". */
  headers: Map<string, string>;
  body: Uint8Array;
  /** The body ended where the server said it would (length, last chunk, or close). */
  complete: boolean;
  /** The body was cut at `maxBodyBytes`. */
  truncated: boolean;
};

const CRLF_CRLF = [13, 10, 13, 10];

function indexOfSequence(haystack: Uint8Array, needle: number[], from = 0): number {
  outer: for (let i = from; i <= haystack.length - needle.length; i++) {
    for (let j = 0; j < needle.length; j++) {
      if (haystack[i + j] !== needle[j]) continue outer;
    }
    return i;
  }
  return -1;
}

function latin1(bytes: Uint8Array): string {
  let text = "";
  for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
  return text;
}

/** Growable byte buffer (a preview body is at most a few MB). */
class Bytes {
  private data = new Uint8Array(16 * 1024);
  length = 0;

  push(chunk: Uint8Array): void {
    if (this.length + chunk.length > this.data.length) {
      const grown = new Uint8Array(Math.max(this.data.length * 2, this.length + chunk.length));
      grown.set(this.data.subarray(0, this.length));
      this.data = grown;
    }
    this.data.set(chunk, this.length);
    this.length += chunk.length;
  }

  view(): Uint8Array {
    return this.data.subarray(0, this.length);
  }

  /** Drops the first `count` bytes. */
  consume(count: number): void {
    this.data.copyWithin(0, count, this.length);
    this.length -= count;
  }
}

type Framing = { kind: "length"; remaining: number } | { kind: "chunked" } | { kind: "close" } | { kind: "none" };

/**
 * Feed it decrypted bytes as they arrive (`push`), tell it when the connection closed (`end`),
 * and read `response()` once `done`.
 */
export class HttpResponseReader {
  private raw = new Bytes();
  private body = new Bytes();
  private status = 0;
  private headers = new Map<string, string>();
  private framing: Framing | null = null;
  /** Bytes still to skip of the current chunk's data, or -1 while reading a size line. */
  private chunkLeft = -1;
  private finished = false;
  private complete = false;
  private truncated = false;
  private failed: string | null = null;

  constructor(private readonly maxBodyBytes: number) {}

  /** True once the headers have been read. */
  get headersDone(): boolean {
    return this.framing !== null;
  }

  /** True when nothing more is needed: body complete, cut, failed, or connection closed. */
  get done(): boolean {
    return this.finished;
  }

  /** The body read so far (to look for `</head>` before the page is over). */
  bodySoFar(): Uint8Array {
    return this.body.view();
  }

  push(chunk: Uint8Array): void {
    if (this.finished || chunk.length === 0) return;
    this.raw.push(chunk);
    this.advance();
  }

  /** The connection closed: a body framed by the close is now complete. */
  end(): void {
    if (this.finished) return;
    this.advance();
    if (this.framing?.kind === "close") this.complete = true;
    if (!this.framing) this.failed ??= "The connection closed before the response headers.";
    this.finished = true;
  }

  /** Stops reading early (the caller has what it needs). */
  stop(): void {
    this.finished = true;
  }

  /** The response, or an error when the headers never arrived or could not be read. */
  response(): HttpResponse {
    if (this.failed || !this.framing) throw new Error(this.failed ?? "No HTTP response.");
    return {
      status: this.status,
      headers: this.headers,
      body: this.body.view().slice(),
      complete: this.complete,
      truncated: this.truncated,
    };
  }

  private advance(): void {
    while (!this.finished) {
      if (!this.framing) {
        if (!this.readHead()) return;
        continue;
      }
      if (!this.readBody()) return;
    }
  }

  /** Parses one header block; interim 1xx responses are skipped. */
  private readHead(): boolean {
    const raw = this.raw.view();
    const end = indexOfSequence(raw, CRLF_CRLF);
    if (end < 0) {
      if (raw.length > 64 * 1024) this.fail("The response headers are too large.");
      return false;
    }
    const lines = latin1(raw.subarray(0, end)).split("\r\n");
    this.raw.consume(end + 4);
    const statusMatch = /^HTTP\/1\.[01] (\d{3})/.exec(lines[0] ?? "");
    if (!statusMatch) {
      this.fail("Not an HTTP/1.1 response.");
      return false;
    }
    const status = Number(statusMatch[1]);
    if (status >= 100 && status < 200) return true; // 100 Continue / 103 Early Hints
    this.status = status;
    for (const line of lines.slice(1)) {
      const colon = line.indexOf(":");
      if (colon <= 0) continue;
      const name = line.slice(0, colon).trim().toLowerCase();
      const value = line.slice(colon + 1).trim();
      const previous = this.headers.get(name);
      this.headers.set(name, previous ? `${previous}, ${value}` : value);
    }
    const transfer = (this.headers.get("transfer-encoding") ?? "").toLowerCase();
    const length = this.headers.get("content-length");
    if (status === 204 || status === 304) {
      this.framing = { kind: "none" };
    } else if (transfer.includes("chunked")) {
      this.framing = { kind: "chunked" };
    } else if (length !== undefined && /^\d+$/.test(length)) {
      this.framing = { kind: "length", remaining: Number(length) };
    } else {
      this.framing = { kind: "close" };
    }
    return true;
  }

  private readBody(): boolean {
    const framing = this.framing;
    if (!framing) return false;
    switch (framing.kind) {
      case "none":
        this.complete = true;
        this.finished = true;
        return false;
      case "close": {
        const raw = this.raw.view();
        if (raw.length === 0) return false;
        this.take(raw.slice());
        this.raw.consume(raw.length);
        return !this.finished;
      }
      case "length": {
        const raw = this.raw.view();
        const take = Math.min(raw.length, framing.remaining);
        if (take > 0) {
          this.take(raw.slice(0, take));
          this.raw.consume(take);
          framing.remaining -= take;
        }
        if (framing.remaining === 0) {
          this.complete = true;
          this.finished = true;
        }
        return false;
      }
      case "chunked":
        return this.readChunk();
    }
  }

  private readChunk(): boolean {
    const raw = this.raw.view();
    if (this.chunkLeft < 0) {
      const lineEnd = indexOfSequence(raw, [13, 10]);
      if (lineEnd < 0) return false;
      const sizeText = latin1(raw.subarray(0, lineEnd)).split(";")[0].trim();
      if (!/^[0-9a-fA-F]+$/.test(sizeText)) {
        this.fail("Malformed chunked body.");
        return false;
      }
      const size = parseInt(sizeText, 16);
      this.raw.consume(lineEnd + 2);
      if (size === 0) {
        // Trailers are not needed; the body is complete.
        this.complete = true;
        this.finished = true;
        return false;
      }
      this.chunkLeft = size;
      return true;
    }
    if (this.chunkLeft > 0) {
      const take = Math.min(raw.length, this.chunkLeft);
      if (take === 0) return false;
      this.take(raw.slice(0, take));
      this.raw.consume(take);
      this.chunkLeft -= take;
      return !this.finished;
    }
    // Chunk data done: its trailing CRLF.
    if (raw.length < 2) return false;
    this.raw.consume(2);
    this.chunkLeft = -1;
    return true;
  }

  private take(bytes: Uint8Array): void {
    const room = this.maxBodyBytes - this.body.length;
    if (bytes.length >= room) {
      this.body.push(bytes.subarray(0, Math.max(0, room)));
      this.truncated = bytes.length > room;
      if (this.truncated || this.body.length >= this.maxBodyBytes) this.finished = true;
      return;
    }
    this.body.push(bytes);
  }

  private fail(message: string): void {
    this.failed = message;
    this.finished = true;
  }
}

/** True once an HTML body has reached `</head` (or `<body`) — a preview needs nothing after it. */
export function headEnded(body: Uint8Array): boolean {
  const tail = latin1(body.subarray(Math.max(0, body.length - 64 * 1024))).toLowerCase();
  return tail.includes("</head") || tail.includes("<body");
}

/**
 * `GET` a web page through Shroud's link relay, with TLS running in this browser.
 *
 * The relay (`GET /api/v1/link-relay`, a WebSocket) moves raw bytes to port 443 of one host and
 * sees nothing but the host name: the TLS session — certificate check included — runs here in
 * WebAssembly (`tls.ts`), and the HTTP exchange inside it is ours (`http.ts`). The website sees
 * the Shroud server's address, not this browser's.
 *
 * Dependencies are passed in (`relayUrl`, `tls`) so the same code runs in Node for tests.
 */

import { HttpResponseReader, type HttpResponse } from "./http";
import type { TlsModule, TlsSession } from "./tls";

/**
 * Sites hand compact OpenGraph-only pages to known preview fetchers; this is the user agent
 * iOS uses too (`LinkPreviewFetcher.userAgent`), measured against YouTube, Amazon and Reddit.
 */
export const PREVIEW_USER_AGENT = "WhatsApp/2 (compatible; ShroudBot/1.0)";

export type RelayFetchOptions = {
  /** Session token — sent in the relay's first frame, never in a URL. */
  token: string;
  /** `wss://…/api/v1/link-relay`. */
  relayUrl: string;
  tls: TlsModule;
  accept: string;
  /** Body bytes to keep at most; the read stops there. */
  maxBytes: number;
  /** Stop once this holds for the body read so far (a page's head is enough). */
  stopWhen?: (body: Uint8Array) => boolean;
  acceptLanguage?: string;
  signal?: AbortSignal;
  /** Per request (each redirect hop gets its own). */
  timeoutMs?: number;
  maxRedirects?: number;
};

export type RelayResponse = HttpResponse & {
  /** The URL that answered, after redirects. */
  url: string;
};

const LOCAL_SUFFIX = /\.(local|localhost|internal|lan|home|arpa|intranet|corp)$/;

/**
 * The URL to fetch, or null when it must not be: https only (http is upgraded, never fetched in
 * the clear), the default port, and a public DNS name — no IP literals or local names. The
 * relay enforces the same rules; checking here just saves the round trip.
 */
export function allowedTarget(raw: string): URL | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol === "http:") url.protocol = "https:";
  if (url.protocol !== "https:") return null;
  if (url.port !== "" && url.port !== "443") return null;
  url.username = "";
  url.password = "";
  const host = url.hostname;
  if (!host.includes(".") || host.startsWith("[") || /^[\d.]+$/.test(host)) return null;
  if (LOCAL_SUFFIX.test(host)) return null;
  return url;
}

/** `GET` with redirects followed (each hop checked against `allowedTarget`). */
export async function relayFetch(url: string, options: RelayFetchOptions): Promise<RelayResponse> {
  let current = url;
  const maxRedirects = options.maxRedirects ?? 4;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const target = allowedTarget(current);
    if (!target) throw new Error("That link cannot be previewed.");
    const response = await fetchOnce(target, options);
    const location = response.headers.get("location");
    if ([301, 302, 303, 307, 308].includes(response.status) && location) {
      current = new URL(location, target).href;
      continue;
    }
    return { ...response, url: target.href };
  }
  throw new Error("Too many redirects.");
}

function encodeRequest(target: URL, options: RelayFetchOptions): Uint8Array {
  const lines = [
    `GET ${target.pathname}${target.search} HTTP/1.1`,
    `Host: ${target.hostname}`,
    `User-Agent: ${PREVIEW_USER_AGENT}`,
    `Accept: ${options.accept}`,
    `Accept-Language: ${options.acceptLanguage ?? "en"}`,
    // Compressed bodies would need a decoder for every scheme; previews read little anyway.
    "Accept-Encoding: identity",
    "Connection: close",
    "",
    "",
  ];
  return new TextEncoder().encode(lines.join("\r\n"));
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value));
}

/** One request/response over one relay socket. */
function fetchOnce(target: URL, options: RelayFetchOptions): Promise<HttpResponse> {
  return new Promise((resolve, reject) => {
    const reader = new HttpResponseReader(options.maxBytes);
    let session: TlsSession | null = null;
    let settled = false;
    const socket = new WebSocket(options.relayUrl);
    socket.binaryType = "arraybuffer";

    const timer = setTimeout(() => fail(new Error("The website took too long.")), options.timeoutMs ?? 12_000);
    const onAbort = () => fail(new DOMException("The preview was cancelled.", "AbortError"));
    if (options.signal?.aborted) {
      queueMicrotask(onAbort);
    } else {
      options.signal?.addEventListener("abort", onAbort);
    }

    function cleanup() {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) socket.close();
      session?.free();
      session = null;
    }
    function fail(error: Error) {
      if (settled) return;
      settled = true;
      cleanup();
      reject(error);
    }
    function succeed() {
      if (settled) return;
      try {
        const response = reader.response();
        settled = true;
        cleanup();
        resolve(response);
      } catch (error) {
        fail(asError(error));
      }
    }
    /** Sends whatever TLS wants to send (handshake, request, alerts). */
    function flush() {
      if (!session || socket.readyState !== WebSocket.OPEN) return;
      const outgoing = session.takeOutgoing();
      if (outgoing.length > 0) socket.send(outgoing);
    }
    /** Hands decrypted bytes to the HTTP reader; finishes once it has enough. */
    function pump() {
      if (!session) return;
      reader.push(session.takePlaintext());
      if (!reader.done && options.stopWhen && reader.headersDone && options.stopWhen(reader.bodySoFar())) {
        reader.stop();
      }
      if (reader.done) succeed();
    }

    socket.onopen = () => {
      socket.send(JSON.stringify({ type: "connect", token: options.token, host: target.hostname }));
    };
    socket.onmessage = (event: MessageEvent) => {
      if (settled) return;
      if (typeof event.data === "string") {
        let frame: { type?: string; error?: { message?: string } };
        try {
          frame = JSON.parse(event.data);
        } catch {
          fail(new Error("Unexpected reply from the link relay."));
          return;
        }
        if (frame.type === "connected") {
          try {
            session = new options.tls.TlsSession(target.hostname);
            session.write(encodeRequest(target, options));
            flush();
          } catch (error) {
            fail(asError(error));
          }
        } else if (frame.type === "error") {
          fail(new Error(frame.error?.message ?? "The website could not be reached."));
        }
        return;
      }
      if (!session) return;
      try {
        session.feed(new Uint8Array(event.data as ArrayBuffer));
        flush();
        pump();
      } catch (error) {
        fail(asError(error));
      }
    };
    socket.onclose = () => {
      if (settled) return;
      if (!session) {
        fail(new Error("The website could not be reached."));
        return;
      }
      try {
        session.finish();
      } catch {
        // A stream cut without close_notify: keep what arrived.
      }
      reader.push(session.takePlaintext());
      reader.end();
      succeed();
    };
  });
}

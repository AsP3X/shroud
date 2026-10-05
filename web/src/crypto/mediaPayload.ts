/** Sealed JSON inside a `content_type = media` message (matches iOS `MediaMessagePayload`). */
import { parseLinkPreview, type LinkPreview } from "../links";
import { parseReplyRef, replyRefWire, type ReplyRef } from "../reply";

export type MediaPayload = {
  t: string;
  mime: string;
  w: number;
  h: number;
  k: string;
  c?: string | null;
  d?: number | null;
  wf?: string | null;
  th?: string | null;
  s?: number | null;
  /** The message this one replies to; absent on payloads sealed before replies existed. */
  re?: Record<string, string> | null;
  /**
   * Link preview of a `t: "link"` message: a text message whose preview has a large image,
   * sent as media so the image can be the (encrypted) blob. `c` is the full message text.
   */
  lp?: Record<string, unknown> | null;
  /**
   * File name of a `t: "file"` message (docs/file-sharing.md §1), cleaned by the sender;
   * receivers clean it again before showing or saving it.
   */
  n?: string | null;
  /**
   * Page count of a PDF sent as a file (docs/file-sharing.md §1, §10), when the sender could read
   * it: an integer ≥ 1. Absent on other types and on payloads from before §10.
   */
  pg?: number | null;
};

/** `pg` as sealed, or null when it is missing or not a page count. */
function pageCountOf(raw: unknown): number | null {
  return typeof raw === "number" && Number.isInteger(raw) && raw >= 1 ? raw : null;
}

export function parseMediaPayload(raw: string): MediaPayload | null {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{")) return null;
  try {
    const parsed = JSON.parse(trimmed) as Partial<MediaPayload>;
    if (typeof parsed.t !== "string" || typeof parsed.k !== "string") return null;
    return {
      t: parsed.t,
      mime: typeof parsed.mime === "string" ? parsed.mime : "application/octet-stream",
      w: typeof parsed.w === "number" ? parsed.w : 0,
      h: typeof parsed.h === "number" ? parsed.h : 0,
      k: parsed.k,
      c: parsed.c ?? null,
      d: typeof parsed.d === "number" ? parsed.d : null,
      wf: parsed.wf ?? null,
      th: parsed.th ?? null,
      s: typeof parsed.s === "number" ? parsed.s : null,
      re: (parsed as { re?: unknown }).re as Record<string, string> | undefined ?? null,
      lp: (parsed as { lp?: unknown }).lp as Record<string, unknown> | undefined ?? null,
      n: typeof parsed.n === "string" ? parsed.n : null,
      pg: pageCountOf((parsed as { pg?: unknown }).pg),
    };
  } catch {
    return null;
  }
}

/**
 * A shared file, whatever its `mime` says. Checked before the MIME sniffing below: a PDF, an
 * `audio/` or `video/` file sent as a file must never read as a voice note or a clip.
 */
export function isFilePayload(payload: MediaPayload): boolean {
  return payload.t === "file";
}

export function isVoicePayload(payload: MediaPayload): boolean {
  if (payload.t === "voice") return true;
  if (payload.t === "image" || payload.t === "video" || payload.t === "link" || payload.t === "file") return false;
  return payload.mime.startsWith("audio/");
}

export function isVideoPayload(payload: MediaPayload): boolean {
  if (payload.t === "video") return true;
  if (payload.t === "image" || payload.t === "voice" || payload.t === "link" || payload.t === "file") return false;
  return payload.mime.startsWith("video/");
}

/** The preview of a `t: "link"` payload; null for photos, videos, voice notes and files. */
export function payloadLinkPreview(payload: MediaPayload): LinkPreview | null {
  return payload.t === "link" ? parseLinkPreview(payload.lp) : null;
}

/**
 * Largest transcript sealed or accepted, in UTF-8 bytes (matches iOS
 * `MessageAnnotation.maxTranscriptBytes`). v3 seals DR + peer + self, so a
 * 16 KB transcript is too big for the 64 KB server cap; senders drop `c` from
 * the media payload when the envelope would overflow.
 */
export const MAX_TRANSCRIPT_BYTES = 16 * 1024;

/** Server cap is 64 KiB decoded. v3 carries DR + peer box + self box. */
export const MAX_SEALED_ENVELOPE_BYTES = 60 * 1024;
/** Plaintext budget before sealing; thumbs and long transcripts are the usual offenders. */
export const MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES = 12 * 1024;

/** Trims to `MAX_TRANSCRIPT_BYTES` at a code-point boundary, marking the cut with "…". */
export function clampTranscript(text: string): string {
  const trimmed = text.trim();
  if (new TextEncoder().encode(trimmed).length <= MAX_TRANSCRIPT_BYTES) return trimmed;
  const budget = MAX_TRANSCRIPT_BYTES - 3; // "…" is three bytes
  let used = 0;
  let kept = "";
  for (const char of trimmed) {
    const code = char.codePointAt(0) ?? 0;
    const size = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
    if (used + size > budget) break;
    used += size;
    kept += char;
  }
  return `${kept.trimEnd()}…`;
}

export function encodeWaveform(buckets: number[]): string | null {
  if (!buckets.length) return null;
  let bin = "";
  for (const b of buckets) bin += String.fromCharCode(Math.max(0, Math.min(255, b | 0)));
  return btoa(bin);
}

/** Averages a 0…1 envelope into `buckets` bytes of 0…255 (matches iOS VoiceWaveform.downsample). */
export function downsampleEnvelope(envelope: number[], buckets: number): number[] {
  if (buckets <= 0 || envelope.length === 0) return [];
  const out: number[] = [];
  const stride = envelope.length / buckets;
  for (let bucket = 0; bucket < buckets; bucket++) {
    const start = Math.floor(bucket * stride);
    const end = Math.max(start + 1, Math.floor((bucket + 1) * stride));
    const from = Math.min(start, envelope.length - 1);
    const to = Math.min(end, envelope.length);
    let sum = 0;
    for (let i = from; i < to; i++) sum += envelope[i];
    const mean = to > from ? sum / (to - from) : 0;
    out.push(Math.max(8, Math.min(255, Math.round(mean * 255))));
  }
  return out;
}

export function decodeWaveform(base64: string | null | undefined): number[] | null {
  if (!base64) return null;
  try {
    const bin = atob(base64.trim());
    if (!bin) return null;
    const out = new Array<number>(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

export function waveformUsable(buckets: number[] | null | undefined): boolean {
  if (!buckets || buckets.length === 0) return false;
  let low = 255;
  let high = 0;
  for (const b of buckets) {
    if (b < low) low = b;
    if (b > high) high = b;
  }
  return high - low >= 8;
}

export function normalizeWaveform(buckets: number[]): number[] {
  return buckets.map((b) => Math.min(1, Math.max(0, b / 255)));
}

export function resampleWaveform(samples: number[], count: number): number[] {
  if (count <= 0) return [];
  if (samples.length === count) return samples;
  if (samples.length === 0) return Array.from({ length: count }, () => 0.1);
  return Array.from({ length: count }, (_, index) => {
    const start = Math.floor((index * samples.length) / count);
    const end = Math.max(start + 1, Math.floor(((index + 1) * samples.length) / count));
    const from = Math.min(start, samples.length - 1);
    const to = Math.min(end, samples.length);
    if (to <= from) return samples[from];
    let sum = 0;
    for (let i = from; i < to; i++) sum += samples[i];
    return sum / (to - from);
  });
}

/** Stable stand-in for messages sealed before waveforms existed, or with a flat envelope. */
export function placeholderWaveform(id: string, count: number): number[] {
  let hash = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    hash ^= id.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return Array.from({ length: count }, (_, index) => {
    const position = index / Math.max(count - 1, 1);
    const envelope = Math.sin(position * Math.PI) * 0.45 + 0.55;
    hash = Math.imul(hash ^ (hash >>> 16), 0x7feb352d);
    hash = Math.imul(hash ^ (hash >>> 15), 0x846ca68b);
    const jitter = ((hash >>> 0) % 1000) / 1000;
    return Math.min(1, Math.max(0.12, envelope * (0.45 + jitter * 0.55)));
  });
}

export function formatVoiceTime(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return `${minutes}:${rest.toString().padStart(2, "0")}`;
}

/** `0:07,32` — centiseconds so the recording timer visibly runs. */
export function formatRecordingTime(seconds: number): string {
  const total = Math.max(0, seconds);
  const minutes = Math.floor(total / 60);
  const secs = Math.floor(total) % 60;
  const centis = Math.floor((total - Math.floor(total)) * 100);
  return `${minutes}:${secs.toString().padStart(2, "0")},${centis.toString().padStart(2, "0")}`;
}

/** The quote sealed with a media message, if it is one. */
export function payloadReply(payload: MediaPayload): ReplyRef | null {
  return parseReplyRef(payload.re);
}

/** Adds the quote to a media payload about to be sealed (no-op without one). */
export function withReply<T extends MediaPayload>(payload: T, replyTo: ReplyRef | null | undefined): T {
  if (!replyTo) return payload;
  return { ...payload, re: replyRefWire(replyTo) };
}

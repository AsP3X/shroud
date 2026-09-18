/** Sealed JSON inside a `content_type = media` message (matches iOS `MediaMessagePayload`). */

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
};

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
    };
  } catch {
    return null;
  }
}

export function isVoicePayload(payload: MediaPayload): boolean {
  return payload.t === "voice";
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

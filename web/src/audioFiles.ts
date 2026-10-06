/**
 * Audio files (docs/file-sharing.md §11): songs, podcasts and recordings sent as `t: "file"`,
 * which play in the chat. The tag cleaning, the display title, the duration labels and the
 * bubble's lines every client shares; §9's vectors pin them (file.selftest.ts).
 */
import { formatBytes } from "./format";
import { fileExtension, fileTypeOf, sanitizeFileName } from "./files";

/** Longest tag text kept, in code points (§11.2). */
const MAX_TAG = 200;
/** Files at least this long get the speed chip (§11.5). */
export const SPEED_CHIP_MIN_MS = 10 * 60 * 1000;

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i);
}

/** §5 step 3: controls, invisible formatting and the bidi overrides. */
const REMOVED = new Set([
  ...range(0x00, 0x08),
  ...range(0x0e, 0x1f),
  ...range(0x7f, 0x9f),
  0xad,
  0x061c,
  0x180e,
  ...range(0x200b, 0x200f),
  ...range(0x202a, 0x202e),
  ...range(0x2060, 0x2064),
  ...range(0x2066, 0x206f),
  0x2028,
  0x2029,
  0xfeff,
  ...range(0xfff9, 0xfffb),
]);
/** §5 step 5: every kind of space. */
const SPACES = new Set([...range(0x09, 0x0d), 0x20, 0xa0, 0x1680, ...range(0x2000, 0x200a), 0x202f, 0x205f, 0x3000]);

/**
 * Cleans a title or artist tag (§11.2), on the sender before sealing and on the receiver again:
 * NFC, the §5 step-3 code points dropped, each run of §5 spaces one U+0020 (none at the start),
 * cut to 200 code points, trailing spaces trimmed. Null when nothing is left.
 */
export function cleanTagText(raw: string | null | undefined): string | null {
  if (typeof raw !== "string" || raw.length === 0) return null;
  const cps: number[] = [];
  for (const char of raw.normalize("NFC")) {
    const cp = char.codePointAt(0)!;
    if (REMOVED.has(cp)) continue;
    if (SPACES.has(cp)) {
      if (cps.length > 0 && cps[cps.length - 1] !== 0x20) cps.push(0x20);
    } else cps.push(cp);
    if (cps.length > MAX_TAG) break;
  }
  if (cps.length > MAX_TAG) cps.length = MAX_TAG;
  while (cps.length > 0 && cps[cps.length - 1] === 0x20) cps.pop();
  return cps.length > 0 ? String.fromCodePoint(...cps) : null;
}

/**
 * What the bubble's quote, the chat list and the notification call an audio file (§11.2):
 * `{ti} – {ar}` when both are there, else `ti`, else the cleaned file name.
 */
export function audioDisplayTitle(title: string | null | undefined, artist: string | null | undefined, name: string): string {
  const ti = cleanTagText(title);
  const ar = cleanTagText(artist);
  if (ti && ar) return `${ti} – ${ar}`;
  return ti ?? sanitizeFileName(name);
}

/** `m:ss`, from one hour `h:mm:ss`, of whole seconds. */
function clock(seconds: number): string {
  const s = Math.max(0, Math.trunc(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rest = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${rest}` : `${m}:${rest}`;
}

/** A total (`d`): rounded to whole seconds (§11.2). */
export function formatAudioTotal(ms: number): string {
  return clock(Math.round(ms / 1000));
}

/** An elapsed time: floored, so it never shows a second that hasn't played yet. */
export function formatAudioElapsed(ms: number): string {
  return clock(Math.floor(ms / 1000));
}

/** Whether a cleaned file name is one of §4's audio types. */
export function isAudioFileName(cleanedName: string | null | undefined): boolean {
  return fileTypeOf(cleanedName ?? "")?.category === "audio";
}

/** `d` as sealed, or null when it is missing or not a duration (ms, ≥ 1). */
export function audioDurationOf(raw: unknown): number | null {
  if (typeof raw !== "number" || !Number.isFinite(raw)) return null;
  const ms = Math.round(raw);
  return ms >= 1 ? ms : null;
}

/** The `audio/…` type the browser is asked about: Opus names its codec, the rest the container. */
export function playableMimeOf(cleanedName: string): string | null {
  const type = fileTypeOf(cleanedName);
  if (type?.category !== "audio") return null;
  return type.ext === "opus" ? 'audio/ogg; codecs="opus"' : type.mime;
}

/**
 * The detail line under an audio file's title (§11.4), outside transfers and failed sends:
 * the active file `{elapsed} / {duration}`; else `{ar} · {duration}` (plus the size while it is
 * not on this device); else `{duration} · {size} · {EXT}`. A missing duration drops its part.
 */
export function audioDetailLine(opts: {
  name: string;
  artist: string | null | undefined;
  durationMs: number | null | undefined;
  size: number | null | undefined;
  onDevice: boolean;
  /** Playing, or paused part-way: the position, in ms. */
  elapsedMs?: number | null;
}): string {
  const duration = opts.durationMs != null && opts.durationMs >= 1 ? formatAudioTotal(opts.durationMs) : null;
  if (opts.elapsedMs != null) {
    const elapsed = formatAudioElapsed(opts.elapsedMs);
    return duration ? `${elapsed} / ${duration}` : elapsed;
  }
  const size = opts.size != null ? formatBytes(opts.size) : null;
  const artist = cleanTagText(opts.artist);
  if (artist) return [artist, duration, opts.onDevice ? null : size].filter(Boolean).join(" · ");
  const ext = fileExtension(opts.name).toUpperCase();
  return [duration, size, ext || null].filter(Boolean).join(" · ");
}

/** The composer row's line (§11.3): `{ar} · {duration} · {size} · {EXT}`, leaving out what is missing. */
export function audioComposerLine(name: string, artist: string | null | undefined, durationMs: number | null | undefined, size: number): string {
  const ext = fileExtension(name).toUpperCase();
  return [
    cleanTagText(artist),
    durationMs != null && durationMs >= 1 ? formatAudioTotal(durationMs) : null,
    formatBytes(size),
    ext || null,
  ]
    .filter(Boolean)
    .join(" · ");
}

/** `Audio, {title}, {artist}, {duration}` without the missing parts (§11.4). */
export function audioAccessibilityLabel(title: string, artist: string | null | undefined, durationMs: number | null | undefined): string {
  return [
    "Audio",
    title,
    cleanTagText(artist),
    durationMs != null && durationMs >= 1 ? formatAudioTotal(durationMs) : null,
  ]
    .filter(Boolean)
    .join(", ");
}

/** The web's "can't play" line (§11.4). */
export const CANT_PLAY_HERE = "Can't play in this browser";

/** The speed chip's steps and its label (§11.5, §11.6). */
export const AUDIO_RATES = [1, 1.5, 2] as const;
export function rateLabel(rate: number): string {
  return `${rate}×`;
}

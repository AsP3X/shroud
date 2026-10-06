/**
 * What an audio file's sender reads from it (docs/file-sharing.md §11.2): the duration from an
 * `<audio preload="metadata">` on the file's object URL, the title and artist tags, and the cover
 * art as a square JPEG of at most 6 KB. All of it within 2 s of the first ask; what isn't read by
 * then goes out without. The composer asks first, the send reuses the same answer.
 */
import { cleanTagText } from "../audioFiles";
import { readAudioTags, type AudioCover } from "./audioTags";
import { MAX_THUMB_BYTES } from "./envelopePreview";

export type AudioSendInfo = {
  /** `d`, in ms (≥ 1), or null. */
  durationMs: number | null;
  /** `ti` / `ar`, already cleaned. */
  title: string | null;
  artist: string | null;
  /** `th` (square JPEG ≤ 6 KB) and its edge as `w` / `h`; null without a usable cover. */
  thumb: Uint8Array | null;
  width: number;
  height: number;
};

/** What isn't read by then goes out without (§11.2). */
const SEND_BUDGET_MS = 2000;
const COVER_EDGE = 160;
const COVER_MIN_EDGE = 64;
const COVER_QUALITY = 0.6;
const COVER_MIN_QUALITY = 0.25;

const asked = new WeakMap<Blob, Promise<AudioSendInfo>>();

/** The duration `<audio preload="metadata">` reads, in ms; null when the browser can't open it. */
function readDuration(file: Blob, give: (ms: number | null) => void): () => void {
  const url = URL.createObjectURL(file);
  const probe = document.createElement("audio");
  let done = false;
  const finish = (ms: number | null) => {
    if (done) return;
    done = true;
    probe.removeAttribute("src");
    probe.load();
    URL.revokeObjectURL(url);
    give(ms);
  };
  probe.preload = "metadata";
  probe.muted = true;
  probe.addEventListener("loadedmetadata", () => {
    const seconds = probe.duration;
    finish(Number.isFinite(seconds) && seconds > 0 ? Math.max(1, Math.round(seconds * 1000)) : null);
  });
  probe.addEventListener("error", () => finish(null));
  probe.src = url;
  return () => finish(null);
}

function jpegOf(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
}

/**
 * The cover centre-cropped to a square, at 160 px (never upscaled), JPEG: shrunk by 0.8 and at a
 * lower quality until it fits 6 KB; null below 64 px or when the picture doesn't decode.
 */
export async function coverJpeg(cover: AudioCover): Promise<{ jpeg: Uint8Array; edge: number } | null> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(cover.blob);
  } catch {
    return null;
  }
  try {
    const side = Math.min(bitmap.width, bitmap.height);
    if (side < COVER_MIN_EDGE) return null;
    const sx = (bitmap.width - side) / 2;
    const sy = (bitmap.height - side) / 2;
    let edge = Math.min(COVER_EDGE, side);
    let quality = COVER_QUALITY;
    while (Math.round(edge) >= COVER_MIN_EDGE) {
      const e = Math.round(edge);
      const canvas = document.createElement("canvas");
      canvas.width = e;
      canvas.height = e;
      const ctx = canvas.getContext("2d");
      if (!ctx) return null;
      ctx.fillStyle = "#000000";
      ctx.fillRect(0, 0, e, e);
      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(bitmap, sx, sy, side, side, 0, 0, e, e);
      const blob = await jpegOf(canvas, quality);
      if (blob && blob.size <= MAX_THUMB_BYTES) return { jpeg: new Uint8Array(await blob.arrayBuffer()), edge: e };
      edge *= 0.8;
      quality = Math.max(COVER_MIN_QUALITY, quality - 0.1);
    }
    return null;
  } finally {
    bitmap.close();
  }
}

/** Reads everything §11.2 asks for, for at most 2 s; never rejects. */
function readAll(file: Blob): Promise<AudioSendInfo> {
  return new Promise<AudioSendInfo>((resolve) => {
    const info: AudioSendInfo = { durationMs: null, title: null, artist: null, thumb: null, width: 0, height: 0 };
    let durationDone = false;
    let tagsDone = false;
    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      cancelDuration();
      resolve({ ...info });
    };
    const maybeDone = () => {
      if (durationDone && tagsDone) settle();
    };
    const timer = window.setTimeout(settle, SEND_BUDGET_MS);
    const cancelDuration = readDuration(file, (ms) => {
      if (settled) return;
      info.durationMs = ms;
      durationDone = true;
      maybeDone();
    });
    void (async () => {
      try {
        const tags = await readAudioTags(file);
        if (settled) return;
        info.title = cleanTagText(tags.title);
        info.artist = cleanTagText(tags.artist);
        if (tags.cover) {
          const cover = await coverJpeg(tags.cover);
          if (settled) return;
          if (cover) {
            info.thumb = cover.jpeg;
            info.width = cover.edge;
            info.height = cover.edge;
          }
        }
      } catch {
        /* sent without the tags */
      } finally {
        tagsDone = true;
        maybeDone();
      }
    })();
  });
}

/** The §11.2 fields of an audio file; asked once per picked file (the composer, then the send). */
export function audioSendInfo(file: Blob): Promise<AudioSendInfo> {
  let task = asked.get(file);
  if (!task) {
    task = readAll(file);
    asked.set(file, task);
  }
  return task;
}

import { api } from "../api/client";
import { aesGcmOpen } from "../crypto/aes";
import { b64ToBytes } from "../crypto/bytes";
import { loadMediaBlob, saveMediaBlob } from "../crypto/mediaCache";
import type { ChatMessage } from "../messaging";
import { decodesNatively, heifToJpeg, isHeif, resetHeicDecoder } from "./heic";
import { setTransfer } from "./transfers";

/**
 * Decrypted photos, ready to draw. Only ciphertext is cached on disk (IndexedDB);
 * the plaintext lives in object URLs for as long as the tab needs it.
 */
export type LoadedImage = {
  /** What `<img>` draws: the photo itself, or a JPEG of it when this browser can't draw the original (HEIC). */
  url: string;
  /** The photo as it was sent, for saving. */
  original: Blob;
};

/** AES-GCM nonce + tag around every sealed blob. */
const SEALED_OVERHEAD = 28;
/** Decrypted photos kept in memory; the oldest are released past this. */
const MAX_LOADED = 40;

const loaded = new Map<string, LoadedImage>();
const loads = new Map<string, Promise<LoadedImage | null>>();
/** Bumped on `forgetImages` so a download that finishes after lock is dropped. */
let epoch = 0;

/** Sealed photos share the media store with voice notes; keep their keys apart. */
function sealedKey(messageId: string): string {
  return `sealed:${messageId.toLowerCase()}`;
}

export function blobOf(bytes: Uint8Array, type: string): Blob {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return new Blob([copy], { type });
}

function remember(messageId: string, image: LoadedImage): LoadedImage {
  const key = messageId.toLowerCase();
  const previous = loaded.get(key);
  if (previous && previous.url !== image.url) URL.revokeObjectURL(previous.url);
  loaded.delete(key);
  loaded.set(key, image);
  for (const [oldKey, old] of loaded) {
    if (loaded.size <= MAX_LOADED) break;
    loaded.delete(oldKey);
    URL.revokeObjectURL(old.url);
  }
  return image;
}

/** The photo if it's already decrypted, so a remounted bubble paints on its first frame. */
export function peekImage(messageId: string): LoadedImage | null {
  return loaded.get(messageId.toLowerCase()) ?? null;
}

/** A photo we're sending: show it straight away from the bytes we already have. */
export function adoptImage(messageId: string, bytes: Uint8Array, mime: string): LoadedImage {
  const original = blobOf(bytes, mime);
  return remember(messageId, { url: URL.createObjectURL(original), original });
}

/** The server re-keys sent messages; move the photo along so the bubble never blinks. */
export function rekeyImage(fromId: string, toId: string): void {
  const image = loaded.get(fromId.toLowerCase());
  if (!image) return;
  loaded.delete(fromId.toLowerCase());
  remember(toId, image);
}

/** Keep the ciphertext of a photo we sent, so reopening the chat never downloads it again. */
export function cacheSealedImage(messageId: string, sealed: Uint8Array): Promise<void> {
  return saveMediaBlob(sealedKey(messageId), sealed);
}

/** Drops every decrypted photo (leaving a chat session, locking, logging out). */
export function forgetImages(): void {
  epoch += 1;
  for (const image of loaded.values()) URL.revokeObjectURL(image.url);
  loaded.clear();
  loads.clear();
  resetHeicDecoder();
}

async function open(message: ChatMessage, token: string): Promise<LoadedImage> {
  const started = epoch;
  const { mediaObjectId, mediaKey } = message;
  if (!mediaObjectId || !mediaKey) throw new Error("This photo has no key.");
  const key = b64ToBytes(mediaKey);
  const download = async () => {
    const expected = message.mediaBytes ? message.mediaBytes + SEALED_OVERHEAD : undefined;
    setTransfer(message.id, { direction: "down", loaded: 0, total: expected ?? null });
    const fetched = await api.getMediaContent(
      token,
      mediaObjectId,
      (done, total) => setTransfer(message.id, { direction: "down", loaded: done, total }),
      expected,
    );
    void saveMediaBlob(sealedKey(message.id), fetched);
    return fetched;
  };
  const cached = await loadMediaBlob(sealedKey(message.id));
  let bytes: Uint8Array;
  if (cached?.length) {
    try {
      bytes = await aesGcmOpen(key, cached);
    } catch {
      // A damaged cache entry must not strand the photo: fetch the real thing once.
      bytes = await aesGcmOpen(key, await download());
    }
  } else {
    bytes = await aesGcmOpen(key, await download());
  }
  const original = blobOf(bytes, message.mime || "image/jpeg");
  let display = original;
  // iPhones send library originals, usually HEIC; only Safari draws those itself.
  if (isHeif(bytes, message.mime) && !(await decodesNatively(original))) {
    display = (await heifToJpeg(bytes)).blob;
  }
  if (started !== epoch) {
    throw new Error("Photo discarded.");
  }
  return remember(message.id, { url: URL.createObjectURL(display), original });
}

/**
 * Downloads (or reads the cached ciphertext), decrypts and, when needed, converts
 * a photo. Deduped per message; resolves null when it can't be shown.
 */
export function ensureImage(message: ChatMessage, token: string): Promise<LoadedImage | null> {
  const key = message.id.toLowerCase();
  const ready = loaded.get(key);
  if (ready) return Promise.resolve(ready);
  const running = loads.get(key);
  if (running) return running;
  const task = open(message, token)
    .catch((err: unknown) => {
      console.warn("Could not open photo:", err);
      return null;
    })
    .finally(() => {
      loads.delete(key);
      setTransfer(message.id, null);
    });
  loads.set(key, task);
  return task;
}

/** A file name for "Save": `Shroud 2026-09-19 14.32.05.jpg`. */
export function photoFileName(message: ChatMessage, original: Blob): string {
  const date = new Date(message.createdAt);
  const pad = (n: number) => n.toString().padStart(2, "0");
  const stamp = Number.isNaN(date.getTime())
    ? "photo"
    : `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}.${pad(date.getMinutes())}.${pad(date.getSeconds())}`;
  const type = original.type.toLowerCase();
  const ext = type.includes("png")
    ? "png"
    : type.includes("heic") || type.includes("heif")
      ? "heic"
      : type.includes("webp")
        ? "webp"
        : type.includes("gif")
          ? "gif"
          : "jpg";
  return `Shroud ${stamp}.${ext}`;
}

import { useSyncExternalStore } from "react";
import { api } from "../api/client";
import { aesGcmOpen, aesGcmSeal } from "../crypto/aes";
import { b64ToBytes } from "../crypto/bytes";
import { hasMediaBlob, loadMediaBlob, saveMediaBlob } from "../crypto/mediaCache";
import type { ChatMessage } from "../messaging";
import { fitEdge } from "./envelopePreview";
import { blobOf } from "./images";
import { setTransfer } from "./transfers";

/**
 * Videos on this device. Like photos, only ciphertext is stored (IndexedDB): the
 * clip under `sealed:<id>`, and a sharp poster frame under `poster:<id>`, sealed
 * with the clip's own key. Decrypted clips live in memory while they're needed.
 *
 * Unlike photos, a clip is never fetched on scroll — it can be 24 MB. It comes
 * down when someone presses play or the download button, as on iOS.
 */
export type LoadedVideo = {
  /** The clip itself; the player makes its own object URL from it. */
  blob: Blob;
};

/** What a bubble needs to know about a clip, without decrypting it. */
export type VideoState = {
  /** Object URL of a sharp poster frame, once there is one. */
  poster: string | null;
  /** Decrypted and in memory: it plays at once. */
  ready: boolean;
  /** Its ciphertext is on this device: playing needs no download. */
  stored: boolean;
};

/** AES-GCM nonce + tag around every sealed blob. */
const SEALED_OVERHEAD = 28;
/** Decrypted clips kept in memory (up to 24 MB each); the least recent go first. */
const MAX_LOADED = 3;
/** Posters are small; keep plenty so scrolling back never flashes the blurry preview. */
const MAX_POSTERS = 120;
const POSTER_EDGE = 720;
const EMPTY: VideoState = { poster: null, ready: false, stored: false };

const loaded = new Map<string, LoadedVideo>();
const loads = new Map<string, { task: Promise<LoadedVideo | null>; abort: AbortController }>();
const states = new Map<string, VideoState>();
/** Ids already checked against the disk this session. */
const looked = new Set<string>();
const listeners = new Set<() => void>();
/** Bumped by `forgetVideos`, so work that finishes after a lock is dropped. */
let epoch = 0;

function key(id: string): string {
  return id.toLowerCase();
}

function sealedKey(id: string): string {
  return `sealed:${key(id)}`;
}

function posterKey(id: string): string {
  return `poster:${key(id)}`;
}

function update(id: string, patch: Partial<VideoState>): void {
  const current = states.get(key(id)) ?? EMPTY;
  const next = { ...current, ...patch };
  if (next.poster === current.poster && next.ready === current.ready && next.stored === current.stored) return;
  if (current.poster && next.poster !== current.poster) URL.revokeObjectURL(current.poster);
  states.delete(key(id));
  states.set(key(id), next);
  // Past the cap, forget the oldest posters (a clip that is still in memory keeps its own).
  let posters = 0;
  for (const state of states.values()) if (state.poster) posters++;
  for (const [oldKey, old] of states) {
    if (posters <= MAX_POSTERS) break;
    if (!old.poster || loaded.has(oldKey)) continue;
    URL.revokeObjectURL(old.poster);
    states.set(oldKey, { ...old, poster: null });
    posters--;
  }
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getVideoState(id: string): VideoState {
  return states.get(key(id)) ?? EMPTY;
}

export function useVideoState(id: string): VideoState {
  return useSyncExternalStore(
    subscribe,
    () => getVideoState(id),
    () => EMPTY,
  );
}

function remember(id: string, video: LoadedVideo): LoadedVideo {
  loaded.delete(key(id));
  loaded.set(key(id), video);
  for (const oldKey of loaded.keys()) {
    if (loaded.size <= MAX_LOADED) break;
    loaded.delete(oldKey);
    update(oldKey, { ready: false });
  }
  update(id, { ready: true });
  return video;
}

export function peekVideo(id: string): LoadedVideo | null {
  return loaded.get(key(id)) ?? null;
}

/** A clip we're sending: it plays from the bytes we already have. */
export function adoptVideo(id: string, bytes: Uint8Array, poster: Blob | null): void {
  remember(id, { blob: blobOf(bytes, "video/mp4") });
  if (poster) adoptPoster(id, poster);
}

/** Shows this frame on the bubble (a clip being compressed, or one just decrypted). */
export function adoptPoster(id: string, poster: Blob): void {
  update(id, { poster: URL.createObjectURL(poster) });
}

/** The server re-keys sent messages; move the clip along so the bubble never blinks. */
export function rekeyVideo(fromId: string, toId: string): void {
  const video = loaded.get(key(fromId));
  const state = states.get(key(fromId));
  loaded.delete(key(fromId));
  states.delete(key(fromId));
  if (video) loaded.set(key(toId), video);
  if (state) states.set(key(toId), state);
  if (video || state) for (const listener of listeners) listener();
}

/** Keeps the ciphertext of a clip we sent, so it never has to come down again. */
export async function cacheSealedVideo(id: string, sealed: Uint8Array): Promise<void> {
  await saveMediaBlob(sealedKey(id), sealed);
  update(id, { stored: true });
}

/** Keeps the poster, sealed with the clip's key, so it is sharp after a reload too. */
export async function cacheSealedPoster(id: string, mediaKey: Uint8Array, jpeg: Blob): Promise<void> {
  try {
    const sealed = await aesGcmSeal(mediaKey, new Uint8Array(await jpeg.arrayBuffer()));
    await saveMediaBlob(posterKey(id), sealed);
  } catch {
    /* best effort: the next play makes another */
  }
}

/**
 * Checks the disk for this clip once per session: whether its ciphertext is
 * here (play instead of download) and its sealed poster. Cheap — a key count and
 * a small decrypt — so bubbles call it as they scroll into view.
 */
export function lookForVideo(message: ChatMessage): void {
  const id = key(message.id);
  if (looked.has(id) || message.id.startsWith("pending:")) return;
  looked.add(id);
  const started = epoch;
  void (async () => {
    const stored = await hasMediaBlob(sealedKey(id));
    if (started !== epoch) return;
    if (stored) update(id, { stored: true });
    if (getVideoState(id).poster || !message.mediaKey) return;
    const sealed = await loadMediaBlob(posterKey(id));
    if (!sealed?.length || started !== epoch) return;
    try {
      const jpeg = await aesGcmOpen(b64ToBytes(message.mediaKey), sealed);
      if (started === epoch && !getVideoState(id).poster) adoptPoster(id, blobOf(jpeg, "image/jpeg"));
    } catch {
      /* stale entry; a play makes a new one */
    }
  })();
}

function once(target: HTMLVideoElement, event: string, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      cleanup();
      reject(new Error(`${event} timed out`));
    }, timeoutMs);
    const onEvent = () => {
      cleanup();
      resolve();
    };
    const onError = () => {
      cleanup();
      reject(new Error("This browser can’t decode the clip."));
    };
    const cleanup = () => {
      window.clearTimeout(timer);
      target.removeEventListener(event, onEvent);
      target.removeEventListener("error", onError);
    };
    target.addEventListener(event, onEvent);
    target.addEventListener("error", onError);
  });
}

/** The first frame as a JPEG, drawn by the browser's own player (no worker needed to watch). */
async function grabPoster(video: Blob): Promise<Blob | null> {
  const url = URL.createObjectURL(video);
  const element = document.createElement("video");
  element.muted = true;
  element.playsInline = true;
  element.preload = "auto";
  try {
    const loadedData = once(element, "loadeddata", 10_000);
    element.src = url;
    await loadedData;
    // Safari paints nothing into a canvas until a seek has landed.
    const seeked = once(element, "seeked", 5_000);
    element.currentTime = Math.min(0.05, (element.duration || 0) / 2);
    await seeked;
    if (!element.videoWidth || !element.videoHeight) return null;
    const size = fitEdge(element.videoWidth, element.videoHeight, POSTER_EDGE);
    const canvas = document.createElement("canvas");
    canvas.width = size.width;
    canvas.height = size.height;
    canvas.getContext("2d")?.drawImage(element, 0, 0, size.width, size.height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.82));
    canvas.width = 0;
    canvas.height = 0;
    return blob;
  } catch {
    return null;
  } finally {
    element.removeAttribute("src");
    element.load();
    URL.revokeObjectURL(url);
  }
}

async function open(message: ChatMessage, token: string, signal: AbortSignal): Promise<LoadedVideo> {
  const started = epoch;
  const { mediaObjectId, mediaKey } = message;
  if (!mediaObjectId || !mediaKey) throw new Error("This video has no key.");
  const fileKey = b64ToBytes(mediaKey);
  const download = async () => {
    const expected = message.mediaBytes ? message.mediaBytes + SEALED_OVERHEAD : undefined;
    const report = (loadedBytes: number, total: number | null) =>
      setTransfer(message.id, { direction: "down", phase: "transferring", loaded: loadedBytes, total });
    report(0, expected ?? null);
    const fetched = await api.getMediaContent(token, mediaObjectId, report, expected, signal);
    setTransfer(message.id, { direction: "down", phase: "finishing", loaded: 0, total: null });
    void saveMediaBlob(sealedKey(message.id), fetched).then(() => {
      if (started === epoch) update(message.id, { stored: true });
    });
    return fetched;
  };
  const cached = await loadMediaBlob(sealedKey(message.id));
  let bytes: Uint8Array;
  if (cached?.length) {
    setTransfer(message.id, { direction: "down", phase: "finishing", loaded: 0, total: null });
    try {
      bytes = await aesGcmOpen(fileKey, cached);
    } catch {
      // A damaged cache entry must not strand the clip: fetch the real thing once.
      bytes = await aesGcmOpen(fileKey, await download());
    }
  } else {
    bytes = await aesGcmOpen(fileKey, await download());
  }
  if (started !== epoch) throw new Error("Video discarded.");
  const video = remember(message.id, { blob: blobOf(bytes, message.mime || "video/mp4") });
  if (!getVideoState(message.id).poster) {
    void grabPoster(video.blob).then((poster) => {
      if (!poster || started !== epoch) return;
      if (!getVideoState(message.id).poster) adoptPoster(message.id, poster);
      void cacheSealedPoster(message.id, fileKey, poster);
    });
  }
  return video;
}

/**
 * Downloads (or reads the stored ciphertext) and decrypts a clip, with progress
 * on the transfer store. Deduped per message; resolves null when it can't be
 * had, or when `cancelVideoDownload` stopped it.
 */
export function ensureVideo(message: ChatMessage, token: string): Promise<LoadedVideo | null> {
  const id = key(message.id);
  const ready = loaded.get(id);
  if (ready) {
    remember(id, ready);
    return Promise.resolve(ready);
  }
  const running = loads.get(id);
  if (running) return running.task;
  const abort = new AbortController();
  const task = open(message, token, abort.signal)
    .catch((err: unknown) => {
      if (!abort.signal.aborted) console.warn("Could not open video:", err);
      return null;
    })
    .finally(() => {
      if (loads.get(id)?.task === task) loads.delete(id);
      setTransfer(message.id, null);
    });
  loads.set(id, { task, abort });
  return task;
}

/** Stops a download in flight (the ring's ✕). */
export function cancelVideoDownload(id: string): void {
  loads.get(key(id))?.abort.abort();
}

/** Drops every decrypted clip and poster (locking, logging out, clearing the cache). */
export function forgetVideos(): void {
  epoch += 1;
  for (const { abort } of loads.values()) abort.abort();
  loads.clear();
  loaded.clear();
  for (const state of states.values()) if (state.poster) URL.revokeObjectURL(state.poster);
  states.clear();
  looked.clear();
  for (const listener of listeners) listener();
}

/** A file name for "Save": `Shroud 2026-09-19 14.32.05.mp4`. */
export function videoFileName(message: ChatMessage): string {
  const date = new Date(message.createdAt);
  const pad = (n: number) => n.toString().padStart(2, "0");
  const stamp = Number.isNaN(date.getTime())
    ? "video"
    : `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}.${pad(date.getMinutes())}.${pad(date.getSeconds())}`;
  const ext = (message.mime || "").includes("quicktime") ? "mov" : "mp4";
  return `Shroud ${stamp}.${ext}`;
}

import type { EncodedParts, VideoReply, VideoRequest } from "./videoWorker";
import { VideoTooLongError, type VideoProbe, type VideoQuality, type VideoTrim } from "./videoPlan";

/**
 * The main thread's side of the video worker: what the send sheet asks about a
 * picked clip, and the encode that runs once it is sent. Creating this module's
 * functions costs nothing; the worker (and mediabunny with it) loads on first use.
 */

/** Poster size: the bubble is at most 360 CSS px wide, so 720 covers 2× screens. */
const POSTER_EDGE = 720;
/** Filmstrip tiles are 48 px tall in the sheet; twice that stays sharp. */
const TILE_EDGE = 120;

/** `video/*` alone doesn't offer .mov or .mkv files in every desktop browser's picker. */
export const VIDEO_ACCEPT = "video/*,.mov,.mp4,.m4v,.webm,.mkv";

export function isVideoFile(file: File): boolean {
  return file.type.startsWith("video/") || /\.(mov|mp4|m4v|webm|mkv|3gp)$/i.test(file.name);
}

/** Files from a picker, a paste or a drop that look like clips (not photos). */
export function videoFiles(files: Iterable<File> | ArrayLike<File> | null | undefined): File[] {
  if (!files) return [];
  return Array.from(files).filter(isVideoFile);
}

/**
 * Clips on a paste: some browsers put the file in `items` and leave `files`
 * empty. Prefer `files` so we don't double-count.
 */
export function clipboardVideos(data: DataTransfer | null | undefined): File[] {
  if (!data) return [];
  const fromFiles = videoFiles(data.files);
  if (fromFiles.length) return fromFiles;
  const extras: File[] = [];
  for (const item of Array.from(data.items ?? [])) {
    if (item.kind !== "file") continue;
    const file = item.getAsFile();
    if (file) extras.push(file);
  }
  return videoFiles(extras);
}

/** What the send sheet hands back: encode happens after the bubble is on screen. */
export type VideoSendDraft = {
  file: File;
  trim: VideoTrim | null;
  mute: boolean;
  /** Rung chosen in the send sheet. The encode uses this, not a fixed 720p. */
  quality: VideoQuality;
  /** Size the sheet promised, so the bubble can show it before the encode finishes. */
  estimatedBytes: number | null;
  /** Frame the sheet promised. The bubble uses this, not the source frame. */
  width: number;
  height: number;
  poster: Blob | null;
  probe: VideoProbe;
};

export type InspectedVideo = { probe: VideoProbe; poster: Blob | null };

export type EncodedVideo = {
  bytes: Uint8Array;
  mime: "video/mp4";
  width: number;
  height: number;
  durationMs: number;
  /** Sharp first frame, for the bubble and the viewer. */
  poster: Blob | null;
  /** ≤ 6 KB preview sealed into the envelope. */
  thumb: Uint8Array | null;
};

/** The person stopped it (closed the sheet, left the chat); nothing to report. */
export class VideoCanceledError extends Error {
  constructor() {
    super("Canceled.");
    this.name = "VideoCanceledError";
  }
}

type Outgoing = VideoRequest extends infer R ? (R extends { id: number } ? Omit<R, "id"> : never) : never;

let worker: Worker | null = null;
let nextId = 1;
const listeners = new Map<number, (reply: VideoReply) => void>();
/** One encode at a time: two clips at once only makes both slower. */
let encodeQueue: Promise<unknown> = Promise.resolve();

function failAll(message: string): void {
  for (const [id, listener] of listeners) listener({ id, type: "error", message });
  listeners.clear();
}

function videoWorker(): Worker {
  if (worker) return worker;
  const created = new Worker(new URL("./videoWorker.ts", import.meta.url), { type: "module" });
  created.onmessage = (event: MessageEvent<VideoReply>) => listeners.get(event.data.id)?.(event.data);
  created.onerror = (event) => {
    // A worker that failed to load can't serve anyone; start over on the next clip.
    event.preventDefault();
    failAll("The video tools failed to load. Reload the page and try again.");
    created.terminate();
    if (worker === created) worker = null;
  };
  worker = created;
  return created;
}

/** Sends one request and routes its replies to `handle` until it resolves or rejects. */
function run<T>(
  message: Outgoing,
  handle: (reply: VideoReply, resolve: (value: T) => void, reject: (error: Error) => void) => void,
  signal?: AbortSignal,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new VideoCanceledError());
      return;
    }
    const id = nextId++;
    const target = videoWorker();
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      listeners.delete(id);
      signal?.removeEventListener("abort", onAbort);
    };
    const onAbort = () => {
      target.postMessage({ id, type: "cancel" } satisfies VideoRequest);
      finish();
      reject(new VideoCanceledError());
    };
    listeners.set(id, (reply) => {
      if (settled) return;
      if (reply.type === "error") {
        finish();
        if (reply.canceled || signal?.aborted) reject(new VideoCanceledError());
        else if (reply.maxSeconds != null) reject(new VideoTooLongError(reply.maxSeconds));
        else reject(new Error(reply.message));
        return;
      }
      handle(
        reply,
        (value) => {
          finish();
          resolve(value);
        },
        (error) => {
          finish();
          reject(error);
        },
      );
    });
    signal?.addEventListener("abort", onAbort, { once: true });
    target.postMessage({ ...message, id } as VideoRequest);
  });
}

/** Length, size, codecs and the first frame of a picked clip. */
export function inspectVideo(file: File, signal?: AbortSignal): Promise<InspectedVideo> {
  return run<InspectedVideo>(
    { type: "inspect", file, posterEdge: POSTER_EDGE },
    (reply, resolve) => {
      if (reply.type === "inspected") resolve({ probe: reply.probe, poster: reply.poster });
    },
    signal,
  );
}

/** Evenly spaced frames for the trim strip, delivered as they decode. */
export function videoFilmstrip(
  file: File,
  count: number,
  onTile: (index: number, tile: Blob) => void,
  signal?: AbortSignal,
): Promise<void> {
  return run<void>(
    { type: "filmstrip", file, count, edge: TILE_EDGE },
    (reply, resolve) => {
      if (reply.type === "tile") onTile(reply.index, reply.tile);
      else if (reply.type === "done") resolve();
    },
    signal,
  );
}

function toEncoded(parts: EncodedParts): EncodedVideo {
  return {
    bytes: new Uint8Array(parts.bytes),
    mime: "video/mp4",
    width: parts.width,
    height: parts.height,
    durationMs: parts.durationMs,
    poster: parts.poster,
    thumb: parts.thumb ? new Uint8Array(parts.thumb) : null,
  };
}

/**
 * Converts a picked clip into what goes on the wire (H.264 + AAC MP4 under the
 * server cap). `onProgress` gets 0…1. Rejects with `VideoTooLongError` when even
 * the smallest size can't fit, and `VideoCanceledError` when `signal` fires.
 */
export function encodeVideo(
  file: File,
  options: {
    trim?: VideoTrim | null;
    mute?: boolean;
    quality?: VideoQuality;
    onProgress?: (value: number) => void;
    signal?: AbortSignal;
  } = {},
): Promise<EncodedVideo> {
  const start = () =>
    run<EncodedVideo>(
      {
        type: "encode",
        file,
        trim: options.trim ?? null,
        mute: Boolean(options.mute),
        quality: options.quality ?? "high",
        posterEdge: POSTER_EDGE,
      },
      (reply, resolve) => {
        if (reply.type === "progress") options.onProgress?.(reply.value);
        else if (reply.type === "encoded") resolve(toEncoded(reply.video));
      },
      options.signal,
    );
  const task = encodeQueue.then(start, start);
  encodeQueue = task.catch(() => undefined);
  return task;
}

/** Drops the worker (lock, logout, leaving the app). Anything in flight fails. */
export function resetVideoWorker(): void {
  failAll("Video tools were reset.");
  encodeQueue = Promise.resolve();
  worker?.terminate();
  worker = null;
}

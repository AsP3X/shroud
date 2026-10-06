import { useSyncExternalStore } from "react";
import { ApiError } from "../api/client";
import { apiBase } from "../config";
import { FileBlobError, sealedSize } from "../crypto/fileBlob";
import { b64ToBytes, randomBytes } from "../crypto/bytes";
import { isWithdrawn } from "../crypto/plaintextCache";
import { blobMimeOf, CONTENT_CHECK_BYTES, contentMatches, fileTypeOf } from "../files";
import type { ChatMessage, SealedFile } from "../messaging";
import { FileJobError, runFileJob, type FileJob } from "./fileJobs";
import type { FileWorkerReply } from "./fileWorker";
import { forgetPdfMemory, rekeyPdfMemory, releasePdfMemory } from "./pdfMemory";
import { setTransfer } from "./transfers";
import { rekeyAudioFile, releaseAudioFile, stopAudioFile } from "../voice/audioFilePlayback";

/**
 * Shared files on the web (docs/file-sharing.md §8): nothing is cached. Opening downloads,
 * decrypts segment by segment into an unpublished `Blob` and checks its first bytes; the last
 * opened file then stays in memory until the chat locks, it is deleted, or another file opens.
 * Files this tab sent are read straight from the picked `File` instead of coming back down.
 */

/** A decrypted file, and whether its first bytes fit its extension (§4). */
export type OpenedFile = { blob: Blob; matches: boolean };

type Opened = OpenedFile & { id: string; url: string | null };

let opened: Opened | null = null;
/** Originals of the files sent from this tab, by message id (the user's own, picked files). */
const sentFiles = new Map<string, File>();
const loads = new Map<string, { task: Promise<OpenedFile>; abort: AbortController }>();
/** Ids dropped by delete: a download still in flight must not publish the file. */
const released = new Set<string>();
const listeners = new Set<() => void>();
/** Bumped by `forgetFiles`, so a download that finishes after a lock is dropped. */
let epoch = 0;

function key(id: string): string {
  return id.toLowerCase();
}

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Runs a job in a worker of its own; terminating it is how a download is cancelled. */
function runJob(job: FileJob, onProgress: (done: number, total: number | null) => void, signal?: AbortSignal): Promise<Blob> {
  let worker: Worker;
  try {
    worker = new Worker(new URL("./fileWorker.ts", import.meta.url), { type: "module" });
  } catch {
    return runFileJob(job, onProgress, signal);
  }
  return new Promise<Blob>((resolve, reject) => {
    let heard = false;
    const stop = () => {
      worker.terminate();
      signal?.removeEventListener("abort", onAbort);
    };
    const onAbort = () => {
      stop();
      reject(new FileJobError({ kind: "aborted" }));
    };
    if (signal?.aborted) {
      onAbort();
      return;
    }
    signal?.addEventListener("abort", onAbort, { once: true });
    worker.onmessage = (event: MessageEvent<FileWorkerReply>) => {
      heard = true;
      const data = event.data;
      if (data.type === "progress") {
        onProgress(data.done, data.total);
        return;
      }
      stop();
      if (data.type === "done") resolve(data.blob);
      else reject(new FileJobError(data.failure));
    };
    worker.onerror = (event) => {
      event.preventDefault();
      stop();
      // A worker that never started (blocked, or unsupported here): do the job on this thread.
      if (!heard) runFileJob(job, onProgress, signal).then(resolve, reject);
      else reject(new FileJobError({ kind: "other", message: event.message || "The file worker stopped." }));
    };
    worker.postMessage(job);
  });
}

/** A job failure as the error the rest of the app knows (`ApiError` for server answers). */
function asAppError(err: unknown): unknown {
  if (err instanceof FileJobError && err.failure.kind === "api") {
    return new ApiError(err.failure.code, err.failure.message, err.failure.status);
  }
  return err;
}

/** Whether a failure is a cancel (the ring's stop button, a lock, a delete). */
export function isCancelled(err: unknown): boolean {
  return err instanceof Error && err.name === "AbortError";
}

/** Whether a download failed because the blob itself is bad (a tag, its length, its header). */
export function isDamaged(err: unknown): boolean {
  return err instanceof FileBlobError || (err instanceof FileJobError && err.failure.kind === "damaged");
}

/** Seals a picked file as SHRF1 under a fresh key, off the main thread. */
export async function sealForUpload(file: File, onProgress?: (done: number, total: number) => void): Promise<SealedFile> {
  const fileKey = randomBytes(32);
  let blob: Blob;
  try {
    blob = await runJob({ type: "seal", file, key: fileKey }, (done, total) => onProgress?.(done, total ?? file.size));
  } catch (err) {
    throw asAppError(err);
  }
  // The file changed on disk while it was read: what was sealed is not what `s` will say.
  if (blob.size !== sealedSize(file.size)) throw new Error("The file changed while it was being sent.");
  return { blob, key: fileKey };
}

function replaceOpened(next: Opened | null): void {
  if (opened?.url) URL.revokeObjectURL(opened.url);
  opened = next;
  emit();
}

/** Decrypted (or our own original) and in reach: opening needs no download. */
export function fileOnDevice(id: string): boolean {
  return opened?.id === key(id) || sentFiles.has(key(id));
}

export function useFileOnDevice(id: string): boolean {
  return useSyncExternalStore(
    subscribe,
    () => fileOnDevice(id),
    () => false,
  );
}

/** The opened file, if it is this message's — synchronous, so a tap can open it at once. */
export function peekOpenedFile(id: string): OpenedFile | null {
  return opened?.id === key(id) ? { blob: opened.blob, matches: opened.matches } : null;
}

/** An object URL for the opened file (kept until it is replaced, deleted or the chat locks). */
export function openedFileUrl(id: string): string | null {
  if (opened?.id !== key(id)) return null;
  opened.url ??= URL.createObjectURL(opened.blob);
  return opened.url;
}

/**
 * The file of `message`, decrypted and checked: from this tab's original when we sent it,
 * else downloaded with progress on the transfer store. Deduped per message; rejects with an
 * `AbortError` when `cancelFileDownload`, a delete or a lock stopped it.
 */
export function ensureFile(message: ChatMessage, token: string): Promise<OpenedFile> {
  const id = key(message.id);
  const ready = peekOpenedFile(id);
  if (ready) return Promise.resolve(ready);
  const running = loads.get(id);
  if (running) return running.task;
  const abort = new AbortController();
  const started = epoch;
  const task = (async (): Promise<OpenedFile> => {
    const type = fileTypeOf(message.fileName ?? "");
    if (!type) throw new Error("This file type isn't supported.");
    const mime = blobMimeOf(type);
    const original = sentFiles.get(id);
    let blob: Blob;
    if (original) {
      blob = original.slice(0, original.size, mime);
    } else {
      const { mediaObjectId, mediaKey, mediaBytes } = message;
      if (!mediaObjectId || !mediaKey || mediaBytes == null) throw new Error("This file can't be downloaded.");
      const url = new URL(`${apiBase()}/media/${mediaObjectId.toLowerCase()}/content`, window.location.href).href;
      setTransfer(message.id, { direction: "down", loaded: 0, total: sealedSize(mediaBytes) });
      try {
        blob = await runJob(
          { type: "open", url, token, key: b64ToBytes(mediaKey), size: mediaBytes, mime },
          (done, total) => setTransfer(message.id, { direction: "down", loaded: done, total }),
          abort.signal,
        );
      } catch (err) {
        throw asAppError(err);
      }
    }
    // Locked, deleted or cancelled meanwhile: nothing of it may be kept.
    if (started !== epoch || released.has(id) || isWithdrawn(message.id) || abort.signal.aborted) {
      throw new FileJobError({ kind: "aborted" });
    }
    const head = new Uint8Array(await blob.slice(0, CONTENT_CHECK_BYTES).arrayBuffer());
    const result: OpenedFile = { blob, matches: contentMatches(type.ext, head) };
    if (started !== epoch || released.has(id)) throw new FileJobError({ kind: "aborted" });
    replaceOpened({ ...result, id, url: null });
    return result;
  })().finally(() => {
    if (loads.get(id)?.task === task) loads.delete(id);
    setTransfer(message.id, null);
  });
  loads.set(id, { task, abort });
  return task;
}

/** Stops a download in flight (the ring's stop button). */
export function cancelFileDownload(id: string): void {
  loads.get(key(id))?.abort.abort();
}

/** A file this tab is sending: opening it reads the original, never the network. */
export function adoptSentFile(id: string, file: File): void {
  sentFiles.set(key(id), file);
  emit();
}

/** The server re-keys sent messages; the original moves along with the bubble. */
export function rekeySentFile(fromId: string, toId: string): void {
  rekeyPdfMemory(fromId, toId);
  rekeyAudioFile(fromId, toId);
  const file = sentFiles.get(key(fromId));
  if (!file) return;
  sentFiles.delete(key(fromId));
  sentFiles.set(key(toId), file);
  emit();
}

/** Drops one message's file from memory (it was deleted). */
export function releaseFile(messageId: string): void {
  const id = key(messageId);
  released.add(id);
  loads.get(id)?.abort.abort();
  loads.delete(id);
  sentFiles.delete(id);
  releasePdfMemory(id);
  // A deleted audio file stops playing (docs/file-sharing.md §11.5).
  releaseAudioFile(id);
  if (opened?.id === id) replaceOpened(null);
  else emit();
}

/** Drops every file from memory (locking, logging out, clearing the cache). */
export function forgetFiles(): void {
  epoch += 1;
  for (const { abort } of loads.values()) abort.abort();
  loads.clear();
  sentFiles.clear();
  // The PDF renders and pages (docs/file-sharing.md §10) go with the files they came from.
  forgetPdfMemory();
  // The playing audio file and its object URL go with them (§11.5).
  stopAudioFile();
  replaceOpened(null);
}

import { CLIENT_HEADER, clientName } from "../config";
import { FileBlobError, sealFileBlob, Shrf1Opener } from "../crypto/fileBlob";

/**
 * The two heavy jobs of file sharing — sealing a picked file and downloading + opening one —
 * written once and run inside `fileWorker.ts`, or on this thread where a worker can't start.
 * Both work one SHRF1 segment at a time, so neither ever holds a whole file in JS memory.
 */

export type SealJob = { type: "seal"; file: Blob; key: Uint8Array };
export type OpenJob = {
  type: "open";
  /** Absolute `GET /media/{id}/content` URL. */
  url: string;
  token: string;
  key: Uint8Array;
  /** The payload's `s`: the blob must be exactly `sealedSize(size)`. */
  size: number;
  /** The type table's MIME, given to the opened `Blob`. */
  mime: string;
};
export type FileJob = SealJob | OpenJob;

/** Why a job failed, in a shape that survives `postMessage`. */
export type JobFailure =
  | { kind: "api"; code: string; message: string; status: number }
  | { kind: "damaged"; message: string }
  | { kind: "aborted" }
  | { kind: "other"; message: string };

export class FileJobError extends Error {
  constructor(readonly failure: JobFailure) {
    super(failure.kind === "aborted" ? "Cancelled" : failure.message);
    this.name = failure.kind === "aborted" ? "AbortError" : "FileJobError";
  }
}

export function failureOf(err: unknown): JobFailure {
  if (err instanceof FileJobError) return err.failure;
  if (err instanceof FileBlobError) return { kind: "damaged", message: err.message };
  if (err instanceof DOMException && err.name === "AbortError") return { kind: "aborted" };
  return { kind: "other", message: err instanceof Error ? err.message : String(err) };
}

/** Progress at most every this many milliseconds, so a 2 GB file doesn't post 30 000 messages. */
const PROGRESS_EVERY_MS = 80;

function throttled(report: (done: number, total: number | null) => void): (done: number, total: number | null) => void {
  let last = 0;
  return (done, total) => {
    const now = Date.now();
    if (done !== total && now - last < PROGRESS_EVERY_MS) return;
    last = now;
    report(done, total);
  };
}

async function download(job: OpenJob, report: (done: number, total: number | null) => void, signal?: AbortSignal): Promise<Blob> {
  const opener = await Shrf1Opener.create(job.key, job.size);
  let res: Response;
  try {
    res = await fetch(job.url, {
      headers: { Accept: "*/*", Authorization: `Bearer ${job.token}`, [CLIENT_HEADER]: clientName() },
      signal,
    });
  } catch (err) {
    if (signal?.aborted) throw new FileJobError({ kind: "aborted" });
    throw new FileJobError({
      kind: "api",
      code: "transport",
      message: err instanceof Error ? err.message : "Network error",
      status: 0,
    });
  }
  if (!res.ok) {
    let code = "http";
    let message = res.statusText || `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { error?: { code?: string; message?: string } };
      if (body.error?.code) code = body.error.code;
      if (body.error?.message) message = body.error.message;
    } catch {
      /* envelope optional */
    }
    throw new FileJobError({ kind: "api", code, message, status: res.status });
  }
  // A blob of the wrong length is refused before a byte of it is decrypted.
  const declared = Number(res.headers.get("Content-Length"));
  if (declared > 0 && declared !== opener.expected) {
    await res.body?.cancel();
    throw new FileBlobError("The file's length doesn't match its size.");
  }
  if (!res.body) throw new FileJobError({ kind: "other", message: "This browser can't stream downloads." });
  const reader = res.body.getReader();
  let received = 0;
  report(0, opener.expected);
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      await opener.push(value);
      received += value.byteLength;
      report(received, opener.expected);
    }
  } catch (err) {
    void reader.cancel().catch(() => undefined);
    if (signal?.aborted) throw new FileJobError({ kind: "aborted" });
    throw err;
  }
  return opener.finish(job.mime);
}

/** Runs one job; `onProgress` gets bytes sealed, or bytes downloaded of the sealed total. */
export async function runFileJob(
  job: FileJob,
  onProgress: (done: number, total: number | null) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  const report = throttled(onProgress);
  if (job.type === "seal") return sealFileBlob(job.file, job.key, { onProgress: report, signal });
  return download(job, report, signal);
}

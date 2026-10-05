/**
 * Seals and opens shared files off the main thread (see fileJobs.ts). One worker per job: the
 * caller terminates it to cancel, which also drops any plaintext it had decrypted so far.
 */
import { failureOf, runFileJob, type FileJob } from "./fileJobs";

export type FileWorkerReply =
  | { type: "progress"; done: number; total: number | null }
  | { type: "done"; blob: Blob }
  | { type: "error"; failure: ReturnType<typeof failureOf> };

function reply(message: FileWorkerReply): void {
  self.postMessage(message);
}

self.onmessage = (event: MessageEvent<FileJob>) => {
  void runFileJob(event.data, (done, total) => reply({ type: "progress", done, total })).then(
    (blob) => reply({ type: "done", blob }),
    (err: unknown) => reply({ type: "error", failure: failureOf(err) }),
  );
};

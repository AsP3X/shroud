import type { TranscriptionEngine, TranscriptionModelId, TranscriptionOutput, TranscriptionRequest } from "./types";
import { WHISPER_MODELS } from "./types";

type WorkerIn =
  | { type: "prepare"; modelId: string }
  | { type: "transcribe"; audio: ArrayBuffer; language: string | null };

type WorkerOut =
  | { type: "progress"; fraction: number }
  | { type: "ready" }
  | { type: "result"; text: string }
  | { type: "error"; message: string };

/**
 * transformers.js Whisper. The only module that talks to Hugging Face — and only
 * for model weights, never for user audio.
 */
export class WhisperEngine implements TranscriptionEngine {
  readonly id = "whisper";
  private worker: Worker | null = null;
  private waiting: {
    resolve: (value: string | void) => void;
    reject: (error: Error) => void;
    progress?: (fraction: number) => void;
  } | null = null;
  private tail: Promise<void> = Promise.resolve();

  private ensureWorker(): Worker {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL("./whisper-worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (event: MessageEvent<WorkerOut>) => {
      const msg = event.data;
      if (msg.type === "progress") {
        this.waiting?.progress?.(msg.fraction);
        return;
      }
      const waiting = this.waiting;
      this.waiting = null;
      if (!waiting) return;
      if (msg.type === "error") waiting.reject(new Error(msg.message));
      else if (msg.type === "result") waiting.resolve(msg.text);
      else waiting.resolve();
    };
    worker.onerror = (event) => {
      const waiting = this.waiting;
      this.waiting = null;
      waiting?.reject(new Error(event.message || "Whisper worker failed."));
    };
    this.worker = worker;
    return worker;
  }

  private send(message: WorkerIn, transfer?: Transferable[], progress?: (fraction: number) => void): Promise<string | void> {
    const worker = this.ensureWorker();
    const run = () =>
      new Promise<string | void>((resolve, reject) => {
        this.waiting = { resolve, reject, progress };
        worker.postMessage(message, transfer ?? []);
      });
    const job = this.tail.then(run, run);
    this.tail = job.then(
      () => undefined,
      () => undefined,
    );
    return job;
  }

  async prepare(model: TranscriptionModelId, progress?: (fraction: number) => void): Promise<void> {
    await this.send({ type: "prepare", modelId: WHISPER_MODELS[model] }, undefined, progress);
  }

  async transcribe(
    samples: Float32Array,
    _sampleRate: number,
    request: TranscriptionRequest,
  ): Promise<TranscriptionOutput> {
    const copy = new Float32Array(samples);
    const text = await this.send(
      { type: "transcribe", audio: copy.buffer, language: request.language ?? null },
      [copy.buffer],
    );
    return { text: typeof text === "string" ? text : "", language: request.language ?? null };
  }
}

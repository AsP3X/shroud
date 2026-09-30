import type { TranscriptionEngine, TranscriptionModelId, TranscriptionOutput, TranscriptionRequest } from "./types";
import { WHISPER_MODELS } from "./types";

type WorkerIn =
  | { type: "prepare"; modelId: string }
  | { type: "transcribe"; audio: ArrayBuffer; language: string | null };

type WorkerResult = { text: string; language: string | null };

type WorkerOut =
  | { type: "progress"; fraction: number }
  | { type: "ready" }
  | { type: "result"; text: string; language?: string | null }
  | { type: "error"; message: string };

/**
 * transformers.js Whisper. The only module that talks to Hugging Face — and only
 * for model weights, never for user audio.
 */
export class WhisperEngine implements TranscriptionEngine {
  readonly id = "whisper";
  private worker: Worker | null = null;
  private preparedFor: string | null = null;
  private waiting: {
    resolve: (value: WorkerResult | void) => void;
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
      else if (msg.type === "result") {
        waiting.resolve({ text: msg.text, language: msg.language ?? null });
      } else waiting.resolve();
    };
    worker.onerror = (event) => {
      const waiting = this.waiting;
      this.waiting = null;
      this.dropWorker(worker);
      waiting?.reject(new Error(event.message || "Whisper worker failed."));
    };
    worker.onmessageerror = () => {
      const waiting = this.waiting;
      this.waiting = null;
      this.dropWorker(worker);
      waiting?.reject(new Error("Whisper worker message was unreadable."));
    };
    this.worker = worker;
    return worker;
  }

  private dropWorker(worker: Worker): void {
    if (this.worker === worker) {
      this.worker = null;
      this.preparedFor = null;
    }
    try {
      worker.terminate();
    } catch {
      /* already dead */
    }
  }

  private send(
    message: WorkerIn,
    transfer?: Transferable[],
    progress?: (fraction: number) => void,
  ): Promise<WorkerResult | void> {
    const run = () =>
      new Promise<WorkerResult | void>((resolve, reject) => {
        try {
          const worker = this.ensureWorker();
          this.waiting = { resolve, reject, progress };
          worker.postMessage(message, transfer ?? []);
        } catch (err) {
          this.waiting = null;
          reject(err instanceof Error ? err : new Error(String(err)));
        }
      });
    const job = this.tail.then(run, run);
    this.tail = job.then(
      () => undefined,
      () => undefined,
    );
    return job;
  }

  async prepare(model: TranscriptionModelId, progress?: (fraction: number) => void): Promise<void> {
    const modelId = WHISPER_MODELS[model];
    if (this.worker && this.preparedFor === modelId) return;
    await this.send({ type: "prepare", modelId }, undefined, progress);
    this.preparedFor = modelId;
  }

  async transcribe(
    samples: Float32Array,
    _sampleRate: number,
    request: TranscriptionRequest,
  ): Promise<TranscriptionOutput> {
    const copy = new Float32Array(samples);
    const sent = await this.send(
      { type: "transcribe", audio: copy.buffer, language: request.language ?? null },
      [copy.buffer],
    );
    if (!sent) return { text: "", language: request.language ?? null };
    return { text: sent.text, language: sent.language ?? request.language ?? null };
  }
}

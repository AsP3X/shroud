import type {
  TranscriptionEngine,
  TranscriptionModelId,
  TranscriptionOutput,
  TranscriptionRequest,
} from "./types";
import { DEFAULT_TRANSCRIPTION_MODEL } from "./types";
import { WhisperEngine } from "./whisper";

const MODEL_KEY = "transcription.model";

/**
 * Owns the loaded engine so two record presses cannot start two downloads, and
 * two notes cannot decode at once (same contract as iOS `TranscriptionSession`).
 */
export class TranscriptionSession {
  private engine: TranscriptionEngine;
  private loaded: TranscriptionModelId | null = null;
  private preparing: Promise<void> | null = null;
  private queue: Promise<void> = Promise.resolve();

  constructor(engine: TranscriptionEngine = new WhisperEngine()) {
    this.engine = engine;
  }

  get selectedModel(): TranscriptionModelId {
    try {
      const raw = localStorage.getItem(MODEL_KEY);
      if (raw === "tiny" || raw === "base" || raw === "small") return raw;
    } catch {
      /* private mode */
    }
    return DEFAULT_TRANSCRIPTION_MODEL;
  }

  get isPrepared(): boolean {
    return this.loaded === this.selectedModel;
  }

  async prepare(progress?: (fraction: number) => void): Promise<void> {
    const target = this.selectedModel;
    if (!this.preparing) {
      const work = this.engine.prepare(target, progress).then(
        () => {
          this.loaded = target;
        },
        (err: unknown) => {
          this.loaded = null;
          throw err;
        },
      );
      this.preparing = work;
      void work.finally(() => {
        if (this.preparing === work) this.preparing = null;
      });
    }
    await this.preparing;
  }

  async transcribe(
    samples: Float32Array,
    sampleRate: number,
    request: TranscriptionRequest = {},
  ): Promise<TranscriptionOutput> {
    await this.prepare();
    let release!: () => void;
    const slot = new Promise<void>((resolve) => {
      release = resolve;
    });
    const previous = this.queue;
    this.queue = previous.then(() => slot, () => slot);
    try {
      await previous;
    } catch {
      /* previous job failed; this one still runs */
    }
    try {
      return await this.engine.transcribe(samples, sampleRate, request);
    } catch (err) {
      this.loaded = null;
      throw err;
    } finally {
      release();
    }
  }
}

export const transcriptionSession = new TranscriptionSession();

export function concatPcm(chunks: Float32Array[]): Float32Array {
  let total = 0;
  for (const part of chunks) total += part.length;
  const merged = new Float32Array(total);
  let offset = 0;
  for (const part of chunks) {
    merged.set(part, offset);
    offset += part.length;
  }
  return merged;
}

/*
 * Whisper inference in a worker so the composer stays responsive.
 * Audio never leaves this worker: only model weights are fetched from Hugging Face.
 *
 * transformers.js defaults ONNX WASM to cdn.jsdelivr.net. That host is not on
 * our CSP (and we do not want a third-party runtime), so the files are bundled
 * from onnxruntime-web and served same-origin. Threads stay at 1: we are not
 * cross-origin isolated (SharedArrayBuffer would require COOP/COEP, which
 * would block the Hugging Face weight download).
 */
import wasmUrl from "onnxruntime-web/ort-wasm-simd-threaded.asyncify.wasm?url";
import wasmFactoryUrl from "onnxruntime-web/ort-wasm-simd-threaded.asyncify.mjs?url";
import { env, pipeline } from "@huggingface/transformers";
import {
  contiguousVoice,
  joinVoicePieces,
  nextVoiceSample,
  voiceNoteDecodeOptions,
  WHISPER_WINDOW_SECONDS,
} from "./decode";
import { detectSpokenLanguage } from "./spoken";

env.allowLocalModels = false;
env.useBrowserCache = true;

const wasm = (
  env.backends.onnx as {
    wasm?: { wasmPaths?: unknown; numThreads?: number; proxy?: boolean };
  }
).wasm;
if (!wasm) {
  throw new Error("ONNX WASM backend is not available in this worker.");
}
wasm.wasmPaths = { wasm: wasmUrl, mjs: wasmFactoryUrl };
wasm.numThreads = 1;
wasm.proxy = false;

type AsrOut = {
  text?: string;
  chunks?: { timestamp?: [number | null, number | null]; text?: string }[];
};

type AsrPipe = {
  (audio: Float32Array, options: Record<string, unknown>): Promise<AsrOut>;
};

const WHISPER_RATE = 16_000;
let pipe: AsrPipe | null = null;

function progressFraction(info: {
  status?: string;
  progress?: number;
  loaded?: number;
  total?: number;
}): number | null {
  if (typeof info.loaded === "number" && typeof info.total === "number" && info.total > 0) {
    return info.loaded / info.total;
  }
  if (typeof info.progress === "number") return Math.min(1, Math.max(0, info.progress / 100));
  return null;
}

async function load(modelId: string): Promise<AsrPipe> {
  return (await pipeline("automatic-speech-recognition", modelId, {
    device: "wasm",
    dtype: "q8",
    progress_callback: (info: { status?: string; progress?: number; loaded?: number; total?: number }) => {
      const fraction = progressFraction(info);
      if (fraction != null) self.postMessage({ type: "progress", fraction });
    },
  })) as AsrPipe;
}

async function handle(
  data:
    | { type: "prepare"; modelId: string }
    | { type: "transcribe"; audio: ArrayBuffer; language: string | null },
): Promise<void> {
  if (data.type === "prepare") {
    pipe = await load(data.modelId);
    self.postMessage({ type: "ready" });
    return;
  }
  if (!pipe) throw new Error("Whisper is not loaded.");
  const samples = new Float32Array(data.audio);
  // A missing language used to be transcribed as English. Detect it from the
  // opening of the note and keep that code for every later window.
  const language = data.language || (await detectSpokenLanguage(pipe, samples));
  const pieces: string[] = [];
  let offset = 0;
  // Each pass hears one window. A long or paused note takes several; the cap
  // is only a backstop if a pass reports no progress.
  for (let pass = 0; pass < 40 && offset < samples.length; pass++) {
    const slice = samples.subarray(offset);
    const seconds = slice.length / WHISPER_RATE;
    if (seconds < 0.25) break;
    let out: AsrOut;
    try {
      out = await pipe(slice, {
        language: language || undefined,
        ...voiceNoteDecodeOptions(),
      });
    } catch (err) {
      // Keep the words already decoded. A later window failing must not drop them.
      console.warn("Whisper window failed:", err instanceof Error ? err.message : err);
      break;
    }
    const heard = contiguousVoice(out.chunks, Math.min(seconds, WHISPER_WINDOW_SECONDS));
    const text = heard ? heard.text : typeof out.text === "string" ? out.text : "";
    if (text.trim()) pieces.push(text);
    const next = nextVoiceSample({
      totalSamples: samples.length,
      offset,
      sampleRate: WHISPER_RATE,
      coveredUntil: heard ? heard.coveredUntil : null,
    });
    if (next == null) break;
    offset = next;
  }
  self.postMessage({ type: "result", text: joinVoicePieces(pieces), language: language || null });
}

let chain: Promise<void> = Promise.resolve();
self.onmessage = (event: MessageEvent) => {
  const data = event.data as
    | { type: "prepare"; modelId: string }
    | { type: "transcribe"; audio: ArrayBuffer; language: string | null };
  chain = chain.then(async () => {
    try {
      await handle(data);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.warn("Whisper worker failed:", message);
      self.postMessage({ type: "error", message });
    }
  });
};

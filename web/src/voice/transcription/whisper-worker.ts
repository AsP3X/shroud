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

type AsrPipe = {
  (audio: Float32Array, options: Record<string, unknown>): Promise<{ text?: string }>;
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
  const seconds = samples.length / WHISPER_RATE;
  const out = await pipe(samples, {
    language: data.language || undefined,
    task: "transcribe",
    return_timestamps: false,
    ...(seconds > 30 ? { chunk_length_s: 30, stride_length_s: 5 } : {}),
  });
  self.postMessage({ type: "result", text: typeof out?.text === "string" ? out.text : "" });
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

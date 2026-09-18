/*
 * Whisper inference in a worker so the composer stays responsive.
 * Audio never leaves this worker: only model weights are fetched from Hugging Face.
 */
import { env, pipeline } from "@huggingface/transformers";

env.allowLocalModels = false;
env.useBrowserCache = true;

type AsrPipe = {
  (audio: Float32Array, options: Record<string, unknown>): Promise<{ text?: string }>;
};

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

async function load(modelId: string, device: "webgpu" | "wasm"): Promise<AsrPipe> {
  return (await pipeline("automatic-speech-recognition", modelId, {
    device,
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
    const preferGpu = typeof navigator !== "undefined" && "gpu" in navigator;
    try {
      pipe = await load(data.modelId, preferGpu ? "webgpu" : "wasm");
    } catch {
      pipe = await load(data.modelId, "wasm");
    }
    self.postMessage({ type: "ready" });
    return;
  }
  if (!pipe) throw new Error("Whisper is not loaded.");
  const samples = new Float32Array(data.audio);
  const out = await pipe(samples, {
    language: data.language || undefined,
    task: "transcribe",
    return_timestamps: false,
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
      self.postMessage({
        type: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  });
};

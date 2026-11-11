/**
 * The pinned Whisper download (audit W3, transcription plan T3.6). Run: npm test -- pinned
 */
import { readFileSync } from "node:fs";
import { type ModelCache, PINNED_WHISPER, PinnedFileError, pinnedCache, pinnedFetch, whisperPipelineOptions } from "./pinned";
import { WHISPER_MODELS } from "./types";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

async function rejects(work: () => Promise<unknown>, message: string): Promise<void> {
  try {
    await work();
  } catch (err) {
    check(err instanceof PinnedFileError, `${message}: wrong error ${String(err)}`);
    return;
  }
  throw new Error(`${message}: did not fail`);
}

// The real `preprocessor_config.json` of whisper-base at the pinned commit, so the test checks
// the shipped table, not a stand-in.
const PREPROCESSOR = new TextEncoder().encode(
  `{
  "chunk_length": 30,
  "feature_extractor_type": "WhisperFeatureExtractor",
  "feature_size": 80,
  "hop_length": 160,
  "n_fft": 400,
  "n_samples": 480000,
  "nb_max_frames": 3000,
  "padding_side": "right",
  "padding_value": 0.0,
  "processor_class": "WhisperProcessor",
  "return_attention_mask": false,
  "sampling_rate": 16000
}
`,
);
const BASE = "onnx-community/whisper-base";
const REVISION = PINNED_WHISPER[BASE].revision;
const PINNED_URL = `https://huggingface.co/${BASE}/resolve/${REVISION}/preprocessor_config.json`;
const MAIN_URL = `https://huggingface.co/${BASE}/resolve/main/preprocessor_config.json`;

/** A network that serves `body` for any URL and records what was asked. */
function network(body: Uint8Array) {
  const asked: string[] = [];
  const fetchImpl = async (input: string | URL) => {
    asked.push(String(input));
    return new Response(body.slice(), { status: 200, headers: { "content-type": "application/json" } });
  };
  return { asked, fetch: pinnedFetch(fetchImpl) };
}

function tampered(): Uint8Array {
  const bytes = PREPROCESSOR.slice();
  bytes[bytes.indexOf(0x33)] = 0x32; // "chunk_length": 30 → 20, same size
  return bytes;
}

// ── The loader asks for a pinned commit ─────────────────────────────────────
for (const repo of Object.values(WHISPER_MODELS)) {
  const options = whisperPipelineOptions(repo);
  check(/^[0-9a-f]{40}$/.test(options.revision), `${repo} loads a 40-hex commit, not a branch`);
  check(options.dtype === "q8", `${repo} loads the quantized files the table lists`);
  const files = Object.entries(PINNED_WHISPER[repo].files);
  check(files.length === 7, `${repo} pins every file the pipeline asks for`);
  for (const [name, file] of files) {
    check(/^[0-9a-f]{64}$/.test(file.sha256) && file.size > 0, `${repo} ${name} has a size and a SHA-256`);
  }
}
await rejects(async () => whisperPipelineOptions("onnx-community/whisper-large-v3"), "an unpinned model does not load");

// The worker cannot be bundled under Node (it imports WASM by URL), so its wiring is checked in
// the source: the options go to `pipeline`, and fetch and cache go through the pins.
const worker = readFileSync("src/voice/transcription/whisper-worker.ts", "utf8");
check(worker.includes("...whisperPipelineOptions(modelId)"), "the worker passes the pinned revision to pipeline()");
check(worker.includes("env.fetch = pinnedFetch("), "the worker fetches through the pins");
check(worker.includes("env.useBrowserCache = false"), "the worker does not read the unchecked browser cache");
check(worker.includes("env.customCache = pinnedCache("), "the worker reads the cache through the pins");

// ── Download ────────────────────────────────────────────────────────────────
{
  const net = network(PREPROCESSOR);
  const response = await net.fetch(PINNED_URL);
  const bytes = new Uint8Array(await response.arrayBuffer());
  check(bytes.length === PREPROCESSOR.length && bytes.every((b, i) => b === PREPROCESSOR[i]), "a matching file is served");
  check(response.headers.get("content-length") === "339", "the pinned size is the progress total");
  check(net.asked[0] === PINNED_URL, "the pinned commit is fetched");
}
{
  const net = network(PREPROCESSOR);
  await (await net.fetch(MAIN_URL)).arrayBuffer();
  check(net.asked[0] === PINNED_URL, "transformers.js's unpinned discovery request goes to the pinned commit");
}
{
  const net = network(tampered());
  const response = await net.fetch(PINNED_URL);
  await rejects(() => response.arrayBuffer(), "a file whose hash differs is refused");
}
await rejects(async () => (await network(PREPROCESSOR.slice(0, 200)).fetch(PINNED_URL)).arrayBuffer(), "a short file is refused");
{
  const longer = new Uint8Array(PREPROCESSOR.length + 1);
  longer.set(PREPROCESSOR);
  await rejects(async () => (await network(longer).fetch(PINNED_URL)).arrayBuffer(), "a longer file is refused");
}
{
  const net = network(PREPROCESSOR);
  const missing = await net.fetch(`https://huggingface.co/${BASE}/resolve/${REVISION}/onnx/encoder_model_fp16.onnx`);
  check(missing.status === 404, "an unlisted file reads as missing");
  for (const url of [
    `https://huggingface.co/${BASE}/resolve/0123456789abcdef0123456789abcdef01234567/preprocessor_config.json`,
    `https://huggingface.co/${BASE}/resolve/refs%2Fpr%2F1/preprocessor_config.json`,
    `https://huggingface.co/attacker/whisper-base/resolve/${REVISION}/preprocessor_config.json`,
    `https://huggingface.co/api/models/${BASE}`,
  ]) {
    await rejects(() => net.fetch(url), `${url} is refused`);
  }
  const range = await net.fetch(PINNED_URL, { headers: { Range: "bytes=0-0" } });
  check(range.status === 206 && range.headers.get("content-range") === "bytes 0-0/339", "the size probe is answered from the pin");
  check(net.asked.length === 0, "nothing unpinned, and no size probe, reaches the network");
  await net.fetch("https://shroud.example/assets/ort.wasm");
  check(net.asked[0] === "https://shroud.example/assets/ort.wasm", "same-origin runtime files pass through");
}

// ── Cache ───────────────────────────────────────────────────────────────────
class MemoryCache implements ModelCache {
  entries = new Map<string, Uint8Array>();
  async match(request: string) {
    const bytes = this.entries.get(request);
    return bytes ? new Response(bytes.slice()) : undefined;
  }
  async put(request: string, response: Response) {
    this.entries.set(request, new Uint8Array(await response.arrayBuffer()));
  }
  async delete(request: string) {
    return this.entries.delete(request);
  }
}
{
  const store = new MemoryCache();
  const cache = pinnedCache(async () => store);
  await cache.put(MAIN_URL, new Response(PREPROCESSOR.slice()));
  check(store.entries.has(PINNED_URL) && store.entries.size === 1, "a file is stored under its pinned URL");
  const hit = await cache.match(MAIN_URL);
  check(hit !== undefined && (await hit.arrayBuffer()).byteLength === 339, "a matching cached file is used");

  store.entries.set(PINNED_URL, tampered());
  check((await cache.match(PINNED_URL)) === undefined, "a cached file whose hash differs is not used");
  check(!store.entries.has(PINNED_URL), "a cached file whose hash differs is deleted");

  await cache.put(`${BASE}/preprocessor_config.json`, new Response(PREPROCESSOR.slice()));
  await cache.put(`https://huggingface.co/attacker/x/resolve/main/a.json`, new Response("x"));
  check(store.entries.size === 0, "no unpinned key is written");
  check((await cache.match(`${BASE}/preprocessor_config.json`)) === undefined, "the local-model key is never served");

  await cache.put("https://shroud.example/assets/ort.wasm", new Response("wasm"));
  check((await cache.match("https://shroud.example/assets/ort.wasm")) !== undefined, "runtime files cache as before");
}
{
  const cache = pinnedCache(() => Promise.reject(new Error("SecurityError")));
  check((await cache.match(PINNED_URL)) === undefined, "no cache available means a download, not a failure");
}

console.log("pinned whisper selftest ok");

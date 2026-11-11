/**
 * The Whisper files the web client may load: one Hugging Face commit per model, and each file's
 * exact size and SHA-256 (transcription plan T3.6, audit W3). Android's `WhisperModelStore` pins
 * its ggml files the same way.
 *
 * Human: A swapped model could put words into the user's own voice-note transcripts, which are
 * sealed and sent to contacts. So the worker only ever loads these bytes. A file that does not
 * match is thrown away and transcription reports itself unavailable; nothing falls back to
 * Hugging Face's default branch.
 * Agent: `pinnedFetch` wraps transformers.js's `env.fetch`: HTTP to huggingface.co only for files
 * listed here, at the pinned commit, and the body errors at its end unless size and SHA-256 match.
 * `pinnedCache` wraps the Cache API under `env.customCache`: a cached file is hashed again before
 * every use and deleted when it does not match. Requests to any other host pass through untouched
 * (the same-origin ONNX WASM files). Where the hashes came from: `docs/transcription-plan.md` T3.6.
 */
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";

export type PinnedFile = { size: number; sha256: string };

export type PinnedModel = {
  /** A full 40-hex commit id, never a branch or tag: a branch can be moved. */
  revision: string;
  files: Readonly<Record<string, PinnedFile>>;
};

/** transformers.js's default `env.remoteHost`. */
export const HUGGING_FACE = "https://huggingface.co/";

// The three repositories share these files byte for byte.
const TOKENIZER: PinnedFile = {
  size: 2_480_466,
  sha256: "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
};
const PREPROCESSOR: PinnedFile = {
  size: 339,
  sha256: "a6a76d28c93edb273669eb9e0b0636a2bddbb1272c3261e47b7ca6dfdbac1b8d",
};
const TOKENIZER_CONFIG_TINY_SMALL: PinnedFile = {
  size: 282_683,
  sha256: "2a4c4281cf9f51ac6ccc406fdc711a087afe6530f671fa7b80953edc498275ce",
};

/**
 * Every file `pipeline("automatic-speech-recognition", …, whisperPipelineOptions(…))` asks for.
 * The `_quantized` ONNX files are what `dtype: "q8"` loads.
 */
export const PINNED_WHISPER: Readonly<Record<string, PinnedModel>> = {
  "onnx-community/whisper-tiny": {
    revision: "ff4177021cc41f7db950912b73ea4fdf7d01d8e7",
    files: {
      "config.json": { size: 2_243, sha256: "46aeea0a406afbeb563fc8e59ca10609203df4299af6a83f73752fef369efd2d" },
      "generation_config.json": {
        size: 3_772,
        sha256: "f5c67e5a4f7102f8cb4d058bc95da276bbc19eeec997267c3bb0f25ef68facd1",
      },
      "tokenizer.json": TOKENIZER,
      "tokenizer_config.json": TOKENIZER_CONFIG_TINY_SMALL,
      "preprocessor_config.json": PREPROCESSOR,
      "onnx/encoder_model_quantized.onnx": {
        size: 10_124_990,
        sha256: "2af4a414ca47aa30f61246017e5fe82b0a8d229281d1255ba666a2a7f6b84d19",
      },
      "onnx/decoder_model_merged_quantized.onnx": {
        size: 30_719_241,
        sha256: "25e807a962b6349356d0ea5d0dfe530b7e5bf0e2a484aeca0359d03143faddd3",
      },
    },
  },
  "onnx-community/whisper-base": {
    revision: "27a07b4b52b3a4c5886134bb4320e93e1a4bbc7c",
    files: {
      "config.json": { size: 2_243, sha256: "f4d0608f7d918166da7edb3e188de5ef1bfe70d9802e785d271fd88111e9cf4b" },
      "generation_config.json": {
        size: 3_832,
        sha256: "61070cf8de25b1e9256e8e102ded49d8d24a8369ed36ef84fdf21549e68125a0",
      },
      "tokenizer.json": TOKENIZER,
      "tokenizer_config.json": {
        size: 282_682,
        sha256: "2e036e4dbacfdeb7242c7d4ec4149f4a16e86026048f94d1637e3a8ee9c6a573",
      },
      "preprocessor_config.json": PREPROCESSOR,
      "onnx/encoder_model_quantized.onnx": {
        size: 23_201_314,
        sha256: "5862993336bf33acd23736071aae2b32261d3b1b2f37780194460d4ef974dd46",
      },
      "onnx/decoder_model_merged_quantized.onnx": {
        size: 53_693_315,
        sha256: "fa3ef9902734ce5ae6f9ef2bdb2ba9a6c4b5785b09f4f420ce036573dc9d090b",
      },
    },
  },
  "onnx-community/whisper-small": {
    revision: "36050c46d777d46dc4b5f43f6d90574fc38f8732",
    files: {
      "config.json": { size: 2_227, sha256: "457854d452f17661e197d74aee12b8e74fb75ba30ebfaa7426d0d61ea1e08a18" },
      "generation_config.json": {
        size: 3_893,
        sha256: "f538b28220c6a6d6f1af1458d4141cacb4ef4963df3de98a19490440c412ddf0",
      },
      "tokenizer.json": TOKENIZER,
      "tokenizer_config.json": TOKENIZER_CONFIG_TINY_SMALL,
      "preprocessor_config.json": PREPROCESSOR,
      "onnx/encoder_model_quantized.onnx": {
        size: 92_326_160,
        sha256: "a43a83f3c5361cd591cfa7c36f14b43cf7cb22f47a415cc14a8d557be800fa92",
      },
      "onnx/decoder_model_merged_quantized.onnx": {
        size: 156_750_845,
        sha256: "ec07c3cbb64172c39791e26ee870a65ac22b458c36722bfe2776b3dbf741e0c9",
      },
    },
  },
};

export class PinnedFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PinnedFileError";
  }
}

/** The options the worker passes to `pipeline(...)`. Throws for a model that is not pinned. */
export function whisperPipelineOptions(modelId: string): { revision: string; device: "wasm"; dtype: "q8" } {
  const pinned = PINNED_WHISPER[modelId];
  if (!pinned) throw new PinnedFileError(`Whisper model ${modelId} is not pinned.`);
  return { revision: pinned.revision, device: "wasm", dtype: "q8" };
}

type Pin = { url: string; file: PinnedFile; name: string };

/**
 * Whether a path names a pinned repository anywhere in it. transformers.js tries the local-model
 * path (`env.localModelPath` + `<repo>/<file>`, so `/models/<repo>/<file>` in the browser) before
 * the Hugging Face URL as a cache key, and that path never carries the pinned commit.
 */
function namesPinnedRepo(path: string): boolean {
  let decoded = path;
  try {
    decoded = decodeURIComponent(path);
  } catch {
    /* a malformed escape: check the raw path */
  }
  return [path, decoded].some((candidate) =>
    Object.keys(PINNED_WHISPER).some(
      (repo) => candidate === repo || candidate.startsWith(`${repo}/`) || candidate.includes(`/${repo}/`),
    ),
  );
}

/**
 * Sorts a request or cache key. `undefined`: neither Hugging Face nor a pinned repository's
 * path, leave it alone. `"missing"`: a
 * file of a pinned repository that is not listed, which reads as absent. `"refused"`: any other
 * Hugging Face request, and any other key that names a pinned repository. `main` maps onto the pinned commit because transformers.js 4.3 fetches
 * `config.json`, and probes two more files, without the caller's `revision` while it works out
 * which files a pipeline needs (`get_pipeline_files` and `get_file_metadata` in `pipelines.js`).
 */
function pinFor(request: string, remoteHost: string): Pin | "missing" | "refused" | undefined {
  const host = new URL(remoteHost);
  // Only the pinned Hugging Face URLs below serve a pinned repository's files. Any other key that
  // names one (the local-model path, with or without an origin) is refused, so it is never read
  // past the hash check.
  if (!URL.canParse(request)) return namesPinnedRepo(request) ? "refused" : undefined;
  const url = new URL(request);
  if (url.origin !== host.origin) return namesPinnedRepo(url.pathname) ? "refused" : undefined;
  const base = host.pathname.endsWith("/") ? host.pathname : `${host.pathname}/`;
  const match = url.pathname.startsWith(base)
    ? /^([^/]+\/[^/]+)\/resolve\/([^/]+)\/(.+)$/.exec(url.pathname.slice(base.length))
    : null;
  if (!match) return "refused";
  const [, repo, rawRevision, rawName] = match;
  const pinned = Object.hasOwn(PINNED_WHISPER, repo) ? PINNED_WHISPER[repo] : undefined;
  const revision = decodeURIComponent(rawRevision);
  if (!pinned || (revision !== pinned.revision && revision !== "main")) return "refused";
  const name = decodeURIComponent(rawName);
  if (!Object.hasOwn(pinned.files, name)) return "missing";
  return { url: `${host.origin}${base}${repo}/resolve/${pinned.revision}/${name}`, file: pinned.files[name], name };
}

function matches(bytes: Uint8Array, file: PinnedFile): boolean {
  return bytes.byteLength === file.size && bytesToHex(sha256(bytes)) === file.sha256;
}

/**
 * Passes the body through while hashing it, and errors the stream instead of closing it when
 * the size or hash is wrong. transformers.js reads a file to its end before it uses or caches
 * it, so a bad file is never parsed and never stored.
 */
function verifiedBody(body: ReadableStream<Uint8Array>, pin: Pin): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const hash = sha256.create();
  let seen = 0;
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) {
        if (seen === pin.file.size && bytesToHex(hash.digest()) === pin.file.sha256) controller.close();
        else controller.error(new PinnedFileError(`Whisper file ${pin.name} does not match its pinned hash.`));
        return;
      }
      seen += value.byteLength;
      if (seen > pin.file.size) {
        await reader.cancel();
        controller.error(new PinnedFileError(`Whisper file ${pin.name} is larger than pinned.`));
        return;
      }
      hash.update(value);
      controller.enqueue(value);
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}

type Fetch = (input: string | URL, init?: RequestInit) => Promise<Response>;

/** Wraps transformers.js's `env.fetch` so Hugging Face can only deliver the pinned files. */
export function pinnedFetch(fetchImpl: Fetch, remoteHost: string = HUGGING_FACE): Fetch {
  return async (input, init) => {
    const request = String(input);
    const pin = pinFor(request, remoteHost);
    if (pin === undefined) return fetchImpl(input, init);
    // Nothing goes to the network for a file that is not pinned. A 404 keeps transformers.js's
    // optional-file probes quiet; a required file then fails the load.
    if (pin === "missing") return new Response(null, { status: 404 });
    if (pin === "refused") throw new PinnedFileError("Refused a Hugging Face request outside the pinned Whisper files.");
    if (new Headers(init?.headers).has("range")) {
      // transformers.js asks for byte 0 only to learn the size. The pin already knows it,
      // so nothing is sent.
      return new Response(null, { status: 206, headers: { "content-range": `bytes 0-0/${pin.file.size}` } });
    }
    const response = await fetchImpl(pin.url, init);
    if (!response.ok || !response.body) return response;
    const headers = new Headers({ "content-length": String(pin.file.size) });
    const type = response.headers.get("content-type");
    if (type) headers.set("content-type", type);
    return new Response(verifiedBody(response.body, pin), { status: 200, headers });
  };
}

/** The part of the Cache API transformers.js's `env.customCache` uses. */
export type ModelCache = {
  match(request: string): Promise<Response | undefined>;
  put(request: string, response: Response): Promise<void>;
  delete(request: string): Promise<boolean>;
};

/**
 * Wraps the browser cache for `env.customCache`. A pinned file is re-hashed on every read, so a
 * cache entry changed on disk is deleted and fetched again from the pinned commit. `open` runs
 * once; when it fails (an iframe in private mode, say) the worker runs without a cache.
 */
export function pinnedCache(open: () => Promise<ModelCache>, remoteHost: string = HUGGING_FACE): ModelCache {
  let opened: Promise<ModelCache | null> | null = null;
  const base = () => (opened ??= open().catch(() => null));
  return {
    async match(request) {
      const pin = pinFor(request, remoteHost);
      const cache = await base();
      if (!cache || pin === "missing" || pin === "refused") return undefined;
      if (pin === undefined) return cache.match(request);
      const hit = await cache.match(pin.url);
      if (!hit) return undefined;
      const bytes = new Uint8Array(await hit.arrayBuffer());
      if (!matches(bytes, pin.file)) {
        await cache.delete(pin.url);
        return undefined;
      }
      const headers = new Headers({ "content-length": String(bytes.byteLength) });
      const type = hit.headers.get("content-type");
      if (type) headers.set("content-type", type);
      return new Response(bytes, { status: 200, headers });
    },
    async put(request, response) {
      const pin = pinFor(request, remoteHost);
      const cache = await base();
      if (!cache || pin === "missing" || pin === "refused") return;
      await cache.put(pin === undefined ? request : pin.url, response);
    },
    async delete(request) {
      const pin = pinFor(request, remoteHost);
      const cache = await base();
      if (!cache || pin === "missing" || pin === "refused") return false;
      return cache.delete(pin === undefined ? request : pin.url);
    },
  };
}

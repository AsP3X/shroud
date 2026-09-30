/** `ftyp` major brands of HEIF stills and sequences (bytes 8…12 of the file). */
const HEIF_BRANDS = new Set(["heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1"]);

/** True for HEIC/HEIF bytes, by declared type or by sniffing the `ftyp` box. */
export function isHeif(bytes: Uint8Array, mime?: string | null): boolean {
  if (mime && /^image\/hei[cf]/i.test(mime)) return true;
  if (bytes.length < 12) return false;
  const text = (from: number, to: number) => String.fromCharCode(...bytes.subarray(from, to));
  return text(4, 8) === "ftyp" && HEIF_BRANDS.has(text(8, 12).toLowerCase());
}

/** Whether this browser can draw the blob itself (Safari handles HEIC natively). */
export async function decodesNatively(blob: Blob): Promise<boolean> {
  try {
    const bitmap = await createImageBitmap(blob);
    bitmap.close();
    return true;
  } catch {
    return false;
  }
}

type Converted = { blob: Blob; width: number; height: number };
type Reply = { id: number; error?: string } & Partial<Converted>;

let worker: Worker | null = null;
let nextId = 1;
const waiting = new Map<number, { resolve: (value: Converted) => void; reject: (error: Error) => void }>();
/** One decode at a time: a 12 MP HEIC is ~48 MB of pixels while it converts. */
let queue: Promise<unknown> = Promise.resolve();

function heicWorker(): Worker {
  if (worker) return worker;
  const created = new Worker(new URL("./heicWorker.ts", import.meta.url), { type: "module" });
  created.onmessage = (event: MessageEvent<Reply>) => {
    const { id, error, blob, width, height } = event.data;
    const pending = waiting.get(id);
    if (!pending) return;
    waiting.delete(id);
    if (error || !blob || !width || !height) pending.reject(new Error(error || "HEIC conversion failed."));
    else pending.resolve({ blob, width, height });
  };
  created.onerror = (event) => {
    // A worker that failed to load can't serve anyone; start over on the next photo.
    event.preventDefault();
    for (const pending of waiting.values()) pending.reject(new Error("HEIC decoder failed to load."));
    waiting.clear();
    created.terminate();
    if (worker === created) worker = null;
  };
  worker = created;
  return created;
}

/** Converts HEIC/HEIF bytes to a JPEG this browser can draw (lazy-loads ~2 MB of WASM). */
export function heifToJpeg(bytes: Uint8Array, quality = 0.92): Promise<Converted> {
  const run = () =>
    new Promise<Converted>((resolve, reject) => {
      const id = nextId++;
      waiting.set(id, { resolve, reject });
      const copy = bytes.slice().buffer;
      heicWorker().postMessage({ id, bytes: copy, quality }, [copy]);
    });
  const task = queue.then(run, run);
  queue = task.catch(() => undefined);
  return task;
}

/** Drops the decoder worker (lock, logout, or a cleared cache). In-flight converts fail. */
export function resetHeicDecoder(): void {
  for (const pending of waiting.values()) pending.reject(new Error("HEIC decoder reset."));
  waiting.clear();
  queue = Promise.resolve();
  worker?.terminate();
  worker = null;
}

/**
 * SHRF1, the segmented blob a shared file travels in (docs/file-sharing.md §3):
 *
 *   header  = "SHRF1" (5) ‖ noncePrefix (7, random) ‖ segmentSize u32 BE (65536)   // 16 bytes
 *   nonce_i = noncePrefix ‖ u32 BE(i) ‖ (last ? 0x01 : 0x00)
 *   ct_i    = AES-256-GCM(k, nonce_i, plaintext[i·64K ..< min((i+1)·64K, n)], aad = header) ‖ tag
 *   blob    = header ‖ ct_0 ‖ … ‖ ct_last
 *
 * Every segment is one plain WebCrypto AES-GCM call, so a file is sealed and opened 64 KiB at a
 * time and never has to sit in memory whole: sealing reads `Blob.slice`s, opening takes the
 * download as it streams in. Both build their output as a `Blob` the browser may keep on disk.
 * An opened file is handed out only after the last segment's tag passed.
 */
import { randomBytes } from "./bytes";

export const SHRF1_SEGMENT = 65536;
export const SHRF1_HEADER_BYTES = 16;
const TAG_BYTES = 16;
const PREFIX_BYTES = 7;
const MAGIC = [0x53, 0x48, 0x52, 0x46, 0x31]; // "SHRF1"
/** Segments sealed together: one 1 MiB read, sixteen GCM calls in flight. */
const SEAL_BATCH = 16;
/** Bytes collected in JS before they are folded into the output `Blob`. */
const FOLD_BYTES = 8 * 1024 * 1024;

/** A blob that is not SHRF1, does not fit the payload's size, or fails a tag. */
export class FileBlobError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FileBlobError";
  }
}

export function segmentCount(plaintextBytes: number): number {
  return Math.max(1, Math.ceil(plaintextBytes / SHRF1_SEGMENT));
}

/** `16 + n + 16 · max(1, ⌈n / 65536⌉)`. */
export function sealedSize(plaintextBytes: number): number {
  return SHRF1_HEADER_BYTES + plaintextBytes + TAG_BYTES * segmentCount(plaintextBytes);
}

/** Copies into a fresh `ArrayBuffer`-backed view, the type WebCrypto's typings ask for. */
function own(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy;
}

function headerFor(prefix: Uint8Array): Uint8Array<ArrayBuffer> {
  const header = new Uint8Array(SHRF1_HEADER_BYTES);
  header.set(MAGIC, 0);
  header.set(prefix, 5);
  new DataView(header.buffer).setUint32(12, SHRF1_SEGMENT, false);
  return header;
}

function nonceFor(prefix: Uint8Array, index: number, last: boolean): Uint8Array<ArrayBuffer> {
  const nonce = new Uint8Array(12);
  nonce.set(prefix, 0);
  new DataView(nonce.buffer).setUint32(7, index, false);
  nonce[11] = last ? 1 : 0;
  return nonce;
}

async function importKey(rawKey: Uint8Array, usage: KeyUsage): Promise<CryptoKey> {
  if (rawKey.byteLength !== 32) throw new FileBlobError("A file key is 32 bytes.");
  return crypto.subtle.importKey("raw", own(rawKey), "AES-GCM", false, [usage]);
}

/** Collects parts into a `Blob`, folding often enough that JS never holds more than a few MiB. */
class BlobParts {
  private blob: Blob = new Blob([]);
  private parts: Uint8Array<ArrayBuffer>[] = [];
  private pending = 0;

  push(part: Uint8Array<ArrayBuffer>): void {
    this.parts.push(part);
    this.pending += part.byteLength;
    if (this.pending >= FOLD_BYTES) this.fold();
  }

  finish(type: string): Blob {
    const out = new Blob([this.blob, ...this.parts], { type });
    this.parts = [];
    this.pending = 0;
    this.blob = new Blob([]);
    return out;
  }

  private fold(): void {
    this.blob = new Blob([this.blob, ...this.parts]);
    this.parts = [];
    this.pending = 0;
  }
}

function aborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted) throw signal.reason ?? new DOMException("Aborted", "AbortError");
}

/**
 * Seals `source` (a picked `File`) under `rawKey`, one 64 KiB segment at a time. `prefix` is
 * random unless a test pins it. The result is `application/octet-stream`: the server is never
 * told what is inside.
 */
export async function sealFileBlob(
  source: Blob,
  rawKey: Uint8Array,
  opts: { prefix?: Uint8Array; onProgress?: (done: number, total: number) => void; signal?: AbortSignal } = {},
): Promise<Blob> {
  const prefix = opts.prefix ?? randomBytes(PREFIX_BYTES);
  if (prefix.byteLength !== PREFIX_BYTES) throw new FileBlobError("A nonce prefix is 7 bytes.");
  const key = await importKey(rawKey, "encrypt");
  const header = headerFor(prefix);
  const total = source.size;
  const count = segmentCount(total);
  const out = new BlobParts();
  out.push(header);
  opts.onProgress?.(0, total);
  for (let first = 0; first < count; first += SEAL_BATCH) {
    aborted(opts.signal);
    const end = Math.min(count, first + SEAL_BATCH);
    const start = first * SHRF1_SEGMENT;
    const read = new Uint8Array(await source.slice(start, Math.min(total, end * SHRF1_SEGMENT)).arrayBuffer());
    const sealed = await Promise.all(
      Array.from({ length: end - first }, (_, offset) => {
        const index = first + offset;
        const from = offset * SHRF1_SEGMENT;
        return crypto.subtle.encrypt(
          { name: "AES-GCM", iv: nonceFor(prefix, index, index === count - 1), additionalData: header, tagLength: 128 },
          key,
          read.subarray(from, Math.min(read.byteLength, from + SHRF1_SEGMENT)),
        );
      }),
    );
    for (const segment of sealed) out.push(new Uint8Array(segment));
    opts.onProgress?.(Math.min(total, end * SHRF1_SEGMENT), total);
  }
  return out.finish("application/octet-stream");
}

/**
 * Opens a SHRF1 blob as it arrives. `push` takes the download chunk by chunk and decrypts each
 * segment once it is whole; `finish` hands the file over only when every byte came, every tag
 * checked and exactly the final segment carried the last flag. Until then the plaintext stays
 * in an unpublished `Blob` that is dropped with the opener.
 */
export class Shrf1Opener {
  /** Bytes the blob must have, from the payload's `s`. */
  readonly expected: number;
  private received = 0;
  private header: Uint8Array<ArrayBuffer> | null = null;
  private prefix: Uint8Array | null = null;
  private index = 0;
  private readonly count: number;
  private readonly buffer = new Uint8Array(SHRF1_SEGMENT + TAG_BYTES);
  private filled = 0;
  private readonly out = new BlobParts();

  private constructor(
    private readonly key: CryptoKey,
    private readonly size: number,
  ) {
    this.count = segmentCount(size);
    this.expected = sealedSize(size);
  }

  static async create(rawKey: Uint8Array, plaintextBytes: number): Promise<Shrf1Opener> {
    if (!Number.isSafeInteger(plaintextBytes) || plaintextBytes < 0) {
      throw new FileBlobError("The file's size is missing.");
    }
    return new Shrf1Opener(await importKey(rawKey, "decrypt"), plaintextBytes);
  }

  /** Plaintext length of segment `i`. */
  private segmentPlain(index: number): number {
    return Math.min(SHRF1_SEGMENT, this.size - index * SHRF1_SEGMENT);
  }

  async push(chunk: Uint8Array): Promise<void> {
    this.received += chunk.byteLength;
    if (this.received > this.expected) throw new FileBlobError("The file is longer than its size says.");
    let offset = 0;
    while (offset < chunk.byteLength) {
      const want = this.header ? this.segmentPlain(this.index) + TAG_BYTES : SHRF1_HEADER_BYTES;
      const take = Math.min(want - this.filled, chunk.byteLength - offset);
      this.buffer.set(chunk.subarray(offset, offset + take), this.filled);
      this.filled += take;
      offset += take;
      if (this.filled < want) continue;
      this.filled = 0;
      if (!this.header) this.readHeader();
      else await this.openSegment(want);
    }
  }

  private readHeader(): void {
    const header = own(this.buffer.subarray(0, SHRF1_HEADER_BYTES));
    for (let i = 0; i < MAGIC.length; i++) {
      if (header[i] !== MAGIC[i]) throw new FileBlobError("This is not a Shroud file.");
    }
    if (new DataView(header.buffer).getUint32(12, false) !== SHRF1_SEGMENT) {
      throw new FileBlobError("Unexpected segment size.");
    }
    this.header = header;
    this.prefix = header.slice(5, 12);
  }

  private async openSegment(length: number): Promise<void> {
    if (!this.header || !this.prefix || this.index >= this.count) throw new FileBlobError("Too many segments.");
    const last = this.index === this.count - 1;
    let plain: ArrayBuffer;
    try {
      plain = await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: nonceFor(this.prefix, this.index, last),
          additionalData: this.header,
          tagLength: 128,
        },
        this.key,
        own(this.buffer.subarray(0, length)),
      );
    } catch {
      throw new FileBlobError("The file was changed or damaged.");
    }
    this.out.push(new Uint8Array(plain));
    this.index += 1;
  }

  /** The opened file, typed `type`; throws unless the blob was complete and every tag passed. */
  finish(type: string): Blob {
    if (this.received !== this.expected || this.index !== this.count || this.filled !== 0) {
      throw new FileBlobError("The file is shorter than its size says.");
    }
    return this.out.finish(type);
  }
}

/** Opens a sealed blob that is already here (tests, and anything read back from a `Blob`). */
export async function openFileBlob(sealed: Blob, rawKey: Uint8Array, plaintextBytes: number, type: string): Promise<Blob> {
  const opener = await Shrf1Opener.create(rawKey, plaintextBytes);
  if (sealed.size !== opener.expected) throw new FileBlobError("The file's length doesn't match its size.");
  const step = SHRF1_SEGMENT * SEAL_BATCH;
  for (let at = 0; at < sealed.size; at += step) {
    await opener.push(new Uint8Array(await sealed.slice(at, Math.min(sealed.size, at + step)).arrayBuffer()));
  }
  return opener.finish(type);
}

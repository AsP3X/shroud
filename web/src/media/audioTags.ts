/**
 * Title, artist and cover art of an audio file about to be sent (docs/file-sharing.md §11.2),
 * read with `Blob.slice` ranges — never the whole file — from:
 *
 * - ID3v2.2–2.4 at the start (`TT2`/`TIT2`, `TP1`/`TPE1`, `PIC`/`APIC`), MP3, AAC and FLAC;
 * - MP4 `moov/udta/meta/ilst` (`©nam`, `©ART`, `covr`), found by walking box headers, M4A;
 * - FLAC `VORBIS_COMMENT` (`TITLE`, `ARTIST`) and `PICTURE` blocks.
 *
 * Tags come from whoever made the file, so every length is checked against the file and capped
 * before anything is read: a hostile or broken tag yields fewer fields, never an exception or a
 * huge buffer. The text is returned raw; `cleanTagText` (audioFiles.ts) cleans it.
 */

export type AudioCover = {
  /** The picture's bytes, as a slice of the file where they lie in it unchanged. */
  blob: Blob;
};

export type AudioTags = { title: string | null; artist: string | null; cover: AudioCover | null };

/** Most a text field is read up to: far beyond the 200 code points kept. */
const MAX_TEXT_BYTES = 64 * 1024;
/** A cover larger than this is skipped (decoding it would cost more than the 2 s allow). */
export const MAX_COVER_BYTES = 16 * 1024 * 1024;
/** An unsynchronised ID3 tag has to be read whole to be undone; past this it is skipped. */
const MAX_UNSYNC_TAG_BYTES = 16 * 1024 * 1024;
/** Most boxes, frames or blocks looked at, whatever the file claims. */
const MAX_STEPS = 4096;
/** A VORBIS_COMMENT block larger than this is read only this far. */
const MAX_COMMENT_BLOCK = 1024 * 1024;

const EMPTY: AudioTags = { title: null, artist: null, cover: null };

async function read(file: Blob, start: number, end: number): Promise<Uint8Array> {
  const from = Math.max(0, Math.min(start, file.size));
  const to = Math.max(from, Math.min(end, file.size));
  if (to <= from) return new Uint8Array(0);
  return new Uint8Array(await file.slice(from, to).arrayBuffer());
}

function asciiAt(bytes: Uint8Array, at: number, text: string): boolean {
  if (at < 0 || at + text.length > bytes.length) return false;
  for (let i = 0; i < text.length; i++) if (bytes[at + i] !== text.charCodeAt(i)) return false;
  return true;
}

function latin1(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += String.fromCharCode(b);
  return out;
}

function u32be(b: Uint8Array, at: number): number {
  return ((b[at] << 24) >>> 0) + (b[at + 1] << 16) + (b[at + 2] << 8) + b[at + 3];
}

function u32le(b: Uint8Array, at: number): number {
  return ((b[at + 3] << 24) >>> 0) + (b[at + 2] << 16) + (b[at + 1] << 8) + b[at];
}

function syncsafe(b: Uint8Array, at: number): number {
  return ((b[at] & 0x7f) << 21) | ((b[at + 1] & 0x7f) << 14) | ((b[at + 2] & 0x7f) << 7) | (b[at + 3] & 0x7f);
}

/** First non-empty value: later ones (ID3v2.4's null-separated lists) are dropped. */
function firstValue(text: string): string | null {
  const value = text.split("\u0000").find((part) => part.length > 0) ?? "";
  return value.length > 0 ? value : null;
}

function decodeUtf8(bytes: Uint8Array): string {
  return new TextDecoder("utf-8").decode(bytes);
}

/** Undoes ID3 unsynchronisation: every `FF 00` back to `FF`. */
export function resync(bytes: Uint8Array): Uint8Array {
  const out = new Uint8Array(bytes.length);
  let n = 0;
  for (let i = 0; i < bytes.length; i++) {
    out[n++] = bytes[i];
    if (bytes[i] === 0xff && bytes[i + 1] === 0x00) i++;
  }
  return out.subarray(0, n);
}

/* --------------------------------------------------------------------------------- ID3v2 */

/** An ID3 text in encoding 0 (Latin-1), 1 (UTF-16 with BOM), 2 (UTF-16BE) or 3 (UTF-8). */
function id3Text(encoding: number, bytes: Uint8Array): string | null {
  switch (encoding) {
    case 0:
      return firstValue(latin1(bytes));
    case 1: {
      if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) return firstValue(utf16(bytes.subarray(2), false));
      if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) return firstValue(utf16(bytes.subarray(2), true));
      return firstValue(utf16(bytes, true));
    }
    case 2:
      return firstValue(utf16(bytes, false));
    case 3:
      return firstValue(decodeUtf8(bytes));
    default:
      return null;
  }
}

/** UTF-16 without a BOM; a v2.4 list's later BOMs are dropped by the decoder. */
function utf16(bytes: Uint8Array, littleEndian: boolean): string {
  const even = bytes.subarray(0, bytes.length - (bytes.length % 2));
  return new TextDecoder(littleEndian ? "utf-16le" : "utf-16be").decode(even).replace(/﻿/g, "");
}

/** Where a text in `encoding` that starts at `at` ends (its terminator), and where the next field starts. */
function terminated(bytes: Uint8Array, at: number, encoding: number): { end: number; next: number } {
  if (encoding === 1 || encoding === 2) {
    for (let i = at; i + 1 < bytes.length; i += 2) if (bytes[i] === 0 && bytes[i + 1] === 0) return { end: i, next: i + 2 };
    return { end: bytes.length, next: bytes.length };
  }
  for (let i = at; i < bytes.length; i++) if (bytes[i] === 0) return { end: i, next: i + 1 };
  return { end: bytes.length, next: bytes.length };
}

type Id3Frame = {
  id: string;
  /** Where the frame's body (after any extra flag bytes) starts in the file or buffer, and its length. */
  start: number;
  size: number;
  /** v2.4 per-frame unsynchronisation. */
  unsync: boolean;
};

/** A source of bytes: the file itself, or an ID3 tag already read and resynchronised. */
type Source = { size: number; read: (start: number, end: number) => Promise<Uint8Array>; slice: (start: number, end: number) => Blob | null };

function blobSource(file: Blob): Source {
  return { size: file.size, read: (s, e) => read(file, s, e), slice: (s, e) => file.slice(s, e) };
}

function bufferSource(bytes: Uint8Array): Source {
  return {
    size: bytes.length,
    read: async (s, e) => bytes.slice(Math.max(0, s), Math.max(0, Math.min(e, bytes.length))),
    slice: () => null,
  };
}

/** The tag's frames that matter here, found by walking frame headers. */
async function id3Frames(src: Source, version: number, from: number, end: number): Promise<Id3Frame[]> {
  const wanted =
    version === 2 ? new Set(["TT2", "TP1", "PIC"]) : new Set(["TIT2", "TPE1", "APIC"]);
  const headerSize = version === 2 ? 6 : 10;
  const frames: Id3Frame[] = [];
  let at = from;
  for (let step = 0; step < MAX_STEPS && at + headerSize <= end; step++) {
    const h = await src.read(at, at + headerSize);
    if (h.length < headerSize || h[0] === 0) break; // padding
    const idLength = version === 2 ? 3 : 4;
    const id = latin1(h.subarray(0, idLength));
    if (!/^[A-Z0-9]+$/.test(id)) break;
    let size: number;
    let formatFlags = 0;
    if (version === 2) size = (h[3] << 16) | (h[4] << 8) | h[5];
    else {
      size = version === 4 ? syncsafe(h, 4) : u32be(h, 4);
      formatFlags = h[9];
    }
    const bodyStart = at + headerSize;
    if (size <= 0 || bodyStart + size > end) break;
    at = bodyStart + size;
    if (!wanted.has(id)) continue;
    let start = bodyStart;
    let length = size;
    let unsync = false;
    if (version === 3) {
      if (formatFlags & 0xc0) continue; // compressed or encrypted
      if (formatFlags & 0x20) {
        start += 1; // group id
        length -= 1;
      }
    } else if (version === 4) {
      if (formatFlags & 0x0c) continue; // compressed or encrypted
      if (formatFlags & 0x40) {
        start += 1;
        length -= 1;
      }
      if (formatFlags & 0x01) {
        start += 4; // data length indicator
        length -= 4;
      }
      unsync = (formatFlags & 0x02) !== 0;
    }
    if (length > 0) frames.push({ id, start, size: length, unsync });
  }
  return frames;
}

async function id3TextFrame(src: Source, frame: Id3Frame): Promise<string | null> {
  let body = await src.read(frame.start, frame.start + Math.min(frame.size, MAX_TEXT_BYTES));
  if (frame.unsync) body = resync(body);
  if (body.length < 1) return null;
  return id3Text(body[0], body.subarray(1));
}

/** The picture of an `APIC` / `PIC` frame, and whether it is the front cover (type 3). */
async function id3Picture(src: Source, frame: Id3Frame, version: number): Promise<{ cover: AudioCover; front: boolean } | null> {
  if (frame.size > MAX_COVER_BYTES + 1024) return null;
  // The fields before the picture: encoding, the MIME (or v2.2's 3-letter format), the type and
  // the description — a few hundred bytes in practice, read with room to spare.
  const headLength = Math.min(frame.size, frame.unsync ? frame.size : 4096);
  let head = await src.read(frame.start, frame.start + headLength);
  if (frame.unsync) head = resync(head);
  if (head.length < 4) return null;
  const encoding = head[0];
  let at = 1;
  if (version === 2) at += 3;
  else {
    const mime = terminated(head, at, 0);
    if (mime.end >= head.length) return null;
    at = mime.next;
  }
  if (at >= head.length) return null;
  const pictureType = head[at];
  at += 1;
  const description = terminated(head, at, encoding);
  if (description.end >= head.length && !frame.unsync) return null;
  at = description.next;
  if (frame.unsync) {
    const data = head.subarray(at);
    return data.length > 0 ? { cover: { blob: new Blob([data.slice()]) }, front: pictureType === 3 } : null;
  }
  const dataStart = frame.start + at;
  const dataEnd = frame.start + frame.size;
  if (dataEnd <= dataStart) return null;
  const blob = src.slice(dataStart, dataEnd) ?? new Blob([(await src.read(dataStart, dataEnd)).slice()]);
  return { cover: { blob }, front: pictureType === 3 };
}

/** The tags of an ID3v2 tag at the start of `file`, and where the audio after it starts. */
export async function readId3(file: Blob): Promise<{ tags: AudioTags; end: number } | null> {
  const header = await read(file, 0, 10);
  if (!asciiAt(header, 0, "ID3") || header.length < 10) return null;
  const version = header[3];
  if (version < 2 || version > 4) return null;
  const flags = header[5];
  const tagSize = syncsafe(header, 6);
  const footer = version === 4 && flags & 0x10 ? 10 : 0;
  const tagEnd = Math.min(file.size, 10 + tagSize);
  const end = tagEnd + footer;
  // v2.2 compression has no defined scheme: nothing in such a tag can be read.
  if (version === 2 && flags & 0x40) return { tags: EMPTY, end };

  // Whole-tag unsynchronisation (v2.2/v2.3) changes every frame's bytes and offsets: read the
  // tag once, undo it, and walk the frames in memory. Within bounds, or not at all.
  let src: Source = blobSource(file);
  let from = 10;
  let to = tagEnd;
  if (flags & 0x80 && version < 4) {
    if (tagSize > MAX_UNSYNC_TAG_BYTES) return { tags: EMPTY, end };
    const whole = resync(await read(file, 10, tagEnd));
    src = bufferSource(whole);
    from = 0;
    to = whole.length;
  }
  // The extended header (v2.3: its size after the size field, v2.4: syncsafe and inclusive).
  if (version >= 3 && flags & 0x40) {
    const ext = await src.read(from, from + 4);
    if (ext.length < 4) return { tags: EMPTY, end };
    from += version === 4 ? syncsafe(ext, 0) : 4 + u32be(ext, 0);
  }
  if (from >= to) return { tags: EMPTY, end };

  const frames = await id3Frames(src, version, from, to);
  let title: string | null = null;
  let artist: string | null = null;
  let cover: AudioCover | null = null;
  let front = false;
  for (const frame of frames) {
    try {
      if ((frame.id === "TIT2" || frame.id === "TT2") && title == null) title = await id3TextFrame(src, frame);
      else if ((frame.id === "TPE1" || frame.id === "TP1") && artist == null) artist = await id3TextFrame(src, frame);
      else if ((frame.id === "APIC" || frame.id === "PIC") && !front) {
        const picture = await id3Picture(src, frame, version);
        if (picture && (!cover || picture.front)) {
          cover = picture.cover;
          front = picture.front;
        }
      }
    } catch {
      /* one bad frame costs that field only */
    }
  }
  return { tags: { title, artist, cover }, end };
}

/* ----------------------------------------------------------------------------------- MP4 */

type Box = { type: string; start: number; body: number; end: number };

/** The boxes in `[from, to)` of `file`, by their headers alone. */
async function boxes(file: Blob, from: number, to: number): Promise<Box[]> {
  const out: Box[] = [];
  let at = from;
  for (let step = 0; step < MAX_STEPS && at + 8 <= to; step++) {
    const h = await read(file, at, at + 16);
    if (h.length < 8) break;
    let size = u32be(h, 0);
    const type = latin1(h.subarray(4, 8));
    let header = 8;
    if (size === 1) {
      if (h.length < 16) break;
      const high = u32be(h, 8);
      // Beyond 2^53 nothing here is real; a size that big ends the walk.
      if (high > 0x1fffff) break;
      size = high * 0x100000000 + u32be(h, 12);
      header = 16;
    } else if (size === 0) size = to - at;
    if (size < header || at + size > to) break;
    out.push({ type, start: at, body: at + header, end: at + size });
    at += size;
  }
  return out;
}

const child = (list: Box[], type: string) => list.find((b) => b.type === type) ?? null;

/** An `ilst` item's `data` box: its well-known type and payload range. */
async function ilstData(file: Blob, item: Box): Promise<{ kind: number; start: number; end: number } | null> {
  const data = child(await boxes(file, item.body, item.end), "data");
  if (!data || data.end - data.body < 8) return null;
  const head = await read(file, data.body, data.body + 8);
  return { kind: u32be(head, 0) & 0xffffff, start: data.body + 8, end: data.end };
}

async function ilstText(file: Blob, item: Box): Promise<string | null> {
  const data = await ilstData(file, item);
  if (!data) return null;
  const bytes = await read(file, data.start, Math.min(data.end, data.start + MAX_TEXT_BYTES));
  // 1 = UTF-8, 2 = UTF-16BE; 0 (implicit) is UTF-8 in practice.
  if (data.kind === 2) return firstValue(utf16(bytes, false));
  if (data.kind === 1 || data.kind === 0) return firstValue(decodeUtf8(bytes));
  return null;
}

/** Tags of an MP4/M4A file: `moov` (often at the end) → `udta` → `meta` → `ilst`. */
export async function readMp4(file: Blob): Promise<AudioTags | null> {
  const head = await read(file, 0, 12);
  if (!asciiAt(head, 4, "ftyp")) return null;
  const moov = child(await boxes(file, 0, file.size), "moov");
  if (!moov) return EMPTY;
  const inMoov = await boxes(file, moov.body, moov.end);
  const udta = child(inMoov, "udta");
  // Some writers put `meta` straight into `moov`.
  const meta = (udta ? child(await boxes(file, udta.body, udta.end), "meta") : null) ?? child(inMoov, "meta");
  if (!meta) return EMPTY;
  // `meta` is a full box (4 bytes of version and flags) in MP4, a plain one in old QuickTime.
  const probe = await read(file, meta.body, meta.body + 8);
  const metaBody = asciiAt(probe, 4, "hdlr") ? meta.body : meta.body + 4;
  const ilst = child(await boxes(file, metaBody, meta.end), "ilst");
  if (!ilst) return EMPTY;
  let title: string | null = null;
  let artist: string | null = null;
  let cover: AudioCover | null = null;
  for (const item of await boxes(file, ilst.body, ilst.end)) {
    try {
      if (item.type === "©nam" && title == null) title = await ilstText(file, item);
      else if (item.type === "©ART" && artist == null) artist = await ilstText(file, item);
      else if (item.type === "covr" && !cover) {
        const data = await ilstData(file, item);
        // 13 = JPEG, 14 = PNG, 27 = BMP; 0 where the writer didn't say.
        if (data && data.end > data.start && data.end - data.start <= MAX_COVER_BYTES && [0, 13, 14, 27].includes(data.kind)) {
          cover = { blob: file.slice(data.start, data.end) };
        }
      }
    } catch {
      /* one bad item costs that field only */
    }
  }
  return { title, artist, cover };
}

/* ---------------------------------------------------------------------------------- FLAC */

/** Tags of the FLAC stream starting at `at` (after any ID3 tag in front of it). */
export async function readFlac(file: Blob, at = 0): Promise<AudioTags | null> {
  const magic = await read(file, at, at + 4);
  if (!asciiAt(magic, 0, "fLaC")) return null;
  let title: string | null = null;
  let artist: string | null = null;
  let cover: AudioCover | null = null;
  let front = false;
  let offset = at + 4;
  for (let step = 0; step < MAX_STEPS && offset + 4 <= file.size; step++) {
    const h = await read(file, offset, offset + 4);
    if (h.length < 4) break;
    const last = (h[0] & 0x80) !== 0;
    const type = h[0] & 0x7f;
    const length = (h[1] << 16) | (h[2] << 8) | h[3];
    const body = offset + 4;
    if (body + length > file.size) break;
    try {
      if (type === 4) {
        const comments = vorbisComments(await read(file, body, body + Math.min(length, MAX_COMMENT_BLOCK)));
        title ??= comments.get("TITLE") ?? null;
        artist ??= comments.get("ARTIST") ?? null;
      } else if (type === 6 && !front) {
        const picture = await flacPicture(file, body, length);
        if (picture && (!cover || picture.front)) {
          cover = picture.cover;
          front = picture.front;
        }
      }
    } catch {
      /* one bad block costs its fields only */
    }
    offset = body + length;
    if (last || type === 127) break;
  }
  return { title, artist, cover };
}

/** `KEY=value` pairs of a VORBIS_COMMENT block (little-endian lengths), first value per key. */
export function vorbisComments(block: Uint8Array): Map<string, string> {
  const out = new Map<string, string>();
  if (block.length < 8) return out;
  let at = 4 + u32le(block, 0); // vendor string
  if (at + 4 > block.length) return out;
  const count = u32le(block, at);
  at += 4;
  for (let i = 0; i < count && i < MAX_STEPS && at + 4 <= block.length; i++) {
    const length = u32le(block, at);
    at += 4;
    if (at + length > block.length) break;
    const entry = decodeUtf8(block.subarray(at, at + length));
    at += length;
    const eq = entry.indexOf("=");
    if (eq <= 0) continue;
    const key = entry.slice(0, eq).toUpperCase();
    const value = entry.slice(eq + 1);
    if (value && !out.has(key)) out.set(key, value);
  }
  return out;
}

async function flacPicture(file: Blob, body: number, length: number): Promise<{ cover: AudioCover; front: boolean } | null> {
  const head = await read(file, body, body + Math.min(length, 8));
  if (head.length < 8) return null;
  const pictureType = u32be(head, 0);
  const mimeLength = u32be(head, 4);
  let at = 8 + mimeLength;
  if (at + 4 > length) return null;
  const descLength = u32be(await read(file, body + at, body + at + 4), 0);
  at += 4 + descLength + 16; // description, then width, height, depth, colours
  if (at + 4 > length) return null;
  const dataLength = u32be(await read(file, body + at, body + at + 4), 0);
  at += 4;
  if (dataLength <= 0 || dataLength > MAX_COVER_BYTES || at + dataLength > length) return null;
  return { cover: { blob: file.slice(body + at, body + at + dataLength) }, front: pictureType === 3 };
}

/* ------------------------------------------------------------------------------- reading */

/**
 * Whatever tags the file holds, by what it starts with (not by its extension: an ID3 tag can sit
 * in front of an MP3, an AAC or a FLAC stream). Never throws; a file with no readable tags gives
 * all nulls.
 */
export async function readAudioTags(file: Blob): Promise<AudioTags> {
  try {
    const id3 = await readId3(file);
    if (id3) {
      // A FLAC stream behind the ID3 tag fills what the ID3 tag left out.
      const flac = await readFlac(file, id3.end).catch(() => null);
      return {
        title: id3.tags.title ?? flac?.title ?? null,
        artist: id3.tags.artist ?? flac?.artist ?? null,
        cover: id3.tags.cover ?? flac?.cover ?? null,
      };
    }
    return (await readFlac(file)) ?? (await readMp4(file)) ?? EMPTY;
  } catch {
    return EMPTY;
  }
}

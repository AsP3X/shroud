/**
 * The audio tag reader (docs/file-sharing.md §11.2) on hand-built files: ID3v2.2, 2.3 and 2.4
 * (text encodings 0–3, unsynchronisation, extended headers, syncsafe sizes, the data length
 * indicator), MP4 `ilst` behind a large `mdat`, FLAC comments and pictures — and broken or hostile
 * ones, which must give fewer fields and never throw.
 * Run: `npx tsx src/media/audioTags.selftest.ts`.
 */
import { readAudioTags, resync, vorbisComments, MAX_COVER_BYTES, type AudioTags } from "./audioTags";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`audioTags selftest: ${what}`);
}

const enc = new TextEncoder();
const ascii = (s: string) => Uint8Array.from([...s].map((c) => c.charCodeAt(0)));
const cat = (...parts: (Uint8Array | number[])[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};
const be32 = (n: number) => [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];
const le32 = (n: number) => [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff];
const be24 = (n: number) => [(n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];
const syncsafe = (n: number) => [(n >>> 21) & 0x7f, (n >>> 14) & 0x7f, (n >>> 7) & 0x7f, n & 0x7f];
const utf16le = (s: string) => {
  const out: number[] = [0xff, 0xfe];
  for (let i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff, s.charCodeAt(i) >> 8);
  return Uint8Array.from(out);
};
const utf16be = (s: string) => {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) out.push(s.charCodeAt(i) >> 8, s.charCodeAt(i) & 0xff);
  return Uint8Array.from(out);
};
/** Some MPEG audio after the tag, so the file looks like one. */
const AUDIO = Uint8Array.from([0xff, 0xfb, 0x90, 0x64, ...new Uint8Array(64)]);
/** A "picture" with the bytes unsynchronisation has to escape. */
const PICTURE = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0xff, 0x00, 0x4a, 0x46, 0xff]);

async function bytesOf(blob: Blob): Promise<Uint8Array> {
  return new Uint8Array(await blob.arrayBuffer());
}
const same = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i]);

async function expectTags(file: Uint8Array, want: { title: string | null; artist: string | null; cover: Uint8Array | null }, what: string) {
  const tags: AudioTags = await readAudioTags(new Blob([file.slice()]));
  check(tags.title === want.title, `${what}: title ${JSON.stringify(tags.title)}, want ${JSON.stringify(want.title)}`);
  check(tags.artist === want.artist, `${what}: artist ${JSON.stringify(tags.artist)}, want ${JSON.stringify(want.artist)}`);
  if (want.cover) {
    check(tags.cover != null, `${what}: a cover`);
    const got = await bytesOf(tags.cover!.blob);
    check(same(got, want.cover), `${what}: cover bytes ${Array.from(got).join(",")}`);
  } else check(tags.cover == null, `${what}: no cover`);
}

/** Never throws, whatever the bytes. */
async function survives(file: Uint8Array, what: string): Promise<AudioTags> {
  try {
    return await readAudioTags(new Blob([file.slice()]));
  } catch (err) {
    throw new Error(`audioTags selftest: ${what} threw ${String(err)}`);
  }
}

/* ------------------------------------------------------------------------------------- ID3 */

function id3(version: 2 | 3 | 4, frames: Uint8Array, flags = 0, extended: Uint8Array | null = null): Uint8Array {
  const body = extended ? cat(extended, frames) : frames;
  return cat(ascii("ID3"), [version, 0, flags], syncsafe(body.length), body);
}
function frame23(id: string, body: Uint8Array, flags: [number, number] = [0, 0]): Uint8Array {
  return cat(ascii(id), be32(body.length), flags, body);
}
function frame24(id: string, body: Uint8Array, flags: [number, number] = [0, 0]): Uint8Array {
  return cat(ascii(id), syncsafe(body.length), flags, body);
}
function frame22(id: string, body: Uint8Array): Uint8Array {
  return cat(ascii(id), be24(body.length), body);
}
/** Applies ID3 unsynchronisation: a 00 after every FF that precedes 00 or a byte ≥ E0 (or ends). */
function unsync(bytes: Uint8Array): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < bytes.length; i++) {
    out.push(bytes[i]);
    if (bytes[i] === 0xff && (i + 1 === bytes.length || bytes[i + 1] === 0 || bytes[i + 1] >= 0xe0)) out.push(0);
  }
  return Uint8Array.from(out);
}

check(same(resync(unsync(PICTURE)), PICTURE), "unsync round trip");

// ID3v2.3: Latin-1 title, UTF-16 (BOM) artist, an APIC front cover after another picture.
{
  const tag = id3(
    3,
    cat(
      frame23("TXXX", cat([0], ascii("ignored\0value"))),
      frame23("TIT2", cat([0], ascii("Caf\xe9 Midnight"), [0])),
      frame23("TPE1", cat([1], utf16le("M83"), [0, 0])),
      frame23("APIC", cat([0], ascii("image/png\0"), [4], ascii("back\0"), [1, 2, 3])),
      frame23("APIC", cat([0], ascii("image/jpeg\0"), [3], ascii("front\0"), PICTURE)),
      new Uint8Array(32), // padding
    ),
  );
  await expectTags(cat(tag, AUDIO), { title: "Café Midnight", artist: "M83", cover: PICTURE }, "ID3v2.3");
}

// ID3v2.4: UTF-8, a 300-byte title (its size only reads right as syncsafe), an extended header,
// a UTF-16BE artist, a per-frame unsynchronised APIC with a data length indicator.
{
  const long = "Ü".repeat(150); // 300 bytes of UTF-8
  const pictureBody = cat([3], ascii("image/jpeg\0"), [3], enc.encode("Cover ü"), [0], PICTURE);
  const unsynced = unsync(pictureBody);
  const tag = id3(
    4,
    cat(
      frame24("TIT2", cat([3], enc.encode(long))),
      frame24("TPE1", cat([2], utf16be("Bon Iver"))),
      frame24("APIC", cat(syncsafe(pictureBody.length), unsynced), [0, 0x03]),
    ),
    0x40,
    Uint8Array.from([...syncsafe(6), 1, 0]),
  );
  await expectTags(cat(tag, AUDIO), { title: long, artist: "Bon Iver", cover: PICTURE }, "ID3v2.4");
}

// ID3v2.4 lists: only the first value of a null-separated list.
{
  const tag = id3(4, cat(frame24("TIT2", cat([3], enc.encode("One\0Two"))), frame24("TPE1", cat([1], utf16le("A"), [0, 0], utf16le("B")))));
  await expectTags(cat(tag, AUDIO), { title: "One", artist: "A", cover: null }, "ID3v2.4 lists");
}

// ID3v2.3 with the whole tag unsynchronised and an extended header (its size after the field).
{
  const frames = cat(
    frame23("TIT2", cat([0], ascii("Unsynced"))),
    frame23("TPE1", cat([3], enc.encode("Ärtist"))),
    frame23("APIC", cat([0], ascii("image/jpeg\0"), [3], [0], PICTURE)),
  );
  const extended = Uint8Array.from([...be32(6), 0, 0, 0, 0, 0, 0]);
  const body = unsync(cat(extended, frames));
  const tag = cat(ascii("ID3"), [3, 0, 0x80 | 0x40], syncsafe(body.length), body);
  await expectTags(cat(tag, AUDIO), { title: "Unsynced", artist: "Ärtist", cover: PICTURE }, "ID3v2.3 unsynchronised");
}

// ID3v2.2: three-letter frames, PIC with a three-letter format.
{
  const tag = id3(
    2,
    cat(
      frame22("TT2", cat([1], utf16le("Old Song"), [0, 0])),
      frame22("TP1", cat([0], ascii("Old Band"))),
      frame22("PIC", cat([0], ascii("JPG"), [3], [0], PICTURE)),
    ),
  );
  await expectTags(cat(tag, AUDIO), { title: "Old Song", artist: "Old Band", cover: PICTURE }, "ID3v2.2");
}

// Broken and hostile ID3 tags: fewer fields, no exception, no huge buffers.
{
  const good = frame23("TIT2", cat([0], ascii("Kept")));
  // A frame claiming more than the tag holds ends the walk; the frames before it count.
  const overlong = cat(ascii("TPE1"), be32(1_000_000), [0, 0], [0], ascii("x"));
  await expectTags(cat(id3(3, cat(good, overlong)), AUDIO), { title: "Kept", artist: null, cover: null }, "overlong frame");
  // A tag claiming far more than the file (a truncated download, or a lie).
  const truncated = cat(ascii("ID3"), [3, 0, 0], syncsafe(200_000_000), good);
  await expectTags(truncated, { title: "Kept", artist: null, cover: null }, "truncated tag");
  // An unknown text encoding, an APIC without a terminator, a garbage frame id, a compressed frame.
  const odd = cat(
    frame23("TPE1", cat([9], ascii("bad encoding"))),
    frame23("TIT2", cat([0], ascii("Zipped")), [0, 0x80]),
    frame23("APIC", cat([0], ascii("image/jpeg"))),
    cat(ascii("t!t2"), be32(4), [0, 0], ascii("junk")),
  );
  await expectTags(cat(id3(3, odd), AUDIO), { title: null, artist: null, cover: null }, "odd frames");
  // A cover larger than the cap is skipped, not read.
  const hugeBody = cat([0], ascii("image/jpeg\0"), [3, 0], new Uint8Array(MAX_COVER_BYTES + 4096));
  const hugeTag = id3(3, cat(frame23("APIC", hugeBody), good));
  await expectTags(cat(hugeTag, AUDIO), { title: "Kept", artist: null, cover: null }, "a cover past the cap");
  // A whole-tag unsynchronised tag past the cap isn't read whole.
  const bigUnsync = cat(ascii("ID3"), [3, 0, 0x80], syncsafe(200_000_000), good);
  await survives(bigUnsync, "huge unsynchronised tag");
  // Not even a whole header, a wrong version, an empty file.
  for (const bytes of [ascii("ID3"), cat(ascii("ID3"), [9, 0, 0, 0, 0, 0, 10]), new Uint8Array(0), ascii("ID3\x04\x00\x40\x00\x00\x00\x02\xff\xff")]) {
    await survives(bytes, `fragment ${Array.from(bytes).join(",")}`);
  }
  // Random bytes behind an ID3 header, many times over.
  let seed = 7;
  const random = (n: number) => Uint8Array.from({ length: n }, () => ((seed = (seed * 1103515245 + 12345) >>> 0) >>> 16) & 0xff);
  for (let i = 0; i < 200; i++) {
    await survives(cat(ascii("ID3"), [2 + (i % 3), 0, i % 2 ? 0x80 : 0x40], syncsafe(300), random(300)), `random ID3 #${i}`);
    await survives(cat(be32(24), ascii("ftypM4A "), random(16), be32(400), ascii("moov"), random(400)), `random MP4 #${i}`);
    await survives(cat(ascii("fLaC"), random(400)), `random FLAC #${i}`);
  }
}

/* ------------------------------------------------------------------------------------- MP4 */

function box(type: string, ...children: (Uint8Array | number[])[]): Uint8Array {
  const body = cat(...children);
  return cat(be32(8 + body.length), ascii(type), body);
}
const dataBox = (kind: number, payload: Uint8Array) => box("data", Uint8Array.from([0, ...be24(kind), 0, 0, 0, 0]), payload);
const ftyp = box("ftyp", ascii("M4A "), be32(0), ascii("M4A isom"));

// `moov` after a 200 KB `mdat`, `©nam` / `©ART` / `covr` in `moov/udta/meta/ilst`.
{
  const ilst = box(
    "ilst",
    box("©too", dataBox(1, enc.encode("Encoder"))),
    box("©nam", dataBox(1, enc.encode("Holocene"))),
    box("©ART", dataBox(1, enc.encode("Bon Iver"))),
    box("covr", dataBox(13, PICTURE)),
  );
  const meta = box("meta", new Uint8Array(4), box("hdlr", new Uint8Array(25)), ilst);
  const moov = box("moov", box("mvhd", new Uint8Array(100)), box("trak", new Uint8Array(500)), box("udta", meta));
  const file = cat(ftyp, box("free", new Uint8Array(8)), box("mdat", new Uint8Array(200_000)), moov);
  await expectTags(file, { title: "Holocene", artist: "Bon Iver", cover: PICTURE }, "MP4");

  // A QuickTime-style `meta` without version and flags, and a 64-bit `mdat`.
  const qtMeta = box("meta", box("hdlr", new Uint8Array(25)), ilst);
  const large = cat(be32(1), ascii("mdat"), be32(0), be32(16 + 1000), new Uint8Array(1000));
  await expectTags(cat(ftyp, large, box("moov", box("udta", qtMeta))), { title: "Holocene", artist: "Bon Iver", cover: PICTURE }, "MP4 QuickTime meta, 64-bit mdat");

  // UTF-16BE text (type 2).
  const utf16 = box("ilst", box("©nam", dataBox(2, utf16be("Wide"))));
  await expectTags(
    cat(ftyp, box("moov", box("udta", box("meta", new Uint8Array(4), box("hdlr", new Uint8Array(25)), utf16)))),
    { title: "Wide", artist: null, cover: null },
    "MP4 UTF-16",
  );
}
// Broken MP4: a box shorter than its header, a child past its parent, no `moov` at all.
{
  await expectTags(cat(ftyp, be32(4), ascii("free")), { title: null, artist: null, cover: null }, "MP4 box too small");
  const bad = cat(be32(16), ascii("ilst"), be32(9999), ascii("©nam"));
  const file = cat(ftyp, box("moov", box("udta", box("meta", new Uint8Array(4), box("hdlr", new Uint8Array(25)), bad))));
  await expectTags(file, { title: null, artist: null, cover: null }, "MP4 child past its parent");
  await expectTags(cat(ftyp, box("mdat", new Uint8Array(10))), { title: null, artist: null, cover: null }, "MP4 without moov");
  await expectTags(cat(ftyp, be32(1), ascii("mdat"), be32(0xffffffff), be32(0)), { title: null, artist: null, cover: null }, "MP4 absurd 64-bit size");
}

/* ------------------------------------------------------------------------------------ FLAC */

function block(type: number, body: Uint8Array, last = false): Uint8Array {
  return cat([(last ? 0x80 : 0) | type], be24(body.length), body);
}
function comments(...entries: string[]): Uint8Array {
  const vendor = enc.encode("reference libFLAC 1.4.3");
  return cat(le32(vendor.length), vendor, le32(entries.length), ...entries.map((e) => cat(le32(enc.encode(e).length), enc.encode(e))));
}
function picture(type: number, data: Uint8Array): Uint8Array {
  const mime = ascii("image/jpeg");
  const desc = enc.encode("cover");
  return cat(be32(type), be32(mime.length), mime, be32(desc.length), desc, be32(300), be32(300), be32(24), be32(0), be32(data.length), data);
}
const streaminfo = block(0, new Uint8Array(34));

{
  const file = cat(
    ascii("fLaC"),
    streaminfo,
    block(4, comments("title=Interview raw take", "ARTIST=Ana", "TITLE=Second")),
    block(6, picture(4, Uint8Array.from([9, 9]))),
    block(6, picture(3, PICTURE)),
    block(1, new Uint8Array(16), true),
    AUDIO,
  );
  await expectTags(file, { title: "Interview raw take", artist: "Ana", cover: PICTURE }, "FLAC");
  // An ID3 tag in front of the FLAC stream: its fields first, the FLAC comments fill the rest.
  const tagged = cat(id3(3, frame23("TIT2", cat([0], ascii("From ID3")))), file);
  await expectTags(tagged, { title: "From ID3", artist: "Ana", cover: PICTURE }, "ID3 + FLAC");
}
// Broken FLAC: a comment count far past the block, a picture whose lengths lie, a block past the end.
{
  const lying = cat(be32(3), be32(0xfffffff0), ascii("image/png"));
  const file = cat(
    ascii("fLaC"),
    streaminfo,
    block(4, cat(le32(0), le32(1_000_000), le32(5), ascii("TITLE"))),
    block(6, lying),
    block(4, comments("ARTIST=Late"), true),
  );
  await expectTags(file, { title: null, artist: "Late", cover: null }, "FLAC with lying blocks");
  await expectTags(cat(ascii("fLaC"), [0x84, 0xff, 0xff, 0xff], ascii("short")), { title: null, artist: null, cover: null }, "FLAC block past the end");
  check(vorbisComments(cat(le32(0xffffffff))).size === 0, "a vendor length past the block");
}

// Files without tags.
await expectTags(AUDIO, { title: null, artist: null, cover: null }, "bare MPEG");
await expectTags(ascii("OggS\0\x02"), { title: null, artist: null, cover: null }, "Ogg (not read)");

console.log("audioTags selftest ok");

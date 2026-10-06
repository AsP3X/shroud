// Generates the file-sharing vectors pinned by the iOS, web and Android tests (docs/file-sharing.md),
// with Node's own crypto (OpenSSL) — an implementation independent of CryptoKit, WebCrypto and the JCA.
//
//   node scripts/gen_file_vectors.mjs
//
// No dependencies. Re-running it must print exactly the constants in FileBlobTests / FileNameTests
// (iOS), file.selftest.ts (web) and FileBlobTest / FileNameTest (Android), and the audio blocks
// (§11: tag cleaning, display titles, durations, content sniffing) pinned next to them.
//
// SHRF1 (docs/file-sharing.md §3):
//   header  = "SHRF1" (5) ‖ noncePrefix (7) ‖ segmentSize u32 BE (65536)        // 16 bytes
//   nonce_i = noncePrefix ‖ u32 BE(i) ‖ (last ? 0x01 : 0x00)
//   ct_i    = AES-256-GCM(k, nonce_i, plaintext[i·64K ..< min((i+1)·64K, n)], aad = header) ‖ tag
//   blob    = header ‖ ct_0 ‖ … ‖ ct_last

import { createCipheriv, createHash } from "node:crypto";

const SEGMENT = 65536;
const MAGIC = Buffer.from("SHRF1", "ascii");

function seal(key, prefix, plaintext) {
  const header = Buffer.concat([MAGIC, prefix, Buffer.from([0, 1, 0, 0])]);
  const count = Math.max(1, Math.ceil(plaintext.length / SEGMENT));
  const parts = [header];
  for (let i = 0; i < count; i++) {
    const last = i === count - 1;
    const nonce = Buffer.alloc(12);
    prefix.copy(nonce, 0);
    nonce.writeUInt32BE(i, 7);
    nonce[11] = last ? 1 : 0;
    const cipher = createCipheriv("aes-256-gcm", key, nonce);
    cipher.setAAD(header);
    const chunk = plaintext.subarray(i * SEGMENT, Math.min((i + 1) * SEGMENT, plaintext.length));
    parts.push(cipher.update(chunk), cipher.final(), cipher.getAuthTag());
  }
  return Buffer.concat(parts);
}

const key = Buffer.from([...Array(32).keys()]); // 00 01 … 1f
const prefix = Buffer.from([0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6]);
const pattern = (n) => Buffer.from(Array.from({ length: n }, (_, i) => i % 251));
const sha = (b) => createHash("sha256").update(b).digest("hex");

console.log("== SHRF1 ==");
console.log("key    =", key.toString("hex"));
console.log("prefix =", prefix.toString("hex"));
const hello = seal(key, prefix, Buffer.from("hello", "utf8"));
console.log(`"hello" (5 B) -> ${hello.length} B: ${hello.toString("hex")}`);
for (const n of [65536, 65537, 200000]) {
  const blob = seal(key, prefix, pattern(n));
  console.log(`pattern(${n}) -> ${blob.length} B, sha256 ${sha(blob)}`);
}
console.log(`sealedSize(n) = 16 + n + 16 * max(1, ceil(n / 65536))`);

// ---------------------------------------------------------------- names (docs/file-sharing.md §5)

const REMOVED = new Set([
  ...range(0x00, 0x08), ...range(0x0e, 0x1f), ...range(0x7f, 0x9f), 0xad, 0x061c, 0x180e, ...range(0x200b, 0x200f),
  ...range(0x202a, 0x202e), ...range(0x2060, 0x2064), ...range(0x2066, 0x206f), 0x2028, 0x2029,
  0xfeff, ...range(0xfff9, 0xfffb),
]);
const SPACES = new Set([...range(0x09, 0x0d), 0x20, 0xa0, 0x1680, ...range(0x2000, 0x200a), 0x202f, 0x205f, 0x3000]);
const REPLACED = new Set([..."<>:\"|?*"].map((c) => c.codePointAt(0)));
const MAX_NAME = 120;

function range(a, b) {
  return Array.from({ length: b - a + 1 }, (_, i) => a + i);
}

function trimSpacesDots(cps) {
  let start = 0;
  let end = cps.length;
  while (start < end && (cps[start] === 0x20 || cps[start] === 0x2e)) start++;
  while (end > start && (cps[end - 1] === 0x20 || cps[end - 1] === 0x2e)) end--;
  return cps.slice(start, end);
}

export function sanitizeFileName(raw) {
  let s = raw.normalize("NFC");
  const cut = Math.max(s.lastIndexOf("/"), s.lastIndexOf("\\"));
  if (cut >= 0) s = s.slice(cut + 1);
  let cps = [];
  for (const ch of s) {
    const cp = ch.codePointAt(0);
    if (REMOVED.has(cp)) continue;
    if (REPLACED.has(cp)) cps.push(0x5f);
    else if (SPACES.has(cp)) {
      if (cps[cps.length - 1] !== 0x20) cps.push(0x20);
    } else cps.push(cp);
  }
  cps = trimSpacesDots(cps);
  let stem = cps;
  let ext = [];
  const dot = cps.lastIndexOf(0x2e);
  if (dot > 0) {
    const candidate = cps.slice(dot + 1);
    if (candidate.length >= 1 && candidate.length <= 10 &&
        candidate.every((c) => (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a))) {
      stem = cps.slice(0, dot);
      ext = candidate;
    }
  }
  const budget = MAX_NAME - (ext.length ? ext.length + 1 : 0);
  if (stem.length > budget) stem = trimSpacesDots(stem.slice(0, budget));
  else stem = trimSpacesDots(stem);
  if (stem.length === 0) stem = [..."file"].map((c) => c.codePointAt(0));
  const out = ext.length ? [...stem, 0x2e, ...ext] : stem;
  return String.fromCodePoint(...out);
}

console.log("\n== names ==");
const names = [
  "report.pdf",
  "../../etc/passwd",
  "C:\\Users\\me\\Desktop\\Budget 2026.XLSX",
  "invoice\u202Efdp.exe",
  "  .hidden.txt  ",
  "what?<now>:\"x\"|*.docx",
  "tabs\tand\nnewlines.txt",
  "many     spaces\u00A0\u3000here.csv",
  "trailing dots....",
  "noext",
  ".pdf",
  "...",
  "",
  "e\u0301te\u0301.txt",
  "a".repeat(130) + ".pptx",
  "x".repeat(200),
  "archive.tar.gz",
  "weird.ext-with-dash",
  "zero\u200Bwidth\uFEFF.apk",
  "emoji 📄 notes.TXT",
];
for (const n of names) console.log(JSON.stringify(n), "->", JSON.stringify(sanitizeFileName(n)));

// ---------------------------------------------------------------- audio (docs/file-sharing.md §11)

const MAX_TAG = 200;

// §11.2: tag text (`ti`, `ar`) is cleaned like a name without the path, character and extension
// rules: NFC, drop the §5 step-3 code points, collapse §5 spaces, trim spaces, cut to 200 code
// points and trim the end again. Empty → absent (null here).
export function cleanTagText(raw) {
  let cps = [];
  for (const ch of raw.normalize("NFC")) {
    const cp = ch.codePointAt(0);
    if (REMOVED.has(cp)) continue;
    if (SPACES.has(cp)) {
      if (cps.length && cps[cps.length - 1] !== 0x20) cps.push(0x20);
    } else cps.push(cp);
  }
  if (cps.length > MAX_TAG) cps = cps.slice(0, MAX_TAG);
  while (cps.length && cps[cps.length - 1] === 0x20) cps.pop();
  return cps.length ? String.fromCodePoint(...cps) : null;
}

// §11.4: durations are whole seconds, rounded for a total (`d`) and floored for the elapsed time.
export function formatDuration(seconds) {
  const s = Math.max(0, Math.trunc(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${r}` : `${m}:${r}`;
}
const totalLabel = (ms) => formatDuration(Math.round(ms / 1000));
const elapsedLabel = (ms) => formatDuration(Math.floor(ms / 1000));

// §11.2: what the bubble, the quote and the chat list call the file.
export function audioDisplayTitle(ti, ar, name) {
  const t = ti == null ? null : cleanTagText(ti);
  const a = ar == null ? null : cleanTagText(ar);
  if (t && a) return `${t} – ${a}`;
  return t ?? sanitizeFileName(name);
}

// §4 content check for the audio types.
const ascii = (b, at, s) => [...s].every((c, i) => b[at + i] === c.charCodeAt(0));
export function audioContentMatches(ext, b) {
  const id3 = ascii(b, 0, "ID3");
  switch (ext) {
    case "mp3": return id3 || (b.length >= 2 && b[0] === 0xff && (b[1] & 0xe0) === 0xe0);
    case "aac": return id3 || (b.length >= 2 && b[0] === 0xff && (b[1] & 0xf6) === 0xf0);
    case "m4a": return ascii(b, 4, "ftyp");
    case "wav": return ascii(b, 0, "RIFF") && ascii(b, 8, "WAVE");
    case "flac": return id3 || ascii(b, 0, "fLaC");
    case "ogg": case "opus": return ascii(b, 0, "OggS");
    case "aif": case "aiff": return ascii(b, 0, "FORM") && (ascii(b, 8, "AIFF") || ascii(b, 8, "AIFC"));
    default: return false;
  }
}

console.log("\n== audio tags ==");
const tags = [
  "Midnight City",
  "  Holocene  ",
  "Bon  Iver",
  "line\nbreak\ttab",
  "rtl‮override",
  "zero​width﻿",
  "Beyoncé",
  "   ",
  "",
  "\u0000\u0007",
  "T".repeat(199) + " x",
  "y".repeat(250),
  "🎵 emoji title",
];
for (const t of tags) console.log(JSON.stringify(t), "->", JSON.stringify(cleanTagText(t)));

console.log("\n== audio titles ==");
const titles = [
  ["Midnight City", "M83", "track01.mp3"],
  ["Midnight City", null, "track01.mp3"],
  [null, "M83", "track01.mp3"],
  ["  ", "  ", "Interview raw take.flac"],
  [null, null, "../x/Demo v3 (final mix).wav"],
];
for (const [ti, ar, n] of titles) console.log(JSON.stringify([ti, ar, n]), "->", JSON.stringify(audioDisplayTitle(ti, ar, n)));

console.log("\n== audio durations ==");
for (const ms of [0, 499, 500, 999, 59499, 59500, 243400, 3599499, 3599500, 3600000, 45296000]) {
  console.log(`${ms} ms -> total ${JSON.stringify(totalLabel(ms))}, elapsed ${JSON.stringify(elapsedLabel(ms))}`);
}

console.log("\n== audio sniff ==");
const sniffs = [
  ["mp3", "494433040000"], ["mp3", "fffb9064"], ["mp3", "fff15080"], ["mp3", "00000020"],
  ["aac", "fff15080"], ["aac", "fff95080"], ["aac", "fffb9064"], ["aac", "494433"],
  ["m4a", "0000002066747970"], ["m4a", "0000002066726565"],
  ["wav", "52494646244200005741564566"], ["wav", "524946462442000041564920"],
  ["flac", "664c614300000022"], ["flac", "4944330300"], ["flac", "4f676753"],
  ["ogg", "4f67675300020000"], ["opus", "4f67675300020000"], ["ogg", "664c6143"],
  ["aiff", "464f524d0000a0c641494646"], ["aif", "464f524d0000a0c641494643"], ["aiff", "464f524d0000a0c64d415220"],
  ["mp3", ""],
];
for (const [ext, hex] of sniffs) console.log(`${ext} ${hex || "(empty)"} -> ${audioContentMatches(ext, Buffer.from(hex, "hex"))}`);

// Generates the file-sharing vectors pinned by the iOS, web and Android tests (docs/file-sharing.md),
// with Node's own crypto (OpenSSL) — an implementation independent of CryptoKit, WebCrypto and the JCA.
//
//   node scripts/gen_file_vectors.mjs
//
// No dependencies. Re-running it must print exactly the constants in FileBlobTests / FileNameTests
// (iOS), file.selftest.ts (web) and FileBlobTest / FileNameTest (Android).
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

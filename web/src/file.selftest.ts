/**
 * File sharing (docs/file-sharing.md): the SHRF1 blob, the name rules, the type table, the
 * content check and the wire shapes. The SHRF1 rows and the 20 name rows are the exact output
 * of `node scripts/gen_file_vectors.mjs`, which iOS and Android pin too.
 * Run: `npx tsx src/file.selftest.ts`.
 */
import { FileBlobError, openFileBlob, sealedSize, sealFileBlob, Shrf1Opener, SHRF1_SEGMENT } from "./crypto/fileBlob";
import { bytesToHex } from "./crypto/bytes";
import { isFilePayload, isVideoPayload, isVoicePayload, parseMediaPayload, withReply } from "./crypto/mediaPayload";
import {
  blobMimeOf,
  contentMatches,
  emptyRefusal,
  FILE_ACCEPT,
  FILE_EXTENSIONS,
  fileAccessibilityLabel,
  fileExtension,
  fileMetaLine,
  fileTypeOf,
  MAX_FILE_BYTES,
  middleTruncationParts,
  openModeOf,
  pdfPageSubtitle,
  sanitizeFileName,
  TOO_MANY_FILES,
  tooLargeRefusal,
  triageFiles,
  unsupportedRefusal,
  warningDialog,
  warningLine,
} from "./files";
import { parseReplyRef, replyKindLabel, replyRefWire } from "./reply";
import { rowBubble } from "./rowBubble";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`file selftest: ${what}`);
}

async function rejects(task: Promise<unknown>, what: string): Promise<void> {
  try {
    await task;
  } catch (err) {
    check(err instanceof FileBlobError, `${what}: expected a FileBlobError, got ${String(err)}`);
    return;
  }
  throw new Error(`file selftest: ${what} must be refused`);
}

async function sha256(blob: Blob): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", await blob.arrayBuffer())));
}

async function bytesOf(blob: Blob): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(await blob.arrayBuffer());
}

/* ------------------------------------------------------------------------------ SHRF1 vectors */

const KEY: Uint8Array<ArrayBuffer> = new Uint8Array(Array.from({ length: 32 }, (_, i) => i));
const PREFIX = Uint8Array.from([0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6]);
const pattern = (n: number) => Uint8Array.from({ length: n }, (_, i) => i % 251);

const hello = await sealFileBlob(new Blob([new TextEncoder().encode("hello")]), KEY, { prefix: PREFIX });
check(hello.size === 37, "hello is 37 bytes");
check(
  bytesToHex(await bytesOf(hello)) ===
    "5348524631a0a1a2a3a4a5a6000100001f042572d7a195d96a199b78b66a618521c6b35bc9",
  "hello vector",
);
check(hello.type === "application/octet-stream", "a sealed blob says nothing about its type");

const VECTORS: [number, number, string][] = [
  [65536, 65568, "440b505128af2e20c12d50b1009d4113697e1ecb8b9791171608c1e3fc83d74e"],
  [65537, 65585, "6caead1d0580c871ba5d374b0ba5ff1482ebaf769370a637c6597b832d5f3b08"],
  [200000, 200080, "b59a0b0206599c2fb139a31cc5a82d0feb6e8e6a715f5e4dbc03081275f924a5"],
];
const sealedPatterns = new Map<number, Blob>();
for (const [n, size, digest] of VECTORS) {
  const sealed = await sealFileBlob(new Blob([pattern(n)]), KEY, { prefix: PREFIX });
  check(sealed.size === size && sealedSize(n) === size, `pattern(${n}) is ${size} bytes`);
  check((await sha256(sealed)) === digest, `pattern(${n}) sha256`);
  sealedPatterns.set(n, sealed);
}
check(sealedSize(0) === 32 && sealedSize(5) === 37 && sealedSize(131072) === 131072 + 48, "sealedSize");

/* ------------------------------------------------------------------------------ SHRF1 opening */

for (const [n] of VECTORS) {
  const opened = await openFileBlob(sealedPatterns.get(n)!, KEY, n, "application/pdf");
  check(opened.type === "application/pdf", "opened blob takes the table's type");
  const bytes = await bytesOf(opened);
  check(bytes.length === n && bytes.every((b, i) => b === i % 251), `pattern(${n}) round trip`);
}

// Streaming: the download arrives in odd chunks that never line up with a segment.
{
  const sealed = await bytesOf(sealedPatterns.get(200000)!);
  const opener = await Shrf1Opener.create(KEY, 200000);
  for (let at = 0; at < sealed.length; at += 1013) await opener.push(sealed.subarray(at, at + 1013));
  const bytes = await bytesOf(opener.finish("text/plain"));
  check(bytes.length === 200000 && bytes.every((b, i) => b === i % 251), "streaming round trip");
}
// A random prefix and a random key round-trip too.
{
  const key = crypto.getRandomValues(new Uint8Array(32));
  const plain = Uint8Array.from({ length: 3 * SHRF1_SEGMENT + 17 }, () => Math.floor(Math.random() * 256));
  const sealed = await sealFileBlob(new Blob([plain]), key);
  const back = await bytesOf(await openFileBlob(sealed, key, plain.length, "application/pdf"));
  check(back.length === plain.length && back.every((b, i) => b === plain[i]), "random round trip");
}

{
  const sealed = await bytesOf(sealedPatterns.get(200000)!);
  const open = (bytes: Uint8Array<ArrayBuffer>, size = 200000, key: Uint8Array = KEY) =>
    openFileBlob(new Blob([bytes]), key, size, "application/octet-stream");

  const flipped = sealed.slice();
  flipped[16 + 70000] ^= 0x01;
  await rejects(open(flipped), "a flipped ciphertext byte");

  const tag = sealed.slice();
  tag[tag.length - 1] ^= 0x80;
  await rejects(open(tag), "a flipped tag byte");

  await rejects(open(sealed.subarray(0, sealed.length - 1)), "a truncated blob");
  await rejects(open(new Uint8Array([...sealed, 0])), "an appended byte");

  // Segments 0 and 1 swapped: both are whole segments, but the index sits in the nonce.
  const seg = SHRF1_SEGMENT + 16;
  const swapped = sealed.slice();
  swapped.set(sealed.subarray(16 + seg, 16 + 2 * seg), 16);
  swapped.set(sealed.subarray(16, 16 + seg), 16 + seg);
  await rejects(open(swapped), "reordered segments");

  await rejects(open(sealed, 199999), "a size one byte short");
  await rejects(open(sealed, 200001), "a size one byte long");
  await rejects(open(sealed, 200000, new Uint8Array(32)), "the wrong key");

  const magic = sealed.slice();
  magic[0] = 0x58;
  await rejects(open(magic), "a wrong magic");
  const segmentSize = sealed.slice();
  segmentSize[14] = 0x80; // 0x00018000
  await rejects(open(segmentSize), "a segment size other than 65536");
  const prefix = sealed.slice();
  prefix[6] ^= 0x01;
  await rejects(open(prefix), "a changed header (it is every segment's AAD)");

  // Dropping the last segment of a two-segment blob: the first was sealed without the last flag.
  const two = await bytesOf(sealedPatterns.get(65537)!);
  await rejects(open(two.subarray(0, 16 + SHRF1_SEGMENT + 16), 65536), "a blob cut at a segment boundary");

  // A segment spliced in from another file under the same key (different prefix).
  const other = await bytesOf(await sealFileBlob(new Blob([pattern(200000)]), KEY));
  const spliced = sealed.slice();
  spliced.set(other.subarray(16 + seg, 16 + 2 * seg), 16 + seg);
  await rejects(open(spliced), "a spliced segment");

  // The streaming reader refuses an overlong blob as soon as it passes the size, and a short
  // one at the end, before any plaintext is handed out.
  const opener = await Shrf1Opener.create(KEY, 200000);
  await opener.push(sealed.subarray(0, sealed.length - 10));
  let refused = false;
  try {
    opener.finish("text/plain");
  } catch (err) {
    refused = err instanceof FileBlobError;
  }
  check(refused, "finish before the last tag");
  await rejects(Shrf1Opener.create(KEY, Number.NaN), "a missing size");
}

// A last flag on a segment that isn't the last: seal two segments by hand, both flagged.
{
  const header = await bytesOf((await sealFileBlob(new Blob([new Uint8Array(0)]), KEY, { prefix: PREFIX })).slice(0, 16));
  const aes = await crypto.subtle.importKey("raw", KEY, "AES-GCM", false, ["encrypt"]);
  const nonce = (i: number) => Uint8Array.from([...PREFIX, 0, 0, 0, i, 1]);
  const segments: Uint8Array<ArrayBuffer>[] = [];
  for (let i = 0; i < 2; i++) {
    const chunk = i === 0 ? pattern(SHRF1_SEGMENT) : pattern(10);
    segments.push(
      new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce(i), additionalData: header }, aes, chunk)),
    );
  }
  const blob = new Blob([header, ...segments]);
  check(blob.size === sealedSize(SHRF1_SEGMENT + 10), "hand-sealed length");
  await rejects(openFileBlob(blob, KEY, SHRF1_SEGMENT + 10, "text/plain"), "a last flag on segment 0");
}

/* ------------------------------------------------------------------------------- name vectors */

const NAMES: [string, string][] = [
  ["report.pdf", "report.pdf"],
  ["../../etc/passwd", "passwd"],
  ["C:\\Users\\me\\Desktop\\Budget 2026.XLSX", "Budget 2026.XLSX"],
  ["invoice\u202Efdp.exe", "invoicefdp.exe"],
  ["  .hidden.txt  ", "hidden.txt"],
  ["what?<now>:\"x\"|*.docx", "what__now___x___.docx"],
  ["tabs\tand\nnewlines.txt", "tabs and newlines.txt"],
  ["many     spaces\u00A0\u3000here.csv", "many spaces here.csv"],
  ["trailing dots....", "trailing dots"],
  ["noext", "noext"],
  [".pdf", "pdf"],
  ["...", "file"],
  ["", "file"],
  ["e\u0301te\u0301.txt", "\u00e9t\u00e9.txt"],
  ["a".repeat(130) + ".pptx", "a".repeat(115) + ".pptx"],
  ["x".repeat(200), "x".repeat(120)],
  ["archive.tar.gz", "archive.tar.gz"],
  ["weird.ext-with-dash", "weird.ext-with-dash"],
  ["zero\u200Bwidth\uFEFF.apk", "zerowidth.apk"],
  ["emoji 📄 notes.TXT", "emoji 📄 notes.TXT"],
];
check(NAMES.length === 20, "all 20 name rows");
for (const [raw, cleaned] of NAMES) {
  const got = sanitizeFileName(raw);
  check(got === cleaned, `name ${JSON.stringify(raw)} -> ${JSON.stringify(got)}, want ${JSON.stringify(cleaned)}`);
  check(sanitizeFileName(got) === got, `cleaning ${JSON.stringify(cleaned)} again changes nothing`);
}
check([...sanitizeFileName("📄".repeat(130) + ".pdf")].length === 120, "the cap counts code points");
check(sanitizeFileName("a/b\\c.pdf") === "c.pdf", "both separators");

/* ---------------------------------------------------------------------------------- type table */

check(FILE_EXTENSIONS.length === 45, `45 extensions, got ${FILE_EXTENSIONS.length}`);
const MACROS = ["doc", "dot", "docm", "dotm", "xls", "xlt", "xlsm", "xltm", "xlsb", "ppt", "pps", "pot", "pptm", "ppsm", "potm"];
for (const ext of FILE_EXTENSIONS) {
  const type = fileTypeOf(`x.${ext}`)!;
  check(type.ext === ext, `fileTypeOf .${ext}`);
  const expected = ext === "apk" ? "app" : MACROS.includes(ext) ? "macros" : null;
  check(type.warning === expected, `warning of .${ext}`);
  check(blobMimeOf(type) === type.mime, `blob type of .${ext}`);
  check(!/html|svg|javascript/.test(type.mime), `.${ext} can't run script`);
  check(FILE_ACCEPT.split(",").includes(`.${ext}`), `picker offers .${ext}`);
}
check(fileTypeOf("Budget 2026.XLSX")?.mime === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx, any case");
check(fileTypeOf("Photo.JPEG")?.mime === "image/jpeg" && fileTypeOf("a.jpg")?.mime === "image/jpeg", "jpg/jpeg");
check(fileTypeOf("clip.mov")?.mime === "video/quicktime" && fileTypeOf("a.3gp")?.mime === "video/3gpp", "videos");
check(fileTypeOf("app.apk")?.mime === "application/vnd.android.package-archive", "apk");
check(fileTypeOf("old.doc")?.mime === "application/msword" && fileTypeOf("old.dot")?.mime === "application/msword", "doc");
for (const name of ["a.svg", "a.html", "a.htm", "a.exe", "a.zip", "a.js", "archive.tar.gz", "noext", "pdf", "a.xml", "a.json"]) {
  check(fileTypeOf(name) === null, `${name} is unsupported`);
}
check(fileExtension("archive.tar.gz") === "gz" && fileExtension("weird.ext-with-dash") === "", "fileExtension");
check(openModeOf(fileTypeOf("a.pdf")!) === "pdf" && openModeOf(fileTypeOf("a.png")!) === "tab", "pdf in the viewer, image in a tab");
check(openModeOf(fileTypeOf("a.mp4")!) === "tab" && openModeOf(fileTypeOf("a.csv")!) === "text", "video tab, csv viewer");
check(openModeOf(fileTypeOf("a.docx")!) === "download" && openModeOf(fileTypeOf("a.apk")!) === "download", "the rest downloads");

/* ------------------------------------------------------------------------------- content check */

const zip = Uint8Array.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]);
const ole = Uint8Array.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1, 0x00]);
const ascii = (text: string) => new TextEncoder().encode(text);
check(contentMatches("pdf", ascii("%PDF-1.7\n")), "pdf at 0");
check(contentMatches("pdf", new Uint8Array([...new Uint8Array(1019), ...ascii("%PDF-")])), "pdf ending at 1024");
check(!contentMatches("pdf", new Uint8Array([...new Uint8Array(1020), ...ascii("%PDF-")])), "pdf past 1024");
check(!contentMatches("pdf", zip), "zip is not a pdf");
for (const ext of ["docx", "dotx", "docm", "dotm", "xlsx", "xltx", "xlsm", "xltm", "xlsb", "pptx", "ppsx", "potx", "pptm", "ppsm", "potm", "apk"]) {
  check(contentMatches(ext, zip) && !contentMatches(ext, ole) && !contentMatches(ext, ascii("PK")), `zip check .${ext}`);
}
for (const ext of ["doc", "dot", "xls", "xlt", "ppt", "pps", "pot"]) {
  check(contentMatches(ext, ole) && !contentMatches(ext, zip), `ole check .${ext}`);
}
check(contentMatches("rtf", ascii("{\\rtf1\\ansi")) && !contentMatches("rtf", ascii("{\\rt")), "rtf");
check(contentMatches("txt", ascii("plain text")) && contentMatches("csv", ascii("a,b\n1,2")), "text");
check(!contentMatches("txt", new Uint8Array([0x61, 0x00, 0x62])), "a NUL is not text");
check(contentMatches("csv", new Uint8Array([...new Uint8Array(8192).fill(0x61), 0x00])), "a NUL past 8 KiB is fine");
check(contentMatches("png", zip) && contentMatches("mkv", new Uint8Array(0)), "images and videos aren't checked");
check(!contentMatches("exe", ascii("MZ")) && !contentMatches("", zip), "unsupported never matches");
check(contentMatches("PDF", ascii("%PDF-")), "the lookup lowercases");

/* ---------------------------------------------------------------------------------------- copy */

check(unsupportedRefusal("a.exe") === "Shroud can't send “a.exe”: this file type isn't supported.", "unsupported copy");
check(tooLargeRefusal("big.mov") === "“big.mov” is larger than 2 GB.", "too large copy");
check(emptyRefusal("e.txt") === "“e.txt” is empty.", "empty copy");
check(TOO_MANY_FILES === "You can send up to 10 files at once.", "count copy");
check(warningLine("app") === "Installs an app" && warningLine("macros") === "May contain macros", "warning lines");
check(warningDialog("app", "Ana").title === "This file can install an app", "app dialog title");
check(
  warningDialog("app", "Ana").message ===
    "APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust Ana and expected this file.",
  "app dialog message",
);
check(warningDialog("macros", "Ana").title === "This file may contain macros", "macro dialog title");
check(
  warningDialog("macros", "Ana").message ===
    "Macros in Office files can run harmful code. Only continue if you trust Ana and expected this file, and don't turn on macros unless you're sure.",
  "macro dialog message",
);
check(fileMetaLine(2.4 * 1024 * 1024, "Quarterly report 2026.pdf") === "2.4 MB · PDF", "meta line");
check(fileAccessibilityLabel("report.pdf", 2048) === "File, report.pdf, 2 KB", "a11y label");
check(fileAccessibilityLabel("m.xlsm", 2048) === "File, m.xlsm, 2 KB, may contain macros", "a11y macros");
check(fileAccessibilityLabel("a.apk", 2048) === "File, a.apk, 2 KB, installs an app", "a11y app");
check(fileMetaLine(2.4 * 1024 * 1024, "Quarterly report 2026.pdf", 12) === "12 pages · 2.4 MB · PDF", "meta line with pages");
check(fileMetaLine(2048, "one.pdf", 1) === "1 page · 2 KB · PDF", "one page");
check(fileMetaLine(2048, "one.docx", 3) === "2 KB · DOCX", "pages only for PDFs");
check(fileMetaLine(2048, "one.pdf", 0) === "2 KB · PDF", "no bogus page count");
check(fileAccessibilityLabel("report.pdf", 2048, 12) === "File, report.pdf, 12 pages, 2 KB", "a11y label with pages");
check(fileAccessibilityLabel("report.pdf", 2048, 1) === "File, report.pdf, 1 page, 2 KB", "a11y one page");
check(pdfPageSubtitle(3, 12) === "Page 3 of 12" && pdfPageSubtitle(1, 1) === "1 page", "viewer subtitle");
{
  const long = "Quarterly report for the whole year 2026.pdf";
  const { head, tail } = middleTruncationParts(long);
  check(head + tail === long && tail.endsWith(".pdf") && tail.length === 10, "middle truncation keeps the extension");
}

/* ------------------------------------------------------------------------------------- picking */

class FakeFile extends Blob {
  constructor(
    readonly name: string,
    private readonly fakeSize: number,
  ) {
    super([]);
  }
  override get size(): number {
    return this.fakeSize;
  }
}
const fake = (name: string, size: number) => new FakeFile(name, size) as unknown as File;
{
  const ok = fake("report.pdf", 10);
  const triaged = triageFiles([fake("a.exe", 10), ok]);
  check(triaged.accepted.length === 1 && triaged.accepted[0] === ok, "unsupported dropped");
  check(triaged.refusal === unsupportedRefusal("a.exe"), "unsupported refusal");
  check(triageFiles([fake("e.txt", 0)]).refusal === emptyRefusal("e.txt"), "empty refused");
  check(triageFiles([fake("big.mov", MAX_FILE_BYTES + 1)]).refusal === tooLargeRefusal("big.mov"), "too large refused");
  check(triageFiles([fake("max.mov", MAX_FILE_BYTES)]).refusal === null, "exactly 2 GiB − 1 MiB is fine");
  const many = Array.from({ length: 12 }, (_, i) => fake(`f${i}.txt`, 1));
  const capped = triageFiles(many);
  check(capped.accepted.length === 10 && capped.accepted[9] === many[9] && capped.refusal === TOO_MANY_FILES, "first 10 kept");
  check(triageFiles(many.slice(0, 3), 2).accepted.length === 2, "room left in an open composer");
  check(triageFiles([fake("../x/evil\u202Etxt.exe", 4)]).refusal === unsupportedRefusal("eviltxt.exe"), "names cleaned before the refusal");
}

/* ---------------------------------------------------------------------------- payload & quotes */

{
  // A file whose sender says it is a clip or audio: still a file.
  for (const mime of ["video/mp4", "audio/mp4", "image/png", "application/pdf"]) {
    const payload = parseMediaPayload(JSON.stringify({ t: "file", n: "report.pdf", mime, k: "a2V5", s: 5, w: 0, h: 0 }));
    check(payload != null && isFilePayload(payload), `t=file parses (${mime})`);
    check(!isVideoPayload(payload!) && !isVoicePayload(payload!), `t=file is never sniffed as ${mime}`);
    check(payload!.n === "report.pdf" && payload!.s === 5, "n and s survive");
  }
  const paged = parseMediaPayload(JSON.stringify({ t: "file", n: "r.pdf", mime: "application/pdf", k: "a2V5", s: 5, w: 0, h: 0, pg: 12 }));
  check(paged?.pg === 12, "pg parses");
  for (const bad of [0, -1, 2.5, "12", null]) {
    const odd = parseMediaPayload(JSON.stringify({ t: "file", n: "r.pdf", mime: "application/pdf", k: "a2V5", s: 5, w: 0, h: 0, pg: bad }));
    check(odd?.pg === null, `pg ${JSON.stringify(bad)} is ignored`);
  }
  check(parseMediaPayload(JSON.stringify({ t: "file", n: "r.pdf", mime: "application/pdf", k: "a2V5", s: 5, w: 0, h: 0 }))?.pg === null, "no pg");
  const legacy = parseMediaPayload('{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5"}');
  check(legacy != null && !isFilePayload(legacy) && legacy.n === null, "photos are not files");

  const quote = parseReplyRef({ id: "ABC", u: "DEF", k: "file", x: "report.pdf" });
  check(quote?.kind === "file" && quote.snippet === "report.pdf", "reply k=file with x = name");
  check(replyRefWire(quote!).k === "file" && replyKindLabel("file") === "File", "quote label File");
  const wrapped = withReply(
    { t: "file", n: "a.pdf", mime: "application/pdf", w: 0, h: 0, k: "a2V5", s: 1 },
    { id: "abc", senderUserId: "def", kind: "file", snippet: "b.pdf" },
  );
  check(parseMediaPayload(JSON.stringify(wrapped))?.re?.k === "file", "file payload carries its quote");

  check(rowBubble({ kind: "file", deleted: false, mediaKey: "k" }) === "file", "rowBubble file");
  check(rowBubble({ kind: "file", deleted: true, mediaKey: "k" }) === "text", "deleted file is a tombstone");
}

/* -------------------------------------------------------------------------- decoded bubble model */

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });
{
  const { messageFromMediaPayload, previewCopy, replyRefFor, tombstone } = await import("./messaging");
  const base = {
    id: "11111111-2222-3333-4444-555555555555",
    senderUserId: "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
    createdAt: "2026-10-05T10:00:00Z",
    isMine: false,
    deleted: false,
    failed: false,
  };
  const payload = parseMediaPayload(
    JSON.stringify({ t: "file", n: "../in\u202Evoice.MP4", mime: "video/mp4", k: "a2V5", s: 9, w: 0, h: 0, d: 4000 }),
  )!;
  const msg = messageFromMediaPayload(base, payload, "77777777-2222-3333-4444-555555555555");
  check(msg.kind === "file", "decoded as a file, not a video");
  check(msg.fileName === "invoice.MP4", "receiver cleans the name again");
  check(msg.mime === "video/mp4" && msg.mediaBytes === 9 && msg.text === "invoice.MP4", "type from the extension");
  check(previewCopy(msg) === "invoice.MP4", "chat list shows the name");
  const captioned = messageFromMediaPayload(base, { ...payload, c: " Q3 numbers " }, null);
  check(captioned.caption === "Q3 numbers" && previewCopy(captioned) === "Q3 numbers", "caption wins in the chat list");
  const ref = replyRefFor(captioned)!;
  check(ref.kind === "file" && ref.snippet === "invoice.MP4", "a reply quotes the file name");
  const spoofed = messageFromMediaPayload(base, { ...payload, n: "notes.pdf", mime: "text/html" }, null);
  check(spoofed.mime === "application/pdf", "the sender's mime is ignored");
  const odd = messageFromMediaPayload(base, { ...payload, n: "page.html" }, null);
  check(odd.kind === "file" && odd.mime === null, "an unsupported file stays a file, with no type");
  check(tombstone(msg).fileName === null && tombstone(msg).mediaKey === null, "tombstone forgets the file");
  const pdfMsg = messageFromMediaPayload(base, { ...payload, n: "r.pdf", pg: 7 }, null);
  check(pdfMsg.pageCount === 7 && tombstone(pdfMsg).pageCount === null, "pg reaches the message, tombstone drops it");
  check(messageFromMediaPayload(base, payload, null).pageCount === null, "no pg, no page count");
}

console.log("file selftest ok");

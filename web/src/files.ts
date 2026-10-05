/**
 * File sharing (docs/file-sharing.md): the supported types, the name rules, the content check
 * and the copy every client shares. The extension decides everything, on both ends — the MIME a
 * sender seals is informational only.
 */
import { formatBytes } from "./format";

export type FileCategory = "text" | "pdf" | "word" | "excel" | "powerpoint" | "image" | "video" | "app";

/** §6: shown on the bubble, and asked about before a received file leaves the browser. */
export type FileWarning = "app" | "macros";

export type FileType = {
  /** Lowercased extension, without the dot. */
  ext: string;
  category: FileCategory;
  /** What the file is opened (and its blob typed) as. */
  mime: string;
  warning: FileWarning | null;
};

/** At most 2 GiB − 1 MiB of plaintext (§2), so the sealed blob stays under the server's 2 GiB. */
export const MAX_FILE_BYTES = 2 * 1024 * 1024 * 1024 - 1024 * 1024;
/** Files per send, like photos. The caption and the reply go on the first. */
export const MAX_FILES_PER_SEND = 10;
/** Longest cleaned name, in code points (§5). */
const MAX_NAME = 120;
/** The web text viewer shows this much of a text file (§7). */
export const TEXT_VIEWER_BYTES = 1024 * 1024;

const WORDML = "application/vnd.openxmlformats-officedocument.wordprocessingml";
const SHEETML = "application/vnd.openxmlformats-officedocument.spreadsheetml";
const PRESENTATIONML = "application/vnd.openxmlformats-officedocument.presentationml";

/** The §4 table. Anything not in it is unsupported: never sent, never downloaded. */
const TABLE: [string, FileCategory, string, FileWarning | null][] = [
  ["txt", "text", "text/plain", null],
  ["csv", "text", "text/csv", null],
  ["pdf", "pdf", "application/pdf", null],
  ["docx", "word", `${WORDML}.document`, null],
  ["dotx", "word", `${WORDML}.template`, null],
  ["rtf", "word", "application/rtf", null],
  ["doc", "word", "application/msword", "macros"],
  ["dot", "word", "application/msword", "macros"],
  ["docm", "word", "application/vnd.ms-word.document.macroEnabled.12", "macros"],
  ["dotm", "word", "application/vnd.ms-word.template.macroEnabled.12", "macros"],
  ["xlsx", "excel", `${SHEETML}.sheet`, null],
  ["xltx", "excel", `${SHEETML}.template`, null],
  ["xls", "excel", "application/vnd.ms-excel", "macros"],
  ["xlt", "excel", "application/vnd.ms-excel", "macros"],
  ["xlsm", "excel", "application/vnd.ms-excel.sheet.macroEnabled.12", "macros"],
  ["xltm", "excel", "application/vnd.ms-excel.template.macroEnabled.12", "macros"],
  ["xlsb", "excel", "application/vnd.ms-excel.sheet.binary.macroEnabled.12", "macros"],
  ["pptx", "powerpoint", `${PRESENTATIONML}.presentation`, null],
  ["ppsx", "powerpoint", `${PRESENTATIONML}.slideshow`, null],
  ["potx", "powerpoint", `${PRESENTATIONML}.template`, null],
  ["ppt", "powerpoint", "application/vnd.ms-powerpoint", "macros"],
  ["pps", "powerpoint", "application/vnd.ms-powerpoint", "macros"],
  ["pot", "powerpoint", "application/vnd.ms-powerpoint", "macros"],
  ["pptm", "powerpoint", "application/vnd.ms-powerpoint.presentation.macroEnabled.12", "macros"],
  ["ppsm", "powerpoint", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12", "macros"],
  ["potm", "powerpoint", "application/vnd.ms-powerpoint.template.macroEnabled.12", "macros"],
  ["jpg", "image", "image/jpeg", null],
  ["jpeg", "image", "image/jpeg", null],
  ["png", "image", "image/png", null],
  ["gif", "image", "image/gif", null],
  ["webp", "image", "image/webp", null],
  ["heic", "image", "image/heic", null],
  ["heif", "image", "image/heif", null],
  ["avif", "image", "image/avif", null],
  ["tif", "image", "image/tiff", null],
  ["tiff", "image", "image/tiff", null],
  ["bmp", "image", "image/bmp", null],
  ["mp4", "video", "video/mp4", null],
  ["m4v", "video", "video/x-m4v", null],
  ["mov", "video", "video/quicktime", null],
  ["webm", "video", "video/webm", null],
  ["mkv", "video", "video/x-matroska", null],
  ["avi", "video", "video/x-msvideo", null],
  ["3gp", "video", "video/3gpp", null],
  ["apk", "app", "application/vnd.android.package-archive", "app"],
];

const TYPES = new Map<string, FileType>(
  TABLE.map(([ext, category, mime, warning]) => [ext, { ext, category, mime, warning }]),
);

/** Every supported extension, for tests and the picker. */
export const FILE_EXTENSIONS: readonly string[] = TABLE.map(([ext]) => ext);

/** The paperclip's `accept`: the §4 extensions, so the picker offers nothing else. */
export const FILE_ACCEPT = FILE_EXTENSIONS.map((ext) => `.${ext}`).join(",");

/* ------------------------------------------------------------------------------- names (§5) */

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i);
}

/** Step 3: controls, invisible formatting and the bidi overrides a hostile name could hide behind. */
const REMOVED = new Set([
  ...range(0x00, 0x08),
  ...range(0x0e, 0x1f),
  ...range(0x7f, 0x9f),
  0xad,
  0x061c,
  0x180e,
  ...range(0x200b, 0x200f),
  ...range(0x202a, 0x202e),
  ...range(0x2060, 0x2064),
  ...range(0x2066, 0x206f),
  0x2028,
  0x2029,
  0xfeff,
  ...range(0xfff9, 0xfffb),
]);
/** Step 4: characters no file system takes. */
const REPLACED = new Set([..."<>:\"|?*"].map((c) => c.codePointAt(0)!));
/** Step 5: every kind of space collapses to one U+0020. */
const SPACES = new Set([...range(0x09, 0x0d), 0x20, 0xa0, 0x1680, ...range(0x2000, 0x200a), 0x202f, 0x205f, 0x3000]);

const SPACE = 0x20;
const DOT = 0x2e;

function trimSpacesDots(cps: number[]): number[] {
  let start = 0;
  let end = cps.length;
  while (start < end && (cps[start] === SPACE || cps[start] === DOT)) start++;
  while (end > start && (cps[end - 1] === SPACE || cps[end - 1] === DOT)) end--;
  return cps.slice(start, end);
}

function isAsciiAlnum(cp: number): boolean {
  return (cp >= 0x30 && cp <= 0x39) || (cp >= 0x41 && cp <= 0x5a) || (cp >= 0x61 && cp <= 0x7a);
}

/** Step 7 on code points: the index of the extension's dot, or -1 when there is none. */
function extensionDot(cps: number[]): number {
  const dot = cps.lastIndexOf(DOT);
  if (dot <= 0) return -1;
  const rest = cps.length - dot - 1;
  if (rest < 1 || rest > 10) return -1;
  for (let i = dot + 1; i < cps.length; i++) if (!isAsciiAlnum(cps[i])) return -1;
  return dot;
}

/**
 * Cleans a file name the way every client does (§5), on code points: sender before sealing,
 * receiver before showing or saving. A path, a hidden extension or a right-to-left override
 * never survives it.
 */
export function sanitizeFileName(raw: string): string {
  let name = raw.normalize("NFC");
  const cut = Math.max(name.lastIndexOf("/"), name.lastIndexOf("\\"));
  if (cut >= 0) name = name.slice(cut + 1);
  let cps: number[] = [];
  for (const char of name) {
    const cp = char.codePointAt(0)!;
    if (REMOVED.has(cp)) continue;
    if (REPLACED.has(cp)) cps.push(0x5f);
    else if (SPACES.has(cp)) {
      if (cps[cps.length - 1] !== SPACE) cps.push(SPACE);
    } else cps.push(cp);
  }
  cps = trimSpacesDots(cps);
  const dot = extensionDot(cps);
  const ext = dot >= 0 ? cps.slice(dot + 1) : [];
  let stem = dot >= 0 ? cps.slice(0, dot) : cps;
  const budget = MAX_NAME - (ext.length ? ext.length + 1 : 0);
  stem = trimSpacesDots(stem.length > budget ? stem.slice(0, budget) : stem);
  if (stem.length === 0) stem = [..."file"].map((c) => c.codePointAt(0)!);
  return String.fromCodePoint(...(ext.length ? [...stem, DOT, ...ext] : stem));
}

/** The lowercased extension of a cleaned name, or "" when it has none. */
export function fileExtension(cleanedName: string): string {
  const cps = [...cleanedName].map((c) => c.codePointAt(0)!);
  const dot = extensionDot(cps);
  return dot >= 0 ? String.fromCodePoint(...cps.slice(dot + 1)).toLowerCase() : "";
}

/** The §4 row for a cleaned name, or null when the type is unsupported. */
export function fileTypeOf(cleanedName: string): FileType | null {
  return TYPES.get(fileExtension(cleanedName)) ?? null;
}

/**
 * The name cut so it fits one line: the start of the stem, and a tail that always keeps the
 * extension. The bubble ellipsizes `head`, never `tail`, so the type stays visible (§5).
 */
export function middleTruncationParts(name: string): { head: string; tail: string } {
  const chars = [...name];
  const ext = fileExtension(name);
  // A few characters of the stem ride along with the extension, as Finder does.
  const keep = Math.min(chars.length, (ext ? ext.length + 1 : 0) + 6);
  return { head: chars.slice(0, chars.length - keep).join(""), tail: chars.slice(chars.length - keep).join("") };
}

/* -------------------------------------------------------------------------- content check (§4) */

const ZIP_TYPES = new Set([
  "docx", "dotx", "docm", "dotm", "xlsx", "xltx", "xlsm", "xltm", "xlsb", "pptx", "ppsx", "potx", "pptm",
  "ppsm", "potm", "apk",
]);
const OLE_TYPES = new Set(["doc", "dot", "xls", "xlt", "ppt", "pps", "pot"]);
const ZIP_MAGIC = [0x50, 0x4b, 0x03, 0x04];
const OLE_MAGIC = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1];
const RTF_MAGIC = [..."{\\rtf"].map((c) => c.charCodeAt(0));
const PDF_MAGIC = [..."%PDF-"].map((c) => c.charCodeAt(0));

/** How many leading bytes `contentMatches` looks at (the text check's 8 KiB). */
export const CONTENT_CHECK_BYTES = 8 * 1024;

function startsWith(bytes: Uint8Array, magic: number[], at = 0): boolean {
  if (bytes.length < at + magic.length) return false;
  for (let i = 0; i < magic.length; i++) if (bytes[at + i] !== magic[i]) return false;
  return true;
}

/**
 * Whether a received file's first bytes fit its extension (§4). `head` is the start of the file,
 * up to `CONTENT_CHECK_BYTES`. Images and videos pass: the browser's decoders refuse what they
 * can't read. An unsupported extension never matches.
 */
export function contentMatches(ext: string, head: Uint8Array): boolean {
  const type = TYPES.get(ext.toLowerCase());
  if (!type) return false;
  if (type.ext === "pdf") {
    const window = Math.min(head.length, 1024);
    for (let at = 0; at + PDF_MAGIC.length <= window; at++) if (startsWith(head, PDF_MAGIC, at)) return true;
    return false;
  }
  if (ZIP_TYPES.has(type.ext)) return startsWith(head, ZIP_MAGIC);
  if (OLE_TYPES.has(type.ext)) return startsWith(head, OLE_MAGIC);
  if (type.ext === "rtf") return startsWith(head, RTF_MAGIC);
  if (type.category === "text") {
    const end = Math.min(head.length, CONTENT_CHECK_BYTES);
    for (let i = 0; i < end; i++) if (head[i] === 0) return false;
    return true;
  }
  return true;
}

/* ---------------------------------------------------------------------------- opening (§7) */

/** How the web opens a file: Shroud's PDF viewer (§10.2), a new tab, the text viewer, or a download. */
export type OpenMode = "pdf" | "tab" | "text" | "download";

export function openModeOf(type: FileType): OpenMode {
  switch (type.category) {
    case "pdf":
      return "pdf";
    case "image":
    case "video":
      return "tab";
    case "text":
      return "text";
    default:
      return "download";
  }
}

/**
 * The type a decrypted blob gets: the table's MIME, never the sender's. A blob URL opened in a
 * tab is same-origin with the app, so nothing that could run script may ever come out of here.
 */
export function blobMimeOf(type: FileType): string {
  if (/html|svg|javascript|ecmascript/i.test(type.mime)) throw new Error(`Refusing to type a file as ${type.mime}`);
  return type.mime;
}

/* ------------------------------------------------------------------------------- copy (§6, §7) */

export function unsupportedRefusal(name: string): string {
  return `Shroud can't send “${name}”: this file type isn't supported.`;
}

export function tooLargeRefusal(name: string): string {
  return `“${name}” is larger than 2 GB.`;
}

export function emptyRefusal(name: string): string {
  return `“${name}” is empty.`;
}

export const TOO_MANY_FILES = "You can send up to 10 files at once.";

export function contentMismatchText(ext: string): string {
  return `This file doesn't match its .${ext} type, so Shroud won't open it.`;
}

export const UNSUPPORTED_FILE = "Unsupported file";
export const NOT_SENT = "Not sent";
export const COMPOSER_NOTE = "Files are sent as they are, without compression, and keep their metadata.";
export const TEXT_VIEWER_CUT = "Showing the first 1 MB.";

/* The PDF viewer (§10.2). */
export const PDF_LOADING = "Loading…";
export const PDF_SEARCH_PLACEHOLDER = "Search in PDF";
export const PDF_NO_RESULTS = "No results";
export const PDF_PROTECTED_TITLE = "This PDF is protected";
export const PDF_PROTECTED_MESSAGE = "Enter its password to open it.";
export const PDF_WRONG_PASSWORD = "Wrong password. Try again.";
export const PDF_DAMAGED_TITLE = "Shroud can't show this PDF.";
export const PDF_DAMAGED_MESSAGE = "It may be damaged or use features Shroud can't display.";

/** The viewer's subtitle: `Page 3 of 12`, `1 page` for a single page. */
export function pdfPageSubtitle(page: number, count: number): string {
  return count === 1 ? "1 page" : `Page ${page} of ${count}`;
}

/** The bubble's warning line. */
export function warningLine(warning: FileWarning): string {
  return warning === "app" ? "Installs an app" : "May contain macros";
}

/** The confirmation asked before a received file with a warning leaves the browser. */
export function warningDialog(warning: FileWarning, sender: string): { title: string; message: string } {
  return warning === "app"
    ? {
        title: "This file can install an app",
        message: `APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust ${sender} and expected this file.`,
      }
    : {
        title: "This file may contain macros",
        message: `Macros in Office files can run harmful code. Only continue if you trust ${sender} and expected this file, and don't turn on macros unless you're sure.`,
      };
}

/** `12 pages`, `1 page` (docs/file-sharing.md §7, §10). */
export function pageCountLabel(pages: number): string {
  return pages === 1 ? "1 page" : `${pages} pages`;
}

/** A page count worth showing: a PDF's, an integer ≥ 1. */
function shownPages(cleanedName: string, pages: number | null | undefined): number | null {
  if (pages == null || !Number.isInteger(pages) || pages < 1) return null;
  return fileTypeOf(cleanedName)?.ext === "pdf" ? pages : null;
}

/** `2.4 MB · PDF` — `TYPE` is the extension upper-cased; a PDF's known page count leads (§7). */
export function fileMetaLine(size: number | null | undefined, cleanedName: string, pages?: number | null): string {
  const ext = fileExtension(cleanedName).toUpperCase();
  const counted = shownPages(cleanedName, pages);
  const parts = [counted ? pageCountLabel(counted) : null, size != null ? formatBytes(size) : null, ext || null].filter(
    Boolean,
  );
  return parts.join(" · ");
}

/** `File, Report.pdf, 12 pages, 2.4 MB, may contain macros`. */
export function fileAccessibilityLabel(
  cleanedName: string,
  size: number | null | undefined,
  pages?: number | null,
): string {
  const type = fileTypeOf(cleanedName);
  const counted = shownPages(cleanedName, pages);
  let label = `File, ${cleanedName}${counted ? `, ${pageCountLabel(counted)}` : ""}${size != null ? `, ${formatBytes(size)}` : ""}`;
  if (type?.warning === "app") label += ", installs an app";
  else if (type?.warning === "macros") label += ", may contain macros";
  return label;
}

/* ------------------------------------------------------------------------- picking (§2, §7) */

/**
 * Splits a pick into the files that can go out and the one refusal to show for it: a type
 * outside the table, an empty or a too large file, or more than `room` files.
 */
export function triageFiles(picked: File[], room = MAX_FILES_PER_SEND): { accepted: File[]; refusal: string | null } {
  let refusal: string | null = null;
  const accepted: File[] = [];
  for (const file of picked) {
    const name = sanitizeFileName(file.name);
    const problem = !fileTypeOf(name)
      ? unsupportedRefusal(name)
      : file.size === 0
        ? emptyRefusal(name)
        : file.size > MAX_FILE_BYTES
          ? tooLargeRefusal(name)
          : null;
    if (problem) refusal ??= problem;
    else accepted.push(file);
  }
  if (accepted.length > Math.max(0, room)) {
    refusal ??= TOO_MANY_FILES;
    return { accepted: accepted.slice(0, Math.max(0, room)), refusal };
  }
  return { accepted, refusal };
}

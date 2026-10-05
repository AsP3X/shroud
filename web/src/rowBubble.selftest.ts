/**
 * A deleted photo, video, voice note or file draws the same text tombstone as a deleted sentence.
 * Run: npx tsx src/rowBubble.selftest.ts
 */
import { rowBubble } from "./rowBubble";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`row bubble selftest: ${what}`);
}

const base = { mediaKey: "key" };

for (const kind of ["text", "image", "video", "voice", "file"] as const) {
  check(rowBubble({ ...base, kind, deleted: true }, true) === "text", `deleted ${kind}`);
}

check(rowBubble({ ...base, kind: "text", deleted: false }) === "text", "live text");
check(rowBubble({ ...base, kind: "image", deleted: false }) === "photo", "live photo");
check(rowBubble({ kind: "image", deleted: false, mediaKey: null }, true) === "photo", "decoded photo");
check(rowBubble({ kind: "image", deleted: false, mediaKey: null }) === "text", "photo without bytes");
check(rowBubble({ ...base, kind: "video", deleted: false }) === "video", "live video");
check(rowBubble({ ...base, kind: "voice", deleted: false }) === "voice", "live voice");
check(rowBubble({ ...base, kind: "file", deleted: false }) === "file", "live file");
check(rowBubble({ kind: "file", deleted: false, mediaKey: null }) === "file", "a file without a key still draws its bubble");

console.log("row bubble selftest ok");

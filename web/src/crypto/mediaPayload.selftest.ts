/**
 * `parseMediaPayload` keeps the caption (`c`), thumbnail (`th`) and waveform (`wf`) only when the
 * sender sealed them as strings. Anything else is dropped: the bubble code calls `.trim()` on all
 * three, and a contact's modified client must not be able to make a chat's history throw
 * (security audit W1).
 * Run: `npm test -- mediaPayload`.
 */
import { parseMediaPayload } from "./mediaPayload";

function check(condition: boolean, what: string): void {
  if (!condition) throw new Error(`mediaPayload selftest: ${what}`);
}

const base = { t: "image", mime: "image/jpeg", w: 4, h: 3, k: "a2V5" };

const sealed = parseMediaPayload(JSON.stringify({ ...base, c: "caption", th: "qqo=", wf: "AQI=" }));
check(sealed?.c === "caption" && sealed.th === "qqo=" && sealed.wf === "AQI=", "string fields are kept");

for (const odd of [5, {}, [], true, ["x"], { trim: "x" }]) {
  const payload = parseMediaPayload(JSON.stringify({ ...base, c: odd, th: odd, wf: odd }));
  const shown = JSON.stringify(odd);
  check(payload != null, `a payload with ${shown} fields still parses`);
  check(payload!.c === null, `c ${shown} is dropped`);
  check(payload!.th === null, `th ${shown} is dropped`);
  check(payload!.wf === null, `wf ${shown} is dropped`);
}

const absent = parseMediaPayload(JSON.stringify({ ...base, c: null }));
check(absent?.c === null && absent.th === null && absent.wf === null, "null and missing fields stay null");

console.log("mediaPayload selftest ok");

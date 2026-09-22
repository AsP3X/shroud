/**
 * Links in message text, and the link previews sealed inside messages.
 *
 * Detection is a line-for-line port of iOS `LinkDetector` (ios/shroud/Services/Links): the
 * server never sees message text, so each client finds links itself, and both must underline
 * the same characters. `links.selftest.ts` checks the same vectors as `LinkDetectorTests`.
 * Offsets are UTF-16 code units — JavaScript string indices.
 *
 * Previews are built by the *sender's* phone (the web client cannot fetch other sites: CORS and
 * this app's CSP forbid it, and routing drafts through the server would hand it plaintext). The
 * browser only renders what arrives sealed in `lp`, and never loads anything from the linked site.
 */

export type DetectedLink = {
  /** UTF-16 offset into the scanned text. */
  start: number;
  /** UTF-16 length. */
  length: number;
  /** What a click opens: the URL as typed, `https://` + a bare host, or `mailto:` + an address. */
  url: string;
  isEmail: boolean;
};

/**
 * Candidates: a scheme URL (group 1), an e-mail (group 2) or a `www.`/bare host (group 3). The
 * look-behind keeps a match from starting inside a word, path or address.
 */
const LINK_PATTERN =
  "(?<![\\p{L}\\p{N}@._\\-/#%+~=&])(?:(https?://[^\\s<>\"]+)|([\\p{L}\\p{N}._%+\\-]+@(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?\\.)+(?:xn--[a-z0-9\\-]{1,59}|\\p{L}{2,63}))|((?:www\\.)?(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?\\.)+(?:xn--[a-z0-9\\-]{1,59}|\\p{L}{2,63})(?::\\d{1,5})?(?:[/?#][^\\s<>\"]*)?))";

/** Characters that end a sentence rather than a URL. */
const TRAILING_PUNCTUATION = new Set(".,:;!?'\"‘’“”»›…");
const CLOSERS: Record<string, string> = { ")": "(", "]": "[", "}": "{" };

/** Two-letter country codes (a bare host must end in one of these or in GENERIC_TLDS). */
const COUNTRY_TLDS = new Set(
  (
    "ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs " +
    "bt bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee " +
    "eg er es et eu fi fj fk fm fo fr ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk " +
    "hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la " +
    "lb lc li lk lr ls lt lu lv ly ma mc md me mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my " +
    "mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro " +
    "rs ru rw sa sb sc sd se sg sh si sk sl sm sn so sr ss st su sv sx sy sz tc td tf tg th tj tk " +
    "tl tm tn to tr tt tv tw tz ua ug uk us uy uz va vc ve vg vi vn vu wf ws ye yt za zm zw"
  ).split(" "),
);

/** The generic TLDs people actually type without a scheme. */
const GENERIC_TLDS = new Set(
  (
    "com org net edu gov mil int info biz name pro app dev xyz online site website tech store shop " +
    "blog news cloud club live page link wiki email social media art design studio travel one top " +
    "mobi museum coop aero asia jobs tel cat post zone network digital agency company services " +
    "solutions systems software team tools works today space fun games music video photo photos " +
    "gallery host codes support help guide center school academy education university health care " +
    "law finance money bank capital fund market events community foundation family life city " +
    "berlin hamburg bayern koeln wien london paris nyc tokyo amsterdam swiss gmbh ltd inc llc eco " +
    "energy bio garden house reisen restaurant cafe coffee bar wine rocks guru expert consulting " +
    "partners tips fyi run chat ninja social"
  ).split(" "),
);

function isAllowedTld(host: string): boolean {
  const labels = host.split(".");
  const tld = (labels[labels.length - 1] ?? "").toLowerCase();
  if (tld.startsWith("xn--")) return tld.length > 4;
  // A TLD in its own script (e.g. `.рф`) — the regex already required letters.
  if (tld.length > 0 && [...tld].every((c) => (c.codePointAt(0) ?? 0) > 0x7f)) return true;
  if (tld.length === 2) return COUNTRY_TLDS.has(tld);
  return GENERIC_TLDS.has(tld);
}

function count(unit: string, text: string): number {
  let n = 0;
  for (let i = 0; i < text.length; i++) if (text[i] === unit) n++;
  return n;
}

/** Drops sentence punctuation and unbalanced closing brackets from the end of a match. */
function trimTrailing(match: string): string {
  let end = match.length;
  while (end > 0) {
    const last = match[end - 1];
    if (TRAILING_PUNCTUATION.has(last)) {
      end -= 1;
      continue;
    }
    const opener = CLOSERS[last];
    if (opener) {
      const body = match.slice(0, end);
      if (count(opener, body) < count(last, body)) {
        end -= 1;
        continue;
      }
    }
    break;
  }
  return match.slice(0, end);
}

/** Parses a URL the way iOS `URL(string:)` accepts it: absolute, with a host. */
function hasHost(raw: string): boolean {
  try {
    return new URL(raw).hostname.length > 0;
  } catch {
    return false;
  }
}

/**
 * Compiled once. Look-behind needs Safari 16.4+; an older engine throws on the pattern, and
 * then messages simply show no links rather than failing to render.
 */
let compiled: RegExp | null | undefined;
function linkRegex(): RegExp | null {
  if (compiled === undefined) {
    try {
      compiled = new RegExp(LINK_PATTERN, "giu");
    } catch {
      compiled = null;
    }
  }
  if (compiled) compiled.lastIndex = 0;
  return compiled;
}

/** Every link in `text`, in order. */
export function detectLinks(text: string): DetectedLink[] {
  const regex = text ? linkRegex() : null;
  if (!regex) return [];
  const found: DetectedLink[] = [];
  for (const match of text.matchAll(regex)) {
    const start = match.index ?? 0;
    const [, scheme, email, host] = match;
    if (scheme !== undefined) {
      const raw = trimTrailing(scheme);
      if (!hasHost(raw)) continue;
      found.push({ start, length: raw.length, url: raw, isEmail: false });
    } else if (email !== undefined) {
      const domain = email.split("@").pop() ?? "";
      if (!isAllowedTld(domain)) continue;
      found.push({ start, length: email.length, url: `mailto:${email}`, isEmail: true });
    } else if (host !== undefined) {
      const raw = trimTrailing(host);
      const hostName = raw.split(/[:/?#]/)[0];
      // `www.` says "this is a web address" on its own; a bare name needs a real TLD.
      if (!raw.toLowerCase().startsWith("www.") && !isAllowedTld(hostName)) continue;
      found.push({ start, length: raw.length, url: `https://${raw}`, isEmail: false });
    }
  }
  return found;
}

/** The link a preview is built for: the first one that is not an e-mail address. */
export function firstPreviewableUrl(text: string): string | null {
  return detectLinks(text).find((link) => !link.isEmail)?.url ?? null;
}

/** Text split into plain runs and links, for rendering. */
export type TextPart = { text: string; link: DetectedLink | null };

export function splitLinks(text: string): TextPart[] {
  const links = detectLinks(text);
  if (!links.length) return [{ text, link: null }];
  const parts: TextPart[] = [];
  let cursor = 0;
  for (const link of links) {
    if (link.start > cursor) parts.push({ text: text.slice(cursor, link.start), link: null });
    parts.push({ text: text.slice(link.start, link.start + link.length), link });
    cursor = link.start + link.length;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), link: null });
  return parts;
}

/* --------------------------------------------------------------- previews -- */

/**
 * A link preview sealed inside a message (`lp`). Wire keys are shared with iOS
 * `LinkPreview.wireObject`: `u` url, `n` site name, `ti` title, `d` description, `th` base64
 * JPEG (small layout), `w`/`h` image size, `vd` video page, `ab` drawn above the text.
 */
export type LinkPreview = {
  url: string;
  siteName: string | null;
  title: string | null;
  summary: string | null;
  /** Base64 JPEG for the small (thumbnail) layout. */
  thumbnail: string | null;
  imageWidth: number | null;
  imageHeight: number | null;
  isVideo: boolean;
  showsAboveText: boolean;
};

export const MAX_SITE_NAME = 64;
export const MAX_TITLE = 200;
export const MAX_SUMMARY = 300;
export const MAX_URL = 2048;
/** Same budget as iOS `LinkPreview.maxThumbnailBytes` (JPEG bytes, not base64). */
export const MAX_THUMBNAIL_BYTES = 6 * 1024;

/** Collapses whitespace and cuts at a code-point boundary, marking the cut with "…". */
function clean(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const collapsed = value.replace(/\s+/gu, " ").trim();
  if (!collapsed) return null;
  const chars = [...collapsed];
  if (chars.length <= max) return collapsed;
  return `${chars.slice(0, max - 1).join("").trimEnd()}…`;
}

function positiveInt(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.round(value) : null;
}

/** Lenient parse of the sealed `lp` object; null unless `u` is an http(s) URL. */
export function parseLinkPreview(value: unknown): LinkPreview | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const url = typeof raw.u === "string" ? raw.u.trim() : "";
  if (!url || url.length > MAX_URL) return null;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
  let thumbnail: string | null = null;
  if (typeof raw.th === "string" && raw.th.trim()) {
    const b64 = raw.th.trim();
    // base64 is 4/3 of the bytes; drop a thumbnail over budget rather than the preview.
    if (Math.floor((b64.length * 3) / 4) <= MAX_THUMBNAIL_BYTES + 2) thumbnail = b64;
  }
  return {
    url,
    siteName: clean(raw.n, MAX_SITE_NAME),
    title: clean(raw.ti, MAX_TITLE),
    summary: clean(raw.d, MAX_SUMMARY),
    thumbnail,
    imageWidth: positiveInt(raw.w),
    imageHeight: positiveInt(raw.h),
    isVideo: raw.vd === true,
    showsAboveText: raw.ab === true,
  };
}

/** The `lp` object to seal (matches iOS `LinkPreview.wireObject`). */
export function linkPreviewWire(preview: LinkPreview): Record<string, unknown> {
  const wire: Record<string, unknown> = { u: preview.url };
  if (preview.siteName) wire.n = preview.siteName;
  if (preview.title) wire.ti = preview.title;
  if (preview.summary) wire.d = preview.summary;
  if (preview.thumbnail) wire.th = preview.thumbnail;
  if (preview.imageWidth) wire.w = preview.imageWidth;
  if (preview.imageHeight) wire.h = preview.imageHeight;
  if (preview.isVideo) wire.vd = true;
  if (preview.showsAboveText) wire.ab = true;
  return wire;
}

/** Host without `www.`, the fallback site name. */
export function displayHost(preview: LinkPreview): string {
  try {
    const host = new URL(preview.url).hostname.toLowerCase();
    return host.startsWith("www.") ? host.slice(4) : host;
  } catch {
    return preview.url;
  }
}

/** First line of the preview block: the site's own name, else its host. */
export function displaySiteName(preview: LinkPreview): string {
  return preview.siteName ?? displayHost(preview);
}

/** True for links this client opens (the detector never produces anything else). */
export function isOpenableUrl(url: string): boolean {
  return /^(https?:|mailto:)/i.test(url);
}

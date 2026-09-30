/**
 * What a web page says about itself in its `<head>` — the raw material of a link preview.
 *
 * A port of iOS `LinkPageMetadataParser` (ios/shroud/Services/Links/LinkPageMetadata.swift):
 * OpenGraph, then Twitter cards, then `<title>` / `description`, read with a tag scanner rather
 * than an HTML parser so broken markup still yields a preview. Both clients must pick the same
 * title, description, and picture for the same page; `linkPreview.selftest.ts` checks the
 * vectors of `LinkPageMetadataParserTests`. Pure: no I/O, and only the head is read, so markup
 * quoted in a page's body cannot inject tags.
 */

export type PageMetadata = {
  siteName: string | null;
  title: string | null;
  summary: string | null;
  /** Absolute `https` URL of the page's preview image. */
  imageUrl: string | null;
  /** Size the page declares for that image — a hint only. */
  imageWidth: number | null;
  imageHeight: number | null;
  /** `og:type` is a video, or the page declares a player. */
  isVideo: boolean;
};

export function isEmptyMetadata(metadata: PageMetadata): boolean {
  return !metadata.title && !metadata.summary && !metadata.imageUrl;
}

/* --------------------------------------------------------------- decoding -- */

function charsetInContentType(contentType: string | null): string | null {
  const match = /charset=([^;"\s]+)/i.exec(contentType ?? "");
  return match ? match[1].replace(/^['"]|['"]$/g, "").toLowerCase() : null;
}

/** `<meta charset>` in the first 1 KB — ASCII is enough to read it whatever the encoding. */
function charsetInMeta(bytes: Uint8Array): string | null {
  let prefix = "";
  for (let i = 0; i < Math.min(bytes.length, 1024); i++) prefix += String.fromCharCode(bytes[i]);
  const match = /charset=["']?([^"'>;\s/]+)/i.exec(prefix);
  return match ? match[1].toLowerCase() : null;
}

/**
 * Decodes with the declared charset, then a `<meta charset>` sniff, then UTF-8. A character cut
 * off at the end (only the head is read) becomes one U+FFFD rather than garbling the page.
 */
export function decodePage(bytes: Uint8Array, contentType: string | null): string {
  for (const label of [charsetInContentType(contentType), charsetInMeta(bytes)]) {
    if (!label) continue;
    try {
      return new TextDecoder(label).decode(bytes);
    } catch {
      // Unknown label: try the next source.
    }
  }
  return new TextDecoder("utf-8").decode(bytes);
}

/* ------------------------------------------------------------ tag scanning -- */

/** Everything before `</head>` (or `<body`). */
function headSection(html: string): string {
  const lower = html.toLowerCase();
  let end = lower.indexOf("</head");
  if (end < 0) end = lower.indexOf("<body");
  return end < 0 ? html : html.slice(0, end);
}

const isSpace = (character: string | undefined): boolean =>
  character !== undefined && /\s/.test(character);

/**
 * The `>` that closes a tag whose attributes start at `start` — a `>` inside a quoted value
 * ("Rust > Go?") does not. -1 for a tag left open.
 */
function tagEnd(html: string, start: number): number {
  let afterEquals = false;
  for (let index = start; index < html.length; index++) {
    const character = html[index];
    if (character === ">") return index;
    if (character === "=") {
      afterEquals = true;
    } else if (afterEquals && (character === '"' || character === "'")) {
      const close = html.indexOf(character, index + 1);
      if (close < 0) return -1;
      index = close;
      afterEquals = false;
    } else if (!isSpace(character)) {
      afterEquals = false;
    }
  }
  return -1;
}

/** The raw source of every `<name …>` tag (`<metadata>` is not `<meta>`). */
function tags(name: string, html: string): string[] {
  const result: string[] = [];
  const lower = html.toLowerCase();
  const opener = `<${name}`;
  let cursor = 0;
  for (;;) {
    const start = lower.indexOf(opener, cursor);
    if (start < 0) break;
    const after = start + opener.length;
    if (after >= html.length) break;
    const next = html[after];
    if (!isSpace(next) && next !== ">" && next !== "/") {
      cursor = after;
      continue;
    }
    const end = tagEnd(html, after);
    if (end < 0) break;
    result.push(html.slice(after, end));
    cursor = end + 1;
  }
  return result;
}

/** Attribute map of one tag's source, keys lowercased; `"…"`, `'…'`, and bare values. */
export function attributes(tag: string): Map<string, string> {
  const result = new Map<string, string>();
  let index = 0;
  const skip = () => {
    while (index < tag.length && (isSpace(tag[index]) || tag[index] === "/")) index++;
  };
  for (;;) {
    skip();
    if (index >= tag.length) break;
    const nameStart = index;
    while (index < tag.length && !isSpace(tag[index]) && tag[index] !== "=" && tag[index] !== "/") index++;
    const name = tag.slice(nameStart, index).toLowerCase();
    skip();
    if (index >= tag.length || tag[index] !== "=") {
      if (name && !result.has(name)) result.set(name, "");
      continue;
    }
    index++;
    while (index < tag.length && isSpace(tag[index])) index++;
    if (index >= tag.length) break;
    let value: string;
    const quote = tag[index];
    if (quote === '"' || quote === "'") {
      const close = tag.indexOf(quote, index + 1);
      const valueEnd = close < 0 ? tag.length : close;
      value = tag.slice(index + 1, valueEnd);
      index = close < 0 ? tag.length : close + 1;
    } else {
      const valueStart = index;
      while (index < tag.length && !isSpace(tag[index])) index++;
      value = tag.slice(valueStart, index);
    }
    if (name && !result.has(name)) result.set(name, value);
  }
  return result;
}

/* ---------------------------------------------------------------- entities -- */

const NAMED_ENTITIES: Record<string, string> = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
  ndash: "–", mdash: "—", hellip: "…", laquo: "«", raquo: "»",
  lsquo: "‘", rsquo: "’", ldquo: "“", rdquo: "”", sbquo: "‚", bdquo: "„",
  bull: "•", middot: "·", copy: "©", reg: "®", trade: "™", euro: "€",
  pound: "£", yen: "¥", deg: "°", times: "×", shy: "",
  auml: "ä", ouml: "ö", uuml: "ü", Auml: "Ä", Ouml: "Ö", Uuml: "Ü", szlig: "ß",
  eacute: "é", egrave: "è", ecirc: "ê", aacute: "á", agrave: "à", acirc: "â",
  oacute: "ó", ograve: "ò", ocirc: "ô", uacute: "ú", iacute: "í", ccedil: "ç",
  ntilde: "ñ", Eacute: "É", oslash: "ø", aring: "å", aelig: "æ",
};

function entityValue(entity: string): string | null {
  const numeric = /^#(x[0-9a-fA-F]+|X[0-9a-fA-F]+|\d+)$/.exec(entity);
  if (numeric) {
    const code = numeric[1][0] === "x" || numeric[1][0] === "X"
      ? parseInt(numeric[1].slice(1), 16)
      : parseInt(numeric[1], 10);
    if (!Number.isFinite(code) || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return null;
    return String.fromCodePoint(code);
  }
  return NAMED_ENTITIES[entity] ?? null;
}

/** Decodes `&amp;`, `&#39;`, `&#x27;` and the common named entities. */
export function decodeEntities(text: string): string {
  if (!text.includes("&")) return text;
  let output = "";
  let index = 0;
  while (index < text.length) {
    const character = text[index];
    if (character === "&") {
      const semicolon = text.indexOf(";", index);
      if (semicolon > index && semicolon - index <= 11) {
        const replacement = entityValue(text.slice(index + 1, semicolon));
        if (replacement !== null) {
          output += replacement;
          index = semicolon + 1;
          continue;
        }
      }
    }
    output += character;
    index++;
  }
  return output;
}

/* ------------------------------------------------------------------- parse -- */

function firstNonEmpty(...values: (string | null | undefined)[]): string | null {
  for (const value of values) {
    const trimmed = value?.trim();
    if (trimmed) return trimmed;
  }
  return null;
}

/**
 * Resolves a (possibly relative) image reference to an `https` URL. `http` is upgraded rather
 * than fetched in the clear; anything else (`data:`, `javascript:`) is dropped.
 */
export function secureUrl(raw: string, page: string): string | null {
  try {
    const resolved = new URL(raw, page);
    if (resolved.protocol === "http:") resolved.protocol = "https:";
    return resolved.protocol === "https:" ? resolved.href : null;
  } catch {
    return null;
  }
}

function title(head: string): string | null {
  const lower = head.toLowerCase();
  const open = lower.indexOf("<title");
  if (open < 0) return null;
  const openEnd = head.indexOf(">", open);
  if (openEnd < 0) return null;
  const close = lower.indexOf("</title", openEnd + 1);
  if (close < 0) return null;
  return decodeEntities(head.slice(openEnd + 1, close));
}

function positiveInt(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw.trim())) return null;
  const value = Number(raw.trim());
  return value > 0 ? value : null;
}

/** Parses the start of an HTML document fetched from `pageUrl`. */
export function parsePageMetadata(bytes: Uint8Array, pageUrl: string, contentType: string | null): PageMetadata {
  const head = headSection(decodePage(bytes, contentType));
  const meta = new Map<string, string>();
  for (const tag of tags("meta", head)) {
    const attrs = attributes(tag);
    const content = attrs.get("content");
    if (content === undefined) continue;
    const decoded = decodeEntities(content);
    if (!decoded) continue;
    for (const key of ["property", "name", "itemprop"]) {
      const name = attrs.get(key)?.toLowerCase();
      if (name && !meta.has(name)) meta.set(name, decoded);
    }
  }
  const rawImage = firstNonEmpty(
    meta.get("og:image:secure_url"),
    meta.get("og:image:url"),
    meta.get("og:image"),
    meta.get("twitter:image"),
    meta.get("twitter:image:src"),
  );
  const type = (meta.get("og:type") ?? "").toLowerCase();
  return {
    siteName: firstNonEmpty(meta.get("og:site_name"), meta.get("application-name")),
    title: firstNonEmpty(meta.get("og:title"), meta.get("twitter:title"), title(head)),
    summary: firstNonEmpty(meta.get("og:description"), meta.get("twitter:description"), meta.get("description")),
    imageUrl: rawImage ? secureUrl(rawImage, pageUrl) : null,
    imageWidth: positiveInt(meta.get("og:image:width")),
    imageHeight: positiveInt(meta.get("og:image:height")),
    isVideo:
      type.startsWith("video") ||
      meta.has("og:video") ||
      meta.has("og:video:url") ||
      meta.has("og:video:secure_url") ||
      meta.has("twitter:player"),
  };
}

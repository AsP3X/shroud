/**
 * Replies: the quote a message carries, sealed inside its own plaintext.
 *
 * The wire format is shared with iOS (`MessageReplyReference`, `MessageTextPayload`). The
 * server never sees it — a reply is an ordinary `text` or `media` message whose plaintext
 * happens to carry `re`, so the relay still cannot tell which message answers which.
 *
 * Plain text messages stay raw UTF-8, exactly as before; only a reply (or a message whose
 * sender attached a link preview, `lp`) is sealed as the JSON envelope
 * `{"t":"text","c":<body>,"re":{…},"lp":{…}}`. Anything that does not parse as that envelope
 * is read back verbatim, which is what lets old and new builds talk to each other.
 */

import { parseLinkPreview, type LinkPreview } from "./links";

export type ReplyKind = "text" | "image" | "video" | "voice";

export type ReplyRef = {
  /** Server id of the quoted message (lowercased). */
  id: string;
  /** Author of the quoted message, so the header can say "You" or their name. */
  senderUserId: string;
  kind: ReplyKind;
  /** One line of the quoted message; empty for media without a caption. */
  snippet: string;
};

/** One line is all the header draws; the envelope is sealed twice, so trim early. */
export const MAX_REPLY_SNIPPET = 120;

const KINDS: ReplyKind[] = ["text", "image", "video", "voice"];

/** Collapses whitespace and cuts at a character boundary, marking the cut with "…". */
export function clampSnippet(raw: string): string {
  const collapsed = raw.replace(/\s+/gu, " ").trim();
  if ([...collapsed].length <= MAX_REPLY_SNIPPET) return collapsed;
  return `${[...collapsed].slice(0, MAX_REPLY_SNIPPET - 1).join("").trimEnd()}…`;
}

/** Reads the sealed `re` object. Returns null unless both ids are present. */
export function parseReplyRef(value: unknown): ReplyRef | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const id = typeof raw.id === "string" ? raw.id.trim().toLowerCase() : "";
  const sender = typeof raw.u === "string" ? raw.u.trim().toLowerCase() : "";
  if (!id || !sender) return null;
  const kind = typeof raw.k === "string" && (KINDS as string[]).includes(raw.k)
    ? (raw.k as ReplyKind)
    : "text";
  const snippet = typeof raw.x === "string" ? clampSnippet(raw.x) : "";
  return { id, senderUserId: sender, kind, snippet };
}

/** The `re` object to seal, matching iOS `MessageReplyReference.wireObject`. */
export function replyRefWire(ref: ReplyRef): Record<string, string> {
  const wire: Record<string, string> = {
    id: ref.id.toLowerCase(),
    u: ref.senderUserId.toLowerCase(),
    k: ref.kind,
  };
  if (ref.snippet) wire.x = ref.snippet;
  return wire;
}

/** Plaintext to seal for a text message: the body itself, or the reply envelope. */
export function textPayload(text: string, replyTo: ReplyRef | null | undefined): string {
  if (!replyTo) return text;
  return JSON.stringify({ t: "text", c: text, re: replyRefWire(replyTo) });
}

/**
 * Splits sealed plaintext into body, quote and link preview (`lp`, written by the sender's
 * phone — see links.ts). Anything unparsable comes back verbatim.
 */
export function parseTextPayload(raw: string): {
  text: string;
  replyTo: ReplyRef | null;
  linkPreview: LinkPreview | null;
} {
  const plain = { text: raw, replyTo: null, linkPreview: null };
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return plain;
  try {
    const parsed = JSON.parse(trimmed) as Record<string, unknown>;
    if (parsed.t !== "text" || typeof parsed.c !== "string") return plain;
    return {
      text: parsed.c,
      replyTo: parseReplyRef(parsed.re),
      linkPreview: parseLinkPreview(parsed.lp),
    };
  } catch {
    return plain;
  }
}

/** Label for a quoted message that has no text of its own. */
export function replyKindLabel(kind: ReplyKind): string {
  switch (kind) {
    case "image":
      return "Photo";
    case "video":
      return "Video";
    case "voice":
      return "Voice message";
    default:
      return "Message";
  }
}

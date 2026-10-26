/**
 * Replies: the quote a message carries, sealed inside its own plaintext.
 *
 * The wire format is shared with iOS (`MessageReplyReference`, `MessageTextPayload`). The
 * server never sees it — a reply is an ordinary `text` or `media` message whose plaintext
 * happens to carry `re`, so the relay still cannot tell which message answers which.
 *
 * A message sent to someone is sealed as the JSON envelope
 * `{"t":"text","c":<body>,"re":{…},"lp":{…},"to":<recipient>}`: `to` names the recipient
 * (`sealedRecipient.ts`, security audit M7); `re` and `lp` are there when it quotes a message or
 * carries a link preview. Builds from before `to` sealed a plain message as raw UTF-8, and every
 * build since replies reads the envelope's `c` and ignores keys it does not know. Anything that
 * does not parse as that envelope is read back verbatim, which is what lets old and new builds
 * talk to each other.
 */

import { linkPreviewWire, parseLinkPreview, previewOfText, type LinkPreview } from "./links";
import { RECIPIENT_KEY, recipientWire } from "./sealedRecipient";

/**
 * `file` quotes carry the file name as `x`, `audio` quotes (an audio file) its display title
 * (docs/file-sharing.md §1, §11.2).
 */
export type ReplyKind = "text" | "image" | "video" | "voice" | "file" | "audio";

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

const KINDS: ReplyKind[] = ["text", "image", "video", "voice", "file", "audio"];

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

/**
 * Plaintext to seal for a text message: the envelope carrying its recipient (`to`), its quote
 * (`re`) and/or its link preview (`lp`) — the same shape iOS `MessageTextPayload.wire` seals —
 * or, with none of them, the body itself.
 */
export function textPayload(
  text: string,
  replyTo: ReplyRef | null | undefined,
  linkPreview?: LinkPreview | null,
  to?: string | null,
): string {
  if (!replyTo && !linkPreview && !to) return text;
  const envelope: Record<string, unknown> = { t: "text", c: text };
  if (replyTo) envelope.re = replyRefWire(replyTo);
  if (linkPreview) envelope.lp = linkPreviewWire(linkPreview);
  if (to) envelope[RECIPIENT_KEY] = recipientWire(to);
  return JSON.stringify(envelope);
}

/**
 * Largest text-message plaintext sealed with a preview. Sealed twice and base64-expanded,
 * much more overflows the server's envelope cap (iOS `maxMediaPayloadPlaintextBytes`, 12 KB).
 */
const MAX_TEXT_PLAINTEXT_BYTES = 12 * 1024;

/**
 * Plaintext for a text message with its link preview trimmed to what fits (iOS
 * `MessagingController.textWire`): a long message first loses the preview's thumbnail, then its
 * description, then the preview itself — a preview is never the reason a message can't be sent.
 */
export function textWire(
  text: string,
  replyTo: ReplyRef | null | undefined,
  linkPreview: LinkPreview | null | undefined,
  /** The recipient's user id, sealed as `to` (`sealedRecipient.ts`). */
  to?: string | null,
): { wire: string; sealedPreview: LinkPreview | null } {
  if (!linkPreview) return { wire: textPayload(text, replyTo, null, to), sealedPreview: null };
  const candidates: LinkPreview[] = [
    linkPreview,
    { ...linkPreview, thumbnail: null },
    { ...linkPreview, thumbnail: null, summary: null },
  ];
  for (const candidate of candidates) {
    const wire = textPayload(text, replyTo, candidate, to);
    if (new TextEncoder().encode(wire).byteLength <= MAX_TEXT_PLAINTEXT_BYTES) {
      return { wire, sealedPreview: candidate };
    }
  }
  return { wire: textPayload(text, replyTo, null, to), sealedPreview: null };
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
      linkPreview: previewOfText(parseLinkPreview(parsed.lp), parsed.c),
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
    case "file":
      return "File";
    case "audio":
      return "Audio";
    default:
      return "Message";
  }
}

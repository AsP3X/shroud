import type { ChatKind } from "./messaging";

/** Which bubble a thread row draws. */
export type RowBubble = "text" | "photo" | "video" | "voice" | "file";

/**
 * A deleted message draws the text tombstone, whatever it was. A live photo needs a key or
 * bytes already decoded in this tab; otherwise it falls through to the text bubble too.
 */
export function rowBubble(
  message: { kind: ChatKind | string; deleted: boolean; mediaKey?: string | null },
  hasDecodedPhoto = false,
): RowBubble {
  if (message.deleted) return "text";
  if (message.kind === "image" && (Boolean(message.mediaKey) || hasDecodedPhoto)) return "photo";
  if (message.kind === "video") return "video";
  if (message.kind === "voice") return "voice";
  if (message.kind === "file") return "file";
  return "text";
}

/**
 * The recipient a message names inside its own plaintext (security audit M7).
 *
 * Every message is sealed twice with the same plaintext: once for the peer and once for the
 * sender's other devices (the `self` box). The envelope does not say whom the `self` box was
 * for, so a server could list my message to Alice in my chat with Bob, and my other devices would
 * show it as sent to Bob. New messages therefore carry `to`, the recipient's user id (lowercase
 * UUID; my own id in Notes), inside the sealed payload, where the server can neither read nor
 * strip it:
 *
 * - text: the envelope `{"c":<body>,"t":"text","to":<id>,…}` (see `reply.ts`). Builds that read
 *   replies already show `c` of that envelope and ignore keys they don't know;
 * - media: `to` in the media payload (`mediaPayload.ts`), whose unknown keys every build ignores;
 * - annotation: `to` in the annotation (`Annotation`), likewise.
 *
 * Opening checks it: a message I sent must name the chat's peer, one from the peer must name me.
 * A payload without `to` (every message from builds before it) is accepted as before. Shared with
 * iOS `SealedRecipient` and Android `SealedRecipient`; change all three together.
 */

/** Key of the recipient's user id in the sealed payload. */
export const RECIPIENT_KEY = "to";

/**
 * The `to` value sealed in `plaintext`: `undefined` when there is none (no such key, `null`, or a
 * plaintext that is not a payload of `contentType`), else the raw JSON value.
 */
function sealedRecipientValue(plaintext: string, contentType: string): unknown {
  const trimmed = plaintext.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return undefined;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return undefined;
  const object = parsed as Record<string, unknown>;
  // A text message is raw text unless it is the envelope (`parseTextPayload`): JSON someone typed
  // names nobody.
  if (contentType !== "media" && contentType !== "annotation") {
    if (object.t !== "text" || typeof object.c !== "string") return undefined;
  }
  const value = object[RECIPIENT_KEY];
  return value === null ? undefined : value;
}

/**
 * Whether `plaintext` (of a message of `contentType`) may be shown in a chat where it is for
 * `expectedRecipient`: the chat's peer for a message I sent, me for the peer's (me in Notes).
 * True when it names nobody (legacy); false when it names someone else, or `to` is not a string.
 */
export function recipientAccepted(plaintext: string, contentType: string, expectedRecipient: string): boolean {
  const value = sealedRecipientValue(plaintext, contentType);
  if (value === undefined) return true;
  return typeof value === "string" && value.toLowerCase() === expectedRecipient.toLowerCase();
}

/** The recipient as sealed: the user id, lowercased. */
export function recipientWire(userId: string): string {
  return userId.toLowerCase();
}

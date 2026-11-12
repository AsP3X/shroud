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
 *
 * `content_type` is set by the server outside the seal, so it never decides whether `to` is read
 * (security audit C1): any plaintext that is a JSON object with `to` is checked. It only marks a
 * text message whose plaintext is a media payload or an annotation as misfiled. The rule is
 * written down in `docs/architecture.md` (Sealed recipient).
 */

/** Key of the recipient's user id in the sealed payload. */
export const RECIPIENT_KEY = "to";

/**
 * The JSON object `plaintext` is, read as the media payload parser reads one (a leading BOM and
 * surrounding whitespace allowed: `trim` drops both), or `undefined` when it is not one.
 */
function plaintextObject(plaintext: string): Record<string, unknown> | undefined {
  const trimmed = plaintext.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return undefined;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return undefined;
  return parsed as Record<string, unknown>;
}

/**
 * Whether `object` has the shape of a media payload (`t` and `k` strings, `parseMediaPayload`) or
 * of an annotation (`t: "transcript"` and a string `r`, `parseAnnotation`).
 */
function isPayloadShaped(object: Record<string, unknown>): boolean {
  if (typeof object.t !== "string") return false;
  return typeof object.k === "string" || (object.t === "transcript" && typeof object.r === "string");
}

/**
 * Whether `plaintext` (of a message labelled `contentType`) may be shown in a chat where it is for
 * `expectedRecipient`: the chat's peer for a message I sent, me for the peer's (me in Notes).
 * True when it names nobody (legacy); false when it names someone else, `to` is not a string, or a
 * message not labelled `media` or `annotation` holds a media payload or an annotation (relabelled
 * by the server, its raw JSON would be the bubble).
 */
export function recipientAccepted(plaintext: string, contentType: string, expectedRecipient: string): boolean {
  const object = plaintextObject(plaintext);
  if (!object) return true;
  if (contentType !== "media" && contentType !== "annotation" && isPayloadShaped(object)) return false;
  const value = object[RECIPIENT_KEY];
  if (value === undefined || value === null) return true;
  return typeof value === "string" && value.toLowerCase() === expectedRecipient.toLowerCase();
}

/** The recipient as sealed: the user id, lowercased. */
export function recipientWire(userId: string): string {
  return userId.toLowerCase();
}

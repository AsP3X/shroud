import {
  clampSnippet,
  MAX_REPLY_SNIPPET,
  parseReplyRef,
  parseTextPayload,
  replyKindLabel,
  replyRefWire,
  textPayload,
  type ReplyRef,
} from "./reply";
import { parseMediaPayload, payloadReply, withReply } from "./crypto/mediaPayload";

/*
 * Wire-format selftest for replies. The quote lives inside the sealed plaintext, so the only
 * thing the two clients must agree on is how it is written and read back — the fixtures below
 * are the same strings `ios/shroudTests/MessageReplyTests.swift` checks.
 */

const MESSAGE_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
const SENDER_ID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

const ref: ReplyRef = {
  id: MESSAGE_ID,
  senderUserId: SENDER_ID,
  kind: "text",
  snippet: "Hey! Are we still on for tomorrow?",
};

/* --- snippets ------------------------------------------------------------ */

if (clampSnippet("  two   lines\nof   text \n") !== "two lines of text") {
  throw new Error("clampSnippet: whitespace must collapse");
}
const long = clampSnippet("a".repeat(400));
if ([...long].length !== MAX_REPLY_SNIPPET || !long.endsWith("…")) {
  throw new Error("clampSnippet: cap with an ellipsis");
}
if ([...clampSnippet("👩‍👩‍👧‍👦".repeat(60))].length > MAX_REPLY_SNIPPET) {
  throw new Error("clampSnippet: must count code points, not UTF-16 units");
}

/* --- reference ----------------------------------------------------------- */

const wire = replyRefWire(ref);
if (wire.id !== MESSAGE_ID || wire.u !== SENDER_ID || wire.k !== "text" || wire.x !== ref.snippet) {
  throw new Error("replyRefWire: terse keys");
}
if (replyRefWire({ ...ref, snippet: "" }).x !== undefined) {
  throw new Error("replyRefWire: an empty snippet is left out");
}
const parsedRef = parseReplyRef(wire);
if (!parsedRef || parsedRef.id !== MESSAGE_ID || parsedRef.kind !== "text") {
  throw new Error("parseReplyRef: round trip");
}
if (parseReplyRef({ u: SENDER_ID }) !== null) throw new Error("parseReplyRef: needs an id");
if (parseReplyRef({ id: MESSAGE_ID }) !== null) throw new Error("parseReplyRef: needs an author");
if (parseReplyRef({ id: MESSAGE_ID, u: SENDER_ID, k: "sticker" })?.kind !== "text") {
  throw new Error("parseReplyRef: unknown kinds fall back to text");
}
if (replyKindLabel("voice") !== "Voice message") throw new Error("replyKindLabel: voice");

/* --- text payload -------------------------------------------------------- */

if (textPayload("Just a message", null) !== "Just a message") {
  throw new Error("textPayload: a plain message stays raw UTF-8");
}
const plain = parseTextPayload("Just a message");
if (plain.text !== "Just a message" || plain.replyTo !== null) {
  throw new Error("parseTextPayload: plain text");
}
const round = parseTextPayload(textPayload("Yes! 10am at the trailhead", ref));
if (round.text !== "Yes! 10am at the trailhead" || round.replyTo?.id !== MESSAGE_ID) {
  throw new Error("parseTextPayload: reply round trip");
}
/* Someone typing JSON must not be mistaken for a reply. */
const typed = parseTextPayload('{"hello":"world"}');
if (typed.text !== '{"hello":"world"}' || typed.replyTo !== null) {
  throw new Error("parseTextPayload: arbitrary JSON stays text");
}
const broken = '{"t":"text","c":"half';
if (parseTextPayload(broken).text !== broken) {
  throw new Error("parseTextPayload: a truncated envelope stays text");
}

/* Golden fixture: exactly what iOS seals (MessageTextPayload.wire). */
const fromIOS =
  '{"t":"text","c":"Perfect, see you there","re":{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301",' +
  '"u":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","k":"voice","x":"Voice message"}}';
const opened = parseTextPayload(fromIOS);
if (
  opened.text !== "Perfect, see you there" ||
  opened.replyTo?.id !== MESSAGE_ID ||
  opened.replyTo?.senderUserId !== SENDER_ID ||
  opened.replyTo?.kind !== "voice" ||
  opened.replyTo?.snippet !== "Voice message"
) {
  throw new Error("parseTextPayload: must open the envelope iOS seals");
}

/* --- media payload ------------------------------------------------------- */

const media = withReply(
  { t: "image", mime: "image/jpeg", w: 1024, h: 768, k: "a2V5", s: 2048 },
  { ...ref, kind: "image", snippet: "Nice shot" },
);
const reparsed = parseMediaPayload(JSON.stringify(media));
if (!reparsed) throw new Error("parseMediaPayload: media with a quote");
const mediaQuote = payloadReply(reparsed);
if (mediaQuote?.id !== MESSAGE_ID || mediaQuote.kind !== "image" || mediaQuote.snippet !== "Nice shot") {
  throw new Error("payloadReply: round trip");
}
/* Payloads sealed before replies existed decode exactly as before. */
const legacy = parseMediaPayload('{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}');
if (!legacy || payloadReply(legacy) !== null || legacy.c !== "caption") {
  throw new Error("payloadReply: legacy payloads have no quote");
}

console.log("reply selftest ok");

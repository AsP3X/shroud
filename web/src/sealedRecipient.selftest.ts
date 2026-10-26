/**
 * Wire format of the sealed recipient (`to`, security audit M7): what new builds seal, that the
 * parsers every current build runs still read it, and which plaintexts `recipientAccepted` lets
 * through. The fixed strings are the ones iOS `SealedRecipientTests` and Android
 * `SealedRecipientTest` check.
 * Run: `npx tsx src/sealedRecipient.selftest.ts`.
 */
import { parseMediaPayload } from "./crypto/mediaPayload";
import { parseAnnotation } from "./messaging";
import { parseTextPayload, textPayload, textWire, type ReplyRef } from "./reply";
import { recipientAccepted } from "./sealedRecipient";

function check(condition: boolean, what: string): void {
  if (!condition) throw new Error(`sealed recipient selftest: ${what}`);
}

const ME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const BOB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const ALICE = "a11ce000-0000-4000-8000-000000000001";

/* --- text ------------------------------------------------------------------ */

const sealed = textPayload("See you at 10", null, null, BOB.toUpperCase());
const sealedObject = JSON.parse(sealed) as Record<string, unknown>;
check(sealedObject.t === "text" && sealedObject.c === "See you at 10", "a plain message is sealed as the envelope");
check(sealedObject.to === BOB, "`to` is the recipient, lowercased");
check(!("re" in sealedObject) && !("lp" in sealedObject), "no quote or preview is invented");
check(textPayload("See you at 10", null) === "See you at 10", "without a recipient the body stays raw");

// The parser every build since replies runs reads the body of an envelope without `re`/`lp`.
const parsed = parseTextPayload(sealed);
check(parsed.text === "See you at 10", "the old parser shows the body");
check(parsed.replyTo === null && parsed.linkPreview === null, "the old parser finds no quote or preview");

// iOS and Android seal the keys sorted; the parser does not care.
const sortedFixture = `{"c":"See you at 10","t":"text","to":"${BOB}"}`;
check(parseTextPayload(sortedFixture).text === "See you at 10", "the iOS/Android key order parses");
check(recipientAccepted(sortedFixture, "text", BOB), "the iOS/Android fixture names Bob");

const quote: ReplyRef = { id: "3f2504e0-4f89-41d3-9a0c-0305e82c3301", senderUserId: ME, kind: "text", snippet: "10?" };
const reply = textWire("Yes", quote, null, BOB).wire;
const replyParsed = parseTextPayload(reply);
check(replyParsed.text === "Yes" && replyParsed.replyTo?.id === quote.id, "a reply keeps its quote next to `to`");
check(recipientAccepted(reply, "text", BOB), "a reply names its recipient");

check(recipientAccepted(sealed, "text", BOB), "the recipient is accepted");
check(recipientAccepted(sealed, "text", BOB.toUpperCase()), "ids compare case-insensitively");
check(!recipientAccepted(sealed, "text", ALICE), "another recipient is refused");
check(!recipientAccepted(sealed, "text", ME), "my id is not Bob's");

// Legacy: messages from builds before `to` name nobody and are accepted anywhere.
check(recipientAccepted("See you at 10", "text", ALICE), "raw text names nobody");
check(recipientAccepted(textPayload("Yes", quote), "text", ALICE), "a legacy reply names nobody");
// Text someone typed is not an envelope, whatever keys it has.
check(recipientAccepted(`{"to":"${BOB}"}`, "text", ALICE), "typed JSON without the envelope names nobody");
check(recipientAccepted(`{"t":"text","c":"x","to":null}`, "text", ALICE), "a null `to` names nobody");
// A `to` that is not an id string fails closed.
check(!recipientAccepted(`{"t":"text","c":"x","to":5}`, "text", ALICE), "a non-string `to` is refused");
check(!recipientAccepted(`{"t":"text","c":"x","to":""}`, "text", ALICE), "an empty `to` is refused");
check(!recipientAccepted(`{"t":"text","c":"x","to":"${BOB} "}`, "text", BOB), "a padded id is refused");

/* --- media ----------------------------------------------------------------- */

const mediaFixture = `{"h":768,"k":"a2V5","mime":"image/jpeg","t":"image","to":"${BOB}","w":1024}`;
const media = parseMediaPayload(mediaFixture);
check(media?.t === "image" && media.k === "a2V5" && media.w === 1024, "the media parser reads a payload with `to`");
check(recipientAccepted(mediaFixture, "media", BOB), "a media payload names Bob");
check(!recipientAccepted(mediaFixture, "media", ALICE), "a media payload for Bob is refused in Alice's chat");
const legacyMedia = '{"t":"voice","mime":"audio/mp4","w":0,"h":0,"k":"a2V5","d":1200}';
check(recipientAccepted(legacyMedia, "media", ALICE), "a legacy media payload names nobody");
check(recipientAccepted("not json", "media", ALICE), "a broken payload names nobody (it shows as Media)");

/* --- annotation ------------------------------------------------------------ */

const annotationFixture = `{"c":"Hello there","r":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","t":"transcript","to":"${BOB}"}`;
const annotation = parseAnnotation(annotationFixture);
check(annotation?.c === "Hello there" && annotation.r === "3f2504e0-4f89-41d3-9a0c-0305e82c3301", "the annotation parser reads it");
check(recipientAccepted(annotationFixture, "annotation", BOB), "an annotation names Bob");
check(!recipientAccepted(annotationFixture, "annotation", ME), "an annotation for Bob is refused for me");

console.log("sealed recipient selftest ok");

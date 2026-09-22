import {
  detectLinks,
  displaySiteName,
  firstPreviewableUrl,
  linkPreviewWire,
  MAX_THUMBNAIL_BYTES,
  MAX_TITLE,
  parseLinkPreview,
  splitLinks,
} from "./links";
import { parseTextPayload } from "./reply";
import {
  isVideoPayload,
  isVoicePayload,
  parseMediaPayload,
  payloadLinkPreview,
} from "./crypto/mediaPayload";

/*
 * Link detection and link-preview wire format. The vectors are the same strings iOS checks in
 * `LinkDetectorTests` / `LinkPreviewPayloadTests`: both clients must underline the same
 * characters and read the same sealed `lp`.
 */

function fail(message: string): never {
  throw new Error(message);
}

/* --- detection (UTF-16 offsets) ------------------------------------------ */

const vectors: [string, [number, number, string][]][] = [
  ["Plain text, no links.", []],
  ["see https://example.com/path?q=1.", [[4, 28, "https://example.com/path?q=1"]]],
  ["www.example.org", [[0, 15, "https://www.example.org"]]],
  ["Route: komoot.com/tour/1398273", [[7, 23, "https://komoot.com/tour/1398273"]]],
  ["(see en.wikipedia.org/wiki/Foo_(bar))", [[5, 31, "https://en.wikipedia.org/wiki/Foo_(bar)"]]],
  ["mail bob@example.com please", [[5, 15, "mailto:bob@example.com"]]],
  ["Ende.Da geht es weiter", []],
  ["file.txt and v1.2.3", []],
  ["HTTPS://Example.COM", [[0, 19, "HTTPS://Example.COM"]]],
  ["two links: a.com and https://b.org/x", [[11, 5, "https://a.com"], [21, 15, "https://b.org/x"]]],
  ["user@host (no tld)", []],
  ["https://", []],
  ["end of sentence www.test.de!", [[16, 11, "https://www.test.de"]]],
  ['"https://quoted.com/a"', [[1, 20, "https://quoted.com/a"]]],
  ["emoji 👍https://x.io", [[8, 12, "https://x.io"]]],
  ["a.b.c and example.com.", [[10, 11, "https://example.com"]]],
  ["localhost:3000 and http://localhost:3000/x", [[19, 23, "http://localhost:3000/x"]]],
  ["Link:https://colon.com", [[5, 17, "https://colon.com"]]],
];

for (const [text, expected] of vectors) {
  const found = detectLinks(text);
  if (found.length !== expected.length) fail(`detectLinks: ${found.length} links in ${JSON.stringify(text)}`);
  found.forEach((link, i) => {
    const [start, length, url] = expected[i];
    if (link.start !== start || link.length !== length || link.url !== url) {
      fail(`detectLinks: ${JSON.stringify(link)} in ${JSON.stringify(text)}`);
    }
  });
}

if (firstPreviewableUrl("write bob@example.com or see example.com") !== "https://example.com") {
  fail("firstPreviewableUrl: e-mail addresses are never previewed");
}
if (firstPreviewableUrl("bob@example.com") !== null) fail("firstPreviewableUrl: e-mail only");
const straße = detectLinks("Visit straße.de today");
if (straße.length !== 1 || straße[0].start !== 6 || straße[0].length !== 9) {
  fail("detectLinks: international domain range");
}
const parts = splitLinks("see example.com now");
if (parts.map((p) => p.text).join("|") !== "see |example.com| now" || !parts[1].link) {
  fail("splitLinks: plain / link / plain");
}

/* --- lp object ----------------------------------------------------------- */

const full = parseLinkPreview({
  u: "https://komoot.com/tour/1398273",
  n: "komoot",
  ti: "Herzogstand – Heimgarten ridge walk",
  d: "Intermediate hike · 13.6 km",
  th: "/9j/4AAQ",
  w: 1200,
  h: 630,
  vd: true,
  ab: true,
});
if (!full) fail("parseLinkPreview: full object");
const again = parseLinkPreview(linkPreviewWire(full));
if (JSON.stringify(again) !== JSON.stringify(full)) fail("linkPreviewWire: round trip");
for (const url of ["javascript:alert(1)", "ftp://example.com", "file:///etc/passwd", ""]) {
  if (parseLinkPreview({ u: url })) fail(`parseLinkPreview: ${url} must be refused`);
}
const bigThumb = btoa("x".repeat(MAX_THUMBNAIL_BYTES + 64));
if (parseLinkPreview({ u: "https://example.com", th: bigThumb })?.thumbnail !== null) {
  fail("parseLinkPreview: a thumbnail over budget is dropped");
}
const long = parseLinkPreview({ u: "https://example.com", ti: "a".repeat(500) });
if ([...(long?.title ?? "")].length !== MAX_TITLE || !long?.title?.endsWith("…")) {
  fail("parseLinkPreview: long titles are clamped");
}
if (displaySiteName(parseLinkPreview({ u: "https://www.example.com/a" })!) !== "example.com") {
  fail("displaySiteName: host fallback");
}

/* --- text envelope ------------------------------------------------------- */

/* What iOS seals (`MessageTextPayload.wire` with a preview). */
const fromIOS =
  '{"t":"text","c":"Route: komoot.com/tour/1398273","lp":{"u":"https://komoot.com/tour/1398273","n":"komoot","ti":"Herzogstand","d":"Ridge walk","w":1200,"h":630,"vd":true,"ab":true}}';
const opened = parseTextPayload(fromIOS);
if (
  opened.text !== "Route: komoot.com/tour/1398273" ||
  opened.replyTo !== null ||
  opened.linkPreview?.url !== "https://komoot.com/tour/1398273" ||
  opened.linkPreview.siteName !== "komoot" ||
  opened.linkPreview.title !== "Herzogstand" ||
  opened.linkPreview.summary !== "Ridge walk" ||
  opened.linkPreview.imageWidth !== 1200 ||
  opened.linkPreview.imageHeight !== 630 ||
  !opened.linkPreview.isVideo ||
  !opened.linkPreview.showsAboveText
) {
  fail("parseTextPayload: must open the preview iOS seals");
}
const broken = parseTextPayload('{"t":"text","c":"still readable","lp":{"u":"javascript:alert(1)"}}');
if (broken.text !== "still readable" || broken.linkPreview !== null) {
  fail("parseTextPayload: a broken preview keeps the message");
}
if (parseTextPayload("just text").linkPreview !== null) fail("parseTextPayload: plain text");

/* --- media envelope (large image) ------------------------------------------ */

const linkMedia = parseMediaPayload(
  JSON.stringify({ t: "link", mime: "image/jpeg", w: 1200, h: 630, k: "a2V5", c: "Route", s: 48000, lp: { u: "https://komoot.com" } }),
);
if (!linkMedia || !payloadLinkPreview(linkMedia) || isVoicePayload(linkMedia) || isVideoPayload(linkMedia)) {
  fail("parseMediaPayload: t=link is a link, not a photo, voice note or video");
}
const photo = parseMediaPayload('{"t":"image","mime":"image/jpeg","w":4,"h":3,"k":"a2V5","c":"caption"}');
if (!photo || payloadLinkPreview(photo) !== null) fail("payloadLinkPreview: photos have none");

console.log("links selftest ok");

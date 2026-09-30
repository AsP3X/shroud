/*
 * The pieces a link preview is built from in the browser: the HTTP reader, the page-metadata
 * parser, image headers, the composer's state machine, and the sealed text budget.
 *
 * The metadata vectors are the ones iOS checks in `LinkPageMetadataParserTests` — both clients
 * must read the same title, description and picture out of the same page.
 */

import { textWire } from "../reply";
import { parseTextPayload } from "../reply";
import type { LinkPreview } from "../links";
import { headEnded, HttpResponseReader } from "./http";
import { imageDimensions, isDecodableSize, MAX_IMAGE_PIXELS } from "./images";
import { decodeEntities, parsePageMetadata, secureUrl } from "./pageMetadata";
import { allowedTarget } from "./relayFetch";
import { LinkPreviewComposer } from "./composer";
import type { LinkPreviewDraft } from "./builder";

function fail(message: string): never {
  throw new Error(message);
}

function expect(condition: unknown, message: string): void {
  if (!condition) fail(message);
}

const utf8 = (text: string) => new TextEncoder().encode(text);
const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes);

/* --- HTTP reader ---------------------------------------------------------- */

function read(chunks: string[], maxBytes = 1024 * 1024, close = true) {
  const reader = new HttpResponseReader(maxBytes);
  for (const chunk of chunks) reader.push(utf8(chunk));
  if (close) reader.end();
  return reader;
}

{
  const reader = read(["HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/html\r\n\r\nhello"]);
  const response = reader.response();
  expect(response.status === 200, "http: status");
  expect(response.headers.get("content-type") === "text/html", "http: header");
  expect(text(response.body) === "hello", "http: body");
  expect(response.complete && !response.truncated, "http: complete");
}
{
  // Split across frames, as the relay delivers them.
  const reader = read(["HTTP/1.1 200 OK\r\nContent-Len", "gth: 11\r\n\r\nhel", "lo world"]);
  expect(text(reader.response().body) === "hello world", "http: split frames");
}
{
  const reader = read([
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
    "5\r\nhello\r\n",
    "6\r\n world\r\n",
    "0\r\n\r\n",
  ]);
  const response = reader.response();
  expect(text(response.body) === "hello world", "http: chunked");
  expect(response.complete, "http: chunked complete");
}
{
  // No length and no chunking: the body ends when the connection does.
  const reader = read(["HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>"]);
  expect(text(reader.response().body) === "<html>", "http: read until close");
  expect(reader.response().complete, "http: close completes");
}
{
  const reader = read(["HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100)], 10);
  const response = reader.response();
  expect(response.body.length === 10 && response.truncated, "http: cap");
}
{
  // 100 Continue is skipped, and a redirect's Location is readable.
  const reader = read(["HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 301 Moved\r\nLocation: https://example.org/\r\nContent-Length: 0\r\n\r\n"]);
  const response = reader.response();
  expect(response.status === 301, "http: interim skipped");
  expect(response.headers.get("location") === "https://example.org/", "http: location");
}
expect(headEnded(utf8("<html><head><title>x</title></head>")), "headEnded: </head>");
expect(headEnded(utf8("<html><head><body>")), "headEnded: <body>");
expect(!headEnded(utf8("<html><head><meta charset=utf-8>")), "headEnded: still in head");

/* --- page metadata (same vectors as iOS) ---------------------------------- */

const PAGE = "https://www.example.com/articles/42";
const parse = (html: string, contentType: string | null = "text/html; charset=utf-8") =>
  parsePageMetadata(utf8(html), PAGE, contentType);

{
  const metadata = parse(`<html><head>
    <title>Fallback title</title>
    <meta property="og:site_name" content="Example">
    <meta property="og:title" content="The real title">
    <meta name="description" content="Plain description">
    <meta property="og:description" content="OG description">
    <meta property="og:image" content="https://cdn.example.com/card.jpg">
    <meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
    </head><body></body></html>`);
  expect(metadata.siteName === "Example", "meta: site name");
  expect(metadata.title === "The real title", "meta: og:title wins");
  expect(metadata.summary === "OG description", "meta: og:description wins");
  expect(metadata.imageUrl === "https://cdn.example.com/card.jpg", "meta: image");
  expect(metadata.imageWidth === 1200 && metadata.imageHeight === 630, "meta: image size");
  expect(!metadata.isVideo, "meta: not a video");
}
{
  const metadata = parse(`<head><TITLE>Only a &amp; title</TITLE>
    <meta name="twitter:description" content='Single &#39;quoted&#39; &#x2014; ok'>
    <meta name=twitter:image content=/img/card.png></head>`);
  expect(metadata.title === "Only a & title", "meta: title entities");
  expect(metadata.summary === "Single 'quoted' — ok", "meta: twitter description");
  expect(metadata.imageUrl === "https://www.example.com/img/card.png", "meta: relative image");
  expect(metadata.siteName === null, "meta: no site name");
}
expect(parse(`<head><meta property="og:type" content="video.other"><meta property="og:title" content="A"></head>`).isVideo, "meta: video type");
expect(parse(`<head><meta property="og:video:url" content="https://x.com/v"><meta property="og:title" content="A"></head>`).isVideo, "meta: video url");
{
  const insecure = parse(`<head><meta property="og:image" content="http://cdn.example.com/a.jpg"></head>`);
  expect(insecure.imageUrl?.startsWith("https://"), "meta: http image upgraded");
  const protocolRelative = parse(`<head><meta property="og:image" content="//cdn.example.com/b.jpg"></head>`);
  expect(protocolRelative.imageUrl === "https://cdn.example.com/b.jpg", "meta: protocol-relative image");
  expect(parse(`<head><meta property="og:image" content="data:image/png;base64,AAAA"></head>`).imageUrl === null, "meta: data image dropped");
}
{
  const metadata = parse(`<head><meta property="og:title" content="Head"></head><body><meta property="og:description" content="Injected"></body>`);
  expect(metadata.title === "Head" && metadata.summary === null, "meta: body tags ignored");
}
expect(parse(`<head><metadata content="x"></metadata><meta property="og:title" content="Real"></head>`).title === "Real", "meta: <metadata> is not <meta>");
{
  // Today's iOS fix: a ">" inside a quoted value does not end the tag.
  const metadata = parse(`<head><meta property="og:title" content="Rust > Go? A comparison"><meta name='description' content='Home > Shop -> Sale'></head>`);
  expect(metadata.title === "Rust > Go? A comparison", "meta: > in title");
  expect(metadata.summary === "Home > Shop -> Sale", "meta: > in description");
}
expect(
  parse(`<head><meta name="description" content="He said "hi"" ><meta property="og:title" content="After"></head>`).title === "After",
  "meta: broken quotes do not swallow the next tag",
);
{
  // Legacy charsets, from the header and from <meta>.
  const cyrillic = new Uint8Array([
    ...utf8('<head><meta charset="windows-1251"><meta property="og:title" content="'),
    0xcf, 0xf0, 0xe8, 0xe2, 0xe5, 0xf2, // Привет
    ...utf8('"></head>'),
  ]);
  expect(parsePageMetadata(cyrillic, PAGE, "text/html").title === "Привет", "meta: windows-1251");
  const latin = new Uint8Array([
    ...utf8('<head><meta property="og:title" content="Gr'),
    0xfc, 0xdf, 0x65, // üße in latin-1
    ...utf8('"></head>'),
  ]);
  expect(parsePageMetadata(latin, PAGE, "text/html; charset=iso-8859-1").title === "Grüße", "meta: latin-1");
}
{
  // Only the head is read, so the last character is often cut in half.
  const full = utf8('<head><meta property="og:title" content="Grüße"></head><body>€');
  const cut = full.subarray(0, full.length - 1);
  expect(parsePageMetadata(cut, PAGE, "text/html; charset=utf-8").title === "Grüße", "meta: cut character");
}
expect(decodeEntities("a &amp; b &#65; &#x42; &nope; &") === "a & b A B &nope; &", "entities");
expect(secureUrl("javascript:alert(1)", PAGE) === null, "secureUrl: script dropped");

/* --- image headers -------------------------------------------------------- */

function png(width: number, height: number): Uint8Array {
  const bytes = new Uint8Array(24);
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
  bytes.set(utf8("IHDR"), 12);
  new DataView(bytes.buffer).setUint32(16, width);
  new DataView(bytes.buffer).setUint32(20, height);
  return bytes;
}
function jpeg(width: number, height: number): Uint8Array {
  const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, ...new Array(14).fill(0), 0xff, 0xc0, 0x00, 0x11, 0x08, 0, 0, 0, 0]);
  const view = new DataView(bytes.buffer);
  view.setUint16(bytes.length - 4, height);
  view.setUint16(bytes.length - 2, width);
  return bytes;
}
expect(JSON.stringify(imageDimensions(png(1200, 630))) === '{"width":1200,"height":630}', "png size");
expect(JSON.stringify(imageDimensions(jpeg(800, 400))) === '{"width":800,"height":400}', "jpeg size");
{
  const gif = new Uint8Array(10);
  gif.set(utf8("GIF89a"), 0);
  new DataView(gif.buffer).setUint16(6, 320, true);
  new DataView(gif.buffer).setUint16(8, 240, true);
  expect(JSON.stringify(imageDimensions(gif)) === '{"width":320,"height":240}', "gif size");
}
expect(imageDimensions(utf8("not an image at all, really not")) === null, "unknown format");
expect(isDecodableSize({ width: 1200, height: 630 }), "decodable: card");
expect(!isDecodableSize({ width: 20000, height: 20000 }), "decodable: bomb refused");
expect(!isDecodableSize({ width: 40, height: 40 }), "decodable: icon refused");
expect(!isDecodableSize(null), "decodable: unknown refused");
expect(MAX_IMAGE_PIXELS === 40_000_000, "pixel cap matches iOS");

/* --- relay targets -------------------------------------------------------- */

expect(allowedTarget("http://example.com/a")?.href === "https://example.com/a", "target: http upgraded");
for (const bad of [
  "https://localhost/",
  "https://127.0.0.1/",
  "https://10.0.0.8/",
  "https://[::1]/",
  "https://printer.local/",
  "https://nas.home/",
  "https://intranet/",
  "https://example.com:8443/",
  "ftp://example.com/",
  "not a url",
]) {
  expect(allowedTarget(bad) === null, `target: ${bad} refused`);
}
expect(allowedTarget("https://user:pass@example.com/")?.href === "https://example.com/", "target: credentials stripped");

/* --- composer ------------------------------------------------------------- */

function draftFor(url: string, large: boolean): LinkPreviewDraft {
  const preview: LinkPreview = {
    url,
    siteName: "Example",
    title: "A page",
    summary: "About something",
    thumbnail: "dGh1bWI=",
    imageWidth: large ? 1200 : null,
    imageHeight: large ? 630 : null,
    isVideo: false,
    showsAboveText: false,
  };
  return {
    preview,
    largeImage: large ? new Uint8Array([1, 2, 3]) : null,
    largeImageWidth: large ? 1200 : null,
    largeImageHeight: large ? 630 : null,
    placeholder: large ? "cGxhY2U=" : null,
    prefersLargeImage: large,
  };
}

async function settle(times = 6): Promise<void> {
  for (let i = 0; i < times; i++) await new Promise((resolve) => setTimeout(resolve, 0));
}

{
  const asked: string[] = [];
  const composer = new LinkPreviewComposer(
    async (url) => {
      asked.push(url);
      return draftFor(url, false);
    },
    () => {},
    0,
  );
  composer.draftChanged("look https://example.com/a and https://other.org", true);
  await settle();
  expect(composer.draft?.preview.url === "https://example.com/a", "composer: first link");
  expect(asked.length === 1, "composer: one fetch");

  // Switched off: nothing is fetched.
  composer.reset();
  composer.draftChanged("https://example.com/b", false);
  await settle();
  expect(composer.phase.kind === "idle" && asked.length === 1, "composer: disabled");

  // ✕ keeps this link dismissed for the rest of the draft; another link loads.
  composer.draftChanged("https://example.com/a", true);
  await settle();
  composer.dismiss();
  composer.draftChanged("https://example.com/a is great", true);
  await settle();
  expect(composer.phase.kind === "idle", "composer: stays dismissed");
  composer.draftChanged("try https://other.org", true);
  await settle();
  expect(composer.draft?.preview.url === "https://other.org", "composer: new link loads");

  // The link must still be in the text at send time.
  expect(composer.takeAttachment("the link is gone") === null, "composer: link left the text");
  expect(composer.phase.kind === "idle", "composer: send resets");
}
{
  const asked: string[] = [];
  const composer = new LinkPreviewComposer(
    async (url) => {
      asked.push(url);
      return draftFor(url, true);
    },
    () => {},
    0,
  );
  composer.draftChanged("https://Example.com/Tour", true);
  await settle();
  expect(composer.usesLargeImage, "composer: large by default for a wide picture");
  // The same page with its host retyped: no second fetch, and the preview still goes out.
  composer.draftChanged("https://example.com/Tour", true);
  await settle();
  expect(asked.length === 1, "composer: host case is the same link");
  composer.toggleShowsAboveText();
  composer.toggleImageSize();
  const attachment = composer.takeAttachment("https://example.com/Tour");
  expect(attachment?.preview.showsAboveText === true, "composer: show above text");
  expect(attachment?.largeImage === null, "composer: smaller image sends the thumbnail only");

  // Paths are case-sensitive: another page gets its own preview.
  composer.draftChanged("https://example.com/tour", true);
  await settle();
  expect(asked.length === 2, "composer: path case is another page");
}

/* --- sealed text budget --------------------------------------------------- */

const preview: LinkPreview = {
  url: "https://example.com/a",
  siteName: "Example",
  title: "A page",
  summary: "x".repeat(300),
  thumbnail: "A".repeat(8000),
  imageWidth: 1200,
  imageHeight: 630,
  isVideo: false,
  showsAboveText: false,
};
{
  const { wire, sealedPreview } = textWire("Look at this", null, preview);
  expect(sealedPreview?.thumbnail === "A".repeat(8000), "budget: thumbnail fits");
  expect(parseTextPayload(wire).linkPreview?.title === "A page", "budget: round trip");
}
{
  // A long message drops the thumbnail first, then the description, then the preview.
  const { sealedPreview } = textWire("y".repeat(6000), null, preview);
  expect(sealedPreview !== null && sealedPreview.thumbnail === null, "budget: thumbnail dropped");
  expect(sealedPreview?.summary === "x".repeat(300), "budget: description kept");
  // Just under the 12 KB budget: only a preview without its description still fits.
  const tighter = textWire("y".repeat(12 * 1024 - 350), null, preview);
  expect(tighter.sealedPreview !== null && tighter.sealedPreview.summary === null, "budget: description dropped");
  expect(tighter.sealedPreview?.title === "A page", "budget: title kept");
  // No room for any preview: the message goes as plain text.
  const hopeless = textWire("y".repeat(12 * 1024 - 50), null, preview);
  expect(hopeless.sealedPreview === null, "budget: preview dropped");
  expect(hopeless.wire === "y".repeat(12 * 1024 - 50), "budget: plain text survives");
}

console.log("link preview selftest ok");

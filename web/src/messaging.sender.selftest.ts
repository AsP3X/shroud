/**
 * A message id is not a capability. Cached plaintext is reused only for the same sender,
 * a message from someone outside the chat never opens, a cached body that names another
 * recipient is not shown, and an idempotent media replay remembers the row the server kept.
 * Run: `npx tsx src/messaging.sender.selftest.ts`.
 */
import type { WireMessage } from "./api/client";
import type { IdentityMaterial } from "./crypto/identity";

function memoryStorage(map: Map<string, string>): Storage {
  return {
    getItem: (key: string) => (map.has(key) ? map.get(key)! : null),
    setItem: (key: string, value: string) => {
      map.set(key, String(value));
    },
    removeItem: (key: string) => {
      map.delete(key);
    },
    key: (index: number) => [...map.keys()][index] ?? null,
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
  };
}

Object.defineProperty(globalThis, "localStorage", { value: memoryStorage(new Map()), configurable: true });
Object.defineProperty(globalThis, "sessionStorage", { value: memoryStorage(new Map()), configurable: true });
Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });

function check(condition: boolean, what: string): void {
  if (!condition) throw new Error(`sender selftest: ${what}`);
}

const ME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const BOB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const TOKEN = "session-token-for-the-selftest";
const ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const identityFetches: string[] = [];
/** What `GET /messages` lists; null answers 404 like every other path. */
let listed: WireMessage[] | null = null;

globalThis.fetch = (async (input: RequestInfo | URL) => {
  const url = new URL(String(input), "https://shroud.test");
  if (listed && url.pathname.endsWith("/messages")) {
    return new Response(JSON.stringify({ conversation_id: null, messages: listed, has_more: false }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }
  identityFetches.push(url.pathname);
  return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "no key" } }), {
    status: 404,
    headers: { "Content-Type": "application/json" },
  });
}) as typeof fetch;

const { createVault, derivePinSecrets } = await import("./crypto/vault");
const { loadPlaintext, loadPreview, savePlaintext } = await import("./crypto/plaintextCache");
const { decodeIncoming, hydratePreviews, keptMediaSend } = await import("./messaging");

const secrets = await derivePinSecrets("123456", new Uint8Array(16), 1);
createVault(ME, { secrets, pepper: new Uint8Array(32).fill(7), guardId: "guard" }, new Uint8Array(32).fill(9));
const material = {} as IdentityMaterial;

function wire(from: string, extra: Partial<WireMessage> = {}): WireMessage {
  return {
    id: ID,
    conversation_id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    sender_user_id: from,
    sender_device_id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    client_message_id: "ffffffff-ffff-4fff-8fff-ffffffffffff",
    content_type: "text",
    ciphertext: btoa("not-an-envelope"),
    deleted_for_everyone: false,
    created_at: "2026-10-02T12:00:00.000Z",
    ...extra,
  };
}

savePlaintext(ID, ME, "for your eyes");

const bob = await decodeIncoming(wire(BOB), ME, BOB, TOKEN, material);
check(bob.senderUserId === BOB, "the re-served row stays Bob's");
check(bob.text === "[Unable to decrypt]", "Bob's row does not open Alice's plaintext");
check(bob.failed === true, "a sender mismatch fails closed");
check(
  identityFetches.some((path) => path.includes(`/keys/identity/${BOB}`)),
  "the other sender is opened for real",
);
check(loadPlaintext(ID, BOB) === null, "Bob has no cached body");
check(loadPlaintext(ID, ME) === "for your eyes", "the first sender's body stays theirs");

const fetchesAfterBob = identityFetches.length;
const mine = await decodeIncoming(wire(ME), ME, BOB, TOKEN, material);
check(mine.text === "for your eyes", "the same sender still reads the cache");
check(mine.senderUserId === ME, "the cached row is still ours");
check(identityFetches.length === fetchesAfterBob, "a cache hit for the same sender does not open");

/*
 * Security audit M7: the server lists a stranger's message in the chat with Bob. It must not
 * open, read a body cached for that sender, or fetch (and so pin) the stranger's key.
 */
const STRANGER = "99999999-9999-4999-8999-999999999999";
const PLANTED = "12121212-1212-4212-8212-121212121212";
const SEALED = "34343434-3434-4343-8343-343434343434";
const GONE = "56565656-5656-4565-8565-565656565656";
savePlaintext(PLANTED, STRANGER, "planted by the server");
const fetchesBeforeStranger = identityFetches.length;

const planted = await decodeIncoming(wire(STRANGER, { id: PLANTED }), ME, BOB, TOKEN, material);
check(planted.failed === true, "a stranger's message in Bob's chat fails");
check(planted.text === "[Unable to decrypt]", "a body cached for the stranger is not shown");
check(planted.isMine === false && planted.senderUserId === STRANGER, "the failed row still names its sender");

const sealed = await decodeIncoming(wire(STRANGER, { id: SEALED }), ME, BOB, TOKEN, material);
check(sealed.failed === true, "a stranger's envelope is not opened");
const gone = await decodeIncoming(
  wire(STRANGER, { id: GONE, ciphertext: null, deleted_for_everyone: true }),
  ME,
  BOB,
  TOKEN,
  material,
);
check(gone.failed === true && !gone.deleted, "a stranger's tombstone is not Bob's deletion");
const note = await decodeIncoming(wire(BOB, { id: SEALED }), ME, ME, TOKEN, material);
check(note.failed === true, "Notes hold only my own messages");
check(identityFetches.length === fetchesBeforeStranger, "no key is fetched for a sender outside the chat");

listed = [wire(STRANGER, { id: PLANTED, created_at: "2026-10-03T12:00:00.000Z" })];
await hydratePreviews(TOKEN, ME, [{ id: BOB, lastMessageAt: "2026-10-03T12:00:00.000Z" }], material);
listed = null;
const preview = loadPreview(ME, BOB);
check(preview?.id === PLANTED && preview.failed === true, "the chat list does not show a stranger's body");
check(preview?.text !== "planted by the server", "the planted body stays out of the preview");
check(identityFetches.length === fetchesBeforeStranger, "the chat list fetches no stranger's key");

const serverBlob = "11111111-1111-4111-8111-111111111111";
const uploadBlob = "22222222-2222-4222-8222-222222222222";
const attempt = JSON.stringify({ t: "image", k: "this-attempt", mime: "image/jpeg", w: 1, h: 1 });
const keptJson = JSON.stringify({ t: "image", k: "kept-key", mime: "image/jpeg", w: 1, h: 1 });
const row = wire(ME, { content_type: "media", media_object_id: serverBlob, ciphertext: btoa("server-envelope") });

let opened = "";
const replay = await keptMediaSend({
  dto: row,
  uploadedMediaObjectId: uploadBlob,
  thisAttemptJson: attempt,
  openKept: async (ciphertext) => {
    opened = ciphertext;
    return keptJson;
  },
});
check(opened === btoa("server-envelope"), "the kept row's envelope is what gets opened");
check(replay.mediaObjectId === serverBlob, "the local message points at the server's blob");
check(replay.payloadJson === keptJson, "the cache stores the kept payload");
if (replay.payloadJson != null) savePlaintext(row.id, row.sender_user_id, replay.payloadJson);
const stored = loadPlaintext(row.id, ME);
check(stored === keptJson, "reading it back is the kept row");
check(stored !== attempt, "this attempt's key is not what a reload will use");

const failed = await keptMediaSend({
  dto: row,
  uploadedMediaObjectId: uploadBlob,
  thisAttemptJson: attempt,
  openKept: async () => {
    throw new Error("self box unavailable");
  },
});
check(failed.mediaObjectId === serverBlob, "a failed open still points at the server blob");
check(failed.payloadJson === null, "a failed open caches nothing");

const same = await keptMediaSend({
  dto: wire(ME, { content_type: "media", media_object_id: uploadBlob.toUpperCase() }),
  uploadedMediaObjectId: uploadBlob,
  thisAttemptJson: attempt,
  openKept: async () => {
    throw new Error("a matching blob is this attempt");
  },
});
check(same.mediaObjectId.toLowerCase() === uploadBlob, "a matching blob id is this upload");
check(same.payloadJson === attempt, "a matching blob keeps this attempt's key");

let aborted = false;
try {
  await keptMediaSend({
    dto: row,
    uploadedMediaObjectId: uploadBlob,
    thisAttemptJson: attempt,
    openKept: async () => {
      const err = new Error("aborted");
      err.name = "AbortError";
      throw err;
    },
  });
} catch (err) {
  aborted = err instanceof Error && err.name === "AbortError";
}
check(aborted, "cancellation is not swallowed");

/*
 * Security audit M7, the `self` box: a body this browser holds names its recipient (`to`). The
 * server re-files my message to Alice under my chat with Bob, or under Notes; it must not show
 * there, from the cache or otherwise. A body without `to` (older builds) still shows.
 */
const { textPayload } = await import("./reply");
const ALICE = "a11ce000-0000-4000-8000-000000000001";
const TO_ALICE = "78787878-7878-4787-8787-787878787878";
const TO_ME = "89898989-8989-4898-8989-898989898989";
const NOTE = "9a9a9a9a-9a9a-49a9-89a9-9a9a9a9a9a9a";
const MEDIA_TO_ALICE = "abababab-abab-4bab-8bab-abababababab";
const ANNOTATION_TO_ALICE = "bcbcbcbc-bcbc-4cbc-8cbc-bcbcbcbcbcbc";
savePlaintext(TO_ALICE, ME, textPayload("only for Alice", null, null, ALICE));
savePlaintext(TO_ME, BOB, textPayload("for you", null, null, ME));
savePlaintext(NOTE, ME, textPayload("a note", null, null, ME));
savePlaintext(
  MEDIA_TO_ALICE,
  ME,
  JSON.stringify({ t: "image", mime: "image/jpeg", w: 1, h: 1, k: "a2V5", c: "for Alice", to: ALICE }),
);
savePlaintext(
  ANNOTATION_TO_ALICE,
  ME,
  JSON.stringify({ t: "transcript", r: ID, c: "Alice's transcript", to: ALICE }),
);

const refiled = await decodeIncoming(wire(ME, { id: TO_ALICE }), ME, BOB, TOKEN, material);
check(refiled.failed === true && refiled.text === "[Unable to decrypt]", "my message to Alice does not show in Bob's chat");
check(refiled.isMine && refiled.senderUserId === ME, "the refused row is still mine");
const inNotes = await decodeIncoming(wire(ME, { id: TO_ALICE }), ME, ME, TOKEN, material);
check(inNotes.failed === true, "my message to Alice does not show in Notes");
const atHome = await decodeIncoming(wire(ME, { id: TO_ALICE }), ME, ALICE, TOKEN, material);
check(atHome.failed === false && atHome.text === "only for Alice", "it still shows in Alice's chat");

const fromBob = await decodeIncoming(wire(BOB, { id: TO_ME }), ME, BOB, TOKEN, material);
check(fromBob.failed === false && fromBob.text === "for you", "Bob's message naming me opens");
const bobsAsMine = await decodeIncoming(wire(ME, { id: TO_ME }), ME, BOB, TOKEN, material);
check(bobsAsMine.failed === true, "a body is never reused for another sender");
const note2 = await decodeIncoming(wire(ME, { id: NOTE }), ME, ME, TOKEN, material);
check(note2.failed === false && note2.text === "a note", "a note naming me opens in Notes");
const noteInBob = await decodeIncoming(wire(ME, { id: NOTE }), ME, BOB, TOKEN, material);
check(noteInBob.failed === true, "a note does not show in Bob's chat");

const mediaRefiled = await decodeIncoming(
  wire(ME, { id: MEDIA_TO_ALICE, content_type: "media" }),
  ME,
  BOB,
  TOKEN,
  material,
);
check(mediaRefiled.failed === true && mediaRefiled.text === "Media", "a photo for Alice does not show in Bob's chat");
check(mediaRefiled.caption == null && mediaRefiled.mediaKey == null, "nothing of the photo's payload is shown");
const annotationRefiled = await decodeIncoming(
  wire(ME, { id: ANNOTATION_TO_ALICE, content_type: "annotation" }),
  ME,
  BOB,
  TOKEN,
  material,
);
check(annotationRefiled.kind === "annotation" && annotationRefiled.annotation === null, "Alice's transcript is not read");

// Legacy: a body cached without `to` shows in whichever chat lists it, as before.
const LEGACY = "cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd";
savePlaintext(LEGACY, ME, "sent by an older build");
const legacyMine = await decodeIncoming(wire(ME, { id: LEGACY }), ME, BOB, TOKEN, material);
check(legacyMine.failed === false && legacyMine.text === "sent by an older build", "a body without `to` still opens");

listed = [wire(ME, { id: TO_ALICE, created_at: "2026-10-04T12:00:00.000Z" })];
await hydratePreviews(TOKEN, ME, [{ id: BOB, lastMessageAt: "2026-10-04T12:00:00.000Z" }], material);
listed = null;
const refiledPreview = loadPreview(ME, BOB);
check(refiledPreview?.id === TO_ALICE && refiledPreview.failed === true, "the chat list does not show it either");
check(refiledPreview?.text !== "only for Alice", "the body stays out of Bob's chat row");
check(loadPlaintext(TO_ALICE, ME) !== null, "Alice's chat keeps its body");

console.log("sender selftest ok");

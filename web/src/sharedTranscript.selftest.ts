/**
 * A shared transcript names its voice note only by id, so it attaches to that note only in the
 * chat it was shared in (security audit W2). Either person in that chat may share it.
 * Run: `npm test -- sharedTranscript`.
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
  if (!condition) throw new Error(`shared transcript selftest: ${what}`);
}

const ME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const ALICE = "a11ce000-0000-4000-8000-000000000001";
const BOB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const TOKEN = "session-token-for-the-selftest";
/** What `GET /messages` lists; every other path answers 404. */
let listed: WireMessage[] = [];

globalThis.fetch = (async (input: RequestInfo | URL) => {
  const url = new URL(String(input), "https://shroud.test");
  if (url.pathname.endsWith("/messages")) {
    return new Response(JSON.stringify({ conversation_id: null, messages: listed, has_more: false }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }
  return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "none" } }), {
    status: 404,
    headers: { "Content-Type": "application/json" },
  });
}) as typeof fetch;

const { createVault, derivePinSecrets } = await import("./crypto/vault");
const { savePlaintext } = await import("./crypto/plaintextCache");
const { applyAnnotations, decodeIncoming, loadHistoryPage } = await import("./messaging");

const secrets = await derivePinSecrets("123456", new Uint8Array(16), 1);
createVault(ME, { secrets, pepper: new Uint8Array(32).fill(7), guardId: "guard" }, new Uint8Array(32).fill(9));
const material = {} as IdentityMaterial;

let clientIds = 0;
function wire(id: string, from: string, contentType: string, createdAt: string): WireMessage {
  clientIds += 1;
  return {
    id,
    conversation_id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    sender_user_id: from,
    sender_device_id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    client_message_id: `ffffffff-ffff-4fff-8fff-${String(clientIds).padStart(12, "0")}`,
    content_type: contentType,
    ciphertext: btoa("not-an-envelope"),
    deleted_for_everyone: false,
    created_at: createdAt,
  };
}

/* Bodies this browser already opened, so the decode reads them from the cache. */
function voiceNote(id: string, from: string, to: string): WireMessage {
  savePlaintext(id, from, JSON.stringify({ t: "voice", mime: "audio/mp4", w: 0, h: 0, k: "a2V5", d: 3000, to }));
  return wire(id, from, "media", "2026-11-01T10:00:00.000Z");
}
function transcript(id: string, from: string, to: string, note: string, text: string): WireMessage {
  savePlaintext(id, from, JSON.stringify({ t: "transcript", r: note, c: text, to }));
  return wire(id, from, "annotation", "2026-11-01T10:05:00.000Z");
}

async function loadChat(peer: string, messages: WireMessage[]) {
  listed = messages;
  const page = await loadHistoryPage(TOKEN, ME, peer, material);
  listed = [];
  return page.messages;
}

/*
 * Alice shares a transcript in her chat whose `r` is the id of Bob's note in my chat with Bob.
 * Bob's note still reads "Voice message" there, however it arrives.
 */
const BOBS_NOTE = "b0b00000-0000-4000-8000-000000000001";
const FROM_ALICE = "a11ce000-0000-4000-8000-0000000000a1";
const planted = await decodeIncoming(
  transcript(FROM_ALICE, ALICE, ME, BOBS_NOTE, "Alice's words"),
  ME,
  ALICE,
  TOKEN,
  material,
);
check(planted.annotation?.r === BOBS_NOTE, "the annotation decodes in Alice's chat");

const bobsChat = await loadChat(BOB, [voiceNote(BOBS_NOTE, BOB, ME)]);
const bobsNote = bobsChat.find((m) => m.id === BOBS_NOTE);
check(bobsNote?.kind === "voice", "Bob's note loads");
check(bobsNote?.text === "Voice message", "Alice's transcript does not attach to Bob's note");
check(bobsNote?.transcript == null, "Bob's note has no transcript");
check(
  applyAnnotations(bobsChat, BOB).find((m) => m.id === BOBS_NOTE)?.transcript == null,
  "folding Bob's thread again does not take it either",
);
// A note with the same id in Alice's chat would take it: the binding is to the chat, not a ban.
const sameIdInAlice = applyAnnotations([{ ...bobsNote!, senderUserId: ALICE }], ALICE)[0];
check(sameIdInAlice.transcript === "Alice's words", "the transcript stays usable in the chat it was shared in");

/* Bob shares the transcript of his own note: it attaches, Alice's earlier one does not win. */
const FROM_BOB = "b0b00000-0000-4000-8000-0000000000b1";
const withBobs = await loadChat(BOB, [
  voiceNote(BOBS_NOTE, BOB, ME),
  transcript(FROM_BOB, BOB, ME, BOBS_NOTE, "Bob's words"),
]);
const shared = withBobs.find((m) => m.id === BOBS_NOTE);
check(shared?.transcript === "Bob's words" && shared.text === "Bob's words", "Bob's transcript of his note attaches");
check(!withBobs.some((m) => m.kind === "annotation"), "the annotation folds away");

/* A note that lands after its annotation still takes the one shared in its chat. */
const LATE_NOTE = "b0b00000-0000-4000-8000-000000000002";
await decodeIncoming(transcript("b0b00000-0000-4000-8000-0000000000b2", BOB, ME, LATE_NOTE, "Later"), ME, BOB, TOKEN, material);
const late = await decodeIncoming(voiceNote(LATE_NOTE, BOB, ME), ME, BOB, TOKEN, material);
check(late.transcript === "Later", "a note decoded after its annotation takes it");

/*
 * A transcript of a note I received, shared by my other device (iOS and Android do this): mine,
 * about Bob's note, in Bob's chat. It attaches.
 */
const RECEIVED = "b0b00000-0000-4000-8000-000000000003";
const mine = await loadChat(BOB, [
  voiceNote(RECEIVED, BOB, ME),
  transcript("aaaaaaaa-0000-4000-8000-0000000000a3", ME, BOB, RECEIVED, "What Bob said"),
]);
check(mine.find((m) => m.id === RECEIVED)?.transcript === "What Bob said", "my transcript of Bob's note attaches");
/* And Bob's transcript of a note I sent him. */
const SENT = "aaaaaaaa-0000-4000-8000-000000000004";
const bobsOfMine = await loadChat(BOB, [
  voiceNote(SENT, ME, BOB),
  transcript("b0b00000-0000-4000-8000-0000000000b4", BOB, ME, SENT, "What I said"),
]);
check(bobsOfMine.find((m) => m.id === SENT)?.transcript === "What I said", "Bob's transcript of my note attaches");

console.log("shared transcript selftest ok");

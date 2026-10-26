/**
 * Security audit M7, the `self` box: envelopes opened for real (no cached body) are checked
 * against the recipient sealed inside them. My message to Alice listed in my chat with Bob, and a
 * message of Bob's that names someone else, fail like an envelope that does not open: nothing is
 * cached and nothing is acked. Envelopes without `to` (older builds) open as before.
 * Run: `npx tsx src/messaging.recipient.selftest.ts`.
 */
import { x25519 } from "@noble/curves/ed25519.js";
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
  if (!condition) throw new Error(`recipient selftest: ${what}`);
}

const ME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const BOB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const ALICE = "a11ce000-0000-4000-8000-000000000001";
const TOKEN = "session-token-for-the-selftest";

function keys(seed: number): { priv: Uint8Array; pub: Uint8Array } {
  const priv = new Uint8Array(32).fill(seed);
  return { priv, pub: x25519.getPublicKey(priv) };
}
const me = keys(1);
const bob = keys(2);
const alice = keys(3);

const acks: string[] = [];
globalThis.fetch = (async (input: RequestInfo | URL) => {
  const url = new URL(String(input), "https://shroud.test");
  const delivered = /^\/api\/v1\/messages\/([^/]+)\/delivered$/.exec(url.pathname);
  if (delivered) {
    acks.push(decodeURIComponent(delivered[1]));
    return new Response(null, { status: 204 });
  }
  if (url.pathname === `/api/v1/keys/identity/${BOB}`) {
    const { bytesToB64 } = await import("./crypto/bytes");
    return new Response(
      JSON.stringify({ user_id: BOB, device_id: BOB, registration_id: 1, identity_key: bytesToB64(bob.pub) }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  }
  return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: url.pathname } }), {
    status: 404,
    headers: { "Content-Type": "application/json" },
  });
}) as typeof fetch;

const { createVault, derivePinSecrets } = await import("./crypto/vault");
const { bytesToB64, utf8 } = await import("./crypto/bytes");
const { sealMessage } = await import("./crypto/messageCrypto");
const { loadPlaintext } = await import("./crypto/plaintextCache");
const { decodeIncoming, deliveryAcksSettled, ingestIncoming } = await import("./messaging");
const { textPayload } = await import("./reply");

const secrets = await derivePinSecrets("123456", new Uint8Array(16), 1);
createVault(ME, { secrets, pepper: new Uint8Array(32).fill(7), guardId: "guard" }, new Uint8Array(32).fill(9));
const material = { agreementPrivate: me.priv, agreementPublic: me.pub } as IdentityMaterial;

/** An envelope `from` sealed for `to`, as `from`'s client would (v2: no ratchet state needed). */
async function sealed(
  plaintext: string,
  from: { id: string; priv: Uint8Array; pub: Uint8Array },
  to: { id: string; pub: Uint8Array },
): Promise<string> {
  const envelope = await sealMessage({
    plaintext: utf8(plaintext),
    peerUserId: to.id,
    ourUserId: from.id,
    ourPrivate: from.priv,
    ourIdentityPublic: from.pub,
    peerIdentityPublic: to.pub,
  });
  return bytesToB64(envelope);
}

let n = 0;
function wire(from: string, ciphertext: string, extra: Partial<WireMessage> = {}): WireMessage {
  n += 1;
  return {
    id: `0000000${n}-0000-4000-8000-000000000000`,
    conversation_id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    sender_user_id: from,
    sender_device_id: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    client_message_id: crypto.randomUUID(),
    content_type: "text",
    ciphertext,
    deleted_for_everyone: false,
    created_at: "2026-10-05T12:00:00.000Z",
    ...extra,
  };
}

const MINE = { id: ME, ...me };

/* My message to Alice, opened from my self box in the chat the server names. */
const toAlice = wire(ME, await sealed(textPayload("only for Alice", null, null, ALICE), MINE, { id: ALICE, pub: alice.pub }));
const inBob = await decodeIncoming(toAlice, ME, BOB, TOKEN, material);
check(inBob.failed === true && inBob.text === "[Unable to decrypt]", "my message to Alice does not open in Bob's chat");
check(loadPlaintext(toAlice.id, ME) === null, "its body is not cached");
const inNotes = await decodeIncoming(toAlice, ME, ME, TOKEN, material);
check(inNotes.failed === true, "nor in Notes");
const inAlice = await decodeIncoming(toAlice, ME, ALICE, TOKEN, material);
check(inAlice.failed === false && inAlice.text === "only for Alice", "it opens in Alice's chat");
check(loadPlaintext(toAlice.id, ME) !== null, "and is cached there");

const photo = JSON.stringify({ t: "image", mime: "image/jpeg", w: 2, h: 2, k: "a2V5", c: "for Alice", to: ALICE });
const photoToAlice = wire(ME, await sealed(photo, MINE, { id: ALICE, pub: alice.pub }), { content_type: "media" });
const photoInBob = await decodeIncoming(photoToAlice, ME, BOB, TOKEN, material);
check(photoInBob.failed === true && photoInBob.text === "Media" && photoInBob.mediaKey == null, "nor does a photo");

/* A note names me; it opens in Notes only. */
const note = wire(ME, await sealed(textPayload("a note", null, null, ME), MINE, { id: ME, pub: me.pub }));
check((await decodeIncoming(note, ME, BOB, TOKEN, material)).failed === true, "a note does not open in Bob's chat");
const noteHome = await decodeIncoming(note, ME, ME, TOKEN, material);
check(noteHome.failed === false && noteHome.text === "a note", "a note opens in Notes");

/* Bob's messages, over the socket: one naming someone else is neither shown, cached nor acked. */
const BOBS = { id: BOB, ...bob };
const ME_PEER = { id: ME, pub: me.pub };
const misaddressed = wire(BOB, await sealed(textPayload("meant for Alice", null, null, ALICE), BOBS, ME_PEER));
const forMe = wire(BOB, await sealed(textPayload("for you", null, null, ME), BOBS, ME_PEER));
const legacy = wire(BOB, await sealed("from an older build", BOBS, ME_PEER));

const refused = await ingestIncoming(misaddressed, ME, BOB, TOKEN, material);
check(refused.failed === true && refused.text === "[Unable to decrypt]", "Bob's message naming Alice does not open");
check(loadPlaintext(misaddressed.id, BOB) === null, "its body is not cached");
const opened = await ingestIncoming(forMe, ME, BOB, TOKEN, material);
check(opened.failed === false && opened.text === "for you", "Bob's message naming me opens");
const old = await ingestIncoming(legacy, ME, BOB, TOKEN, material);
check(old.failed === false && old.text === "from an older build", "a message without `to` opens as before");
await deliveryAcksSettled();
check(!acks.includes(misaddressed.id.toLowerCase()), "the misaddressed message is not acked");
check(acks.includes(forMe.id.toLowerCase()) && acks.includes(legacy.id.toLowerCase()), "the others are acked");

console.log("recipient selftest ok");

/**
 * A message id is not a capability. Cached plaintext is reused only for the same sender,
 * and an idempotent media replay remembers the row the server kept.
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

globalThis.fetch = (async (input: RequestInfo | URL) => {
  const url = new URL(String(input), "https://shroud.test");
  identityFetches.push(url.pathname);
  return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "no key" } }), {
    status: 404,
    headers: { "Content-Type": "application/json" },
  });
}) as typeof fetch;

const { createVault, derivePinSecrets } = await import("./crypto/vault");
const { loadPlaintext, savePlaintext } = await import("./crypto/plaintextCache");
const { decodeIncoming, keptMediaSend } = await import("./messaging");

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

console.log("sender selftest ok");

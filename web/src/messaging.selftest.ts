/**
 * Delivery acks (`POST /messages/{id}/delivered`, iOS `MessagingController.swift:1340-1344,
 * 1447-1451, 4318-4320`): the peer's messages are acked the first time this browser sees them —
 * over the socket, in the open chat's poll and in history pages — once each, one at a time,
 * without holding up the page, and never for our own messages, Notes, or messages deleted for
 * everyone. A failed ack is dropped.
 * Run: `npx tsx src/messaging.selftest.ts`.
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
  if (!condition) throw new Error(`messaging selftest: ${what}`);
}

const ME = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
const PEER = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
const TOKEN = "session-token-for-the-selftest";
const CONVERSATION = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";

let clock = Date.parse("2026-10-01T10:00:00Z");
/** One wire message; server ids are upper-case here so the acks' lower-casing shows. */
function wire(id: string, from: string, extra: Partial<WireMessage> = {}): WireMessage {
  clock += 1000;
  return {
    id,
    conversation_id: CONVERSATION,
    sender_user_id: from,
    sender_device_id: "0f8fad5b-d9cb-469f-a165-70867728950e",
    client_message_id: crypto.randomUUID(),
    content_type: "text",
    // No body: decoding then needs no keys or network ("[Unable to decrypt]").
    ciphertext: null,
    deleted_for_everyone: false,
    created_at: new Date(clock).toISOString(),
    ...extra,
  };
}

/* The server: GET /messages answers with `page` (newest first); acks are recorded. */
let page: WireMessage[] = [];
const acks: { id: string; auth: string | null; method: string }[] = [];
let ackInFlight = 0;
let ackOverlap = false;
let holdAcks: Promise<void> | null = null;
const failingAcks = new Map<string, "404" | "offline">();

function reply(status: number, body: unknown): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(String(input), "https://shroud.test");
  const delivered = /^\/api\/v1\/messages\/([^/]+)\/delivered$/.exec(url.pathname);
  if (delivered) {
    const id = decodeURIComponent(delivered[1]);
    ackInFlight++;
    if (ackInFlight > 1) ackOverlap = true;
    try {
      if (holdAcks) await holdAcks;
      await new Promise((resolve) => setTimeout(resolve, 1));
      acks.push({ id, auth: new Headers(init?.headers).get("Authorization"), method: init?.method ?? "GET" });
      const failure = failingAcks.get(id);
      if (failure === "offline") throw new TypeError("Failed to fetch");
      if (failure === "404") return reply(404, { error: { code: "NOT_FOUND", message: "Message delivery not found." } });
      return reply(204, null);
    } finally {
      ackInFlight--;
    }
  }
  if (url.pathname === "/api/v1/messages") {
    check(url.searchParams.get("peer_user_id") === PEER, "history is read for the peer");
    return reply(200, { conversation_id: CONVERSATION, messages: page, has_more: false, reaction_seq: 0 });
  }
  return reply(404, { error: { code: "NOT_FOUND", message: url.pathname } });
}) as typeof fetch;

const { createVault, derivePinSecrets } = await import("./crypto/vault");
const { savePlaintext } = await import("./crypto/plaintextCache");
const { deliveryAcksSettled, fetchLatest, ingestIncoming, loadHistoryPage } = await import("./messaging");

// An open vault, so "this browser has the body already" (the plaintext cache) means something.
const secrets = await derivePinSecrets("123456", new Uint8Array(16), 1);
createVault(ME, { secrets, pepper: new Uint8Array(32).fill(7), guardId: "guard" }, new Uint8Array(32).fill(9));
const material = {} as IdentityMaterial; // never used: no message here has a body to open

const ackedIds = () => acks.map((a) => a.id);

/* History page: only the peer's messages this browser has not seen, oldest first. */
const oldNew = wire("A1A1A1A1-0000-4000-8000-000000000001", PEER);
const cached = wire("A1A1A1A1-0000-4000-8000-000000000002", PEER);
const mine = wire("A1A1A1A1-0000-4000-8000-000000000003", ME);
const tombstone = wire("A1A1A1A1-0000-4000-8000-000000000004", PEER, { deleted_for_everyone: true });
const annotation = wire("A1A1A1A1-0000-4000-8000-000000000005", PEER, { content_type: "annotation" });
const newest = wire("A1A1A1A1-0000-4000-8000-000000000006", PEER, { content_type: "media" });
savePlaintext(cached.id, "seen before");
page = [newest, annotation, tombstone, mine, cached, oldNew];

// Acks wait for a slow server; the page must not.
let release!: () => void;
holdAcks = new Promise<void>((resolve) => {
  release = resolve;
});
const first = await loadHistoryPage(TOKEN, ME, PEER, material);
check(first.messages.length === 5, "the page decodes without waiting for its acks");
check(acks.length === 0, "nothing acked before the server answers");
release();
holdAcks = null;
await deliveryAcksSettled();
check(
  JSON.stringify(ackedIds()) ===
    JSON.stringify([oldNew.id, annotation.id, newest.id].map((id) => id.toLowerCase())),
  `history acks: ${ackedIds().join(", ")}`,
);
check(acks.every((a) => a.method === "POST" && a.auth === `Bearer ${TOKEN}`), "POST with the session token");
check(!ackOverlap, "one ack at a time");

/* The same page again (scrolling back, a reload of the chat): nothing is acked twice. */
await loadHistoryPage(TOKEN, ME, PEER, material);
await deliveryAcksSettled();
check(acks.length === 3, "a page loaded again acks nothing new");

/* The open chat's poll: new ids only. */
const polled = wire("B2B2B2B2-0000-4000-8000-000000000001", PEER);
const known = wire("B2B2B2B2-0000-4000-8000-000000000002", PEER);
const mineFromOtherDevice = wire("B2B2B2B2-0000-4000-8000-000000000003", ME);
page = [mineFromOtherDevice, known, polled, ...page];
const latest = await fetchLatest(TOKEN, ME, PEER, material, new Set([known.id, ...first.messages.map((m) => m.id)]));
await deliveryAcksSettled();
check(latest.messages.some((m) => m.id === polled.id), "the poll returns the new message");
check(ackedIds().length === 4 && ackedIds()[3] === polled.id.toLowerCase(), `poll acks: ${ackedIds().join(", ")}`);

/* The socket: acked once, before decoding; never ours. */
const live = wire("C3C3C3C3-0000-4000-8000-000000000001", PEER);
await ingestIncoming(live, ME, PEER, TOKEN, material);
await ingestIncoming(live, ME, PEER, TOKEN, material); // the same event twice
await ingestIncoming(wire("C3C3C3C3-0000-4000-8000-000000000002", ME), ME, PEER, TOKEN, material);
await deliveryAcksSettled();
check(ackedIds().length === 5 && ackedIds()[4] === live.id.toLowerCase(), `socket acks: ${ackedIds().join(", ")}`);

/* Notes (our own chat) never acks, whoever the message claims to be from. */
await ingestIncoming(wire("D4D4D4D4-0000-4000-8000-000000000001", PEER), ME, ME, TOKEN, material);
await deliveryAcksSettled();
check(acks.length === 5, "Notes sends no ack");

/* A failed ack is dropped and the queue keeps going. */
const lost = wire("E5E5E5E5-0000-4000-8000-000000000001", PEER);
const gone = wire("E5E5E5E5-0000-4000-8000-000000000002", PEER);
const after = wire("E5E5E5E5-0000-4000-8000-000000000003", PEER);
failingAcks.set(lost.id.toLowerCase(), "offline");
failingAcks.set(gone.id.toLowerCase(), "404");
for (const dto of [lost, gone, after]) await ingestIncoming(dto, ME, PEER, TOKEN, material);
await deliveryAcksSettled();
check(ackedIds().slice(5).join(",") === [lost, gone, after].map((d) => d.id.toLowerCase()).join(","), "every ack was tried");
await ingestIncoming(lost, ME, PEER, TOKEN, material);
await deliveryAcksSettled();
check(acks.length === 8, "a failed ack is not retried");
check(!ackOverlap, "still one ack at a time");

console.log("messaging selftest ok");

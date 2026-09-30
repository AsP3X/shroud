/**
 * Sender tags on identity boxes: a box built from public keys alone (what the server can do)
 * must not open as the peer once the peer is known to tag. Run: `npx tsx src/crypto/boxAuth.selftest.ts`.
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { aesGcmSeal } from "./aes";
import { bytesToB64, concatBytes, randomBytes, utf8, utf8decode } from "./bytes";

try {
  void globalThis.localStorage;
} catch {
  const map = new Map<string, string>();
  const storage: Storage = {
    get length() {
      return map.size;
    },
    clear() {
      map.clear();
    },
    getItem(key) {
      return map.get(key) ?? null;
    },
    key(index) {
      return [...map.keys()][index] ?? null;
    },
    removeItem(key) {
      map.delete(key);
    },
    setItem(key, value) {
      map.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, "localStorage", { value: storage, configurable: true });
}

const { sealBox, openBox } = await import("./sealedBox");
const { openMessage, sealMessage } = await import("./messageCrypto");
const { createVault, derivePinSecrets } = await import("./vault");

const alice = randomBytes(32);
const bob = randomBytes(32);
const mallory = randomBytes(32);
const aPub = x25519.getPublicKey(alice);
const bPub = x25519.getPublicKey(bob);
const aliceId = "00000000-0000-4000-8000-00000000000a";
const bobId = "00000000-0000-4000-8000-00000000000b";
const T = Date.parse("2026-09-01T12:00:00Z");

/** An untagged box as builds before the tag sealed it — and as anyone with the public keys can. */
async function forgedBox(plaintext: string, senderPub: Uint8Array, recipientPub: Uint8Array) {
  const eph = randomBytes(32);
  const ek = x25519.getPublicKey(eph);
  const info = concatBytes(utf8("shroud-msg-v1"), ek, senderPub, recipientPub);
  const key = hkdf(sha256, x25519.getSharedSecret(eph, recipientPub), utf8("shroud-v1"), info, 32);
  return { ek: bytesToB64(ek), ct: bytesToB64(await aesGcmSeal(key, utf8(plaintext))) };
}

function envelope(value: unknown): Uint8Array {
  return utf8(JSON.stringify(value));
}

function freshVault(): Promise<void> {
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const key = localStorage.key(i);
    if (key?.startsWith("shroud.")) localStorage.removeItem(key);
  }
  return derivePinSecrets("123456").then((secrets) =>
    createVault(aliceId, { secrets, pepper: randomBytes(32), guardId: "test" }, randomBytes(32)),
  );
}

const bobOpens = (data: Uint8Array, sentAt: number) =>
  openMessage({
    envelopeData: data,
    peerUserId: aliceId,
    ourUserId: bobId,
    ourPrivate: bob,
    ourIdentityPublic: bPub,
    senderIdentityPublic: aPub,
    asSender: false,
    sentAt,
  }).then(utf8decode);

const aliceOpensOwn = (data: Uint8Array, sentAt: number) =>
  openMessage({
    envelopeData: data,
    peerUserId: bobId,
    ourUserId: aliceId,
    ourPrivate: alice,
    ourIdentityPublic: aPub,
    senderIdentityPublic: aPub,
    asSender: true,
    sentAt,
  }).then(utf8decode);

async function rejects(label: string, run: () => Promise<unknown>): Promise<void> {
  try {
    await run();
  } catch {
    return;
  }
  throw new Error(`accepted: ${label}`);
}

const aliceSeals = (text: string) =>
  sealMessage({
    plaintext: utf8(text),
    peerUserId: bobId,
    ourUserId: aliceId,
    ourPrivate: alice,
    ourIdentityPublic: aPub,
    peerIdentityPublic: bPub,
  });

// Box level: a tag binds the box to the sender's identity and to its bytes.
{
  const box = await sealBox(utf8("hi"), alice, aPub, bPub);
  const opened = await openBox(box, bob, aPub, bPub);
  if (!opened.authenticated || utf8decode(opened.plaintext) !== "hi") throw new Error("tagged box");
  const untagged = await openBox({ ek: box.ek, ct: box.ct }, bob, aPub, bPub);
  if (untagged.authenticated) throw new Error("untagged box reported authenticated");
  // Mallory tags with her own identity: the static ECDH does not match Alice's.
  const byMallory = await sealBox(utf8("hi"), mallory, aPub, bPub);
  await rejects("tag from a key that is not the sender's", () => openBox(byMallory, bob, aPub, bPub));
  const other = await sealBox(utf8("other"), alice, aPub, bPub);
  await rejects("tag moved onto other ciphertext", () =>
    openBox({ ek: other.ek, ct: other.ct, t: box.t }, bob, aPub, bPub),
  );
  // Bob's box to Alice, handed back to Bob as if Alice sent it.
  const bobToAlice = await sealBox(utf8("mine"), bob, bPub, aPub);
  await rejects("reflected box", () => openBox(bobToAlice, bob, aPub, bPub));
}

// The reported issue: with nothing tagged seen yet, a forged v2 opens as Alice (transition).
await freshVault();
{
  const forged = envelope({ v: 2, peer: await forgedBox("forged", aPub, bPub) });
  if ((await bobOpens(forged, T)) !== "forged") throw new Error("legacy v2 no longer opens");
}

// Once Bob has a tagged message from Alice, untagged boxes after it are refused.
await freshVault();
{
  const real = await aliceSeals("real");
  const parsed = JSON.parse(utf8decode(real)) as { v: number; peer: { t?: string }; self: { t?: string } };
  if (parsed.v !== 3 || !parsed.peer.t || !parsed.self.t) throw new Error("v3 boxes are not tagged");
  // Bob reads this by ratchet; the peer box tag alone must set the watermark.
  if ((await bobOpens(real, T)) !== "real") throw new Error("bob open real");

  const forgedV2 = envelope({ v: 2, peer: await forgedBox("forged v2", aPub, bPub) });
  await rejects("forged v2 after the watermark", () => bobOpens(forgedV2, T + 1000));
  const forgedV1 = { v: 1, ...(await forgedBox("forged v1", aPub, bPub)) };
  await rejects("forged v1 after the watermark", () => bobOpens(envelope(forgedV1), T + 1000));
  // Junk ratchet body so the peer-box fallback runs.
  const junkV3 = (peer: unknown) =>
    envelope({ v: 3, dh: bytesToB64(randomBytes(32)), n: 0, pn: 0, ct: bytesToB64(randomBytes(40)), peer });
  await rejects("forged v3 fallback", async () =>
    bobOpens(junkV3(await forgedBox("forged v3", aPub, bPub)), T + 1000),
  );
  await rejects("v3 fallback tagged by mallory", async () =>
    bobOpens(junkV3(await sealBox(utf8("mallory"), mallory, aPub, bPub)), T + 1000),
  );
  await rejects("untagged box with no readable time", () => bobOpens(forgedV2, NaN));

  // Pre-upgrade history (older than the watermark) still reads on a fresh device.
  const oldV2 = envelope({ v: 2, peer: await forgedBox("old", aPub, bPub) });
  if ((await bobOpens(oldV2, T - 1000)) !== "old") throw new Error("pre-watermark history refused");

  // A tagged v2 from Alice still opens, and an older tagged one moves the watermark back.
  const taggedV2 = envelope({ v: 2, peer: await sealBox(utf8("tagged v2"), alice, aPub, bPub) });
  if ((await bobOpens(taggedV2, T - 5000)) !== "tagged v2") throw new Error("tagged v2");
  await rejects("untagged between the old and the new watermark", () => bobOpens(oldV2, T - 1000));
}

// A sibling device with no ratchet state reads through the tagged peer box.
await freshVault();
{
  const real = await aliceSeals("to sibling");
  const parsed = JSON.parse(utf8decode(real)) as Record<string, unknown>;
  const drBroken = envelope({ ...parsed, ct: bytesToB64(randomBytes(40)) });
  if ((await bobOpens(drBroken, T)) !== "to sibling") throw new Error("sibling peer-box read");
  const forged = envelope({ v: 2, peer: await forgedBox("forged", aPub, bPub) });
  await rejects("forged v2 after a peer-box read", () => bobOpens(forged, T + 1));
}

// Self boxes: "sent by me" can no longer be forged either.
await freshVault();
{
  const real = await aliceSeals("own");
  if ((await aliceOpensOwn(real, T)) !== "own") throw new Error("alice open own");
  const forgedSelf = envelope({ v: 2, self: await forgedBox("fake own", aPub, aPub) });
  await rejects("forged self box", () => aliceOpensOwn(forgedSelf, T + 1));
}

console.log("box auth selftest ok");

/**
 * Sender tags on identity boxes: a box built from public keys alone (what the server can do)
 * never opens, whatever the envelope, while tagged boxes and ratchet bodies do.
 * Run: `npx tsx src/crypto/boxAuth.selftest.ts`.
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { aesGcmOpen, aesGcmSeal } from "./aes";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, utf8, utf8decode } from "./bytes";

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

/** An untagged box as builds before the tag sealed it — and as anyone with the public keys can. */
async function forgedBox(plaintext: string, senderPub: Uint8Array, recipientPub: Uint8Array) {
  const eph = randomBytes(32);
  const ek = x25519.getPublicKey(eph);
  const info = concatBytes(utf8("shroud-msg-v1"), ek, senderPub, recipientPub);
  const key = hkdf(sha256, x25519.getSharedSecret(eph, recipientPub), utf8("shroud-v1"), info, 32);
  return { ek: bytesToB64(ek), ct: bytesToB64(await aesGcmSeal(key, utf8(plaintext))) };
}

/** What the recipient's key alone makes of a forged box: the forgery is real ciphertext. */
async function openWithoutTag(
  box: { ek: string; ct: string },
  ourPrivate: Uint8Array,
  senderPub: Uint8Array,
  recipientPub: Uint8Array,
): Promise<string> {
  const ek = b64ToBytes(box.ek);
  const info = concatBytes(utf8("shroud-msg-v1"), ek, senderPub, recipientPub);
  const key = hkdf(sha256, x25519.getSharedSecret(ourPrivate, ek), utf8("shroud-v1"), info, 32);
  return utf8decode(await aesGcmOpen(key, b64ToBytes(box.ct)));
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

const bobOpens = (data: Uint8Array) =>
  openMessage({
    envelopeData: data,
    peerUserId: aliceId,
    ourUserId: bobId,
    ourPrivate: bob,
    ourIdentityPublic: bPub,
    senderIdentityPublic: aPub,
    asSender: false,
  }).then(utf8decode);

const aliceOpensOwn = (data: Uint8Array) =>
  openMessage({
    envelopeData: data,
    peerUserId: bobId,
    ourUserId: aliceId,
    ourPrivate: alice,
    ourIdentityPublic: aPub,
    senderIdentityPublic: aPub,
    asSender: true,
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
  if (!box.t) throw new Error("sealBox left out the tag");
  if (utf8decode(await openBox(box, bob, aPub, bPub)) !== "hi") throw new Error("tagged box");
  await rejects("untagged box", () => openBox({ ek: box.ek, ct: box.ct }, bob, aPub, bPub));
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

// Junk ratchet body, so a v3 envelope can only open through its peer box.
const junkV3 = (peer: unknown) =>
  envelope({ v: 3, dh: bytesToB64(randomBytes(32)), n: 0, pn: 0, ct: bytesToB64(randomBytes(40)), peer });

// Untagged boxes are refused on a fresh device, before anything tagged was ever seen.
await freshVault();
{
  const sample = await forgedBox("forged", aPub, bPub);
  if ((await openWithoutTag(sample, bob, aPub, bPub)) !== "forged") throw new Error("forged boxes don't decrypt");
  await rejects("untagged v1", async () => bobOpens(envelope({ v: 1, ...(await forgedBox("v1", aPub, bPub)) })));
  await rejects("untagged v2 peer box", async () =>
    bobOpens(envelope({ v: 2, peer: await forgedBox("v2", aPub, bPub) })),
  );
  await rejects("untagged v3 peer-box fallback", async () => bobOpens(junkV3(await forgedBox("v3", aPub, bPub))));
  await rejects("untagged v2 self box", async () =>
    aliceOpensOwn(envelope({ v: 2, self: await forgedBox("fake own", aPub, aPub) })),
  );
  await rejects("untagged v3 self box", async () => {
    const own = JSON.parse(utf8decode(await aliceSeals("own"))) as Record<string, unknown>;
    return aliceOpensOwn(envelope({ ...own, self: await forgedBox("fake own", aPub, aPub) }));
  });
}

// Tagged boxes open; a tag that doesn't verify does not.
await freshVault();
{
  const taggedV2 = envelope({ v: 2, peer: await sealBox(utf8("tagged v2"), alice, aPub, bPub) });
  if ((await bobOpens(taggedV2)) !== "tagged v2") throw new Error("tagged v2 peer box");
  const ownV2 = envelope({ v: 2, self: await sealBox(utf8("own v2"), alice, aPub, aPub) });
  if ((await aliceOpensOwn(ownV2)) !== "own v2") throw new Error("tagged v2 self box");
  if ((await bobOpens(junkV3(await sealBox(utf8("tagged v3"), alice, aPub, bPub)))) !== "tagged v3") {
    throw new Error("tagged v3 peer-box fallback");
  }
  await rejects("v3 fallback tagged by mallory", async () =>
    bobOpens(junkV3(await sealBox(utf8("mallory"), mallory, aPub, bPub))),
  );
  const real = await sealBox(utf8("real"), alice, aPub, bPub);
  const other = await sealBox(utf8("other"), alice, aPub, bPub);
  await rejects("v2 with a tag from another box", () =>
    bobOpens(envelope({ v: 2, peer: { ek: other.ek, ct: other.ct, t: real.t } })),
  );
}

// What this build sends: every box tagged, read by ratchet, by self box and by a sibling device.
await freshVault();
{
  const real = await aliceSeals("real");
  const parsed = JSON.parse(utf8decode(real)) as Record<string, unknown> & {
    v: number;
    peer: { ek: string; ct: string; t?: string };
    self: { t?: string };
  };
  if (parsed.v !== 3 || !parsed.peer.t || !parsed.self.t) throw new Error("v3 boxes are not tagged");
  if ((await aliceOpensOwn(real)) !== "real") throw new Error("alice open own");
  // A sibling device with no ratchet state reads through the tagged peer box.
  const drBroken = envelope({ ...parsed, ct: bytesToB64(randomBytes(40)) });
  if ((await bobOpens(drBroken)) !== "real") throw new Error("sibling peer-box read");
  // The ratchet body is authentic on its own: it opens even beside an untagged peer box.
  const untaggedPeer = envelope({ ...parsed, peer: { ek: parsed.peer.ek, ct: parsed.peer.ct } });
  if ((await bobOpens(untaggedPeer)) !== "real") throw new Error("ratchet read");
  const next = await aliceSeals("next");
  if ((await bobOpens(next)) !== "next") throw new Error("ratchet read of the next message");
}

console.log("box auth selftest ok");

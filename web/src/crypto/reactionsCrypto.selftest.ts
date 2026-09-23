/**
 * Reaction envelopes open only as tagged v2: an untagged box (what the server can build from
 * public keys alone), a v1 box, or a box from anyone but the claimed sender is refused.
 * Run bundled: `npx esbuild src/crypto/reactionsCrypto.selftest.ts --bundle --platform=node
 * --format=esm | node --input-type=module`.
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { randomBytes, utf8, utf8decode } from "./bytes";

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

const { openTaggedEnvelope, sealIdentityEnvelope } = await import("./messageCrypto");
const { createVault, derivePinSecrets } = await import("./vault");

const alice = randomBytes(32);
const bob = randomBytes(32);
const mallory = randomBytes(32);
const aPub = x25519.getPublicKey(alice);
const bPub = x25519.getPublicKey(bob);
const mPub = x25519.getPublicKey(mallory);

await derivePinSecrets("123456").then((secrets) =>
  createVault("00000000-0000-4000-8000-00000000000a", { secrets, pepper: randomBytes(32), guardId: "test" }, randomBytes(32)),
);

async function rejects(label: string, run: () => Promise<unknown>): Promise<void> {
  try {
    await run();
  } catch {
    return;
  }
  throw new Error(`accepted: ${label}`);
}

const bobOpens = (data: Uint8Array, claimedSender = aPub) =>
  openTaggedEnvelope({
    envelopeData: data,
    ourPrivate: bob,
    ourIdentityPublic: bPub,
    senderIdentityPublic: claimedSender,
    asSender: false,
  }).then(utf8decode);

const sealed = await sealIdentityEnvelope(utf8("❤️"), alice, bPub, aPub);
if ((await bobOpens(sealed)) !== "❤️") throw new Error("tagged v2 opens for the peer");
const own = await openTaggedEnvelope({
  envelopeData: sealed,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  senderIdentityPublic: aPub,
  asSender: true,
}).then(utf8decode);
if (own !== "❤️") throw new Error("tagged v2 opens for our other devices");

const parsed = JSON.parse(utf8decode(sealed)) as { v: number; peer: { ek: string; ct: string; t?: string } };
const untagged = utf8(JSON.stringify({ ...parsed, peer: { ek: parsed.peer.ek, ct: parsed.peer.ct } }));
await rejects("untagged box", () => bobOpens(untagged));
await rejects("v1 envelope", () => bobOpens(utf8(JSON.stringify({ v: 1, ek: parsed.peer.ek, ct: parsed.peer.ct }))));
await rejects("genuine box claimed by someone else", () => bobOpens(sealed, mPub));

console.log("reactions crypto selftest ok");

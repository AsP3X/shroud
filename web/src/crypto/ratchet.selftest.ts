import { x25519 } from "@noble/curves/ed25519.js";
import { bytesToHex, randomBytes, utf8, utf8decode } from "./bytes";
import { initiateAsSender, prepareAsReceiver, ratchetDecrypt, ratchetEncrypt, rootSeed } from "./ratchet";

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

const alice = randomBytes(32);
const bob = randomBytes(32);
const aPub = x25519.getPublicKey(alice);
const bPub = x25519.getPublicKey(bob);

const seedA = rootSeed(alice, bPub);
const seedB = rootSeed(bob, aPub);
if (bytesToHex(seedA) !== bytesToHex(seedB)) {
  throw new Error("rootSeed not symmetric");
}

const aliceS = initiateAsSender(alice, bPub);
const bobS = prepareAsReceiver(bob, aPub);

const p1 = utf8("hello from alice");
const e1 = await ratchetEncrypt(p1, aliceS);
if (utf8decode(await ratchetDecrypt(e1, bobS)) !== "hello from alice") throw new Error("p1");

const p2 = utf8("reply from bob");
const e2 = await ratchetEncrypt(p2, bobS);
if (utf8decode(await ratchetDecrypt(e2, aliceS)) !== "reply from bob") throw new Error("p2");

const p3 = utf8("alice again");
const e3 = await ratchetEncrypt(p3, aliceS);
if (utf8decode(await ratchetDecrypt(e3, bobS)) !== "alice again") throw new Error("p3");

const { sealBox, openBox } = await import("./sealedBox");
const msg = utf8("sealed hello");
const box = await sealBox(msg, bPub, aPub, bPub);
const opened = await openBox(box, bob, aPub, bPub);
if (utf8decode(opened) !== "sealed hello") throw new Error("sealed box roundtrip");

const { sealMessage, openMessage, ratchetStorageName } = await import("./messageCrypto");
const { createVault, derivePinSecrets } = await import("./vault");
const aliceId = "00000000-0000-4000-8000-00000000000a";
const bobId = "00000000-0000-4000-8000-00000000000b";
// One vault stands in for both browsers; sessions are named by (our id, peer id) within it.
createVault(
  aliceId,
  { secrets: await derivePinSecrets("123456"), pepper: randomBytes(32), guardId: "test" },
  randomBytes(32),
);
const aliceSession = ratchetStorageName(aliceId, bobId)!;
for (let i = localStorage.length - 1; i >= 0; i--) {
  const key = localStorage.key(i);
  if (key?.startsWith("shroud.ratchet.")) localStorage.removeItem(key);
}
const a1 = await sealMessage({
  plaintext: utf8("A1"),
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  peerIdentityPublic: bPub,
});
if (utf8decode(await openMessage({
  envelopeData: a1,
  peerUserId: aliceId,
  ourUserId: bobId,
  ourPrivate: bob,
  ourIdentityPublic: bPub,
  senderIdentityPublic: aPub,
  asSender: false,
})) !== "A1") throw new Error("bob open A1");
const a1env = JSON.parse(utf8decode(a1)) as { v?: number; peer?: { ek?: string }; self?: { ek?: string } };
if (a1env.v !== 3 || !a1env.peer?.ek || !a1env.self?.ek) throw new Error("v3 missing identity boxes");
const phoneAlice = localStorage.getItem(aliceSession);
if (!phoneAlice) throw new Error("alice session missing after A1");

localStorage.removeItem(aliceSession);
await sealMessage({
  plaintext: utf8("A-web"),
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  peerIdentityPublic: bPub,
});
const webAlice = localStorage.getItem(aliceSession);
if (!webAlice) throw new Error("alice web session missing");

const b1 = await sealMessage({
  plaintext: utf8("B1"),
  peerUserId: aliceId,
  ourUserId: bobId,
  ourPrivate: bob,
  ourIdentityPublic: bPub,
  peerIdentityPublic: aPub,
});
if (utf8decode(await openMessage({
  envelopeData: b1,
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  senderIdentityPublic: bPub,
  asSender: false,
})) !== "B1") throw new Error("alice web open B1 via peer box");
if (localStorage.getItem(aliceSession) !== webAlice) {
  throw new Error("peer-box fallback overwrote sibling session");
}

localStorage.removeItem(aliceSession);
if (utf8decode(await openMessage({
  envelopeData: b1,
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  senderIdentityPublic: bPub,
  asSender: false,
})) !== "B1") throw new Error("alice sibling with no session open B1 via peer box");

localStorage.removeItem(aliceSession);
const a2 = await sealMessage({
  plaintext: utf8("A2"),
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  peerIdentityPublic: bPub,
});
if (utf8decode(await openMessage({
  envelopeData: a2,
  peerUserId: aliceId,
  ourUserId: bobId,
  ourPrivate: bob,
  ourIdentityPublic: bPub,
  senderIdentityPublic: aPub,
  asSender: false,
})) !== "A2") throw new Error("bob open sibling A2 via peer box");
localStorage.setItem(aliceSession, phoneAlice);
const a3 = await sealMessage({
  plaintext: utf8("A3"),
  peerUserId: bobId,
  ourUserId: aliceId,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  peerIdentityPublic: bPub,
});
if (utf8decode(await openMessage({
  envelopeData: a3,
  peerUserId: aliceId,
  ourUserId: bobId,
  ourPrivate: bob,
  ourIdentityPublic: bPub,
  senderIdentityPublic: aPub,
  asSender: false,
})) !== "A3") throw new Error("bob open phone A3 after sibling send");

console.log("ratchet selftest ok");

import { x25519 } from "@noble/curves/ed25519.js";
import { bytesToHex, randomBytes, utf8, utf8decode } from "./bytes";
import { initiateAsSender, prepareAsReceiver, ratchetDecrypt, ratchetEncrypt, rootSeed } from "./ratchet";

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

console.log("ratchet selftest ok");

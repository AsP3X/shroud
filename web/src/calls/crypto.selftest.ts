/**
 * Sealed call signals against the docs/calls.md test vector (the iPhone tests the same one), and
 * the signals that must not open: tampered, reflected, relabelled, from another call.
 * Run: npx esbuild src/calls/crypto.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { bytesToHex, hexToBytes } from "../crypto/bytes";
import { callKeys, deriveCallSecret, openSignal, sealSignal, signalKeyBytes } from "./crypto";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`calls crypto selftest: ${what}`);
}

async function rejects(run: () => Promise<unknown>, what: string): Promise<void> {
  let opened = false;
  try {
    await run();
    opened = true;
  } catch {
    /* expected */
  }
  check(!opened, what);
}

/* --- the vector --- */
const alicePrivate = hexToBytes("ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d");
const alicePublic = hexToBytes("8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467");
const bobPrivate = hexToBytes("58b2859744734402b8fa838480195ffd0c8cfbda7bbe22a710a7d4d8b79b1afd");
const bobPublic = hexToBytes("389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338");
const callId = "0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b";
const nonce = hexToBytes("000102030405060708090a0b");
const payload =
  "c1.AAECAwQFBgcICQoLQDFMfgG6gj47QCtynr5cS3IjS6czaNhnseRvgliRTrXZeUi+lMfrLwnqwjds+cTC3HtQ";

check(bytesToHex(x25519.getPublicKey(alicePrivate)) === bytesToHex(alicePublic), "alice's public key");
check(bytesToHex(x25519.getPublicKey(bobPrivate)) === bytesToHex(bobPublic), "bob's public key");
check(
  bytesToHex(x25519.getSharedSecret(alicePrivate, bobPublic)) ===
    "8e190e7874f1b7804fc6c5e9e650e0a2481638c33a1053d9a1d5a78b19d6a12a",
  "x25519 shared",
);

const aliceSecret = deriveCallSecret(alicePrivate, alicePublic, bobPublic);
const bobSecret = deriveCallSecret(bobPrivate, bobPublic, alicePublic);
check(
  bytesToHex(aliceSecret) === "39ea5a3a4128617d799fab4480c1bff13f92bef72ccb0bbc246da1504ba3839f",
  "call secret",
);
check(bytesToHex(bobSecret) === bytesToHex(aliceSecret), "both sides derive the same secret");
check(
  bytesToHex(signalKeyBytes(aliceSecret, callId, "caller")) ===
    "3a3d8f2724fe45e6af1af14b490fd1a4e43aacb8d8e13a9009eb677c79bebb30",
  "key(caller)",
);
check(
  bytesToHex(signalKeyBytes(aliceSecret, callId, "callee")) ===
    "46ac38a6ec923643613298cd51555c02f62c163bf191a62f200740d3a0cfb099",
  "key(callee)",
);
check(
  bytesToHex(signalKeyBytes(aliceSecret, callId.toUpperCase(), "caller")) ===
    bytesToHex(signalKeyBytes(aliceSecret, callId, "caller")),
  "the salt is the id's bytes, whatever its case",
);

// Alice calls Bob.
const alice = await callKeys(aliceSecret, callId, "caller");
const bob = await callKeys(bobSecret, callId, "callee");
const offer = { t: "offer", sdp: "v=0\r\n", n: 1 };
const sealed = await sealSignal(alice.send, callId, "sdp_offer", offer, nonce);
check(sealed === payload, `sealed payload matches the vector (got ${sealed})`);
check(
  (await sealSignal(alice.send, callId.toUpperCase(), "sdp_offer", offer, nonce)) === payload,
  "the additional data takes the id in lower case",
);

/* --- opening --- */
const opened = await openSignal(bob.receive, callId, "sdp_offer", payload);
check(JSON.stringify(opened) === JSON.stringify(offer), "bob opens the vector");
const fresh = await sealSignal(bob.send, callId, "sdp_answer", { t: "answer", sdp: "v=0\r\n", n: 1 });
check(fresh.startsWith("c1.") && fresh !== payload, "a real signal takes a random nonce");
check(
  (await sealSignal(bob.send, callId, "sdp_answer", { t: "answer", sdp: "v=0\r\n", n: 1 })) !== fresh,
  "two seals of one signal differ",
);
const answer = await openSignal(alice.receive, callId, "sdp_answer", fresh);
check(answer.t === "answer" && answer.n === 1, "alice opens bob's answer");

/* --- what must not open --- */
await rejects(() => openSignal(alice.receive, callId, "sdp_offer", payload), "a signal reflected to its sender");
await rejects(() => openSignal(bob.send, callId, "sdp_offer", payload), "the sender's own key opens nothing");
await rejects(() => openSignal(bob.receive, callId, "sdp_answer", payload), "a relabelled signal type");
await rejects(() => openSignal(bob.receive, callId, "media_state", payload), "a relabelled signal type (media)");
const otherCall = "0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6c";
await rejects(() => openSignal(bob.receive, otherCall, "sdp_offer", payload), "a signal replayed into another call");
const otherBob = await callKeys(bobSecret, otherCall, "callee");
await rejects(() => openSignal(otherBob.receive, otherCall, "sdp_offer", payload), "another call's keys");

const raw = Uint8Array.from(atob(payload.slice(3)), (c) => c.charCodeAt(0));
for (const index of [0, 12, raw.length - 1]) {
  const tampered = raw.slice();
  tampered[index] ^= 0x01;
  const b64 = btoa(String.fromCharCode(...tampered));
  await rejects(() => openSignal(bob.receive, callId, "sdp_offer", `c1.${b64}`), `a flipped bit at byte ${index}`);
}
await rejects(
  () => openSignal(bob.receive, callId, "sdp_offer", `c1.${btoa(String.fromCharCode(...raw.slice(0, 27)))}`),
  "a payload shorter than nonce and tag",
);
await rejects(() => openSignal(bob.receive, callId, "sdp_offer", payload.replace("c1.", "c2.")), "another version");
await rejects(() => openSignal(bob.receive, callId, "sdp_offer", "c1.%%%"), "not base64");
await rejects(() => openSignal(bob.receive, callId, "sdp_offer", ""), "an empty payload");
// Someone with only the public keys (the server) derives some other secret.
const malloryPrivate = hexToBytes("11".repeat(32));
const mallorySecret = deriveCallSecret(malloryPrivate, x25519.getPublicKey(malloryPrivate), bobPublic);
const mallory = await callKeys(mallorySecret, callId, "caller");
const forged = await sealSignal(mallory.send, callId, "sdp_offer", offer, nonce);
await rejects(() => openSignal(bob.receive, callId, "sdp_offer", forged), "a key without alice's private key");
const list = await sealSignal(alice.send, callId, "sdp_offer", [1, 2] as unknown as Record<string, unknown>);
await rejects(() => openSignal(bob.receive, callId, "sdp_offer", list), "a plaintext that is not an object");
await rejects(async () => signalKeyBytes(aliceSecret, "not-a-call", "caller"), "a malformed call id");

console.log("calls crypto selftest ok");

// Generates the vectors pinned by MessageCryptoTest, DoubleRatchetTest, MediaCryptoTest,
// DeviceNameSealTest and IdentitySafetyNumberTest with Node's own crypto (OpenSSL) — an
// implementation independent of the BouncyCastle/JCA code under test.
//
//   node android/app/src/test/java/de/corespace/shroud/core/crypto/gen_message_crypto_vectors.mjs
//
// No dependencies. Re-running it must print exactly the constants in those tests. The rows the
// iOS and web tests already pin (the web-sealed box of SenderTagTests.webSealedTagVerifiesHere and
// both DeviceNameSealTests goldens) are recomputed and checked first, so this script is shown to
// match iOS `MessageCrypto.swift` / `DeviceNameSeal.swift` and web `sealedBox.ts` /
// `deviceName.ts` before it prints the Android-only rows (crypto spec §16.3):
//
//   - a deterministic v2 envelope (MessageCrypto.swift:92-118, draw order peer eph, peer nonce,
//     self eph, self nonce) and a deterministic v3 envelope (:131-199: ratchet DH, ratchet
//     nonce, self eph, self nonce, peer eph, peer nonce);
//   - the Double Ratchet trace of DoubleRatchet.swift (rootSeed, initiateAsSender, a receive
//     with its DH ratchet, a reply, Alice's receive);
//   - the media blob (MediaCrypto.swift:40-53), key 0x01 x 32 then nonce 0x02 x 12;
//   - the kind-4 "Android app" device name (crypto D4, plan P4);
//   - safety numbers (IdentitySafetyNumber.swift:10-36), incl. a pair that needs the unsigned
//     compare (0x80 is negative as a Kotlin Byte).

import { createCipheriv, createDecipheriv, createHash, createHmac, createPrivateKey, createPublicKey, diffieHellman, hkdfSync } from "node:crypto";

const utf8 = (s) => Buffer.from(s, "utf8");
const fill = (byte, n) => Buffer.alloc(n, byte);
const seq = (start, n) => Buffer.from([...Array(n).keys()].map((i) => (start + i) & 0xff));
const b64 = (buf) => Buffer.from(buf).toString("base64");
const hex = (buf) => Buffer.from(buf).toString("hex");

// ---- X25519 on raw keys (RFC 8410 DER wrappers) ----
const PKCS8_PREFIX = Buffer.from("302e020100300506032b656e04220420", "hex");
const SPKI_PREFIX = Buffer.from("302a300506032b656e032100", "hex");
const priv = (raw) => createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, raw]), format: "der", type: "pkcs8" });
const pubKey = (raw) => createPublicKey({ key: Buffer.concat([SPKI_PREFIX, raw]), format: "der", type: "spki" });
const x25519Pub = (rawPriv) => createPublicKey(priv(rawPriv)).export({ format: "der", type: "spki" }).subarray(12);
const ecdh = (rawPriv, rawPub) => diffieHellman({ privateKey: priv(rawPriv), publicKey: pubKey(rawPub) });

const hkdf = (ikm, salt, info, len) => Buffer.from(hkdfSync("sha256", ikm, salt, info, len));
const hmac = (key, data) => createHmac("sha256", key).update(data).digest();

function gcmSeal(key, nonce, plaintext, aad) {
  const c = createCipheriv("aes-256-gcm", key, nonce);
  if (aad) c.setAAD(aad);
  return Buffer.concat([nonce, c.update(plaintext), c.final(), c.getAuthTag()]);
}

function gcmOpen(key, combined, aad) {
  const d = createDecipheriv("aes-256-gcm", key, combined.subarray(0, 12));
  if (aad) d.setAAD(aad);
  d.setAuthTag(combined.subarray(combined.length - 16));
  return Buffer.concat([d.update(combined.subarray(12, combined.length - 16)), d.final()]);
}

function check(cond, what) {
  if (!cond) throw new Error(`vector check failed: ${what}`);
}

// ---- identity boxes (MessageCrypto.swift:490-645, web sealedBox.ts) ----
const messageKey = (shared, ek, sPub, rPub) => hkdf(shared, utf8("shroud-v1"), Buffer.concat([utf8("shroud-msg-v1"), ek, sPub, rPub]), 32);
const tagKey = (ourPriv, theirPub, sPub, rPub) =>
  hkdf(ecdh(ourPriv, theirPub), utf8("shroud-box-auth-v1"), Buffer.concat([utf8("shroud-box-auth-v1"), sPub, rPub]), 32);
const boxTag = (key, ek, ct) => hmac(key, Buffer.concat([utf8("shroud-box-tag-v1"), ek, ct]));

function sealBox(plaintext, sPriv, sPub, rPub, ephPriv, nonce) {
  const ek = x25519Pub(ephPriv);
  const ct = gcmSeal(messageKey(ecdh(ephPriv, rPub), ek, sPub, rPub), nonce, plaintext);
  const t = boxTag(tagKey(sPriv, rPub, sPub, rPub), ek, ct);
  return { ek: b64(ek), ct: b64(ct), t: b64(t) };
}

const alicePriv = fill(0x11, 32);
const bobPriv = fill(0x22, 32);
const alicePub = x25519Pub(alicePriv);
const bobPub = x25519Pub(bobPriv);

console.log("keys (alicePriv = 0x11 x 32, bobPriv = 0x22 x 32)");
console.log(`  alicePub   ${hex(alicePub)}  ${b64(alicePub)}`);
console.log(`  bobPub     ${hex(bobPub)}  ${b64(bobPub)}`);
console.log(`  ECDH(a,b)  ${hex(ecdh(alicePriv, bobPub))}`);
check(hex(ecdh(alicePriv, bobPub)) === hex(ecdh(bobPriv, alicePub)), "ECDH symmetric");

// Shared iOS/web vector: SenderTagTests.webSealedTagVerifiesHere (ios/shroudTests/SenderTagTests.swift:157-180).
{
  const box = { ek: "S6UOubR4gmyGJzC+XuOl+S0Y3VykkvhJXJ32/m3JWCw=", ct: "s67IM+reT2lNl2zRNUPKbSdmtPpU5BX5o/cOH0WTaaHS23Hi", t: "iFxRl5LlVWjGZgfs2F1WLl9Olc8ZHOzP67HNLMoeywM=" };
  const ek = Buffer.from(box.ek, "base64");
  const ct = Buffer.from(box.ct, "base64");
  const mk = messageKey(ecdh(bobPriv, ek), ek, alicePub, bobPub);
  const tk = tagKey(bobPriv, alicePub, alicePub, bobPub);
  check(gcmOpen(mk, ct).toString("utf8") === "from web", "web box opens");
  check(b64(boxTag(tk, ek, ct)) === box.t, "web box tag");
  console.log("\nweb box of webSealedTagVerifiesHere (checked)");
  console.log(`  message key ${hex(mk)}`);
  console.log(`  tag key     ${hex(tk)}`);
}

// ---- deterministic v2 (MessageCrypto.swift:92-118): peer box, then self box ----
{
  const p = utf8("hello shroud");
  const peer = sealBox(p, alicePriv, alicePub, bobPub, fill(0x77, 32), fill(0x88, 12));
  const self = sealBox(p, alicePriv, alicePub, alicePub, fill(0x99, 32), fill(0xaa, 12));
  console.log('\ndeterministic v2 "hello shroud" alice -> bob (peer eph 0x77, nonce 0x88; self eph 0x99, nonce 0xaa)');
  console.log(`  peer ${JSON.stringify(peer)}`);
  console.log(`  self ${JSON.stringify(self)}`);
}

// ---- Double Ratchet (DoubleRatchet.swift) ----
const sortedConcat = (a, b) => (Buffer.compare(a, b) <= 0 ? Buffer.concat([a, b]) : Buffer.concat([b, a]));
const rootSeed = (ourPriv, theirPub) => hkdf(ecdh(ourPriv, theirPub), sortedConcat(x25519Pub(ourPriv), theirPub), utf8("shroud-dr-root-v3"), 32);
const kdfRK = (root, dhOut) => {
  const okm = hkdf(dhOut, root, utf8("shroud-dr-rk-v3"), 64);
  return [okm.subarray(0, 32), okm.subarray(32)];
};
const kdfCK = (ck) => [hmac(ck, Buffer.from([1])), hmac(ck, Buffer.from([2]))];

console.log("\nDouble Ratchet trace (alice initiator, bob receiver)");
const rk0 = rootSeed(alicePriv, bobPub);
check(hex(rk0) === hex(rootSeed(bobPriv, alicePub)), "rootSeed symmetric");
console.log(`  rootSeed   ${hex(rk0)}`);
// Alice initiateAsSender with DH priv 0x33 (:87-111).
const aliceDh = fill(0x33, 32);
let [aRoot, aSend] = kdfRK(rk0, ecdh(aliceDh, bobPub));
console.log(`  alice root ${hex(aRoot)}`);
console.log(`  alice send ${hex(aSend)}`);
const [aNext, mk0] = kdfCK(aSend);
console.log(`  kdfCK next ${hex(aNext)}`);
console.log(`  kdfCK mk0  ${hex(mk0)}`);
const m1 = { v: 3, dh: b64(x25519Pub(aliceDh)), n: 0, pn: 0, ct: b64(gcmSeal(mk0, fill(0x44, 12), utf8("hello from alice"))) };
console.log(`  m1 ${JSON.stringify(m1)}`);
// Bob prepareAsReceiver (identity as the first DH private), decrypt: DH ratchet with new DH 0x55.
let [bRoot1, bRecv] = kdfRK(rk0, ecdh(bobPriv, Buffer.from(m1.dh, "base64")));
check(hex(bRoot1) === hex(aRoot) && hex(bRecv) === hex(aSend), "bob receive chain");
const bobDh = fill(0x55, 32);
const [bRoot2, bSend] = kdfRK(bRoot1, ecdh(bobDh, Buffer.from(m1.dh, "base64")));
const [, bMk] = kdfCK(bRecv);
check(gcmOpen(bMk, Buffer.from(m1.ct, "base64")).toString() === "hello from alice", "bob opens m1");
console.log(`  bob root2  ${hex(bRoot2)}`);
console.log(`  bob send   ${hex(bSend)}`);
const [, bMk0] = kdfCK(bSend);
const m2 = { v: 3, dh: b64(x25519Pub(bobDh)), n: 0, pn: 0, ct: b64(gcmSeal(bMk0, fill(0x66, 12), utf8("reply from bob"))) };
console.log(`  m2 ${JSON.stringify(m2)}`);
// Alice receives m2: DH ratchet from her send private 0x33, then her next DH 0x57 (:214-238).
const [aRoot2, aRecv] = kdfRK(aRoot, ecdh(aliceDh, Buffer.from(m2.dh, "base64")));
check(hex(aRoot2) === hex(bRoot2), "alice root after receive");
check(hex(aRecv) === hex(bSend), "alice receive chain");
check(gcmOpen(kdfCK(aRecv)[1], Buffer.from(m2.ct, "base64")).toString() === "reply from bob", "alice opens m2");
const aliceDh2 = fill(0x57, 32);
const [aRoot3, aSend2] = kdfRK(aRoot2, ecdh(aliceDh2, Buffer.from(m2.dh, "base64")));
console.log(`  alice root3 ${hex(aRoot3)}  (after her new DH 0x57)`);
console.log(`  alice send2 ${hex(aSend2)}`);
console.log(`  alice dh2   ${b64(x25519Pub(aliceDh2))}`);

// ---- deterministic v3 (MessageCrypto.swift:131-199) ----
// Alice (lower id) seals "hello" to Bob with no session: initiateAsSender DH 0x33, ratchet nonce
// 0x44, then the self box (eph 0x99, nonce 0xaa), then the peer box (eph 0x77, nonce 0x88).
{
  const p = utf8("hello");
  const ct = gcmSeal(mk0, fill(0x44, 12), p);
  const self = sealBox(p, alicePriv, alicePub, alicePub, fill(0x99, 32), fill(0xaa, 12));
  const peer = sealBox(p, alicePriv, alicePub, bobPub, fill(0x77, 32), fill(0x88, 12));
  console.log('\ndeterministic v3 "hello" alice -> bob (DH 0x33, nonce 0x44; self 0x99/0xaa; peer 0x77/0x88)');
  console.log(`  ${JSON.stringify({ v: 3, dh: m1.dh, n: 0, pn: 0, ct: b64(ct), peer, self })}`);
}

// ---- media (MediaCrypto.swift:40-53) ----
{
  const sealed = gcmSeal(fill(0x01, 32), fill(0x02, 12), utf8("media bytes"));
  console.log('\nmedia "media bytes", key 0x01 x 32, nonce 0x02 x 12');
  console.log(`  ${b64(sealed)}`);
}

// ---- device names (DeviceNameSeal.swift, web deviceName.ts) ----
{
  const historyKey = seq(0, 32);
  const deviceId = "0F8FAD5B-D9CB-469F-A165-70867728950E";
  const nonce = seq(0xa0, 12);
  const key = hkdf(historyKey, utf8("shroud-v1"), utf8("shroud-device-name-v1"), 32);
  const aad = utf8(`shroud-device-name-v1:${deviceId.toLowerCase()}`);
  const sealName = (name, kind, custom) => {
    const padded = Buffer.alloc(128);
    const bytes = utf8(name);
    padded[0] = kind | (custom ? 0x80 : 0);
    bytes.copy(padded, 1);
    padded[1 + bytes.length] = 0x80;
    return b64(gcmSeal(key, nonce, padded, aad));
  };
  // Shared goldens (ios/shroudTests/DeviceNameSealTests.swift:12-13, :27-28).
  check(
    sealName("Küchen-iPad ✨", 2, false) ===
      "oKGio6SlpqeoqaqrdjG5oKGAC3ucvlABL0bwNia0p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X/DkcQzpugtYldAYowYWm3N",
    "device name golden",
  );
  check(
    sealName("Office iPhone", 1, true) ===
      "oKGio6Slpqeoqaqr9TUcequLCzXYh2gPJQOSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X+l2LSRw4GSvCdsnGiSSnet",
    "renamed golden",
  );
  console.log("\ndevice names (historyKey 00..1f, device 0F8FAD5B-…, nonce a0..ab; iOS goldens checked)");
  console.log(`  name key ${hex(key)}`);
  console.log(`  ("Pixel 9 Pro", kind 4) ${sealName("Pixel 9 Pro", 4, false)}`);
}

// ---- safety numbers (IdentitySafetyNumber.swift:10-36, web safetyNumber.ts) ----
function safety(local, peer) {
  const [first, second] = Buffer.compare(local, peer) < 0 ? [local, peer] : [peer, local];
  const d = createHash("sha256").update(Buffer.concat([first, second])).digest();
  const groups = [];
  for (let i = 0; i < 12; i++) {
    const v = (d[(2 * i) % 32] << 16) | (d[(2 * i + 1) % 32] << 8) | d[(3 * i) % 32];
    groups.push(String(v % 100000).padStart(5, "0"));
  }
  return groups.join(" ");
}
{
  const h1 = Buffer.from("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", "hex"); // RFC 7748 §6.1 Alice public
  const h2 = Buffer.from("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", "hex"); // RFC 7748 §6.1 Bob public
  const pairs = [
    ["alicePub, bobPub", alicePub, bobPub],
    ["01..20, 80..9f", seq(0x01, 32), seq(0x80, 32)],
    ["01..20, 21..40", seq(0x01, 32), seq(0x21, 32)],
    ["00 x 32, ff x 32", fill(0x00, 32), fill(0xff, 32)],
    ["RFC 7748 h1, h2", h1, h2],
    ["01..20, 01..20", seq(0x01, 32), seq(0x01, 32)],
  ];
  console.log("\nsafety numbers (symmetric; checked both ways)");
  for (const [label, a, b] of pairs) {
    check(safety(a, b) === safety(b, a), `safety symmetric ${label}`);
    console.log(`  ${label.padEnd(18)} ${safety(a, b)}`);
  }
}

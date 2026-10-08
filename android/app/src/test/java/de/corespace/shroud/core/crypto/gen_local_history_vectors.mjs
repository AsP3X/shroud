// Generates the at-rest vectors pinned by LocalHistoryCryptoTest and LocalNamesTest, with Node's
// own crypto (OpenSSL) — an implementation independent of the BouncyCastle/JCA code under test.
//
//   node android/app/src/test/java/de/corespace/shroud/core/crypto/gen_local_history_vectors.mjs
//
// No dependencies. Re-running it must print exactly the constants in the two tests; the iOS-derived
// rows (the seven subkeys and the empty-AAD blob, crypto spec §16.3) double as a check that this
// script matches iOS `LocalHistoryCrypto.swift`.
//
// Format (ios/shroud/Services/Messaging/LocalHistoryCrypto.swift:36-88, plus Android's AAD, plan C10):
//   subkey = HKDF-SHA256(ikm = historyKey, salt = "shroud-local-at-rest-v1", info = context, L = 32)
//   blob   = "SHRD1" ‖ nonce (12) ‖ AES-256-GCM(subkey, nonce, plaintext, aad) ‖ tag (16)
//   names  = hex(HMAC-SHA256(subkey(RecordNames), kind + ":" + id.toLowerCase())).slice(0, 32)

import { createCipheriv, createHmac, hkdfSync } from "node:crypto";

const utf8 = (s) => Buffer.from(s, "utf8");
const master = Buffer.alloc(32, 0x5a); // ios/shroudTests/SealedTestKey.swift:8
const nonce = Buffer.from([...Array(12).keys()].map((i) => i + 1)); // 01 02 … 0c
const plaintext = utf8("secret chat body — notes & 🔒"); // LocalHistoryCryptoTests.swift:8

const contexts = [
  ["MessagesSnapshot", "shroud-local-messages-v1"],
  ["MediaFile", "shroud-local-media-v1"],
  ["PlaintextPayload", "shroud-local-plaintext-v1"],
  ["IdentityKeychain", "shroud-keychain-identity-v1"],
  ["RatchetKeychain", "shroud-keychain-ratchet-v1"],
  ["LanguageStats", "shroud-local-language-stats-v1"],
  ["RecordNames", "shroud-local-names-v1"],
];

const subkey = (info) => Buffer.from(hkdfSync("sha256", master, utf8("shroud-local-at-rest-v1"), utf8(info), 32));

function seal(key, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  if (aad.length > 0) cipher.setAAD(aad);
  const ct = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([utf8("SHRD1"), nonce, ct, cipher.getAuthTag()]);
}

const namesKey = subkey("shroud-local-names-v1");
const name = (kind, id) => createHmac("sha256", namesKey).update(utf8(`${kind}:${id.toLowerCase()}`)).digest("hex").slice(0, 32);

console.log("subkeys for master 0x5A x 32");
for (const [label, info] of contexts) console.log(`  ${label.padEnd(18)} ${info.padEnd(31)} ${subkey(info).toString("hex")}`);

const messages = subkey("shroud-local-messages-v1");
console.log("\nblob, MessagesSnapshot, nonce 01..0c, empty AAD (iOS)");
console.log("  " + seal(messages, Buffer.alloc(0)).toString("base64"));

const peer = "0F8FAD5B-D9CB-469F-A165-70867728950E"; // DeviceNameSealTests.swift device id, reused
// alicePub of crypto spec §16.3 (X25519 public of 0x11 x 32); byte ids are named by their lower-case hex.
const alicePub = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13";
const aad = `threads/${name("thread", peer)}`;
console.log("\nLocalNames (namesKey = subkey RecordNames)");
console.log(`  ratchet:${peer} -> ${name("ratchet", peer)}`);
console.log(`  thread:${peer}  -> ${name("thread", peer)}`);
console.log(`  sender-tag:<alicePub hex>                     -> ${name("sender-tag", alicePub)}`);

console.log(`\nblob, MessagesSnapshot, nonce 01..0c, AAD "${aad}"`);
console.log("  " + seal(messages, utf8(aad)).toString("base64"));

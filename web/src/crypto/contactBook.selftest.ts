import { bytesToHex } from "@noble/hashes/utils.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { utf8 } from "./bytes";
import { openContactBook, sealContactBook } from "./contactBook";

function check(cond: boolean, label: string) {
  if (!cond) throw new Error(label);
}

// Pinned so iOS `ContactNameBookTests` and Android `ContactNameBookTest` seal the same bytes.
const historyKey = new Uint8Array(32).map((_, i) => i + 1);
const owner = "0190A3B4-0000-7000-8000-000000000001";
const nonce = new Uint8Array(12).map((_, i) => 0xa0 + i);
const names = { "0190a3b4-0000-7000-8000-0000000000aa": "alice", "0190a3b4-0000-7000-8000-0000000000bb": "bob_2" };
const sealed = sealContactBook(historyKey, owner, names, nonce);
check(sealed.length === 1404, "pads to 1024 bytes before sealing");
// SHA-256 of the Base64 text; the other apps' tests pin the same digest.
check(
  bytesToHex(sha256(utf8(sealed))) === "b346b39ba8ba513d4ee111f8a44ae5441df440965245bb7a5e0157bbbfe158e8",
  "the sealed bytes match iOS and Android",
);
const opened = openContactBook(historyKey, owner.toLowerCase(), sealed);
check(JSON.stringify(opened) === JSON.stringify(names), "opens to the same names");
check(openContactBook(historyKey, "0190a3b4-0000-7000-8000-000000000002", sealed) === null, "another account's id doesn't open it");
check(
  openContactBook(historyKey, owner, sealContactBook(historyKey, owner, { "not-an-id": "alice", "0190a3b4-0000-7000-8000-0000000000cc": "Bad Name" })) !== null &&
    Object.keys(openContactBook(historyKey, owner, sealContactBook(historyKey, owner, { "not-an-id": "alice" }))!).length === 0,
  "malformed entries are dropped",
);
console.log("contactBook.selftest ok");

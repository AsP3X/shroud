import { gcm } from "@noble/ciphers/aes.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, utf8 } from "./bytes";

/*
 * The account's contact-name book, shared by its devices through the server, which can't
 * read it. The same bytes as iOS `ContactNameBook` and Android `ContactNameBookSeal`.
 *
 * A name reaches a device only when that contact's app seals it to the account. A device that
 * has opened names keeps them in this book, so a browser (which keeps no contact list) or a
 * new phone learns them from the account's other devices at once.
 *
 *   key       = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-contact-names-v1", 32)
 *   aad       = "shroud-contact-names-v1:" + lowercase owner user id
 *   plaintext = UTF-8 JSON {"v":1,"names":{"<lowercase peer id>":"<username>",…}}, ids sorted,
 *               no spaces, then spaces
 *               to the next multiple of 1024 bytes, so the size shows little of the count
 *   sealed    = nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16), sent as standard Base64
 */

const LABEL = "shroud-contact-names-v1";
const SALT = utf8("shroud-v1");
const NONCE_BYTES = 12;
const PAD_TO = 1024;
const NAME_RE = /^[a-z0-9_]{3,32}$/;
const ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function bookKey(historyKey: Uint8Array): Uint8Array {
  return hkdf(sha256, historyKey, SALT, utf8(LABEL), 32);
}

function associatedData(ownerId: string): Uint8Array {
  return utf8(`${LABEL}:${ownerId.toLowerCase()}`);
}

/** Only well-formed ids and usernames survive, whichever device wrote the book. */
export function cleanNames(names: Record<string, unknown>): Record<string, string> {
  // Sorted by id, so every app writes the same JSON for the same names.
  const out: Record<string, string> = {};
  for (const [id, name] of Object.entries(names).sort(([a], [b]) => (a.toLowerCase() < b.toLowerCase() ? -1 : 1))) {
    const key = id.toLowerCase();
    if (ID_RE.test(key) && typeof name === "string" && NAME_RE.test(name)) out[key] = name;
  }
  return out;
}

export function sealContactBook(
  historyKey: Uint8Array,
  ownerId: string,
  names: Record<string, string>,
  nonce: Uint8Array = randomBytes(NONCE_BYTES),
): string {
  const json = utf8(JSON.stringify({ v: 1, names: cleanNames(names) }));
  const padded = new Uint8Array(Math.ceil((json.length + 1) / PAD_TO) * PAD_TO).fill(0x20);
  padded.set(json, 0);
  const sealed = gcm(bookKey(historyKey), nonce, associatedData(ownerId)).encrypt(padded);
  return bytesToB64(concatBytes(nonce, sealed));
}

/** Null when the book doesn't open: another account's key, or damaged. */
export function openContactBook(
  historyKey: Uint8Array,
  ownerId: string,
  sealedB64: string | null | undefined,
): Record<string, string> | null {
  if (!sealedB64) return null;
  try {
    const combined = b64ToBytes(sealedB64);
    if (combined.length < NONCE_BYTES + 16) return null;
    const plain = gcm(bookKey(historyKey), combined.slice(0, NONCE_BYTES), associatedData(ownerId)).decrypt(
      combined.slice(NONCE_BYTES),
    );
    const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plain).trimEnd()) as {
      v?: number;
      names?: Record<string, unknown>;
    };
    if (parsed?.v !== 1 || !parsed.names || typeof parsed.names !== "object") return null;
    return cleanNames(parsed.names);
  } catch {
    return null;
  }
}

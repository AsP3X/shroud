import { gcm } from "@noble/ciphers/aes.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { hmac } from "@noble/hashes/hmac.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { storageSealed } from "../storageSeal";
import { b64ToBytes, bytesToB64, bytesToHex, randomBytes, utf8, utf8decode } from "./bytes";

/*
 * The at-rest vault: everything this browser keeps about the account is sealed under one
 * random 256-bit key — the identity keys, ratchet sessions, decrypted message bodies and
 * transcripts, chat previews, language statistics and the media cache. Storage names that
 * would otherwise carry a message id or a contact's user id are keyed hashes.
 *
 * The vault key never touches storage in the clear. It is kept twice, wrapped:
 *   - under the PIN and a server-held pepper, which opens it on every page load and after a
 *     lock. PBKDF2 turns the PIN into two keys: an auth key the server checks before it hands
 *     out the pepper (`POST /pin-guard/unlock`, ten wrong tries and the pepper is deleted), and
 *     key material that only becomes the wrap key together with that pepper. A copy of this
 *     browser's storage alone therefore cannot be brute-forced: every PIN guess costs a request;
 *   - under a key derived from the recovery phrase's history key, which opens it when the
 *     PIN is forgotten, so "Forgot PIN" re-derives it from the phrase instead of losing data.
 *
 * Locking drops the key from memory. A reload is a fresh page with no key, so it asks for
 * the PIN too — the key has nowhere to survive that would not also be readable from disk.
 */

const RECORD_PREFIX = "shroud.vault.";
const SEALED_TAG = "e1.";
const PBKDF2_ITERATIONS = 600_000;
const NONCE_BYTES = 12;

type VaultRecord = {
  v: 1;
  /** PBKDF2 iterations and salt for the PIN wrap. */
  iter: number;
  salt: string;
  /** Digits in the PIN, so the keypad does not run a key derivation per keystroke. */
  len?: number;
  /** Vault key sealed under PIN + pepper; absent after "Forgot PIN" until a new one is chosen. */
  pin?: string;
  /** The server's PIN guard that holds the pepper for `pin`. */
  guard?: string;
  /** Vault key sealed under the phrase. */
  phrase: string;
};

type OpenVault = {
  userId: string;
  key: Uint8Array;
  storageKey: Uint8Array;
  nameKey: Uint8Array;
  mediaKey: Promise<CryptoKey>;
};

let open: OpenVault | null = null;
const listeners = new Set<() => void>();

function recordKey(userId: string): string {
  return RECORD_PREFIX + userId.toLowerCase();
}

function subkey(key: Uint8Array, info: string): Uint8Array {
  return hkdf(sha256, key, undefined, utf8(info), 32);
}

function seal(key: Uint8Array, plaintext: Uint8Array, aad: string): Uint8Array {
  const nonce = randomBytes(NONCE_BYTES);
  const body = gcm(key, nonce, utf8(aad)).encrypt(plaintext);
  const out = new Uint8Array(NONCE_BYTES + body.length);
  out.set(nonce, 0);
  out.set(body, NONCE_BYTES);
  return out;
}

function unseal(key: Uint8Array, combined: Uint8Array, aad: string): Uint8Array {
  if (combined.length < NONCE_BYTES + 16) throw new Error("vault: sealed value too short");
  return gcm(key, combined.subarray(0, NONCE_BYTES), utf8(aad)).decrypt(combined.subarray(NONCE_BYTES));
}

function readRecord(userId: string): VaultRecord | null {
  try {
    const raw = localStorage.getItem(recordKey(userId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as VaultRecord;
    if (parsed.v !== 1 || !parsed.salt || !parsed.phrase || !parsed.iter) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writeRecord(userId: string, record: VaultRecord): void {
  if (storageSealed()) return;
  localStorage.setItem(recordKey(userId), JSON.stringify(record));
}

/** What a PIN turns into: the server's auth key, and the local half of the wrap key. */
export type PinSecrets = {
  authKey: Uint8Array;
  material: Uint8Array;
  salt: Uint8Array;
  iter: number;
  length: number;
};

/** A PIN plus the pepper its guard released (or just created). */
export type PinUnlock = { secrets: PinSecrets; pepper: Uint8Array; guardId: string };

/** PBKDF2 over the PIN. `salt`/`iter` come from the vault record, or are fresh for a new PIN. */
export async function derivePinSecrets(
  pin: string,
  salt: Uint8Array = randomBytes(16),
  iter: number = PBKDF2_ITERATIONS,
): Promise<PinSecrets> {
  const base = await crypto.subtle.importKey("raw", utf8(pin) as BufferSource, "PBKDF2", false, [
    "deriveBits",
  ]);
  const bits = new Uint8Array(
    await crypto.subtle.deriveBits(
      { name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations: iter },
      base,
      256,
    ),
  );
  const secrets = {
    authKey: subkey(bits, "shroud-web-pin-guard-auth"),
    material: subkey(bits, "shroud-web-pin-wrap"),
    salt,
    iter,
    length: pin.length,
  };
  bits.fill(0);
  return secrets;
}

/** What the server stores to recognise the auth key: its SHA-256, never the key itself. */
export function pinVerifier(secrets: PinSecrets): Uint8Array {
  return sha256(secrets.authKey);
}

function pinKek(unlock: PinUnlock): Uint8Array {
  const joined = new Uint8Array(unlock.secrets.material.length + unlock.pepper.length);
  joined.set(unlock.secrets.material, 0);
  joined.set(unlock.pepper, unlock.secrets.material.length);
  const kek = subkey(joined, "shroud-web-vault-pin-kek");
  joined.fill(0);
  return kek;
}

function phraseKek(historyKey: Uint8Array): Uint8Array {
  return subkey(historyKey, "shroud-web-vault-phrase");
}

function pinAad(userId: string): string {
  return `shroud.vault.pin.${userId.toLowerCase()}`;
}

function phraseAad(userId: string): string {
  return `shroud.vault.phrase.${userId.toLowerCase()}`;
}

function activate(userId: string, key: Uint8Array): void {
  const mediaRaw = subkey(key, "shroud-web-vault-media");
  open = {
    userId: userId.toLowerCase(),
    key,
    storageKey: subkey(key, "shroud-web-vault-storage"),
    nameKey: subkey(key, "shroud-web-vault-names"),
    mediaKey: crypto.subtle.importKey("raw", mediaRaw as BufferSource, "AES-GCM", false, [
      "encrypt",
      "decrypt",
    ]),
  };
  mediaRaw.fill(0);
  for (const listener of listeners) listener();
}

/* --- lifecycle ------------------------------------------------------------------------------ */

export function hasVault(userId: string): boolean {
  return readRecord(userId) !== null;
}

/** Whether the vault can be opened with a PIN (false after "Forgot PIN", until a new one is set). */
export function vaultHasPin(userId: string): boolean {
  const record = readRecord(userId);
  return Boolean(record?.pin && record.guard);
}

/** Digits the stored PIN has, when known. */
export function vaultPinLength(userId: string): number | null {
  return readRecord(userId)?.len ?? null;
}

export function isVaultOpen(userId?: string): boolean {
  if (!open) return false;
  return userId ? open.userId === userId.toLowerCase() : true;
}

/** Runs whenever a vault opens (the app re-reads what it could not decrypt before). */
export function onVaultOpen(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Creates a fresh vault for this account and opens it. Replaces any vault already there. */
export function createVault(userId: string, unlock: PinUnlock, historyKey: Uint8Array): void {
  const key = randomBytes(32);
  const kek = pinKek(unlock);
  const record: VaultRecord = {
    v: 1,
    iter: unlock.secrets.iter,
    salt: bytesToB64(unlock.secrets.salt),
    len: unlock.secrets.length,
    pin: bytesToB64(seal(kek, key, pinAad(userId))),
    guard: unlock.guardId,
    phrase: bytesToB64(seal(phraseKek(historyKey), key, phraseAad(userId))),
  };
  kek.fill(0);
  writeRecord(userId, record);
  activate(userId, key);
}

/** The guard and PBKDF2 parameters an unlock needs; null when no PIN can open the vault. */
export function vaultPinGuard(userId: string): { guardId: string; salt: Uint8Array; iter: number } | null {
  const record = readRecord(userId);
  if (!record?.pin || !record.guard) return null;
  return { guardId: record.guard, salt: b64ToBytes(record.salt), iter: record.iter };
}

/** Opens the vault with the PIN and the pepper its guard released. */
export function openVaultWithPin(userId: string, unlock: PinUnlock): boolean {
  const record = readRecord(userId);
  if (!record?.pin) return false;
  const kek = pinKek(unlock);
  try {
    activate(userId, unseal(kek, b64ToBytes(record.pin), pinAad(userId)));
    return true;
  } catch {
    return false;
  } finally {
    kek.fill(0);
  }
}

/** Opens the vault with the phrase's history key — the "Forgot PIN" path. */
export function openVaultWithPhrase(userId: string, historyKey: Uint8Array): boolean {
  const record = readRecord(userId);
  if (!record) return false;
  try {
    activate(userId, unseal(phraseKek(historyKey), b64ToBytes(record.phrase), phraseAad(userId)));
    return true;
  } catch {
    return false;
  }
}

/** Wraps the open vault's key under a new PIN and its new guard (after "Forgot PIN"). */
export function setVaultPin(userId: string, unlock: PinUnlock): void {
  const record = readRecord(userId);
  if (!record || !open || open.userId !== userId.toLowerCase()) throw new Error("vault is locked");
  const kek = pinKek(unlock);
  writeRecord(userId, {
    ...record,
    iter: unlock.secrets.iter,
    salt: bytesToB64(unlock.secrets.salt),
    len: unlock.secrets.length,
    pin: bytesToB64(seal(kek, open.key, pinAad(userId))),
    guard: unlock.guardId,
  });
  kek.fill(0);
}

/** "Forgot PIN": the PIN wrap goes; only the phrase can open the vault until a new PIN is set. */
export function forgetVaultPin(userId: string): void {
  const record = readRecord(userId);
  if (!record) return;
  const { pin: _pin, len: _len, guard: _guard, ...rest } = record;
  writeRecord(userId, rest);
}

/** Lock: the key leaves memory, and nothing sealed can be read or written until the next unlock. */
export function closeVault(): void {
  if (!open) return;
  open.key.fill(0);
  open.storageKey.fill(0);
  open.nameKey.fill(0);
  open = null;
}

/* --- local storage -------------------------------------------------------------------------- */

/**
 * Storage name for an account item. `prefix` stays readable (the wipe and the storage
 * settings count by it); the id — a message id or a contact's user id — becomes a keyed
 * hash, so the list of names says nothing about who talks to whom. Null while locked.
 */
export function vaultName(prefix: string, id: string): string | null {
  if (!open) return null;
  return prefix + bytesToHex(hmac(sha256, open.nameKey, utf8(id.toLowerCase())));
}

/** Whether a stored name is one `vaultName` produced (as opposed to a pre-vault plaintext name). */
export function isVaultName(prefix: string, name: string): boolean {
  return name.startsWith(prefix) && /^[0-9a-f]{64}$/.test(name.slice(prefix.length));
}

/** Seals a string for `localStorage` under this storage name. Null while locked. */
export function sealForStorage(name: string, value: string): string | null {
  if (!open) return null;
  return SEALED_TAG + bytesToB64(seal(open.storageKey, utf8(value), name));
}

/** Opens what `sealForStorage` wrote. Null while locked, for plaintext, or for a value that fails. */
export function openFromStorage(name: string, stored: string | null): string | null {
  if (!open || !stored || !stored.startsWith(SEALED_TAG)) return null;
  try {
    return utf8decode(unseal(open.storageKey, b64ToBytes(stored.slice(SEALED_TAG.length)), name));
  } catch {
    return null;
  }
}

export function isSealedValue(stored: string | null): boolean {
  return Boolean(stored?.startsWith(SEALED_TAG));
}

/** Reads and opens one sealed `localStorage` item. */
export function vaultGet(name: string): string | null {
  try {
    return openFromStorage(name, localStorage.getItem(name));
  } catch {
    return null;
  }
}

/**
 * Seals and writes one `localStorage` item. Returns false — and writes nothing — while the
 * vault is locked: an account item is never stored in the clear.
 */
export function vaultSet(name: string, value: string): boolean {
  if (storageSealed()) return false;
  const sealed = sealForStorage(name, value);
  if (sealed === null) return false;
  try {
    localStorage.setItem(name, sealed);
    return true;
  } catch {
    return false;
  }
}

/* --- media (IndexedDB) ---------------------------------------------------------------------- */

/** Seals bytes for the media store. Null while locked. */
export async function sealMedia(name: string, data: Uint8Array): Promise<ArrayBuffer | null> {
  const vault = open;
  if (!vault) return null;
  const nonce = randomBytes(NONCE_BYTES);
  const body = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce as BufferSource, additionalData: utf8(name) as BufferSource },
    await vault.mediaKey,
    data as BufferSource,
  );
  const out = new Uint8Array(NONCE_BYTES + body.byteLength);
  out.set(nonce, 0);
  out.set(new Uint8Array(body), NONCE_BYTES);
  return out.buffer;
}

/** Opens what `sealMedia` wrote. Null while locked, or for anything that fails. */
export async function openMedia(name: string, sealed: Uint8Array): Promise<Uint8Array | null> {
  const vault = open;
  if (!vault || sealed.length < NONCE_BYTES + 16) return null;
  try {
    const plain = await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: sealed.slice(0, NONCE_BYTES) as BufferSource,
        additionalData: utf8(name) as BufferSource,
      },
      await vault.mediaKey,
      sealed.slice(NONCE_BYTES) as BufferSource,
    );
    return new Uint8Array(plain);
  } catch {
    return null;
  }
}

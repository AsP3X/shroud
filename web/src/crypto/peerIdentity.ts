import { api } from "../api/client";
import { b64ToBytes, bytesToB64 } from "./bytes";
import { deleteRatchet } from "./messageCrypto";
import { onVaultOpen, vaultGet, vaultName, vaultSet } from "./vault";

/**
 * The first identity key this browser sees for a contact, kept in the vault.
 *
 * Human: A contact's key is remembered the first time we see it. If the server later
 * hands out a different one, calls and new messages wait until you accept it. Comparing
 * the safety number is what tells you the first key was really theirs.
 * Agent: The pinned key is what decrypts. `pending` is the fetched key, never written
 * over the pin until `acceptPeerKey`. Storage names are vault hashes, so the list of
 * keys does not say who they belong to.
 */

const PREFIX = "shroud.peer-key.";

export const PEER_KEY_CHANGED =
  "This contact's encryption key changed. Verify their safety number before trusting new messages or calls.";

export class PeerKeyChanged extends Error {
  constructor() {
    super(PEER_KEY_CHANGED);
    this.name = "PeerKeyChanged";
  }
}

export type PeerPin = {
  /** Canonical standard base64 of the pinned 32-byte key. */
  key: string;
  verified: boolean;
  /** A fetched key that does not match `key`, waiting for the user. */
  pending: string | null;
};

const memory = new Map<string, PeerPin>();
/** Peers whose server key we already rechecked this unlock. Decrypt does not ask again. */
const rechecked = new Set<string>();
const blockedListeners = new Set<(userId: string) => void>();

/** Canonical base64, or null when `value` is not a 32-byte key. */
export function canonicalKey(value: string): string | null {
  try {
    const bytes = b64ToBytes(value);
    if (bytes.length !== 32) return null;
    return bytesToB64(bytes);
  } catch {
    return null;
  }
}

/** What a fetch does to the pin. The pinned key never moves until it is accepted. */
export function notePeerKey(current: PeerPin | null, fetched: string): PeerPin {
  if (!current) return { key: fetched, verified: false, pending: null };
  if (current.key === fetched) return { ...current, pending: null };
  return { ...current, pending: fetched };
}

export function peerKeyBlocked(pin: PeerPin | null): boolean {
  return pin?.pending != null;
}

/** The user compared safety numbers and trusts the fetched key. The comparison starts over. */
export function acceptPeerKey(pin: PeerPin): PeerPin {
  if (!pin.pending) return pin;
  return { key: pin.pending, verified: false, pending: null };
}

export function confirmPeerKey(pin: PeerPin): PeerPin {
  return { ...pin, verified: true };
}

function idOf(userId: string): string {
  return userId.toLowerCase();
}

function load(userId: string): PeerPin | null {
  const id = idOf(userId);
  const cached = memory.get(id);
  if (cached) return cached;
  const name = vaultName(PREFIX, id);
  if (!name) return null;
  const raw = vaultGet(name);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<PeerPin>;
    if (!parsed.key || canonicalKey(parsed.key) !== parsed.key) return null;
    const pin: PeerPin = {
      key: parsed.key,
      verified: parsed.verified === true,
      pending: parsed.pending && canonicalKey(parsed.pending) === parsed.pending ? parsed.pending : null,
    };
    memory.set(id, pin);
    return pin;
  } catch {
    return null;
  }
}

function save(userId: string, pin: PeerPin): void {
  const id = idOf(userId);
  const previous = memory.get(id) ?? null;
  memory.set(id, pin);
  const name = vaultName(PREFIX, id);
  if (name) vaultSet(name, JSON.stringify(pin));
  if (pin.pending && previous?.pending !== pin.pending) {
    for (const listener of blockedListeners) listener(id);
  }
}

/** Fired when a fetch finds a key that does not match the pin. */
export function onPeerKeyBlocked(listener: (userId: string) => void): () => void {
  blockedListeners.add(listener);
  return () => blockedListeners.delete(listener);
}

export function isPeerKeyBlocked(userId: string): boolean {
  return peerKeyBlocked(load(userId));
}

async function fetchPin(token: string, userId: string): Promise<PeerPin> {
  const res = await api.peerIdentity(token, userId);
  const fetched = canonicalKey(res.identity_key);
  if (!fetched) throw new Error("peer identity: not a key");
  const pin = notePeerKey(load(userId), fetched);
  save(userId, pin);
  return pin;
}

/**
 * The pinned key for opening messages. The first look fetches it. Later looks use the pin
 * and recheck the server once per unlock, so a history page does not ask once per message.
 * A changed key stays the old one here; sending and calls are what refuse it.
 */
export async function peerIdentityPublic(token: string, userId: string): Promise<Uint8Array> {
  const id = idOf(userId);
  const cached = load(userId);
  if (cached) {
    if (!rechecked.has(id)) {
      rechecked.add(id);
      void fetchPin(token, userId).catch(() => {
        rechecked.delete(id);
      });
    }
    return b64ToBytes(cached.key);
  }
  return b64ToBytes((await fetchPin(token, userId)).key);
}

/**
 * The pinned key for a call or a new message. Asks the server first. Throws `PeerKeyChanged`
 * while a different key is waiting to be accepted, so a substituted key is never sealed to.
 */
export async function peerIdentityForSending(token: string, userId: string): Promise<Uint8Array> {
  const pin = await fetchPin(token, userId);
  rechecked.add(idOf(userId));
  if (peerKeyBlocked(pin)) throw new PeerKeyChanged();
  return b64ToBytes(pin.key);
}

export function storedPeerKey(userId: string): Uint8Array | null {
  const pin = load(userId);
  return pin ? b64ToBytes(pin.key) : null;
}

export function peerKeyVerified(userId: string): boolean {
  return load(userId)?.verified === true;
}

/** The user compared this contact's safety number. */
export function markPeerKeyVerified(userId: string): void {
  const pin = load(userId);
  if (!pin || pin.verified) return;
  save(userId, confirmPeerKey(pin));
}

/**
 * Trusts the fetched key after the safety number was compared, and drops the ratchet session
 * that was built with the previous key.
 */
export function acceptChangedPeerKey(peerUserId: string, ourUserId: string): boolean {
  const pin = load(peerUserId);
  if (!pin?.pending) return false;
  save(peerUserId, acceptPeerKey(pin));
  deleteRatchet(ourUserId, peerUserId);
  return true;
}

/** Drops the in-memory copy. The vault copy remains until the next unlock reads it. */
export function forgetPeerKeyCache(): void {
  memory.clear();
  rechecked.clear();
}

// A different account opening this tab must not reuse the previous account's pins.
onVaultOpen(forgetPeerKeyCache);

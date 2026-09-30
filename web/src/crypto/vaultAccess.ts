import { api, ApiError } from "../api/client";
import { loadSession, sealSessionToken } from "../session";
import { STATS_KEY } from "../voice/language";
import { storageSealed } from "../storageSeal";
import { b64ToBytes, bytesToB64 } from "./bytes";
import { dropUnsealedMediaBlobs } from "./mediaCache";
import { flushPendingIdentity, loadPlaintextIdentity, pendingIdentity } from "./store";
import {
  createVault,
  derivePinSecrets,
  forgetVaultPin,
  hasVault,
  isSealedValue,
  isVaultName,
  isVaultOpen,
  openVaultWithPin,
  pinVerifier,
  sealForStorage,
  setVaultPin,
  vaultHasPin,
  vaultName,
  vaultPinGuard,
  vaultPinLength,
  type PinUnlock,
} from "./vault";

/*
 * The PIN and the vault (vault.ts): choosing a PIN creates the vault and a PIN guard on the
 * server, entering it asks the guard for the pepper and opens the vault, and "Forgot PIN"
 * drops the PIN wrap so only the phrase can open it again. Too many wrong PINs delete the
 * guard on the server; the PIN wrap is then useless and is dropped here too.
 *
 * Browsers from before the vault kept a SHA-256 PIN hash and every account item in the
 * clear. Their first unlock checks the old hash once, creates the vault under the same PIN
 * and seals everything in place (`sealLegacyStorage`). The sweep runs after every unlock,
 * so a tab still running the old code cannot leave plaintext behind for long.
 */

const LEGACY_PIN_PREFIX = "shroud.pin.";
const LEGACY_PREFIXES = ["shroud.pt.", "shroud.preview.", "shroud.ratchet."] as const;

type LegacyPinRecord = { salt: string; hash: string };

function legacyPinKey(userId: string): string {
  return LEGACY_PIN_PREFIX + userId.toLowerCase();
}

function readLegacyPin(userId: string): LegacyPinRecord | null {
  try {
    const raw = localStorage.getItem(legacyPinKey(userId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as LegacyPinRecord;
    return parsed.salt && parsed.hash ? parsed : null;
  } catch {
    return null;
  }
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Whether a PIN can unlock this browser (the vault's PIN wrap, or a pre-vault PIN hash). */
export function hasPin(userId: string): boolean {
  return vaultHasPin(userId) || (!hasVault(userId) && readLegacyPin(userId) !== null);
}

/**
 * The vault exists but neither a PIN nor an open key can reach it: "Forgot PIN" was pressed,
 * or the page reloaded before the new PIN was chosen. Only the phrase step opens it.
 */
export function needsPhrase(userId: string): boolean {
  return hasVault(userId) && !vaultHasPin(userId) && !isVaultOpen(userId);
}

/** Digits to wait for before trying the PIN, when the vault knows (each try costs a KDF run). */
export function pinLength(userId: string): number | null {
  return vaultPinLength(userId);
}

/** Why a PIN did not open the vault. */
export type UnlockFailure =
  | { kind: "wrong"; message: string }
  /** The guard is gone (too many wrong PINs): only the phrase opens the vault now. */
  | { kind: "gone"; message: string }
  /** The server could not be asked; nothing was counted. */
  | { kind: "offline"; message: string };

export type UnlockResult = { ok: true } | ({ ok: false } & UnlockFailure);

/**
 * Chooses the PIN: registers a new guard with the server (which needs the session token, held
 * in memory since login or read from the open vault), then wraps the vault key under PIN +
 * pepper. With the vault open (after "Forgot PIN") it is re-wrapped; otherwise this creates the
 * vault from the phrase's history key and seals what was waiting for it.
 */
export async function setPin(userId: string, pin: string): Promise<void> {
  if (storageSealed()) return;
  const token = loadSession()?.token;
  if (!token) throw new Error("no session to register the PIN with");
  const secrets = await derivePinSecrets(pin);
  const guard = await api.createPinGuard(token, bytesToB64(pinVerifier(secrets)));
  const unlock: PinUnlock = { secrets, pepper: b64ToBytes(guard.pepper), guardId: guard.guard_id };
  if (isVaultOpen(userId) && hasVault(userId)) {
    setVaultPin(userId, unlock);
  } else {
    const identity = pendingIdentity(userId) ?? loadPlaintextIdentity(userId);
    if (!identity) throw new Error("no identity to create the vault from");
    createVault(userId, unlock, identity.historyKey);
  }
  flushPendingIdentity(userId);
  await sealLegacyStorage(userId);
}

/** Opens the vault with the PIN, asking the server's guard for the pepper. */
export async function unlockWithPin(userId: string, pin: string): Promise<UnlockResult> {
  const guard = vaultPinGuard(userId);
  if (guard) {
    const secrets = await derivePinSecrets(pin, guard.salt, guard.iter);
    let pepper: Uint8Array;
    try {
      const released = await api.unlockPinGuard(guard.guardId, bytesToB64(secrets.authKey));
      pepper = b64ToBytes(released.pepper);
    } catch (err) {
      if (err instanceof ApiError && err.code === "PIN_INCORRECT") {
        return { ok: false, kind: "wrong", message: err.message };
      }
      if (err instanceof ApiError && (err.code === "PIN_GUARD_GONE" || err.status === 410)) {
        forgetVaultPin(userId);
        return { ok: false, kind: "gone", message: err.message };
      }
      return { ok: false, kind: "offline", message: "Can’t reach Shroud to check the PIN. Try again." };
    }
    if (!openVaultWithPin(userId, { secrets, pepper, guardId: guard.guardId })) {
      // The server accepted the PIN, so this is a damaged record, not a guess.
      return { ok: false, kind: "gone", message: "This PIN no longer opens Shroud here. Use your encryption phrase." };
    }
    flushPendingIdentity(userId);
    await sealLegacyStorage(userId);
    return { ok: true };
  }
  // A browser from before the vault: its SHA-256 PIN hash is checked here, once, and the vault
  // (with its server guard) is created under the same PIN.
  const legacy = hasVault(userId) ? null : readLegacyPin(userId);
  if (!legacy) return { ok: false, kind: "gone", message: "Use your encryption phrase to unlock." };
  const hash = await sha256Hex(`${legacy.salt}:${pin}`);
  if (!constantTimeEqual(hash, legacy.hash)) return { ok: false, kind: "wrong", message: "Wrong PIN." };
  try {
    await setPin(userId, pin);
  } catch {
    return { ok: false, kind: "offline", message: "Can’t reach Shroud to secure this browser. Try again." };
  }
  return { ok: true };
}

/**
 * "Forgot PIN", before the local wrap is dropped: delete the server's pepper. A copy of this
 * browser still has the wrap, and without this it can still unlock. There is no session on
 * the lock screen — the token is sealed — so this is the unauthenticated abandon call.
 * Throws when the server cannot be reached; the caller keeps the PIN in that case.
 */
export async function abandonPin(userId: string): Promise<void> {
  const guard = vaultPinGuard(userId);
  if (!guard) return;
  await api.abandonPinGuard(guard.guardId);
}

/** "Forgot PIN": only the phrase can open the vault until a new PIN is chosen. */
export function clearPin(userId: string): void {
  forgetVaultPin(userId);
  try {
    localStorage.removeItem(legacyPinKey(userId));
  } catch {
    /* storage unavailable */
  }
}

/** Every storage name in `localStorage`, read before any is changed (writes reindex). */
function storageNames(): string[] {
  const names: string[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const name = localStorage.key(i);
      if (name !== null) names.push(name);
    }
  } catch {
    /* storage unavailable */
  }
  return names;
}

/** Seals `value` under `name`, then removes the plaintext at `from` (which may be the same name). */
function reseal(from: string, name: string, value: string): void {
  const sealed = sealForStorage(name, value);
  if (sealed === null) return;
  try {
    localStorage.setItem(name, sealed);
    if (from !== name) localStorage.removeItem(from);
  } catch {
    /* quota: the next unlock tries again */
  }
}

/**
 * Seals every account item this browser still holds in the clear: identity keys, ratchet
 * sessions, message bodies and transcripts, chat previews, language statistics. The old PIN
 * hash is deleted and unsealed media is dropped. Needs the open vault.
 */
export async function sealLegacyStorage(userId: string): Promise<void> {
  if (!isVaultOpen(userId) || storageSealed()) return;
  sealSessionToken();
  const me = userId.toLowerCase();
  for (const name of storageNames()) {
    let value: string | null;
    try {
      value = localStorage.getItem(name);
    } catch {
      continue;
    }
    if (value === null) continue;

    if (name === legacyPinKey(userId)) {
      localStorage.removeItem(name);
      continue;
    }
    if ((name === `shroud.identity.${me}` || name === STATS_KEY) && !isSealedValue(value)) {
      reseal(name, name, value);
      continue;
    }
    const prefix = LEGACY_PREFIXES.find((p) => name.startsWith(p));
    if (!prefix || isVaultName(prefix, name)) continue;
    const id = name.slice(prefix.length);
    // Previews and sessions are `<owner>.<peer>`; another account's wait for its own unlock.
    const [owner, peer] = id.split(".");
    if (prefix !== "shroud.pt." && owner !== me) continue;
    const target = vaultName(prefix, id);
    if (!target) return;
    if (prefix === "shroud.preview.") {
      // The ids move into the sealed value, which is where the chat list reads them.
      try {
        const preview = JSON.parse(value) as Record<string, unknown>;
        if (peer) reseal(name, target, JSON.stringify({ ...preview, me, peer }));
        else localStorage.removeItem(name);
      } catch {
        localStorage.removeItem(name);
      }
      continue;
    }
    reseal(name, target, value);
  }
  await dropUnsealedMediaBlobs();
}

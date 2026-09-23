import { b64ToBytes, bytesEqual, bytesToB64, utf8, utf8decode } from "./bytes";
import {
  deserializeSession,
  initiateAsSender,
  prepareAsReceiver,
  RATCHET_VERSION,
  ratchetDecrypt,
  ratchetEncrypt,
  serializeSession,
  type RatchetMessage,
  type RatchetSession,
} from "./ratchet";
import { openBox, sealBox, type SealedBox } from "./sealedBox";
import { storageSealed } from "../storageSeal";
import { isVaultOpen, vaultGet, vaultName, vaultSet } from "./vault";

export type RatchetEnvelope = {
  v: number;
  dh: string;
  n: number;
  pn: number;
  ct: string;
  /** Recipient identity box — the peer's other devices can open without DR state. */
  peer?: SealedBox;
  self?: SealedBox;
};

export type SealedEnvelope = {
  v: number;
  ek?: string;
  ct?: string;
  peer?: SealedBox;
  self?: SealedBox;
};

/** Storage name of a ratchet session: a keyed hash, so the names do not list who we talk to. */
export function ratchetStorageName(ourUserId: string, peerUserId: string): string | null {
  return vaultName("shroud.ratchet.", `${ourUserId.toLowerCase()}.${peerUserId.toLowerCase()}`);
}

export function loadRatchet(ourUserId: string, peerUserId: string): RatchetSession | null {
  const name = ratchetStorageName(ourUserId, peerUserId);
  if (!name) return null;
  try {
    const raw = vaultGet(name);
    if (!raw) return null;
    return deserializeSession(raw);
  } catch {
    return null;
  }
}

/**
 * Sealed into the vault. The shell only runs unlocked, so a locked vault here means the lock
 * landed mid-message; the step is dropped rather than written in the clear.
 */
export function saveRatchet(ourUserId: string, peerUserId: string, session: RatchetSession): void {
  if (storageSealed()) return;
  const name = ratchetStorageName(ourUserId, peerUserId);
  if (!name || !vaultSet(name, serializeSession(session))) {
    throw new Error("ratchet: vault is locked");
  }
}

function peekVersion(data: Uint8Array): number | null {
  try {
    const parsed = JSON.parse(utf8decode(data)) as { v?: number };
    return typeof parsed.v === "number" ? parsed.v : null;
  } catch {
    return null;
  }
}

export async function sealMessage(opts: {
  plaintext: Uint8Array;
  peerUserId: string;
  ourUserId: string;
  ourPrivate: Uint8Array;
  ourIdentityPublic: Uint8Array;
  peerIdentityPublic: Uint8Array;
}): Promise<Uint8Array> {
  // Locked, the stored session cannot be read; starting a fresh one would fork the ratchet.
  if (!isVaultOpen()) throw new Error("seal: vault is locked");
  const existing = loadRatchet(opts.ourUserId, opts.peerUserId);
  const mayStartRatchet =
    existing != null || opts.ourUserId.toLowerCase() < opts.peerUserId.toLowerCase();

  if (!mayStartRatchet) {
    return sealV2(opts.plaintext, opts.peerIdentityPublic, opts.ourIdentityPublic);
  }

  let session =
    existing ?? initiateAsSender(opts.ourPrivate, opts.peerIdentityPublic);
  if (existing && !bytesEqual(existing.peerIdentityPublic, opts.peerIdentityPublic)) {
    session.peerIdentityPublic = opts.peerIdentityPublic;
  }
  const drBody = await ratchetEncrypt(opts.plaintext, session);
  saveRatchet(opts.ourUserId, opts.peerUserId, session);
  const drMessage = JSON.parse(utf8decode(drBody)) as RatchetMessage;
  const selfBox = await sealBox(
    opts.plaintext,
    opts.ourIdentityPublic,
    opts.ourIdentityPublic,
    opts.ourIdentityPublic,
  );
  const peerBox = await sealBox(
    opts.plaintext,
    opts.peerIdentityPublic,
    opts.ourIdentityPublic,
    opts.peerIdentityPublic,
  );
  const envelope: RatchetEnvelope = {
    v: RATCHET_VERSION,
    dh: drMessage.dh,
    n: drMessage.n,
    pn: drMessage.pn,
    ct: drMessage.ct,
    peer: peerBox,
    self: selfBox,
  };
  return utf8(JSON.stringify(envelope));
}

async function sealV2(
  plaintext: Uint8Array,
  peerPublic: Uint8Array,
  ourPublic: Uint8Array,
): Promise<Uint8Array> {
  const peer = await sealBox(plaintext, peerPublic, ourPublic, peerPublic);
  const selfBox = await sealBox(plaintext, ourPublic, ourPublic, ourPublic);
  const envelope: SealedEnvelope = { v: 2, peer, self: selfBox };
  return utf8(JSON.stringify(envelope));
}

export async function openMessage(opts: {
  envelopeData: Uint8Array;
  peerUserId: string;
  ourUserId: string;
  ourPrivate: Uint8Array;
  ourIdentityPublic: Uint8Array;
  senderIdentityPublic: Uint8Array;
  asSender: boolean;
}): Promise<Uint8Array> {
  if (!isVaultOpen()) throw new Error("open: vault is locked");
  const version = peekVersion(opts.envelopeData);
  if (version === 3) {
    const v3 = JSON.parse(utf8decode(opts.envelopeData)) as RatchetEnvelope;
    if (opts.asSender) {
      if (!v3.self) throw new Error("open: missing self box");
      return openBox(v3.self, opts.ourPrivate, opts.ourIdentityPublic, opts.ourIdentityPublic);
    }
    try {
      return await openRatchetRecipient(v3, opts);
    } catch (err) {
      if (!v3.peer) throw err;
      // Sibling devices share IK but not ephemeral DH. Opening the identity box
      // must not write ratchet state — a failed DR attempt may have mutated a clone.
      return await openBox(v3.peer, opts.ourPrivate, opts.senderIdentityPublic, opts.ourIdentityPublic);
    }
  }
  const envelope = JSON.parse(utf8decode(opts.envelopeData)) as SealedEnvelope;
  if (envelope.v === 1) {
    if (!envelope.ek || !envelope.ct) throw new Error("open: bad v1");
    return openBox(
      { ek: envelope.ek, ct: envelope.ct },
      opts.ourPrivate,
      opts.senderIdentityPublic,
      opts.ourIdentityPublic,
    );
  }
  if (envelope.v === 2) {
    const box = opts.asSender ? envelope.self : envelope.peer;
    if (!box) throw new Error("open: missing v2 box");
    if (opts.asSender) {
      return openBox(box, opts.ourPrivate, opts.ourIdentityPublic, opts.ourIdentityPublic);
    }
    return openBox(box, opts.ourPrivate, opts.senderIdentityPublic, opts.ourIdentityPublic);
  }
  throw new Error("open: unsupported envelope");
}

async function openRatchetRecipient(
  v3: RatchetEnvelope,
  opts: {
    peerUserId: string;
    ourUserId: string;
    ourPrivate: Uint8Array;
    senderIdentityPublic: Uint8Array;
  },
): Promise<Uint8Array> {
  const drData = utf8(JSON.stringify({ v: v3.v, dh: v3.dh, n: v3.n, pn: v3.pn, ct: v3.ct }));
  const existing = loadRatchet(opts.ourUserId, opts.peerUserId);

  if (existing && (existing.recvChainKey || existing.touched)) {
    const clone = deserializeSession(serializeSession(existing));
    const plain = await ratchetDecrypt(drData, clone);
    saveRatchet(opts.ourUserId, opts.peerUserId, clone);
    return plain;
  }

  const candidates: RatchetSession[] = [];
  if (existing) {
    const unusedInitiator =
      existing.sendChainKey != null &&
      existing.recvChainKey == null &&
      existing.dhRecvPublic != null &&
      bytesEqual(existing.dhRecvPublic, existing.peerIdentityPublic);
    if (unusedInitiator) {
      candidates.push(prepareAsReceiver(opts.ourPrivate, opts.senderIdentityPublic));
      candidates.push(existing);
    } else {
      candidates.push(existing);
    }
  }
  if (candidates.length === 0) {
    candidates.push(prepareAsReceiver(opts.ourPrivate, opts.senderIdentityPublic));
  }

  let lastError: unknown;
  for (const session of candidates) {
    try {
      const clone = deserializeSession(serializeSession(session));
      const plain = await ratchetDecrypt(drData, clone);
      saveRatchet(opts.ourUserId, opts.peerUserId, clone);
      return plain;
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("open: decrypt failed");
}

export function envelopeToWireB64(envelopeJson: Uint8Array): string {
  return bytesToB64(envelopeJson);
}

export function wireB64ToEnvelope(b64: string): Uint8Array {
  return b64ToBytes(b64);
}

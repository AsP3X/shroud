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

/** Drops the session so the next message starts from the peer's current identity key. */
export function deleteRatchet(ourUserId: string, peerUserId: string): void {
  const name = ratchetStorageName(ourUserId, peerUserId);
  if (!name) return;
  try {
    localStorage.removeItem(name);
  } catch {
    /* the next send finds whatever is still there */
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
    return sealV2(opts.plaintext, opts.ourPrivate, opts.peerIdentityPublic, opts.ourIdentityPublic);
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
    opts.ourPrivate,
    opts.ourIdentityPublic,
    opts.ourIdentityPublic,
  );
  const peerBox = await sealBox(
    opts.plaintext,
    opts.ourPrivate,
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

/**
 * A v2 envelope (identity boxes only, tagged): for records overwritten in place, such as
 * reactions, where ratchet steps would be lost and every device must open them at any time.
 */
export async function sealIdentityEnvelope(
  plaintext: Uint8Array,
  ourPrivate: Uint8Array,
  peerPublic: Uint8Array,
  ourPublic: Uint8Array,
): Promise<Uint8Array> {
  if (!isVaultOpen()) throw new Error("seal: vault is locked");
  return sealV2(plaintext, ourPrivate, peerPublic, ourPublic);
}

async function sealV2(
  plaintext: Uint8Array,
  ourPrivate: Uint8Array,
  peerPublic: Uint8Array,
  ourPublic: Uint8Array,
): Promise<Uint8Array> {
  const peer = await sealBox(plaintext, ourPrivate, ourPublic, peerPublic);
  const selfBox = await sealBox(plaintext, ourPrivate, ourPublic, ourPublic);
  const envelope: SealedEnvelope = { v: 2, peer, self: selfBox };
  return utf8(JSON.stringify(envelope));
}

/**
 * Opens a v2 envelope — for records overwritten in place (reactions), which never come as v1 or
 * v3. Its box must carry a sender tag that verifies, like every identity box (see
 * `openMessage`). No ratchet state is touched.
 */
export async function openTaggedEnvelope(opts: {
  envelopeData: Uint8Array;
  ourPrivate: Uint8Array;
  ourIdentityPublic: Uint8Array;
  senderIdentityPublic: Uint8Array;
  asSender: boolean;
}): Promise<Uint8Array> {
  if (!isVaultOpen()) throw new Error("open: vault is locked");
  const envelope = JSON.parse(utf8decode(opts.envelopeData)) as SealedEnvelope;
  if (envelope.v !== 2) throw new Error("open: expected a v2 envelope");
  const box = opts.asSender ? envelope.self : envelope.peer;
  if (!box) throw new Error("open: missing box");
  // Our own boxes are sealed from and to our identity.
  const sender = opts.asSender ? opts.ourIdentityPublic : opts.senderIdentityPublic;
  return openBox(box, opts.ourPrivate, sender, opts.ourIdentityPublic);
}

/**
 * Identity boxes open only with a sender tag that verifies (`sealedBox.ts`). An untagged box
 * (every v1 box, and v2/v3 boxes from builds before the tag) says nothing about who sealed it:
 * the server could have, from the two public keys alone. It is refused like a forgery and
 * shows as "Unable to decrypt"; what this browser already opened and cached stays readable.
 *
 * Double Ratchet bodies need no tag: their root comes from the identity ECDH.
 */
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
  const openOwn = (box: SealedBox) =>
    openBox(box, opts.ourPrivate, opts.ourIdentityPublic, opts.ourIdentityPublic);
  const openPeer = (box: SealedBox) =>
    openBox(box, opts.ourPrivate, opts.senderIdentityPublic, opts.ourIdentityPublic);
  const version = peekVersion(opts.envelopeData);
  if (version === 3) {
    const v3 = JSON.parse(utf8decode(opts.envelopeData)) as RatchetEnvelope;
    if (opts.asSender) {
      if (!v3.self) throw new Error("open: missing self box");
      return openOwn(v3.self);
    }
    try {
      return await openRatchetRecipient(v3, opts);
    } catch (err) {
      if (!v3.peer) throw err;
      // Sibling devices share IK but not ephemeral DH. Opening the identity box
      // must not write ratchet state — a failed DR attempt may have mutated a clone.
      return await openPeer(v3.peer);
    }
  }
  const envelope = JSON.parse(utf8decode(opts.envelopeData)) as SealedEnvelope;
  // v1 (one peer-ward box) never carried a sender tag, so none can open.
  if (envelope.v === 1) throw new Error("open: v1 has no sender tag");
  if (envelope.v === 2) {
    const box = opts.asSender ? envelope.self : envelope.peer;
    if (!box) throw new Error("open: missing v2 box");
    return opts.asSender ? openOwn(box) : openPeer(box);
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

import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { hmac } from "@noble/hashes/hmac.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { aesGcmOpen, aesGcmSeal } from "./aes";
import {
  b64ToBytes,
  bytesEqual,
  bytesToB64,
  bytesToHex,
  hexToBytes,
  randomBytes,
  sortedConcat,
  utf8,
} from "./bytes";

export const RATCHET_VERSION = 3;
const MAX_SKIP = 64;
const INFO_ROOT = utf8("shroud-dr-root-v3");
const INFO_RK = utf8("shroud-dr-rk-v3");

export type RatchetMessage = {
  v: number;
  dh: string;
  n: number;
  pn: number;
  ct: string;
};

export type RatchetSession = {
  rootKey: Uint8Array;
  sendChainKey: Uint8Array | null;
  recvChainKey: Uint8Array | null;
  sendN: number;
  recvN: number;
  prevChainLength: number;
  dhSendPrivate: Uint8Array | null;
  dhSendPublic: Uint8Array | null;
  dhRecvPublic: Uint8Array | null;
  peerIdentityPublic: Uint8Array;
  touched: boolean;
  skipped: Record<string, Uint8Array>;
};

function kdfRK(root: Uint8Array, dhOut: Uint8Array): { root: Uint8Array; chain: Uint8Array } {
  const okm = hkdf(sha256, dhOut, root, INFO_RK, 64);
  return { root: okm.slice(0, 32), chain: okm.slice(32) };
}

function kdfCK(chainKey: Uint8Array): { next: Uint8Array; msg: Uint8Array } {
  return {
    next: hmac(sha256, chainKey, new Uint8Array([0x01])),
    msg: hmac(sha256, chainKey, new Uint8Array([0x02])),
  };
}

export function rootSeed(ourPrivate: Uint8Array, theirIdentityPublic: Uint8Array): Uint8Array {
  const shared = x25519.getSharedSecret(ourPrivate, theirIdentityPublic);
  const ourPub = x25519.getPublicKey(ourPrivate);
  const salt = sortedConcat(ourPub, theirIdentityPublic);
  return hkdf(sha256, shared, salt, INFO_ROOT, 32);
}

export function initiateAsSender(
  ourPrivate: Uint8Array,
  theirIdentityPublic: Uint8Array,
): RatchetSession {
  const rk0 = rootSeed(ourPrivate, theirIdentityPublic);
  const dh = randomBytes(32);
  const dhOut = x25519.getSharedSecret(dh, theirIdentityPublic);
  const { root, chain } = kdfRK(rk0, dhOut);
  return {
    rootKey: root,
    sendChainKey: chain,
    recvChainKey: null,
    sendN: 0,
    recvN: 0,
    prevChainLength: 0,
    dhSendPrivate: dh,
    dhSendPublic: x25519.getPublicKey(dh),
    dhRecvPublic: theirIdentityPublic,
    peerIdentityPublic: theirIdentityPublic,
    touched: true,
    skipped: {},
  };
}

export function prepareAsReceiver(
  ourPrivate: Uint8Array,
  theirIdentityPublic: Uint8Array,
): RatchetSession {
  const rk0 = rootSeed(ourPrivate, theirIdentityPublic);
  return {
    rootKey: rk0,
    sendChainKey: null,
    recvChainKey: null,
    sendN: 0,
    recvN: 0,
    prevChainLength: 0,
    dhSendPrivate: ourPrivate,
    dhSendPublic: x25519.getPublicKey(ourPrivate),
    dhRecvPublic: null,
    peerIdentityPublic: theirIdentityPublic,
    touched: false,
    skipped: {},
  };
}

function dhRatchet(session: RatchetSession, remotePublic: Uint8Array): void {
  session.prevChainLength = session.sendN;
  session.sendN = 0;
  session.recvN = 0;
  session.dhRecvPublic = remotePublic;
  if (!session.dhSendPrivate) throw new Error("ratchet: no send private");
  const dh1 = x25519.getSharedSecret(session.dhSendPrivate, remotePublic);
  const step1 = kdfRK(session.rootKey, dh1);
  session.rootKey = step1.root;
  session.recvChainKey = step1.chain;
  const newDH = randomBytes(32);
  session.dhSendPrivate = newDH;
  session.dhSendPublic = x25519.getPublicKey(newDH);
  const dh2 = x25519.getSharedSecret(newDH, remotePublic);
  const step2 = kdfRK(session.rootKey, dh2);
  session.rootKey = step2.root;
  session.sendChainKey = step2.chain;
}

function skipMessageKeys(session: RatchetSession, target: number): void {
  if (!session.recvChainKey) return;
  if (target < session.recvN) return;
  const distance = target - session.recvN;
  if (distance > MAX_SKIP) throw new Error("ratchet: skipped too far");
  let chain = session.recvChainKey;
  while (session.recvN < target) {
    const { next, msg } = kdfCK(chain);
    chain = next;
    if (session.dhRecvPublic) {
      session.skipped[`${bytesToB64(session.dhRecvPublic)}:${session.recvN}`] = msg;
    }
    session.recvN += 1;
  }
  session.recvChainKey = chain;
  const keys = Object.keys(session.skipped);
  if (keys.length > MAX_SKIP) {
    for (const key of keys.slice(0, keys.length - MAX_SKIP)) delete session.skipped[key];
  }
}

export async function ratchetEncrypt(
  plaintext: Uint8Array,
  session: RatchetSession,
): Promise<Uint8Array> {
  if (!session.sendChainKey) {
    const peer = session.peerIdentityPublic;
    const dh = randomBytes(32);
    const remote = session.dhRecvPublic ?? peer;
    const dhOut = x25519.getSharedSecret(dh, remote);
    const { root, chain } = kdfRK(session.rootKey, dhOut);
    session.rootKey = root;
    session.sendChainKey = chain;
    session.dhSendPrivate = dh;
    session.dhSendPublic = x25519.getPublicKey(dh);
    session.prevChainLength = session.sendN;
    session.sendN = 0;
  }
  if (!session.sendChainKey || !session.dhSendPublic) throw new Error("ratchet: no session");
  const { next, msg } = kdfCK(session.sendChainKey);
  session.sendChainKey = next;
  const combined = await aesGcmSeal(msg, plaintext);
  const message: RatchetMessage = {
    v: RATCHET_VERSION,
    dh: bytesToB64(session.dhSendPublic),
    n: session.sendN,
    pn: session.prevChainLength,
    ct: bytesToB64(combined),
  };
  session.sendN += 1;
  session.touched = true;
  return utf8(JSON.stringify(message));
}

export async function ratchetDecrypt(
  envelopeData: Uint8Array,
  session: RatchetSession,
): Promise<Uint8Array> {
  const message = JSON.parse(utf8decodeSafe(envelopeData)) as RatchetMessage;
  if (message.v !== RATCHET_VERSION) throw new Error("ratchet: bad version");
  const dhData = b64ToBytes(message.dh);
  const ctData = b64ToBytes(message.ct);
  const skipKey = `${bytesToB64(dhData)}:${message.n}`;
  const skipped = session.skipped[skipKey];
  if (skipped) {
    delete session.skipped[skipKey];
    return aesGcmOpen(skipped, ctData);
  }
  if (!session.dhRecvPublic || !bytesEqual(session.dhRecvPublic, dhData)) {
    skipMessageKeys(session, message.pn);
    dhRatchet(session, dhData);
  }
  skipMessageKeys(session, message.n);
  if (!session.recvChainKey) throw new Error("ratchet: no recv chain");
  const { next, msg } = kdfCK(session.recvChainKey);
  session.recvChainKey = next;
  session.recvN += 1;
  session.touched = true;
  return aesGcmOpen(msg, ctData);
}

function utf8decodeSafe(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes);
}

export function serializeSession(session: RatchetSession): string {
  const skip: Record<string, string> = {};
  for (const [k, v] of Object.entries(session.skipped)) skip[k] = bytesToHex(v);
  return JSON.stringify({
    rootKey: bytesToHex(session.rootKey),
    sendChainKey: session.sendChainKey ? bytesToHex(session.sendChainKey) : null,
    recvChainKey: session.recvChainKey ? bytesToHex(session.recvChainKey) : null,
    sendN: session.sendN,
    recvN: session.recvN,
    prevChainLength: session.prevChainLength,
    dhSendPrivate: session.dhSendPrivate ? bytesToHex(session.dhSendPrivate) : null,
    dhSendPublic: session.dhSendPublic ? bytesToHex(session.dhSendPublic) : null,
    dhRecvPublic: session.dhRecvPublic ? bytesToHex(session.dhRecvPublic) : null,
    peerIdentityPublic: bytesToHex(session.peerIdentityPublic),
    touched: session.touched,
    skipped: skip,
  });
}

export function deserializeSession(raw: string): RatchetSession {
  const p = JSON.parse(raw) as Record<string, unknown>;
  const skipIn = (p.skipped ?? {}) as Record<string, string>;
  const skipped: Record<string, Uint8Array> = {};
  for (const [k, v] of Object.entries(skipIn)) skipped[k] = hexToBytes(v);
  const opt = (v: unknown) => (typeof v === "string" && v.length ? hexToBytes(v) : null);
  return {
    rootKey: hexToBytes(p.rootKey as string),
    sendChainKey: opt(p.sendChainKey),
    recvChainKey: opt(p.recvChainKey),
    sendN: p.sendN as number,
    recvN: p.recvN as number,
    prevChainLength: p.prevChainLength as number,
    dhSendPrivate: opt(p.dhSendPrivate),
    dhSendPublic: opt(p.dhSendPublic),
    dhRecvPublic: opt(p.dhRecvPublic),
    peerIdentityPublic: hexToBytes(p.peerIdentityPublic as string),
    touched: Boolean(p.touched),
    skipped,
  };
}

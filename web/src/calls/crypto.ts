import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import type { CallSignalType } from "../api/client";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, sortedConcat, utf8, utf8decode } from "../crypto/bytes";

/*
 * Sealed call signals (docs/calls.md, "Sealed signals"). The server relays each signal without
 * reading it: only the two people in the call can open one, so the server cannot swap the DTLS
 * fingerprint in an SDP. The per-direction key stops a signal being reflected to its sender;
 * the signal type in the additional data stops the server relabelling one.
 */

export type CallRole = "caller" | "callee";

/** The two directions of one call: we seal with `send` and open with `receive`. */
export type CallKeys = { send: CryptoKey; receive: CryptoKey };

const PAYLOAD_PREFIX = "c1.";
const NONCE_BYTES = 12;
const TAG_BYTES = 16;

function buf(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

/** A call id (UUID, any case) as its 16 bytes: the key's salt. */
export function callIdBytes(callId: string): Uint8Array {
  const hex = callId.replace(/-/g, "");
  if (!/^[0-9a-f]{32}$/i.test(hex)) throw new Error("call crypto: not a call id");
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/**
 * The secret one pair of people shares for all their calls, the same on both sides and on each of
 * their devices: X25519 of the identity keys, bound to both public keys (lower one first).
 */
export function deriveCallSecret(
  ourPrivate: Uint8Array,
  ourPublic: Uint8Array,
  peerPublic: Uint8Array,
): Uint8Array {
  const shared = x25519.getSharedSecret(ourPrivate, peerPublic);
  const info = concatBytes(utf8("shroud-call-secret-v1"), sortedConcat(ourPublic, peerPublic));
  const secret = hkdf(sha256, shared, utf8("shroud-call-v1"), info, 32);
  shared.fill(0);
  return secret;
}

/** The raw key one role seals its signals of one call with (exported for the test vector). */
export function signalKeyBytes(secret: Uint8Array, callId: string, role: CallRole): Uint8Array {
  return hkdf(sha256, secret, callIdBytes(callId), utf8(`shroud-call-signal-v1|${role}`), 32);
}

async function importKey(raw: Uint8Array, usage: KeyUsage): Promise<CryptoKey> {
  const key = await crypto.subtle.importKey("raw", buf(raw), "AES-GCM", false, [usage]);
  raw.fill(0);
  return key;
}

/** Both directions of one call, as non-extractable WebCrypto keys. */
export async function callKeys(secret: Uint8Array, callId: string, ourRole: CallRole): Promise<CallKeys> {
  const peerRole: CallRole = ourRole === "caller" ? "callee" : "caller";
  const [send, receive] = await Promise.all([
    importKey(signalKeyBytes(secret, callId, ourRole), "encrypt"),
    importKey(signalKeyBytes(secret, callId, peerRole), "decrypt"),
  ]);
  return { send, receive };
}

function additionalData(callId: string, signalType: CallSignalType): Uint8Array {
  return utf8(`shroud-call-v1|${callId.toLowerCase()}|${signalType}`);
}

/**
 * Seals one signal: `"c1." + base64(nonce ‖ ciphertext ‖ tag)`. `nonce` is for the test vector
 * only; every real signal takes a fresh random one.
 */
export async function sealSignal(
  key: CryptoKey,
  callId: string,
  signalType: CallSignalType,
  plaintext: Record<string, unknown>,
  nonce: Uint8Array = randomBytes(NONCE_BYTES),
): Promise<string> {
  if (nonce.length !== NONCE_BYTES) throw new Error("call crypto: bad nonce");
  const body = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: buf(nonce), additionalData: buf(additionalData(callId, signalType)), tagLength: 128 },
    key,
    buf(utf8(JSON.stringify(plaintext))),
  );
  return PAYLOAD_PREFIX + bytesToB64(concatBytes(nonce, new Uint8Array(body)));
}

/** Opens one signal as a JSON object. Throws when it does not open (a signal to drop). */
export async function openSignal(
  key: CryptoKey,
  callId: string,
  signalType: CallSignalType,
  payload: string,
): Promise<Record<string, unknown>> {
  if (!payload.startsWith(PAYLOAD_PREFIX)) throw new Error("call crypto: unknown payload version");
  let sealed: Uint8Array;
  try {
    sealed = b64ToBytes(payload.slice(PAYLOAD_PREFIX.length));
  } catch {
    throw new Error("call crypto: payload is not base64");
  }
  if (sealed.length < NONCE_BYTES + TAG_BYTES) throw new Error("call crypto: payload too short");
  const opened = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: buf(sealed.subarray(0, NONCE_BYTES)),
      additionalData: buf(additionalData(callId, signalType)),
      tagLength: 128,
    },
    key,
    buf(sealed.subarray(NONCE_BYTES)),
  );
  const value: unknown = JSON.parse(utf8decode(new Uint8Array(opened)));
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("call crypto: plaintext is not an object");
  }
  return value as Record<string, unknown>;
}

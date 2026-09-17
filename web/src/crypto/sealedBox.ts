import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { aesGcmOpen, aesGcmSeal } from "./aes";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, utf8 } from "./bytes";

export type SealedBox = { ek: string; ct: string };

const SALT = utf8("shroud-v1");
const INFO_PREFIX = utf8("shroud-msg-v1");

function deriveMessageKey(
  shared: Uint8Array,
  ephemeralPublic: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Uint8Array {
  const info = concatBytes(INFO_PREFIX, ephemeralPublic, senderIdentityPublic, recipientIdentityPublic);
  return hkdf(sha256, shared, SALT, info, 32);
}

export async function sealBox(
  plaintext: Uint8Array,
  recipientPublic: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Promise<SealedBox> {
  const ephemeral = randomBytes(32);
  const ek = x25519.getPublicKey(ephemeral);
  const shared = x25519.getSharedSecret(ephemeral, recipientPublic);
  const key = deriveMessageKey(shared, ek, senderIdentityPublic, recipientIdentityPublic);
  const combined = await aesGcmSeal(key, plaintext);
  return { ek: bytesToB64(ek), ct: bytesToB64(combined) };
}

export async function openBox(
  box: SealedBox,
  ourPrivate: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Promise<Uint8Array> {
  const ek = b64ToBytes(box.ek);
  const combined = b64ToBytes(box.ct);
  const shared = x25519.getSharedSecret(ourPrivate, ek);
  const key = deriveMessageKey(shared, ek, senderIdentityPublic, recipientIdentityPublic);
  return aesGcmOpen(key, combined);
}

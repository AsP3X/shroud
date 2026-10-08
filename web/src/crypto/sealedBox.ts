import { x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { hmac } from "@noble/hashes/hmac.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { aesGcmOpen, aesGcmSeal } from "./aes";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, utf8 } from "./bytes";

/**
 * One identity box: ephemeral ECDH to the recipient, AES-GCM, plus `t`, the sender tag.
 *
 * The box key comes from ECDH(ephemeral, recipient) alone, so anyone holding the two public
 * identity keys can build a box that opens. `t` is what says who sent it: an HMAC over the
 * box under a key from the static ECDH of the two identity keys, which only the sender and
 * the recipient can compute. A box without `t` (builds from before the tag sealed them) is
 * never opened: nothing says who built it.
 */
export type SealedBox = { ek: string; ct: string; t?: string };

const SALT = utf8("shroud-v1");
const INFO_PREFIX = utf8("shroud-msg-v1");
const AUTH_SALT = utf8("shroud-box-auth-v1");
const AUTH_INFO_PREFIX = utf8("shroud-box-auth-v1");
const TAG_LABEL = utf8("shroud-box-tag-v1");

function deriveMessageKey(
  shared: Uint8Array,
  ephemeralPublic: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Uint8Array {
  const info = concatBytes(INFO_PREFIX, ephemeralPublic, senderIdentityPublic, recipientIdentityPublic);
  return hkdf(sha256, shared, SALT, info, 32);
}

/**
 * ECDH(sender identity, recipient identity) is the same from either end. The ordered public
 * keys in the info make the A→B key differ from B→A, so a box cannot be reflected.
 */
function boxTag(
  ourPrivate: Uint8Array,
  theirIdentityPublic: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
  ek: Uint8Array,
  ct: Uint8Array,
): Uint8Array {
  const staticShared = x25519.getSharedSecret(ourPrivate, theirIdentityPublic);
  const info = concatBytes(AUTH_INFO_PREFIX, senderIdentityPublic, recipientIdentityPublic);
  const key = hkdf(sha256, staticShared, AUTH_SALT, info, 32);
  return hmac(sha256, key, concatBytes(TAG_LABEL, ek, ct));
}

function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export async function sealBox(
  plaintext: Uint8Array,
  senderIdentityPrivate: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Promise<SealedBox> {
  const ephemeral = randomBytes(32);
  const ek = x25519.getPublicKey(ephemeral);
  const shared = x25519.getSharedSecret(ephemeral, recipientIdentityPublic);
  const key = deriveMessageKey(shared, ek, senderIdentityPublic, recipientIdentityPublic);
  const combined = await aesGcmSeal(key, plaintext);
  const tag = boxTag(
    senderIdentityPrivate,
    recipientIdentityPublic,
    senderIdentityPublic,
    recipientIdentityPublic,
    ek,
    combined,
  );
  return { ek: bytesToB64(ek), ct: bytesToB64(combined), t: bytesToB64(tag) };
}

/** Throws unless the box carries a tag that proves `senderIdentityPublic` sealed it. */
function verifyBoxTag(
  box: SealedBox,
  ourPrivate: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): void {
  if (box.t == null) throw new Error("box: no sender tag");
  const expected = boxTag(
    ourPrivate,
    senderIdentityPublic,
    senderIdentityPublic,
    recipientIdentityPublic,
    b64ToBytes(box.ek),
    b64ToBytes(box.ct),
  );
  if (!constantTimeEqual(expected, b64ToBytes(box.t))) throw new Error("box: sender tag mismatch");
}

/** Opens a box `senderIdentityPublic` sealed; throws for an untagged box or a tag that fails. */
export async function openBox(
  box: SealedBox,
  ourPrivate: Uint8Array,
  senderIdentityPublic: Uint8Array,
  recipientIdentityPublic: Uint8Array,
): Promise<Uint8Array> {
  verifyBoxTag(box, ourPrivate, senderIdentityPublic, recipientIdentityPublic);
  const ek = b64ToBytes(box.ek);
  const combined = b64ToBytes(box.ct);
  const shared = x25519.getSharedSecret(ourPrivate, ek);
  const key = deriveMessageKey(shared, ek, senderIdentityPublic, recipientIdentityPublic);
  return aesGcmOpen(key, combined);
}

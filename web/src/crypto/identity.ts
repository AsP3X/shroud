import { ed25519, x25519 } from "@noble/curves/ed25519.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { mnemonicToSeed } from "./bip39";
import { bytesToB64, bytesToHex, hexToBytes, randomBytes, randomU32 } from "./bytes";

const SALT = new TextEncoder().encode("shroud-v1");

export type OneTimePreKey = { keyId: number; privateKey: Uint8Array; publicKey: Uint8Array };

export type IdentityMaterial = {
  userId: string;
  registrationId: number;
  agreementPrivate: Uint8Array;
  agreementPublic: Uint8Array;
  signingPrivate: Uint8Array;
  signingPublic: Uint8Array;
  historyKey: Uint8Array;
  signedPreKeyId: number;
  signedPreKeyPrivate: Uint8Array;
  signedPreKeyPublic: Uint8Array;
  signedPreKeySignature: Uint8Array;
  oneTimePreKeys: OneTimePreKey[];
};

export type PutKeyBundleRequest = {
  registration_id: number;
  identity_key: string;
  signed_pre_key: { key_id: number; public_key: string; signature: string };
  one_time_pre_keys: { key_id: number; public_key: string }[];
};

function hkdfShroud(seed: Uint8Array, info: string, length: number): Uint8Array {
  return hkdf(sha256, seed, SALT, new TextEncoder().encode(info), length);
}

export function establish(
  mnemonicWords: string[],
  userId: string,
  oneTimePreKeyCount = 100,
): IdentityMaterial {
  const seed = mnemonicToSeed(mnemonicWords);
  const agreementPrivate = hkdfShroud(seed, "shroud-identity-x25519", 32);
  const signingPrivate = hkdfShroud(seed, "shroud-identity-ed25519", 32);
  const historyKey = hkdfShroud(seed, "shroud-history-aes", 32);
  const regRaw = hkdfShroud(seed, "shroud-registration-id", 4);
  const registrationId =
    (((regRaw[0] << 24) | (regRaw[1] << 16) | (regRaw[2] << 8) | regRaw[3]) >>> 0) % 16384;

  const signedPreKeyPrivate = randomBytes(32);
  const signedPreKeyPublic = x25519.getPublicKey(signedPreKeyPrivate);
  const signedPreKeyId = (randomU32() % 0xff_ffff) + 1;
  const signedPreKeySignature = ed25519.sign(signedPreKeyPublic, signingPrivate);

  const oneTimePreKeys: OneTimePreKey[] = [];
  for (let i = 0; i < oneTimePreKeyCount; i++) {
    const privateKey = randomBytes(32);
    oneTimePreKeys.push({
      keyId: i + 1,
      privateKey,
      publicKey: x25519.getPublicKey(privateKey),
    });
  }

  return {
    userId,
    registrationId,
    agreementPrivate,
    agreementPublic: x25519.getPublicKey(agreementPrivate),
    signingPrivate,
    signingPublic: ed25519.getPublicKey(signingPrivate),
    historyKey,
    signedPreKeyId,
    signedPreKeyPrivate,
    signedPreKeyPublic,
    signedPreKeySignature,
    oneTimePreKeys,
  };
}

/** The phrase's history key alone — what opens the vault after a forgotten PIN (vault.ts). */
export function historyKeyFromMnemonic(mnemonicWords: string[]): Uint8Array {
  return hkdfShroud(mnemonicToSeed(mnemonicWords), "shroud-history-aes", 32);
}

/** The phrase's X25519 identity public key alone — what the account publishes (`identity_key`). */
export function identityKeyFromMnemonic(mnemonicWords: string[]): Uint8Array {
  return x25519.getPublicKey(hkdfShroud(mnemonicToSeed(mnemonicWords), "shroud-identity-x25519", 32));
}

export function matchesMnemonic(material: IdentityMaterial, words: string[]): boolean {
  try {
    return bytesToHex(identityKeyFromMnemonic(words)) === bytesToHex(material.agreementPublic);
  } catch {
    return false;
  }
}

export function putBundleRequest(material: IdentityMaterial): PutKeyBundleRequest {
  return {
    registration_id: material.registrationId,
    identity_key: bytesToB64(material.agreementPublic),
    signed_pre_key: {
      key_id: material.signedPreKeyId,
      public_key: bytesToB64(material.signedPreKeyPublic),
      signature: bytesToB64(material.signedPreKeySignature),
    },
    one_time_pre_keys: material.oneTimePreKeys.map((key) => ({
      key_id: key.keyId,
      public_key: bytesToB64(key.publicKey),
    })),
  };
}

export function serializeIdentity(material: IdentityMaterial): string {
  return JSON.stringify({
    userId: material.userId,
    registrationId: material.registrationId,
    agreementPrivate: bytesToHex(material.agreementPrivate),
    signingPrivate: bytesToHex(material.signingPrivate),
    historyKey: bytesToHex(material.historyKey),
    signedPreKeyId: material.signedPreKeyId,
    signedPreKeyPrivate: bytesToHex(material.signedPreKeyPrivate),
    signedPreKeySignature: bytesToHex(material.signedPreKeySignature),
    oneTimePreKeys: material.oneTimePreKeys.map((k) => ({
      keyId: k.keyId,
      privateKey: bytesToHex(k.privateKey),
    })),
  });
}

export function deserializeIdentity(raw: string): IdentityMaterial {
  const parsed = JSON.parse(raw) as {
    userId: string;
    registrationId: number;
    agreementPrivate: string;
    signingPrivate: string;
    historyKey: string;
    signedPreKeyId: number;
    signedPreKeyPrivate: string;
    signedPreKeySignature: string;
    oneTimePreKeys: { keyId: number; privateKey: string }[];
  };
  const agreementPrivate = hexToBytes(parsed.agreementPrivate);
  const signingPrivate = hexToBytes(parsed.signingPrivate);
  const signedPreKeyPrivate = hexToBytes(parsed.signedPreKeyPrivate);
  return {
    userId: parsed.userId,
    registrationId: parsed.registrationId,
    agreementPrivate,
    agreementPublic: x25519.getPublicKey(agreementPrivate),
    signingPrivate,
    signingPublic: ed25519.getPublicKey(signingPrivate),
    historyKey: hexToBytes(parsed.historyKey),
    signedPreKeyId: parsed.signedPreKeyId,
    signedPreKeyPrivate,
    signedPreKeyPublic: x25519.getPublicKey(signedPreKeyPrivate),
    signedPreKeySignature: hexToBytes(parsed.signedPreKeySignature),
    oneTimePreKeys: parsed.oneTimePreKeys.map((k) => {
      const privateKey = hexToBytes(k.privateKey);
      return { keyId: k.keyId, privateKey, publicKey: x25519.getPublicKey(privateKey) };
    }),
  };
}

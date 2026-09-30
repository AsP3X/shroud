import { randomBytes } from "./bytes";

function buf(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

async function importAes(key: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", buf(key), "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** CryptoKit AES.GCM.combined: 12-byte nonce ‖ ciphertext ‖ 16-byte tag. */
export async function aesGcmSeal(key: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
  const nonce = randomBytes(12);
  const cryptoKey = await importAes(key);
  const bufOut = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: buf(nonce), tagLength: 128 },
    cryptoKey,
    buf(plaintext),
  );
  const body = new Uint8Array(bufOut);
  const out = new Uint8Array(12 + body.length);
  out.set(nonce, 0);
  out.set(body, 12);
  return out;
}

export async function sealFile(plaintext: Uint8Array): Promise<{ key: Uint8Array; sealed: Uint8Array }> {
  const key = randomBytes(32);
  const sealed = await aesGcmSeal(key, plaintext);
  return { key, sealed };
}

export async function aesGcmOpen(key: Uint8Array, combined: Uint8Array): Promise<Uint8Array> {
  if (combined.length < 12 + 16) throw new Error("aes-gcm: ciphertext too short");
  const nonce = combined.slice(0, 12);
  const body = combined.slice(12);
  const cryptoKey = await importAes(key);
  const opened = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: buf(nonce), tagLength: 128 },
    cryptoKey,
    buf(body),
  );
  return new Uint8Array(opened);
}

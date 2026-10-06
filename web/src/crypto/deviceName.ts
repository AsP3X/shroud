import { gcm } from "@noble/ciphers/aes.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { b64ToBytes, bytesToB64, concatBytes, randomBytes, utf8 } from "./bytes";

/*
 * Sealed device names (docs/architecture.md#sealed-device-names), the same bytes as iOS
 * `DeviceNameSeal`.
 *
 * The server keeps a device's name only as `devices.sealed_name`. The key comes from the
 * phrase's history key, so every device of the account opens every other device's name and
 * nobody else can. The device id is the associated data: a stored name cannot be moved onto
 * another device. The name is padded to one fixed size, so its length does not show either.
 * The kind rides inside the seal too, so the list can pick an icon for a name like "Work laptop".
 *
 *   key       = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-device-name-v1", 32)
 *   aad       = "shroud-device-name-v1:" + lowercase device id
 *   plaintext = kind(1) ‖ UTF-8 name ‖ 0x80 ‖ 0x00… to 128 bytes
 *   kind      = 1 iPhone app, 2 iPad app, 3 web browser, 4 Android app, 0 anything else;
 *               | 0x80 when a person typed the name (an iPhone or Android phone then stops
 *               putting its own name back)
 *   sealed    = nonce(12) ‖ AES-256-GCM ciphertext(128) ‖ tag(16), sent as standard Base64
 *
 * Kind 4 is the Android app's (port plan decision P4; the same byte as iOS
 * `DeviceNameSeal.Kind.android` and the Android app's `DeviceNameSeal.Kind.Android`).
 * A build without it reads 4 as "other", and a rename from such a build writes "other" back;
 * the Android app repairs that kind on its next unlock. Reading the kind here keeps a rename
 * from this browser from dropping it.
 */

const LABEL = "shroud-device-name-v1";
const SALT = utf8("shroud-v1");
const NONCE_BYTES = 12;
const PADDED_BYTES = 128;
/** Longest name in UTF-8 bytes; the kind and the padding need two bytes more. */
export const DEVICE_NAME_MAX_BYTES = 96;

export type DeviceKind = "iphone" | "ipad" | "web" | "android" | "other";
const KIND_BYTES: Record<DeviceKind, number> = { other: 0, iphone: 1, ipad: 2, web: 3, android: 4 };

/** `custom`: a person chose this name, rather than the device naming itself. */
export type DeviceLabel = { name: string; kind: DeviceKind; custom?: boolean };
const CUSTOM_BIT = 0x80;

function nameKey(historyKey: Uint8Array): Uint8Array {
  return hkdf(sha256, historyKey, SALT, utf8(LABEL), 32);
}

function associatedData(deviceId: string): Uint8Array {
  return utf8(`${LABEL}:${deviceId.toLowerCase()}`);
}

/**
 * One line, no control or bidi characters, at most `DEVICE_NAME_MAX_BYTES` bytes cut between
 * characters (an emoji is never split). Empty when nothing is left.
 */
export function normalizeDeviceName(raw: string): string {
  const oneLine = raw
    // Controls, line breaks and bidi overrides (a name must not reorder the text around it);
    // joiners stay so an emoji sequence survives.
    .replace(/[\p{Cc}\u2028\u2029\u200E\u200F\u202A-\u202E\u2066-\u2069]+/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
  const encoder = new TextEncoder();
  if (encoder.encode(oneLine).length <= DEVICE_NAME_MAX_BYTES) return oneLine;
  const segments =
    typeof Intl !== "undefined" && "Segmenter" in Intl
      ? Array.from(new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(oneLine), (s) => s.segment)
      : Array.from(oneLine);
  let out = "";
  let bytes = 0;
  for (const segment of segments) {
    const size = encoder.encode(segment).length;
    if (bytes + size > DEVICE_NAME_MAX_BYTES) break;
    out += segment;
    bytes += size;
  }
  return out.trim();
}

/** What a list shows for a device: its name, or "Unnamed device" when it has none or it won't open. */
export function deviceDisplayName(label: DeviceLabel | null): string {
  return label?.name.trim() || "Unnamed device";
}

/** `nonce` is for test vectors only; leave it out. */
export function sealDeviceName(
  historyKey: Uint8Array,
  deviceId: string,
  label: DeviceLabel,
  nonce: Uint8Array = randomBytes(NONCE_BYTES),
): string {
  const text = normalizeDeviceName(label.name);
  if (!text) throw new Error("device name is empty");
  const padded = new Uint8Array(PADDED_BYTES);
  const bytes = utf8(text);
  padded[0] = KIND_BYTES[label.kind] | (label.custom ? CUSTOM_BIT : 0);
  padded.set(bytes, 1);
  padded[1 + bytes.length] = 0x80;
  const sealed = gcm(nameKey(historyKey), nonce, associatedData(deviceId)).encrypt(padded);
  return bytesToB64(concatBytes(nonce, sealed));
}

/** Null when the name does not open: another account's key, another device, or damaged. */
export function openDeviceName(
  historyKey: Uint8Array,
  deviceId: string,
  sealedB64: string | null | undefined,
): DeviceLabel | null {
  if (!sealedB64) return null;
  try {
    const combined = b64ToBytes(sealedB64);
    if (combined.length !== NONCE_BYTES + PADDED_BYTES + 16) return null;
    const padded = gcm(
      nameKey(historyKey),
      combined.slice(0, NONCE_BYTES),
      associatedData(deviceId),
    ).decrypt(combined.slice(NONCE_BYTES));
    let end = padded.length - 1;
    while (end >= 0 && padded[end] === 0) end--;
    if (end < 1 || padded[end] !== 0x80) return null;
    const name = new TextDecoder("utf-8", { fatal: true }).decode(padded.slice(1, end));
    if (!name) return null;
    const kindByte = padded[0] & ~CUSTOM_BIT;
    const kind =
      (Object.keys(KIND_BYTES) as DeviceKind[]).find((k) => KIND_BYTES[k] === kindByte) ?? "other";
    return { name, kind, custom: (padded[0] & CUSTOM_BIT) !== 0 };
  } catch {
    return null;
  }
}

/**
 * Sealed device names: the golden vectors iOS `DeviceNameSealTests` and the Android
 * `DeviceNameSealTest` check too, the round trip, and the refusals (another device id, another
 * account's key, tampering).
 * Run: `npx tsx src/crypto/deviceName.selftest.ts`, or bundled: `npx esbuild
 * src/crypto/deviceName.selftest.ts --bundle --platform=node --format=esm | node --input-type=module`.
 */
import { gcm } from "@noble/ciphers/aes.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { b64ToBytes, bytesToB64, bytesToHex, concatBytes, utf8 } from "./bytes";
import { DEVICE_NAME_MAX_BYTES, normalizeDeviceName, openDeviceName, sealDeviceName } from "./deviceName";

function check(condition: boolean, what: string) {
  if (!condition) throw new Error(`device name selftest: ${what}`);
}

const historyKey = Uint8Array.from({ length: 32 }, (_, i) => i);
const otherKey = Uint8Array.from({ length: 32 }, (_, i) => 255 - i);
const deviceId = "0F8FAD5B-D9CB-469F-A165-70867728950E";
const otherDevice = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const nonce = Uint8Array.from({ length: 12 }, (_, i) => 0xa0 + i);

// Shared with ios/shroudTests/DeviceNameSealTests.swift. Change both or neither.
const GOLDEN_LABEL = { name: "Küchen-iPad ✨", kind: "ipad" } as const;
const GOLDEN =
  "oKGio6SlpqeoqaqrdjG5oKGAC3ucvlABL0bwNia0p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X/DkcQzpugtYldAYowYWm3N";

// A rename: the kind byte carries the "typed by a person" bit.
const GOLDEN_RENAMED_LABEL = { name: "Office iPhone", kind: "iphone", custom: true } as const;
const GOLDEN_RENAMED =
  "oKGio6Slpqeoqaqr9TUcequLCzXYh2gPJQOSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X+l2LSRw4GSvCdsnGiSSnet";

// Kind byte 4, the Android app (port plan decision P4). The Android client seals its own name
// with it; the same vector is in its `DeviceNameSealTest` and in iOS `DeviceNameSealTests`.
const GOLDEN_ANDROID_LABEL = { name: "Pixel 9 Pro", kind: "android" } as const;
const GOLDEN_ANDROID =
  "oKGio6SlpqeoqaqrcCoTZKeETiyRh3IPy2YSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X91Wb6trMGRUJzqvlJCgop+";

const golden = sealDeviceName(historyKey, deviceId, GOLDEN_LABEL, nonce);
if (process.argv.includes("--print")) console.log(golden);
check(golden === GOLDEN, `golden vector changed: ${golden}`);
check(b64ToBytes(golden).length === 156, "sealed size is fixed");
const opened = openDeviceName(historyKey, deviceId, GOLDEN);
check(opened?.name === GOLDEN_LABEL.name && opened.kind === "ipad" && !opened.custom, "golden opens");
check(openDeviceName(historyKey, deviceId.toLowerCase(), GOLDEN)?.name === GOLDEN_LABEL.name, "id case does not matter");

// Every name seals to the same size, whatever its length.
const short = sealDeviceName(historyKey, deviceId, { name: "Mac", kind: "web" });
const long = sealDeviceName(historyKey, deviceId, { name: "x".repeat(300), kind: "other" });
check(b64ToBytes(short).length === b64ToBytes(long).length, "length hidden");
const longOpened = openDeviceName(historyKey, deviceId, long);
check(longOpened?.name === "x".repeat(DEVICE_NAME_MAX_BYTES) && longOpened.kind === "other", "long name cut to the limit");
check(openDeviceName(historyKey, deviceId, short)?.kind === "web", "kind round-trips");
check(sealDeviceName(historyKey, deviceId, GOLDEN_RENAMED_LABEL, nonce) === GOLDEN_RENAMED, "renamed golden changed");
const renamed = openDeviceName(historyKey, deviceId, GOLDEN_RENAMED);
check(renamed?.name === "Office iPhone" && renamed.kind === "iphone" && renamed.custom === true, "custom flag round-trips");
check(sealDeviceName(historyKey, deviceId, { name: "Mac", kind: "web" }) !== short, "fresh nonce per seal");

// The Android app's kind: its golden vector, the byte itself, and a rename keeping it.
const android = sealDeviceName(historyKey, deviceId, GOLDEN_ANDROID_LABEL, nonce);
check(android === GOLDEN_ANDROID, `android golden changed: ${android}`);
const androidOpened = openDeviceName(historyKey, deviceId, GOLDEN_ANDROID);
check(
  androidOpened?.name === "Pixel 9 Pro" && androidOpened.kind === "android" && !androidOpened.custom,
  "android golden opens as the Android app",
);
const nameKey = hkdf(sha256, historyKey, utf8("shroud-v1"), utf8("shroud-device-name-v1"), 32);
// The name key every client derives from this history key (Android port crypto spec §16.3).
check(
  bytesToHex(nameKey) === "aaf7dc6f151ce2c56310f3621370a17c3bdf6a584790bd4ad8275905098e7dd8",
  "name key changed",
);
const aad = utf8(`shroud-device-name-v1:${deviceId.toLowerCase()}`);
const androidPadded = gcm(nameKey, nonce, aad).decrypt(b64ToBytes(GOLDEN_ANDROID).slice(12));
check(androidPadded[0] === 0x04, "the Android app is kind byte 4");
const androidRenamed = openDeviceName(
  historyKey,
  deviceId,
  sealDeviceName(historyKey, deviceId, { name: "Work phone", kind: "android", custom: true }),
);
check(
  androidRenamed?.name === "Work phone" && androidRenamed.kind === "android" && androidRenamed.custom === true,
  "a rename keeps the Android kind",
);

// A kind this build does not know yet reads as "other", the name intact (iOS `DeviceNameSeal.swift:82`).
const unknownPadded = new Uint8Array(128);
const unknownName = utf8("Future device");
unknownPadded[0] = 0x05 | 0x80;
unknownPadded.set(unknownName, 1);
unknownPadded[1 + unknownName.length] = 0x80;
const unknown = bytesToB64(concatBytes(nonce, gcm(nameKey, nonce, aad).encrypt(unknownPadded)));
const unknownOpened = openDeviceName(historyKey, deviceId, unknown);
check(
  unknownOpened?.name === "Future device" && unknownOpened.kind === "other" && unknownOpened.custom === true,
  "an unknown kind reads as other",
);

check(openDeviceName(historyKey, otherDevice, short) === null, "bound to its device");
check(openDeviceName(otherKey, deviceId, short) === null, "only the account opens it");
const tampered = b64ToBytes(short);
tampered[20] ^= 1;
check(openDeviceName(historyKey, deviceId, bytesToB64(tampered)) === null, "tampering refused");
check(openDeviceName(historyKey, deviceId, null) === null, "absent is null");
check(openDeviceName(historyKey, deviceId, "not base64") === null, "junk is null");

check(normalizeDeviceName("  Work\n\tlaptop \u0007 ") === "Work laptop", "one line, trimmed");
check(normalizeDeviceName("🌙".repeat(40)) === "🌙".repeat(24), "cut between characters");
check(normalizeDeviceName(" \n ") === "", "blank is empty");
check(normalizeDeviceName("Mac\u202Egnp.exe") === "Mac gnp.exe", "bidi override dropped");
check(normalizeDeviceName("👩\u200D💻 laptop") === "👩\u200D💻 laptop", "emoji joiner kept");
let threw = false;
try {
  sealDeviceName(historyKey, deviceId, { name: "   ", kind: "web" });
} catch {
  threw = true;
}
check(threw, "blank name refused");

console.log("device name selftest ok");

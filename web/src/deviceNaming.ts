import { api } from "./api/client";
import { openDeviceName, sealDeviceName, type DeviceLabel } from "./crypto/deviceName";

/**
 * This browser's name in the account's device list. Right after a login or sign-up the app asks
 * for it (`DeviceNameDialog`); it reaches the server only sealed (`crypto/deviceName.ts`), so
 * only the account's devices can read it.
 *
 * The flag lives in memory only: a reload between login and the app loses it, and then the app
 * still asks when this browser has no name the account can read.
 */
let freshSignIn = false;

/** A login or sign-up just finished; the app asks for this browser's name when it opens. */
export function markFreshSignIn(): void {
  freshSignIn = true;
}

export function isFreshSignIn(): boolean {
  return freshSignIn;
}

export function clearFreshSignIn(): void {
  freshSignIn = false;
}

/** A starting point for the name: "Safari on iPhone", "Chrome on Mac"… */
export function suggestedDeviceName(): string {
  if (typeof navigator === "undefined") return "Browser";
  const ua = navigator.userAgent;
  // iPadOS asks for desktop sites and says "Macintosh"; only the touch screen gives it away.
  const touchMac = /Macintosh/.test(ua) && navigator.maxTouchPoints > 1;
  const browser = /EdgiOS|EdgA\/|Edg\//.test(ua)
    ? "Edge"
    : /OPiOS|OPR\//.test(ua)
      ? "Opera"
      : /SamsungBrowser/.test(ua)
        ? "Samsung Internet"
        : /FxiOS|Firefox\//.test(ua)
          ? "Firefox"
          : /CriOS|Chrome\//.test(ua)
            ? "Chrome"
            : /Safari\//.test(ua)
              ? "Safari"
              : "Browser";
  // Phones first: an iPhone says "like Mac OS X" and Android says "Linux".
  const os = /iPhone|iPod/.test(ua)
    ? "iPhone"
    : /iPad/.test(ua) || touchMac
      ? "iPad"
      : /Android/.test(ua)
        ? "Android"
        : /CrOS/.test(ua)
          ? "ChromeOS"
          : /Mac OS X|Macintosh/.test(ua)
            ? "Mac"
            : /Windows/.test(ua)
              ? "Windows"
              : /Linux/.test(ua)
                ? "Linux"
                : null;
  return os ? `${browser} on ${os}` : browser;
}

/** This browser's current name, or null when it has none the account can read. */
export async function currentDeviceLabel(
  token: string,
  deviceId: string,
  historyKey: Uint8Array,
): Promise<DeviceLabel | null> {
  const me = await api.me(token);
  return openDeviceName(historyKey, deviceId, me.device.sealed_name);
}

/**
 * Seals `label` for `deviceId` and stores it. Any of the account's devices may name any other;
 * a name a person typed is marked `custom`, so an iPhone keeps it instead of its own name.
 */
export async function saveDeviceName(
  token: string,
  deviceId: string,
  historyKey: Uint8Array,
  label: DeviceLabel,
): Promise<void> {
  await api.putDeviceName(token, deviceId, sealDeviceName(historyKey, deviceId, label));
}

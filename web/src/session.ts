import type { Session } from "./api/client";

const TOKEN_KEY = "shroud.session";
const DEVICE_KEY = "shroud.device-anchor";
const LOCKED_KEY = "shroud.locked";
const PIN_KEY_PREFIX = "shroud.pin.";
const IDLE_MS = 5 * 60 * 1000;
/** Delay before a hidden tab locks, so reload/navigation does not demand a PIN. */
const HIDE_LOCK_MS = 15_000;

// A reload fires visibilitychange(hidden) on the outgoing document, which used to
// persist shroud.locked into the next page. A new document should start unlocked.
if (typeof sessionStorage !== "undefined") {
  sessionStorage.removeItem(LOCKED_KEY);
}

export type DeviceAnchor = { username: string; deviceId: string };

type PinRecord = { salt: string; hash: string };

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as Session;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(session));
  saveDeviceAnchor({ username: session.user.username, deviceId: session.device.id });
  setLocked(false);
}

export function clearSession(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(LOCKED_KEY);
}

export function loadDeviceAnchor(username: string): string | null {
  try {
    const raw = localStorage.getItem(DEVICE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as DeviceAnchor;
    if (parsed.username.toLowerCase() === username.trim().toLowerCase()) return parsed.deviceId;
    return null;
  } catch {
    return null;
  }
}

export function saveDeviceAnchor(anchor: DeviceAnchor): void {
  localStorage.setItem(
    DEVICE_KEY,
    JSON.stringify({
      username: anchor.username.trim().toLowerCase(),
      deviceId: anchor.deviceId,
    }),
  );
}

export function isLocked(): boolean {
  return sessionStorage.getItem(LOCKED_KEY) === "1";
}

export function setLocked(locked: boolean): void {
  if (locked) sessionStorage.setItem(LOCKED_KEY, "1");
  else sessionStorage.removeItem(LOCKED_KEY);
}

function pinKey(userId: string): string {
  return PIN_KEY_PREFIX + userId.toLowerCase();
}

export function hasPin(userId: string): boolean {
  return readPinRecord(userId) !== null;
}

function readPinRecord(userId: string): PinRecord | null {
  try {
    const raw = localStorage.getItem(pinKey(userId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PinRecord;
    if (!parsed.salt || !parsed.hash) return null;
    return parsed;
  } catch {
    return null;
  }
}

async function sha256Hex(value: string): Promise<string> {
  const data = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function newSalt(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function pinsEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function setPin(userId: string, pin: string): Promise<void> {
  const salt = newSalt();
  const hash = await sha256Hex(`${salt}:${pin}`);
  localStorage.setItem(pinKey(userId), JSON.stringify({ salt, hash }));
}

export async function verifyPin(userId: string, pin: string): Promise<boolean> {
  const record = readPinRecord(userId);
  if (!record) return false;
  const hash = await sha256Hex(`${record.salt}:${pin}`);
  return pinsEqual(hash, record.hash);
}

/** Idle + hidden-tab lock. Returns a disposer. */
export function installAutoLock(onLock: () => void): () => void {
  let timer = window.setTimeout(lock, IDLE_MS);
  let hideTimer = 0;
  let unloading = false;

  function lock() {
    if (!loadSession() || isLocked()) return;
    setLocked(true);
    onLock();
  }

  function bump() {
    if (isLocked()) return;
    window.clearTimeout(timer);
    window.clearTimeout(hideTimer);
    timer = window.setTimeout(lock, IDLE_MS);
  }

  function onVisibility() {
    if (document.hidden) {
      if (unloading) return;
      window.clearTimeout(hideTimer);
      hideTimer = window.setTimeout(lock, HIDE_LOCK_MS);
    } else {
      bump();
    }
  }

  function onPageHide() {
    unloading = true;
    window.clearTimeout(hideTimer);
  }

  function onPageShow() {
    unloading = false;
    if (!document.hidden) bump();
  }

  if (document.hidden) {
    hideTimer = window.setTimeout(lock, HIDE_LOCK_MS);
  }

  const events = ["pointerdown", "keydown"] as const;
  for (const ev of events) window.addEventListener(ev, bump);
  document.addEventListener("visibilitychange", onVisibility);
  window.addEventListener("pagehide", onPageHide);
  window.addEventListener("pageshow", onPageShow);
  return () => {
    window.clearTimeout(timer);
    window.clearTimeout(hideTimer);
    for (const ev of events) window.removeEventListener(ev, bump);
    document.removeEventListener("visibilitychange", onVisibility);
    window.removeEventListener("pagehide", onPageHide);
    window.removeEventListener("pageshow", onPageShow);
  };
}

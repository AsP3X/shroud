import type { Session } from "./api/client";

const TOKEN_KEY = "shroud.session";
const DEVICE_KEY = "shroud.device-anchor";
const LOCKED_KEY = "shroud.locked";
const TAB_LIVE_KEY = "shroud.tab-live";
const LAST_ACTIVE_KEY = "shroud.last-active";
const PIN_KEY_PREFIX = "shroud.pin.";
const IDLE_MS = 5 * 60 * 1000;
/** Delay before a hidden tab locks, so reload/navigation does not demand a PIN. */
const HIDE_LOCK_MS = 15_000;
/** Away this long → PIN on the next visit (not a full login). */
const PIN_AFTER_MS = 12 * 60 * 60 * 1000;
/** No activity this long → drop the token; next visit is a full login. */
const LOGOUT_AFTER_MS = 14 * 24 * 60 * 60 * 1000;

/**
 * Session token lives in localStorage (survives tab close).
 * Closing a tab is not a logout. A reload of the same tab stays unlocked.
 * Returning after 12h asks for the PIN. 14 days idle clears the session.
 */
function expireIfStale(): boolean {
  if (!localStorage.getItem(TOKEN_KEY)) return false;
  if (Date.now() - readLastActive() < LOGOUT_AFTER_MS) return false;
  clearSession();
  return true;
}

function gateSessionLock() {
  if (typeof localStorage === "undefined" || typeof sessionStorage === "undefined") return;
  const ephemeral = sessionStorage.getItem(TOKEN_KEY);
  if (ephemeral && !localStorage.getItem(TOKEN_KEY)) {
    localStorage.setItem(TOKEN_KEY, ephemeral);
  }
  sessionStorage.removeItem(TOKEN_KEY);

  if (expireIfStale() || !localStorage.getItem(TOKEN_KEY)) {
    sessionStorage.removeItem(LOCKED_KEY);
    sessionStorage.removeItem(TAB_LIVE_KEY);
    return;
  }

  const idleFor = Date.now() - readLastActive();
  const sameTab = sessionStorage.getItem(TAB_LIVE_KEY) === "1";
  if (idleFor >= PIN_AFTER_MS) {
    sessionStorage.setItem(LOCKED_KEY, "1");
  } else if (!sameTab) {
    sessionStorage.removeItem(LOCKED_KEY);
  }
  // same-tab reload: keep LOCKED if the idle timer already locked this document
  sessionStorage.setItem(TAB_LIVE_KEY, "1");
}

function readLastActive(): number {
  const raw = localStorage.getItem(LAST_ACTIVE_KEY);
  const parsed = raw ? Number(raw) : NaN;
  const now = Date.now();
  if (Number.isFinite(parsed) && parsed <= now + 60_000) return parsed;
  try {
    localStorage.setItem(LAST_ACTIVE_KEY, String(now));
  } catch {
    /* quota */
  }
  return now;
}

let lastTouchWrite = 0;

export function touchLastActive(force = false): void {
  const now = Date.now();
  if (!force && now - lastTouchWrite < 30_000 && lastTouchWrite !== 0) return;
  lastTouchWrite = now;
  try {
    localStorage.setItem(LAST_ACTIVE_KEY, String(now));
  } catch {
    /* quota */
  }
}

gateSessionLock();

export type DeviceAnchor = { username: string; deviceId: string };

type PinRecord = { salt: string; hash: string };

export function loadSession(): Session | null {
  try {
    if (expireIfStale()) return null;
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as Session;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(session));
  sessionStorage.setItem(TAB_LIVE_KEY, "1");
  touchLastActive(true);
  saveDeviceAnchor({ username: session.user.username, deviceId: session.device.id });
  setLocked(false);
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(LAST_ACTIVE_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(LOCKED_KEY);
  sessionStorage.removeItem(TAB_LIVE_KEY);
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
    if (!loadSession()) {
      onLock();
      return;
    }
    if (isLocked()) return;
    setLocked(true);
    onLock();
  }

  function bump() {
    if (isLocked()) return;
    touchLastActive();
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

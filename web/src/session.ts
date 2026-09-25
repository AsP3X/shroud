import type { Session } from "./api/client";
import { closeVault, hasVault, isVaultOpen, vaultGet, vaultSet } from "./crypto/vault";
import { storageSealed } from "./storageSeal";

const TOKEN_KEY = "shroud.session";
/** The bearer token, sealed in the vault; `shroud.session` keeps only who is signed in. */
export const SEALED_TOKEN_PREFIX = "shroud.token.";
const DEVICE_KEY = "shroud.device-anchor";
const LOCKED_KEY = "shroud.locked";
const TAB_LIVE_KEY = "shroud.tab-live";
const LAST_ACTIVE_KEY = "shroud.last-active";
const LOCK_HIDDEN_KEY = "shroud.lock-on-hidden";
const IDLE_MS = 5 * 60 * 1000;
/** Delay before a hidden tab locks, so reload/navigation does not demand a PIN. */
const HIDE_LOCK_MS = 15_000;
/** Away this long → PIN on the next visit (not a full login). */
const PIN_AFTER_MS = 12 * 60 * 60 * 1000;
/** No activity this long → drop the token; next visit is a full login. */
const LOGOUT_AFTER_MS = 14 * 24 * 60 * 60 * 1000;

/**
 * `shroud.session` says who is signed in on which device; the bearer token itself is sealed in
 * the vault (`shroud.token.<user>`) and only readable unlocked. Between login and the first PIN
 * there is no vault yet, so the token waits in memory; a reload in that window means logging
 * in again.
 * Session metadata lives in localStorage (survives tab close).
 * Closing a tab is not a logout. A reload asks for the PIN: the vault key lived only in that page.
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
  if (storageSealed()) return now;
  try {
    localStorage.setItem(LAST_ACTIVE_KEY, String(now));
  } catch {
    /* quota */
  }
  return now;
}

let lastTouchWrite = 0;

export function touchLastActive(force = false): void {
  if (storageSealed()) return;
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

/** The token this page holds in memory: from a login, or read out of the vault. */
let liveToken: { userId: string; token: string } | null = null;

function sealedTokenKey(userId: string): string {
  return SEALED_TOKEN_PREFIX + userId.toLowerCase();
}

function readMeta(): Session | null {
  const raw = localStorage.getItem(TOKEN_KEY);
  if (!raw) return null;
  return JSON.parse(raw) as Session;
}

/**
 * The signed-in session. Locked, `token` is empty: the app only routes to the unlock screen
 * then. Null when nobody is signed in, or when the token is gone for good (lost before a vault
 * existed, or superseded) — the next step is a login.
 */
export function loadSession(): Session | null {
  try {
    if (expireIfStale()) return null;
    const meta = readMeta();
    if (!meta) return null;
    // Stored before the vault: the unlock seals it (`sealSessionToken`).
    if (meta.token) return meta;
    const userId = meta.user.id.toLowerCase();
    const token =
      (liveToken?.userId === userId ? liveToken.token : null) ?? vaultGet(sealedTokenKey(userId)) ?? "";
    if (token) return { ...meta, token };
    return hasVault(userId) && !isVaultOpen(userId) ? { ...meta, token: "" } : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  if (storageSealed()) return;
  const userId = session.user.id.toLowerCase();
  liveToken = { userId, token: session.token };
  // A new login supersedes whatever token the vault held; unlocking must not bring it back.
  localStorage.removeItem(sealedTokenKey(userId));
  localStorage.setItem(TOKEN_KEY, JSON.stringify({ ...session, token: "" }));
  sealSessionToken();
  sessionStorage.setItem(TAB_LIVE_KEY, "1");
  touchLastActive(true);
  saveDeviceAnchor({ username: session.user.username, deviceId: session.device.id });
  setLocked(false);
}

/**
 * Seals the token into the open vault: one held in memory since login, or one a pre-vault
 * browser stored in the clear (which is then stripped from `shroud.session`).
 */
export function sealSessionToken(): void {
  if (storageSealed()) return;
  try {
    const meta = readMeta();
    if (meta?.token) liveToken = { userId: meta.user.id.toLowerCase(), token: meta.token };
    if (!liveToken || !isVaultOpen(liveToken.userId)) return;
    if (!vaultSet(sealedTokenKey(liveToken.userId), liveToken.token)) return;
    if (meta?.token) localStorage.setItem(TOKEN_KEY, JSON.stringify({ ...meta, token: "" }));
  } catch {
    /* storage unavailable: the next unlock tries again */
  }
}

/** Lock: the token leaves memory too, once the vault holds a sealed copy. */
function forgetLiveToken(): void {
  if (liveToken && hasVault(liveToken.userId)) liveToken = null;
}

/**
 * Locks for real: the vault key and the token leave memory, so nothing on disk can be read
 * until the PIN (or the phrase) opens the vault again. Every lock goes through here.
 */
export function lockNow(): void {
  if (storageSealed()) return;
  closeVault();
  forgetLiveToken();
  setLocked(true);
}

export function clearSession(): void {
  liveToken = null;
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(LAST_ACTIVE_KEY);
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const key = localStorage.key(i);
    if (key?.startsWith(SEALED_TOKEN_PREFIX)) localStorage.removeItem(key);
  }
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
  if (storageSealed()) return;
  localStorage.setItem(
    DEVICE_KEY,
    JSON.stringify({
      username: anchor.username.trim().toLowerCase(),
      deviceId: anchor.deviceId,
    }),
  );
}

/** iOS calls this "Lock chats in background". Defaults on. */
let lockOnHiddenOverride: boolean | null = null;

export function lockOnHidden(): boolean {
  if (lockOnHiddenOverride !== null) return lockOnHiddenOverride;
  try {
    return localStorage.getItem(LOCK_HIDDEN_KEY) !== "0";
  } catch {
    return true;
  }
}

export function setLockOnHidden(enabled: boolean): void {
  lockOnHiddenOverride = enabled;
  if (storageSealed()) return;
  try {
    localStorage.setItem(LOCK_HIDDEN_KEY, enabled ? "1" : "0");
  } catch {
    /* private mode: the in-memory override still applies for this tab */
  }
}

export function isLocked(): boolean {
  return sessionStorage.getItem(LOCKED_KEY) === "1";
}

export function setLocked(locked: boolean): void {
  if (storageSealed()) return;
  if (locked) sessionStorage.setItem(LOCKED_KEY, "1");
  else sessionStorage.removeItem(LOCKED_KEY);
}

/** Holds that keep the auto-lock off while a call rings or runs (see `holdAutoLock`). */
let autoLockHolds = 0;
const onHoldsReleased = new Set<() => void>();

/**
 * Keeps the idle and hidden-tab locks from firing until the returned release runs: a call must
 * not be cut because nobody touched the keyboard, or because the tab went to the background.
 * When the last hold goes the usual rules apply again, to the time already passed: a tab hidden
 * longer than the limit, or idle longer than it, locks at once. Locking by hand is not held.
 */
export function holdAutoLock(): () => void {
  autoLockHolds += 1;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    autoLockHolds -= 1;
    if (autoLockHolds === 0) for (const recheck of [...onHoldsReleased]) recheck();
  };
}

/** Idle + hidden-tab lock. Returns a disposer. */
export function installAutoLock(onLock: () => void): () => void {
  let timer = window.setTimeout(lock, IDLE_MS);
  let hideTimer = 0;
  let unloading = false;
  let lastInput = Date.now();
  let hiddenAt = document.hidden ? Date.now() : 0;

  function lock() {
    // A wipe already dropped the token and is clearing the tab. Navigating to unlock here
    // would unmount it before it finishes.
    if (storageSealed()) return;
    // A call holds the lock off; `recheck` runs the rules again once it is over.
    if (autoLockHolds > 0) return;
    const wasLocked = isLocked();
    // Idempotent, and first: whatever else happens, the keys leave memory.
    lockNow();
    if (!loadSession()) {
      onLock();
      return;
    }
    if (wasLocked) return;
    onLock();
  }

  function bump() {
    if (isLocked()) return;
    lastInput = Date.now();
    touchLastActive();
    window.clearTimeout(timer);
    window.clearTimeout(hideTimer);
    timer = window.setTimeout(lock, IDLE_MS);
  }

  function onVisibility() {
    if (document.hidden) {
      hiddenAt = Date.now();
      if (unloading || !lockOnHidden()) return;
      window.clearTimeout(hideTimer);
      hideTimer = window.setTimeout(lock, HIDE_LOCK_MS);
    } else {
      hiddenAt = 0;
      bump();
    }
  }

  /** The last hold went: whatever came due meanwhile applies now, the rest keeps counting. */
  function recheck() {
    if (isLocked()) return;
    const now = Date.now();
    if (document.hidden && hiddenAt && !unloading && lockOnHidden()) {
      const left = HIDE_LOCK_MS - (now - hiddenAt);
      if (left <= 0) {
        lock();
        return;
      }
      window.clearTimeout(hideTimer);
      hideTimer = window.setTimeout(lock, left);
    }
    const idleLeft = IDLE_MS - (now - lastInput);
    if (idleLeft <= 0) {
      lock();
      return;
    }
    window.clearTimeout(timer);
    timer = window.setTimeout(lock, idleLeft);
  }

  function onPageHide() {
    unloading = true;
    window.clearTimeout(hideTimer);
  }

  function onPageShow() {
    unloading = false;
    if (!document.hidden) bump();
  }

  if (document.hidden && lockOnHidden()) {
    hideTimer = window.setTimeout(lock, HIDE_LOCK_MS);
  }

  const events = ["pointerdown", "keydown"] as const;
  for (const ev of events) window.addEventListener(ev, bump);
  document.addEventListener("visibilitychange", onVisibility);
  window.addEventListener("pagehide", onPageHide);
  window.addEventListener("pageshow", onPageShow);
  onHoldsReleased.add(recheck);
  return () => {
    window.clearTimeout(timer);
    window.clearTimeout(hideTimer);
    for (const ev of events) window.removeEventListener(ev, bump);
    document.removeEventListener("visibilitychange", onVisibility);
    window.removeEventListener("pagehide", onPageHide);
    window.removeEventListener("pageshow", onPageShow);
    onHoldsReleased.delete(recheck);
  };
}

import { sha256 } from "@noble/hashes/sha2.js";
import { api } from "./api/client";
import { bytesToB64, utf8 } from "./crypto/bytes";

/*
 * Removing this browser from another device's Devices list wipes it at once — the same full
 * wipe as Log Out (`deviceWipe.ts`), whatever the page is doing:
 *
 * - Unlocked, the shell hears it from the server: a 401 `DEVICE_REMOVED` on its poll, or the
 *   socket's `auth.error` (see AppShell).
 * - Locked (the PIN screen, or the PIN-setup and phrase screens), the token is sealed in the
 *   vault and the page cannot make an authenticated call. It asks `POST /auth/session-status`
 *   instead, with the token's SHA-256 — the one thing it can read while locked.
 * - Any page, and a browser with no Shroud tab open: the server sends a Web Push. The service
 *   worker (`public/sw.js`) tells every open tab, and with none open deletes what it can reach
 *   itself and leaves a marker (a Cache Storage entry, since a worker has no localStorage) that
 *   the next page load acts on before it shows anything.
 */

/**
 * Standard Base64 of SHA-256 over the token's UTF-8 bytes — what `session-status` looks up.
 *
 * It sits in localStorage in the clear because the lock screen must be able to ask whether this
 * browser was removed, and locked it can read nothing sealed. It is safe there: the server hashes
 * every token presented to it, so the hash cannot authenticate, and it says nothing about the
 * account beyond "a session existed".
 */
export const TOKEN_HASH_KEY = "shroud.token-hash";

export function tokenHash(token: string): string {
  return bytesToB64(sha256(utf8(token)));
}

export function readTokenHash(): string | null {
  try {
    return localStorage.getItem(TOKEN_HASH_KEY);
  } catch {
    return null;
  }
}

/* --- asking the server while locked -------------------------------------------------------- */

const PROBE_EVERY_MS = 30_000;
/** Showing a tab fires both `visibilitychange` and `focus`; one question is enough. */
const PROBE_GAP_MS = 5_000;

/**
 * True when the server says the session behind `hash` belongs to a removed device or a deleted
 * account; false for a live, signed-out or unknown session. Null when it could not say: offline, rate-limited
 * (429), a server error. Only a definite `removed: true` wipes.
 */
export async function askSessionRemoved(hash: string): Promise<boolean | null> {
  try {
    const status = await api.sessionStatus(hash);
    return typeof status?.removed === "boolean" ? status.removed : null;
  } catch {
    return null;
  }
}

/**
 * While locked: asks on start, whenever the tab is shown or focused, and every 30 s while it is
 * visible. A hidden tab does not ask — its timers stall anyway, and the push covers it.
 * Calls `onRemoved` once. Returns a disposer.
 */
export function watchForRemoval(onRemoved: () => void): () => void {
  let stopped = false;
  let inflight = false;
  let lastAsked = 0;

  async function ask(force: boolean) {
    if (stopped || inflight || document.hidden) return;
    if (!force && Date.now() - lastAsked < PROBE_GAP_MS) return;
    const hash = readTokenHash();
    // A session from before this hash existed: the next unlock stores it (session.ts).
    if (!hash) return;
    inflight = true;
    lastAsked = Date.now();
    try {
      // A login meanwhile (this tab or another) replaced the hash: the answer was about the
      // old session, which the new one supersedes anyway.
      if ((await askSessionRemoved(hash)) === true && !stopped && readTokenHash() === hash) {
        stopped = true;
        onRemoved();
      }
    } finally {
      inflight = false;
    }
  }

  const onShown = () => void ask(false);
  const timer = window.setInterval(() => void ask(true), PROBE_EVERY_MS);
  document.addEventListener("visibilitychange", onShown);
  window.addEventListener("focus", onShown);
  window.addEventListener("online", onShown);
  void ask(true);
  return () => {
    stopped = true;
    window.clearInterval(timer);
    document.removeEventListener("visibilitychange", onShown);
    window.removeEventListener("focus", onShown);
    window.removeEventListener("online", onShown);
  };
}

/* --- who runs the wipe --------------------------------------------------------------------- */

type Owner = "shell" | "app";
const handlers: Record<Owner, (() => void) | null> = { shell: null, app: null };
/** Signalled before anything was listening (the worker's marker at startup, an early message). */
let pending = false;

/**
 * This browser was removed. The chat shell runs the wipe when it is mounted (it hangs up a
 * call first and owns the dialog); otherwise the app does, over whatever screen is showing.
 */
export function signalDeviceRemoved(): void {
  const handler = handlers.shell ?? handlers.app;
  if (handler) handler();
  else pending = true;
}

/** A removal is waiting for its handler — read by the app's first render. */
export function removalPending(): boolean {
  return pending;
}

export function onDeviceRemoved(owner: Owner, handler: () => void): () => void {
  handlers[owner] = handler;
  if (pending) {
    pending = false;
    // Whoever is registered by then runs it (a remount may have replaced this handler).
    queueMicrotask(signalDeviceRemoved);
  }
  return () => {
    if (handlers[owner] === handler) handlers[owner] = null;
  };
}

/* --- the service worker -------------------------------------------------------------------- */

/** Must match `public/sw.js`. */
const WORKER_MESSAGE = "shroud.device-removed";
/**
 * Cache Storage entry the worker writes when a removal push arrives. The wipe's media step
 * deletes every cache but the Whisper weights, so it goes with the rest of the data.
 */
export const REMOVED_MARKER_CACHE = "shroud.device-removed";
const MARKER_CHECK_TIMEOUT_MS = 1500;

/** Once per page: the worker's removal message starts the wipe from any screen. */
export function installRemovalMessages(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  const container = navigator.serviceWorker;
  container.addEventListener("message", (event: MessageEvent) => {
    const data = event.data as { type?: unknown } | null;
    if (data?.type === WORKER_MESSAGE) signalDeviceRemoved();
  });
  container.startMessages();
}

/**
 * The worker saw a removal while no Shroud tab was open (or before this one could act). Bounded:
 * a Cache Storage that never answers must not keep the app from starting — an answer that comes
 * later still signals the removal.
 *
 * The marker is checked against the server when there is a hash to ask with: a marker left
 * behind by an earlier removal (the push landed after that wipe had finished) must not wipe a
 * newer login. Only a definite "not removed" drops it; offline, the marker is trusted.
 */
export async function removedWhileClosed(): Promise<boolean> {
  if (typeof caches === "undefined") return false;
  let settled = false;
  let timer = 0;
  const marked = caches.has(REMOVED_MARKER_CACHE).catch(() => false);
  const found = await Promise.race([
    marked,
    new Promise<boolean>((resolve) => {
      timer = window.setTimeout(() => resolve(false), MARKER_CHECK_TIMEOUT_MS);
    }),
  ]);
  window.clearTimeout(timer);
  settled = true;
  if (!found) {
    void marked.then(async (late) => {
      if (late && settled && (await markerStillApplies())) signalDeviceRemoved();
    });
    return false;
  }
  return markerStillApplies();
}

async function markerStillApplies(): Promise<boolean> {
  const hash = readTokenHash();
  if (!hash) return true;
  if ((await askSessionRemoved(hash)) === false) {
    await clearRemovalMarker();
    return false;
  }
  return true;
}

/** Nothing signed in to wipe: the marker has done its job. */
export async function clearRemovalMarker(): Promise<void> {
  if (typeof caches === "undefined") return;
  try {
    await caches.delete(REMOVED_MARKER_CACHE);
  } catch {
    /* the next load looks again */
  }
}

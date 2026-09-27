import { api, ApiError } from "./api/client";
import { closeMediaDb, MEDIA_DB_NAME } from "./crypto/mediaCache";
import { forgetPeerKeyCache } from "./crypto/peerIdentity";
import { closeVault } from "./crypto/vault";
import { forgetPushRegistration, hasPushRegistration } from "./notifications/push";
import { sealStorage, storageSealed } from "./storageSeal";

/*
 * Logging out leaves nothing of the account in this browser: decrypted messages and chat
 * previews, cached photos, videos and voice notes, the identity key, ratchet sessions, the PIN,
 * the session token, every preference and the push registration (the service worker, its
 * subscription and any notification still on screen). The origin belongs to Shroud, so
 * "everything" is literal — local and session storage are emptied, every IndexedDB database is
 * deleted — and a final pass proves it. One thing stays: the Whisper model weights, public files that cost
 * hundreds of megabytes to fetch again. The device-id anchor goes too; at the 5-device cap the
 * server hands the next login a device nobody is signed in on.
 *
 * A wipe that is interrupted (tab closed, crash) is finished on the next page load: a marker is
 * written as it starts and removed only once the verify step passes.
 */

export type WipeStepId = "session" | "messages" | "media" | "keys" | "settings" | "verify";

export const WIPE_STEPS: readonly WipeStepId[] = [
  "session",
  "messages",
  "media",
  "keys",
  "settings",
  "verify",
];

/** Something `verify` found, and the step that should have removed it. */
export type Leftover = { step: "messages" | "media" | "keys" | "settings"; label: string };

export type StepResult = {
  /** Items this step removed: message bodies and previews, media files, keys, settings. */
  removed: number;
  /** Session step: the server could not be reached, so only this browser dropped the token. */
  offline?: boolean;
  /** Verify step: what is still here. Empty means nothing of the account is left. */
  leftovers?: Leftover[];
};

/** What was stored when the wipe began — the numbers the dialog reports. */
export type WipeInventory = { messages: number; media: number; keys: number; settings: number };

const PENDING_KEY = "shroud.wipe-pending";
const MESSAGE_PREFIXES = ["shroud.pt.", "shroud.preview."];
const KEY_PREFIXES = ["shroud.identity.", "shroud.ratchet.", "shroud.boxauth.", "shroud.vault.", "shroud.token.", "shroud.pin."];
/** Left behind by a session: on their own they say someone used this browser. */
const ACCOUNT_PREFIXES = [
  ...MESSAGE_PREFIXES,
  ...KEY_PREFIXES,
  "shroud.device-anchor",
  "transcription.languageStats",
];
const SESSION_KEY = "shroud.session";

/** The wipe marker: removed last, once `verify` finds nothing else. */
export function isPreservedStorageKey(key: string): boolean {
  return key === PENDING_KEY;
}
/** transformers.js keeps the Whisper weights here. */
const KEPT_CACHES = new Set(["transformers-cache"]);
const SERVER_TIMEOUT_MS = 4000;
const DB_DELETE_TIMEOUT_MS = 2500;
const CHANNEL = "shroud.device-wipe";

type KeyStore = Pick<Storage, "length" | "key" | "getItem" | "removeItem">;

/** Every key in a storage area, read before removing any (removal reindexes). */
export function storageKeys(store: KeyStore): string[] {
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const key = store.key(i);
    if (key !== null) keys.push(key);
  }
  return keys;
}

const hasPrefix = (key: string, prefixes: string[]) => prefixes.some((p) => key.startsWith(p));

export function isMessageKey(key: string): boolean {
  return hasPrefix(key, MESSAGE_PREFIXES);
}

export function isKeyMaterialKey(key: string): boolean {
  return hasPrefix(key, KEY_PREFIXES);
}

/** Removes the matching keys and returns how many there were. */
export function removeKeys(store: KeyStore, match: (key: string) => boolean): number {
  let removed = 0;
  for (const key of storageKeys(store)) {
    if (!match(key)) continue;
    try {
      store.removeItem(key);
      removed++;
    } catch {
      /* the verify step reports it */
    }
  }
  return removed;
}

/**
 * Account data with no session to go with it: a logout from before this code existed, or a
 * wipe that never finished. Either way it belongs to nobody who is signed in.
 */
export function hasOrphanedAccountData(store: KeyStore): boolean {
  const keys = storageKeys(store);
  // A wipe drops the token before it writes its marker, so a session here is a newer sign-in
  // and nothing on this page belongs to anyone else.
  if (keys.includes(SESSION_KEY)) return false;
  return keys.includes(PENDING_KEY) || keys.some((key) => hasPrefix(key, ACCOUNT_PREFIXES));
}

/** What `verify` found in local storage besides its own marker. */
export function describeStoredLeftovers(store: KeyStore): Leftover[] {
  const keys = storageKeys(store).filter((key) => !isPreservedStorageKey(key));
  const found: Leftover[] = [];
  if (keys.some(isMessageKey)) found.push({ step: "messages", label: "messages" });
  if (keys.some(isKeyMaterialKey)) found.push({ step: "keys", label: "encryption keys" });
  if (keys.some((key) => !isMessageKey(key) && !isKeyMaterialKey(key))) {
    found.push({ step: "settings", label: "settings" });
  }
  return found;
}

/* --- IndexedDB and Cache Storage --------------------------------------------------------- */

async function databaseNames(): Promise<string[] | null> {
  if (typeof indexedDB === "undefined") return [];
  if (typeof indexedDB.databases !== "function") return null;
  try {
    const list = await indexedDB.databases();
    return list.map((db) => db.name).filter((name): name is string => Boolean(name));
  } catch {
    return null;
  }
}

async function countMediaFiles(): Promise<number> {
  const names = await databaseNames();
  if (names !== null && !names.includes(MEDIA_DB_NAME)) return 0;
  return new Promise((resolve) => {
    let db: IDBDatabase | null = null;
    const done = (count: number) => {
      db?.close();
      resolve(count);
    };
    try {
      const req = indexedDB.open(MEDIA_DB_NAME);
      req.onerror = () => done(0);
      req.onsuccess = () => {
        db = req.result;
        const stores = [...db.objectStoreNames];
        if (stores.length === 0) return done(0);
        const tx = db.transaction(stores, "readonly");
        let total = 0;
        for (const name of stores) {
          const count = tx.objectStore(name).count();
          count.onsuccess = () => {
            total += count.result;
          };
        }
        tx.oncomplete = () => done(total);
        tx.onerror = () => done(total);
      };
    } catch {
      done(0);
    }
  });
}

/** Deletes that did not complete — what `verify` reports where `indexedDB.databases()` is missing. */
const undeleted = new Set<string>();

function deleteDatabase(name: string): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      resolve(ok);
    };
    // "blocked" is not failure: the delete lands once the last connection closes. Give other
    // tabs a moment to let go, then report it rather than wait forever.
    const timer = window.setTimeout(() => finish(false), DB_DELETE_TIMEOUT_MS);
    try {
      const req = indexedDB.deleteDatabase(name);
      req.onsuccess = () => finish(true);
      req.onerror = () => finish(false);
    } catch {
      finish(false);
    }
  });
}

async function deleteAllDatabases(): Promise<void> {
  await closeMediaDb();
  // `databases()` is missing or blocked in some private-mode browsers and returns null.
  // `new Set(null)` throws, which used to stick the wipe on this step forever.
  const names = new Set((await databaseNames()) ?? []);
  names.add(MEDIA_DB_NAME);
  await Promise.all(
    [...names].map(async (name) => {
      if (await deleteDatabase(name)) undeleted.delete(name);
      else undeleted.add(name);
    }),
  );
}

async function deleteCaches(): Promise<void> {
  if (typeof caches === "undefined") return;
  try {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => !KEPT_CACHES.has(name)).map((name) => caches.delete(name)));
  } catch {
    /* the verify step reports it */
  }
}

function expireCookies(): void {
  for (const pair of document.cookie.split(";")) {
    const name = pair.split("=")[0]?.trim();
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
}

/* --- other tabs --------------------------------------------------------------------------- */

let channel: BroadcastChannel | null | undefined;

function wipeChannel(): BroadcastChannel | null {
  if (channel === undefined) {
    channel = typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel(CHANNEL);
  }
  return channel;
}

/**
 * Other Shroud tabs follow a logout at once: they stop writing and reload onto the welcome
 * screen, dropping their decrypted state, instead of refilling stores that are being emptied.
 */
export function followWipesInOtherTabs(): void {
  const ch = wipeChannel();
  if (!ch) return;
  ch.onmessage = (event: MessageEvent) => {
    // The tab running the wipe is sealed already and must not reload itself mid-animation.
    if (event.data !== "wipe" || storageSealed()) return;
    sealStorage();
    window.location.replace("/");
  };
}

/* --- the wipe ------------------------------------------------------------------------------ */

/**
 * First thing a wipe does, before any step: stop this tab writing, drop the session token, leave
 * the marker that makes the next load finish the job, count what is here, tell the other tabs.
 * The token goes first, so a tab closed from here on can never come back signed in.
 */
export async function beginWipe(): Promise<WipeInventory> {
  sealStorage();
  forgetPeerKeyCache();
  closeVault();
  removeKeys(
    localStorage,
    (key) => key === SESSION_KEY || key === "shroud.last-active" || key.startsWith("shroud.token."),
  );
  try {
    sessionStorage.clear();
  } catch {
    /* nothing to clear */
  }
  try {
    localStorage.setItem(PENDING_KEY, String(Date.now()));
  } catch {
    /* storage unavailable: nothing can be left in it either */
  }
  const keys = storageKeys(localStorage).filter((key) => !isPreservedStorageKey(key));
  const inventory: WipeInventory = {
    messages: keys.filter(isMessageKey).length,
    keys: keys.filter(isKeyMaterialKey).length,
    settings: keys.filter((key) => !isMessageKey(key) && !isKeyMaterialKey(key)).length,
    media: await countMediaFiles(),
  };
  wipeChannel()?.postMessage("wipe");
  return inventory;
}

async function endServerSession(token: string | null): Promise<boolean> {
  if (!token) return false;
  let timer = 0;
  try {
    await Promise.race([
      api.logout(token),
      new Promise((_, reject) => {
        timer = window.setTimeout(() => reject(new ApiError("timeout", "", 0)), SERVER_TIMEOUT_MS);
      }),
    ]);
    return false;
  } catch (err) {
    // 401: the session was already over. Only an unreachable server leaves it alive there.
    return !(err instanceof ApiError && err.status !== 0);
  } finally {
    window.clearTimeout(timer);
  }
}

/** Runs one step. Every step is safe to repeat; `verify` repeats the others once if needed. */
export async function runWipeStep(
  step: WipeStepId,
  opts: { token: string | null; inventory: WipeInventory },
): Promise<StepResult> {
  switch (step) {
    case "session": {
      // The token already left this browser in `beginWipe`; this ends it on the server too.
      const offline = await endServerSession(opts.token);
      return { removed: 1, offline };
    }
    case "messages":
      removeKeys(localStorage, isMessageKey);
      return { removed: opts.inventory.messages };
    case "media":
      await deleteAllDatabases();
      await deleteCaches();
      return { removed: opts.inventory.media };
    case "keys":
      removeKeys(localStorage, isKeyMaterialKey);
      return { removed: opts.inventory.keys };
    case "settings":
      removeKeys(localStorage, (key) => !isPreservedStorageKey(key));
      try {
        sessionStorage.clear();
      } catch {
        /* nothing to clear */
      }
      expireCookies();
      // The server forgot the subscription at logout; this browser forgets it too.
      await forgetPushRegistration();
      return { removed: opts.inventory.settings };
    case "verify": {
      let leftovers = await findLeftovers();
      if (leftovers.length > 0) {
        for (const again of ["messages", "media", "keys", "settings"] as const) {
          await runWipeStep(again, opts);
        }
        leftovers = await findLeftovers();
      }
      if (leftovers.length === 0) removeKeys(localStorage, (key) => key === PENDING_KEY);
      return { removed: 0, leftovers };
    }
  }
}

/** Everything that would contradict "nothing of the account is left". */
export async function findLeftovers(): Promise<Leftover[]> {
  const found = describeStoredLeftovers(localStorage);
  try {
    if (sessionStorage.length > 0 && !found.some((l) => l.step === "settings")) {
      found.push({ step: "settings", label: "settings" });
    }
  } catch {
    /* unavailable, so empty */
  }
  const names = await databaseNames();
  if ((names ?? [...undeleted]).length > 0) {
    found.push({ step: "media", label: "cached media (close other Shroud tabs)" });
  }
  if (typeof caches !== "undefined") {
    try {
      if ((await caches.keys()).some((name) => !KEPT_CACHES.has(name))) {
        found.push({ step: "media", label: "offline cache" });
      }
    } catch {
      /* unavailable, so empty */
    }
  }
  if (document.cookie.trim() !== "") found.push({ step: "settings", label: "cookies" });
  if (await hasPushRegistration()) found.push({ step: "settings", label: "notifications" });
  return found;
}

/**
 * Called before the app reads anything (session, theme). Finishes a wipe that was cut off, and
 * clears account data a past logout left behind. A signed-in browser is never touched, and
 * storage is not sealed: nothing is running yet, and the next login has to be able to write.
 */
export function finishWipeOnLoad(): Promise<void> | null {
  try {
    if (!hasOrphanedAccountData(localStorage)) return null;
    // Synchronous, so the first render already sees an empty store.
    removeKeys(localStorage, (key) => !isPreservedStorageKey(key));
    sessionStorage.clear();
  } catch {
    return null;
  }
  return (async () => {
    // A login that lands while this is still deleting is a new session. Stop, and leave its
    // stores alone — the marker stays until the next wipe, which is harmless beside a token.
    const signedInAgain = () => {
      try {
        return localStorage.getItem(SESSION_KEY) !== null;
      } catch {
        return false;
      }
    };
    if (signedInAgain()) return;
    await deleteAllDatabases();
    if (signedInAgain()) return;
    await deleteCaches();
    if (signedInAgain()) return;
    expireCookies();
    await forgetPushRegistration();
    if (signedInAgain()) return;
    if ((await findLeftovers()).length === 0) {
      removeKeys(localStorage, (key) => key === PENDING_KEY);
    }
  })();
}

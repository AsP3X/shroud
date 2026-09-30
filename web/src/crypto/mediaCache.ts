import { storageSealed } from "../storageSeal";
import { isVaultName, openMedia, sealMedia, vaultName } from "./vault";

/*
 * Voice notes, sealed photos and clips, and posters. Every entry is sealed again under the
 * vault's media key (vault.ts) and stored under a keyed hash of its id, so the database lists
 * neither message ids nor readable bytes. Locked, reads miss and writes are dropped.
 */
const NAME_PREFIX = "m.";

export const MEDIA_DB_NAME = "shroud-media";
const DB_NAME = MEDIA_DB_NAME;
const STORE = "blobs";
const VERSION = 1;

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  // Opening creates the database, so a read during a wipe would bring back the one it deleted.
  if (storageSealed()) return Promise.reject(new Error("storage sealed for a device wipe"));
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
    };
    req.onsuccess = () => {
      const db = req.result;
      db.onclose = () => {
        dbPromise = null;
      };
      // Another tab is deleting the database (logout): let go, or its delete waits on us.
      db.onversionchange = () => {
        db.close();
        dbPromise = null;
      };
      resolve(db);
    };
    req.onerror = () => {
      dbPromise = null;
      reject(req.error ?? new Error("indexedDB open failed"));
    };
  });
  return dbPromise;
}

function id(messageId: string): string | null {
  return vaultName(NAME_PREFIX, messageId);
}

export async function loadMediaBlob(messageId: string): Promise<Uint8Array | null> {
  const name = id(messageId);
  if (!name) return null;
  try {
    const db = await openDb();
    const sealed = await new Promise<Uint8Array | null>((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const req = tx.objectStore(STORE).get(name);
      req.onsuccess = () => {
        const value = req.result;
        if (value instanceof ArrayBuffer) resolve(new Uint8Array(value));
        else if (value instanceof Uint8Array) resolve(value);
        else resolve(null);
      };
      req.onerror = () => reject(req.error);
    });
    return sealed ? await openMedia(name, sealed) : null;
  } catch {
    return null;
  }
}

/** Whether anything is stored under this key, without reading it (a sealed video is up to 25 MB). */
export async function hasMediaBlob(messageId: string): Promise<boolean> {
  const name = id(messageId);
  if (!name) return false;
  try {
    const db = await openDb();
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const req = tx.objectStore(STORE).count(name);
      req.onsuccess = () => resolve(req.result > 0);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return false;
  }
}

export async function saveMediaBlob(messageId: string, data: Uint8Array): Promise<void> {
  const name = id(messageId);
  if (!name) return;
  try {
    const sealed = await sealMedia(name, data);
    if (!sealed) return;
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(sealed, name);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* quota / private mode */
  }
}

/**
 * Drops everything cached for these messages: voice bytes (`<id>`), the sealed photo or clip
 * (`sealed:<id>`) and a video's poster (`poster:<id>`). Used when messages are deleted, so
 * nothing of them stays on this device. One transaction however many there are.
 */
export async function deleteMediaBlobs(...messageIds: string[]): Promise<void> {
  const names = messageIds.flatMap((messageId) => {
    const key = messageId.toLowerCase();
    return [key, `sealed:${key}`, `poster:${key}`].map(id);
  });
  if (names.length === 0 || names.some((name) => name === null)) return;
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      for (const entry of names) store.delete(entry as string);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* nothing stored, or storage unavailable */
  }
}

/** Closes this tab's connection, so deleting the database is not blocked by it. */
export async function closeMediaDb(): Promise<void> {
  const pending = dbPromise;
  dbPromise = null;
  if (!pending) return;
  try {
    (await pending).close();
  } catch {
    /* never opened */
  }
}

export async function clearMediaBlobs(): Promise<void> {
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* ignore */
  }
}

/**
 * Deletes every entry not stored under a vault name: media cached before the vault existed,
 * in the clear under its message id. It is a cache — photos, clips and voice notes download
 * again, and posters are redrawn — so dropping it beats keeping plaintext around.
 */
export async function dropUnsealedMediaBlobs(): Promise<void> {
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const req = tx.objectStore(STORE).openKeyCursor();
      req.onsuccess = () => {
        const cursor = req.result;
        if (!cursor) return;
        if (typeof cursor.key !== "string" || !isVaultName(NAME_PREFIX, cursor.key)) {
          tx.objectStore(STORE).delete(cursor.primaryKey);
        }
        cursor.continue();
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* nothing stored, or storage unavailable */
  }
}

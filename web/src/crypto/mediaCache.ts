const DB_NAME = "shroud-media";
const STORE = "blobs";
const VERSION = 1;

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
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
      resolve(db);
    };
    req.onerror = () => {
      dbPromise = null;
      reject(req.error ?? new Error("indexedDB open failed"));
    };
  });
  return dbPromise;
}

function id(messageId: string): string {
  return messageId.toLowerCase();
}

export async function loadMediaBlob(messageId: string): Promise<Uint8Array | null> {
  try {
    const db = await openDb();
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const req = tx.objectStore(STORE).get(id(messageId));
      req.onsuccess = () => {
        const value = req.result;
        if (value instanceof ArrayBuffer) resolve(new Uint8Array(value));
        else if (value instanceof Uint8Array) resolve(value);
        else resolve(null);
      };
      req.onerror = () => reject(req.error);
    });
  } catch {
    return null;
  }
}

/** Whether anything is stored under this key, without reading it (a sealed video is up to 25 MB). */
export async function hasMediaBlob(messageId: string): Promise<boolean> {
  try {
    const db = await openDb();
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const req = tx.objectStore(STORE).count(id(messageId));
      req.onsuccess = () => resolve(req.result > 0);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return false;
  }
}

export async function saveMediaBlob(messageId: string, data: Uint8Array): Promise<void> {
  try {
    const db = await openDb();
    const copy = new Uint8Array(data.byteLength);
    copy.set(data);
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(copy.buffer, id(messageId));
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* quota / private mode */
  }
}

/**
 * Drops everything cached for one message: voice bytes (`<id>`), the sealed photo or clip
 * (`sealed:<id>`) and a video's poster (`poster:<id>`). Used when a message is deleted, so
 * nothing of it stays on this device.
 */
export async function deleteMediaBlobs(messageId: string): Promise<void> {
  const key = id(messageId);
  try {
    const db = await openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      for (const entry of [key, `sealed:${key}`, `poster:${key}`]) store.delete(entry);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    /* nothing stored, or storage unavailable */
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

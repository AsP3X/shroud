import { storageSealed } from "../storageSeal";
import { clearMediaBlobs } from "./mediaCache";

const prefix = "shroud.pt.";
const previewPrefix = "shroud.preview.";

/**
 * Messages withdrawn in this tab. A history refresh that already fetched the body can
 * otherwise write it back after delete, into both the plaintext cache and the chat list.
 */
const withdrawn = new Set<string>();

export type ChatPreview = {
  /** Message the preview shows; lets a transcript that arrives later fill it in. */
  id?: string;
  text: string;
  at: string;
  isMine: boolean;
  failed?: boolean;
};

function previewKey(me: string, peer: string): string {
  return `${previewPrefix}${me.toLowerCase()}.${peer.toLowerCase()}`;
}

export function loadPreview(me: string, peer: string): ChatPreview | null {
  try {
    const raw = localStorage.getItem(previewKey(me, peer));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ChatPreview;
    if (!parsed.text || !parsed.at) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function savePreview(me: string, peer: string, preview: ChatPreview): void {
  if (storageSealed()) return;
  if (preview.id && withdrawn.has(preview.id.toLowerCase()) && preview.text !== "Message deleted") {
    return;
  }
  const existing = loadPreview(me, peer);
  if (existing) {
    const haveT = Date.parse(existing.at) || 0;
    const nextT = Date.parse(preview.at) || 0;
    if (haveT > nextT) return;
    if (haveT === nextT && !existing.failed && preview.failed) return;
  }
  try {
    localStorage.setItem(previewKey(me, peer), JSON.stringify(preview));
  } catch {
    /* quota */
  }
}

export function loadPlaintext(messageId: string): string | null {
  const key = messageId.toLowerCase();
  if (withdrawn.has(key)) return null;
  try {
    return localStorage.getItem(prefix + key);
  } catch {
    return null;
  }
}

export function savePlaintext(messageId: string, text: string): void {
  const key = messageId.toLowerCase();
  if (withdrawn.has(key) || storageSealed()) return;
  try {
    localStorage.setItem(prefix + key, text);
  } catch {
    /* quota */
  }
}

/** Forgets one decrypted body — a deleted message must not linger in the cache. */
export function forgetPlaintext(messageId: string): void {
  const key = messageId.toLowerCase();
  withdrawn.add(key);
  try {
    localStorage.removeItem(prefix + key);
  } catch {
    /* storage unavailable */
  }
}

/**
 * If this message is what a chat list is showing, stop quoting its body.
 * Chats that aren't open have no thread to fall back to, so the line becomes
 * "Message deleted" — the same words the bubble shows after an unsend.
 */
export function redactPreviewsFor(me: string, messageId: string): void {
  const id = messageId.toLowerCase();
  const head = `${previewPrefix}${me.toLowerCase()}.`;
  const keys: string[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith(head)) keys.push(key);
    }
  } catch {
    return;
  }
  for (const key of keys) {
    try {
      const parsed = JSON.parse(localStorage.getItem(key) ?? "") as ChatPreview;
      if (parsed.id?.toLowerCase() !== id) continue;
      const peer = key.slice(head.length);
      replacePreview(me, peer, { ...parsed, text: "Message deleted", failed: false });
    } catch {
      /* a damaged preview is left alone */
    }
  }
}

/**
 * Overwrites (or with `null`, removes) a chat's preview regardless of age. `savePreview`
 * never moves backwards in time, which is right for arriving messages but wrong after a
 * delete, when the line has to fall back to an older message.
 */
export function replacePreview(me: string, peer: string, preview: ChatPreview | null): void {
  if (storageSealed()) return;
  try {
    if (preview) localStorage.setItem(previewKey(me, peer), JSON.stringify(preview));
    else localStorage.removeItem(previewKey(me, peer));
  } catch {
    /* quota */
  }
}

export type CacheStats = { messages: number; previews: number; bytes: number };

function cacheKeys(): string[] {
  const keys: string[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && (key.startsWith(prefix) || key.startsWith(previewPrefix))) keys.push(key);
    }
  } catch {
    /* storage unavailable */
  }
  return keys;
}

export function cacheStats(): CacheStats {
  let messages = 0;
  let previews = 0;
  let bytes = 0;
  for (const key of cacheKeys()) {
    if (key.startsWith(prefix)) messages++;
    else previews++;
    bytes += key.length + (localStorage.getItem(key)?.length ?? 0);
  }
  return { messages, previews, bytes };
}

/** Drops every decrypted message body and chat preview held on this device. */
export function clearCache(): void {
  for (const key of cacheKeys()) {
    try {
      localStorage.removeItem(key);
    } catch {
      /* keep going */
    }
  }
  void clearMediaBlobs();
}

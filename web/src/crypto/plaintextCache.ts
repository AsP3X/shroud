import { clearMediaBlobs } from "./mediaCache";

const prefix = "shroud.pt.";
const previewPrefix = "shroud.preview.";

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
  try {
    return localStorage.getItem(prefix + messageId.toLowerCase());
  } catch {
    return null;
  }
}

export function savePlaintext(messageId: string, text: string): void {
  try {
    localStorage.setItem(prefix + messageId.toLowerCase(), text);
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

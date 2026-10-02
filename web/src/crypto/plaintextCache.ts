import { storageSealed } from "../storageSeal";
import { clearMediaBlobs } from "./mediaCache";
import { openFromStorage, sealForStorage, vaultGet, vaultName, vaultSet } from "./vault";

/*
 * Decrypted message bodies (voice transcripts included) and chat-list previews. Both are
 * sealed in the vault (vault.ts) under hashed names; while the vault is locked nothing
 * here can be read, and nothing is written.
 */

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

/** What is sealed under a preview name: the preview, plus whose chat it is. */
type StoredPreview = ChatPreview & { me?: string; peer?: string };

function previewKey(me: string, peer: string): string | null {
  return vaultName(previewPrefix, `${me.toLowerCase()}.${peer.toLowerCase()}`);
}

function plaintextKey(messageId: string): string | null {
  return vaultName(prefix, messageId);
}

/** One localStorage name per message id; the sender is bound in the vault AAD, not the name. */
function senderAAD(name: string, senderUserId: string): string {
  return `${name}#sender:${senderUserId.toLowerCase()}`;
}

function readPreview(name: string): StoredPreview | null {
  try {
    const raw = vaultGet(name);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredPreview;
    if (!parsed.text || !parsed.at) return null;
    return parsed;
  } catch {
    return null;
  }
}

function writePreview(me: string, peer: string, preview: ChatPreview): void {
  const name = previewKey(me, peer);
  if (!name) return;
  const { me: _me, peer: _peer, ...rest } = preview as StoredPreview;
  vaultSet(name, JSON.stringify({ ...rest, me: me.toLowerCase(), peer: peer.toLowerCase() }));
}

export function loadPreview(me: string, peer: string): ChatPreview | null {
  const name = previewKey(me, peer);
  if (!name) return null;
  const stored = readPreview(name);
  if (!stored) return null;
  const { me: _me, peer: _peer, ...preview } = stored;
  return preview;
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
  writePreview(me, peer, preview);
}

/**
 * Plaintext saved for `senderUserId`. A message id the server re-serves for someone else
 * does not open: the vault AAD includes the sender, and there is no fallback to an older
 * record that was sealed under the name alone.
 */
export function loadPlaintext(messageId: string, senderUserId: string): string | null {
  const key = messageId.toLowerCase();
  if (withdrawn.has(key)) return null;
  const name = plaintextKey(key);
  if (!name) return null;
  try {
    return openFromStorage(name, localStorage.getItem(name), senderAAD(name, senderUserId));
  } catch {
    return null;
  }
}

/**
 * A body sealed before the sender was part of the AAD (vault migration of `shroud.pt.<id>`).
 * Decode does not call this: a record with no sender is not trusted as anyone's plaintext.
 */
export function loadUnboundPlaintext(messageId: string): string | null {
  const key = messageId.toLowerCase();
  if (withdrawn.has(key)) return null;
  const name = plaintextKey(key);
  return name ? vaultGet(name) : null;
}

export function savePlaintext(messageId: string, senderUserId: string, text: string): void {
  const key = messageId.toLowerCase();
  if (withdrawn.has(key) || storageSealed()) return;
  const name = plaintextKey(key);
  if (!name) return;
  const sealed = sealForStorage(name, text, senderAAD(name, senderUserId));
  if (sealed === null) return;
  try {
    localStorage.setItem(name, sealed);
  } catch {
    /* storage unavailable */
  }
}

/** Whether this tab already forgot the message (`forgetPlaintext`), so nothing of it may be stored again. */
export function isWithdrawn(messageId: string): boolean {
  return withdrawn.has(messageId.toLowerCase());
}

/** Forgets one decrypted body — a deleted message must not linger in the cache. */
export function forgetPlaintext(messageId: string): void {
  const key = messageId.toLowerCase();
  withdrawn.add(key);
  const name = plaintextKey(key);
  if (!name) return;
  try {
    localStorage.removeItem(name);
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
  const owner = me.toLowerCase();
  for (const key of cacheKeys()) {
    if (!key.startsWith(previewPrefix)) continue;
    const stored = readPreview(key);
    if (!stored || stored.me !== owner || !stored.peer) continue;
    if (stored.id?.toLowerCase() !== id) continue;
    const { me: _me, peer, ...preview } = stored;
    replacePreview(me, peer, { ...preview, text: "Message deleted", failed: false });
  }
}

/**
 * Overwrites (or with `null`, removes) a chat's preview regardless of age. `savePreview`
 * never moves backwards in time, which is right for arriving messages but wrong after a
 * delete, when the line has to fall back to an older message.
 */
export function replacePreview(me: string, peer: string, preview: ChatPreview | null): void {
  if (storageSealed()) return;
  if (preview) {
    writePreview(me, peer, preview);
    return;
  }
  const name = previewKey(me, peer);
  if (!name) return;
  try {
    localStorage.removeItem(name);
  } catch {
    /* storage unavailable */
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

/** Drops every stored message body and chat preview held on this device. */
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

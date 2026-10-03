/**
 * The account's privacy switches as the server last confirmed them (Settings → Privacy).
 *
 * The server enforces every one of them in both directions — hiding your read receipts also
 * hides your contacts' from you, and the same for typing and online / last seen. This copy
 * only tidies what the browser already shows: ticks drawn from reads made while both allowed
 * them, typing frames it would send for nothing, presence fetched before a change.
 *
 * Null until loaded. A server from before these switches sends only the chat-delete flag and
 * enforces none of them, which reads as "all on".
 */

import { useSyncExternalStore } from "react";
import type { PrivacySettings } from "./api/client";

let current: PrivacySettings | null = null;
const listeners = new Set<() => void>();

export function privacySettings(): PrivacySettings | null {
  return current;
}

export function setPrivacySettings(next: PrivacySettings | null): void {
  current = next ? withDefaults(next) : null;
  for (const listener of listeners) listener();
}

export function usePrivacySettings(): PrivacySettings | null {
  return useSyncExternalStore(subscribe, privacySettings);
}

/** Typing frames are worth sending: unknown yet counts as allowed, as the server's default. */
export function sendsTyping(): boolean {
  return current?.send_typing !== false;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function withDefaults(settings: PrivacySettings): PrivacySettings {
  return {
    allow_peer_chat_delete: settings.allow_peer_chat_delete,
    send_read_receipts: settings.send_read_receipts ?? true,
    send_typing: settings.send_typing ?? true,
    share_presence: settings.share_presence ?? true,
    discoverable_by_username: settings.discoverable_by_username ?? false,
  };
}

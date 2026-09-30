import { useSyncExternalStore } from "react";
import type { NotificationSettings } from "../api/client";
import { storageSealed } from "../storageSeal";

/**
 * How this browser notifies (Settings → Notifications and Sounds).
 *
 * Per browser, in localStorage, like the theme: preferences, not secrets. The ones that decide
 * what the server pushes while the app is closed are mirrored to it (`serverSettings`); the
 * rest only shape what this page does while it is open.
 */

export type SoundId = "note" | "chime" | "glass" | "pop" | "pulse" | "none";

export type NotificationPrefs = {
  /** Notifications on this browser at all: Web Push while it is closed, and the page's own. */
  enabled: boolean;
  /** Say who wrote. Off, a notification only says that something arrived. */
  showSender: boolean;
  /**
   * Put the message text in notifications the page shows while it is unlocked. Off by default:
   * the operating system keeps notifications in its notification centre, outside the vault.
   * Pushes never carry text either way — the server has none.
   */
  showPreview: boolean;
  reactions: boolean;
  contactRequests: boolean;
  /** Played while Shroud is open; `none` also keeps system notifications silent. */
  sound: SoundId;
  /** Unread count in the tab title, on the tab icon and on an installed app's icon. */
  badge: boolean;
  badgeIncludesMuted: boolean;
  /** The chat list's offer to turn notifications on was closed. */
  offerDismissed: boolean;
};

export const DEFAULT_PREFS: NotificationPrefs = {
  enabled: false,
  showSender: true,
  showPreview: false,
  reactions: true,
  contactRequests: true,
  sound: "note",
  badge: true,
  badgeIncludesMuted: false,
  offerDismissed: false,
};

const KEY = "shroud.notifications";
const SOUNDS: readonly SoundId[] = ["note", "chime", "glass", "pop", "pulse", "none"];
const listeners = new Set<() => void>();
let cached: { raw: string | null; prefs: NotificationPrefs } | null = null;

function parse(raw: string | null): NotificationPrefs {
  if (!raw) return DEFAULT_PREFS;
  try {
    const stored = JSON.parse(raw) as Partial<Record<keyof NotificationPrefs, unknown>>;
    const flag = (key: keyof NotificationPrefs) =>
      typeof stored[key] === "boolean" ? (stored[key] as boolean) : (DEFAULT_PREFS[key] as boolean);
    return {
      enabled: flag("enabled"),
      showSender: flag("showSender"),
      showPreview: flag("showPreview"),
      reactions: flag("reactions"),
      contactRequests: flag("contactRequests"),
      sound: SOUNDS.includes(stored.sound as SoundId) ? (stored.sound as SoundId) : DEFAULT_PREFS.sound,
      badge: flag("badge"),
      badgeIncludesMuted: flag("badgeIncludesMuted"),
      offerDismissed: flag("offerDismissed"),
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function loadNotificationPrefs(): NotificationPrefs {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    /* private mode: defaults */
  }
  // The same object for the same stored value, so `useSyncExternalStore` settles.
  if (cached && cached.raw === raw) return cached.prefs;
  const prefs = parse(raw);
  cached = { raw, prefs };
  return prefs;
}

export function updateNotificationPrefs(patch: Partial<NotificationPrefs>): NotificationPrefs {
  const next = { ...loadNotificationPrefs(), ...patch };
  const raw = JSON.stringify(next);
  let stored: string | null = raw;
  try {
    if (!storageSealed()) localStorage.setItem(KEY, raw);
    stored = localStorage.getItem(KEY);
  } catch {
    /* storage blocked: this tab keeps it */
    stored = cached?.raw ?? null;
  }
  // Keyed on what storage holds, so a write that did not land still reads back in this tab.
  cached = { raw: stored, prefs: next };
  for (const notify of [...listeners]) notify();
  return next;
}

/** The part of the preferences the server acts on while this browser is closed. */
export function serverSettings(prefs: NotificationPrefs): Partial<NotificationSettings> {
  return {
    enabled: prefs.enabled,
    show_sender: prefs.showSender,
    reactions: prefs.reactions,
    contact_requests: prefs.contactRequests,
    // A browser plays the system's sound or none; the server only needs to know which.
    sound: prefs.sound === "none" ? "none" : "default",
    badge: prefs.badge,
    badge_includes_muted: prefs.badgeIncludesMuted,
  };
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify);
  const onStorage = (event: StorageEvent) => {
    if (event.key === KEY) notify();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(notify);
    window.removeEventListener("storage", onStorage);
  };
}

export function useNotificationPrefs(): NotificationPrefs {
  return useSyncExternalStore(subscribe, loadNotificationPrefs, () => DEFAULT_PREFS);
}

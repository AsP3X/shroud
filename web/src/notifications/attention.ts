import { faviconHref } from "../logo";
import { loadNotificationPrefs, type NotificationPrefs } from "./prefs";
import { notificationPermission, showPageNotification } from "./push";
import { playSound } from "./sounds";

/**
 * What the open page does when something arrives: nothing, a sound, or a system
 * notification — and the unread count in the tab title, on the tab icon and on the app icon.
 */

export type Arrival = {
  kind: "message" | "reaction" | "contact_request";
  /** Groups a chat's notifications (its conversation id; "contacts" for requests). */
  tag: string;
  peer: string | null;
  sender: string;
  /** What the message says, when this page could read it. */
  text: string | null;
  muted: boolean;
  /** The chat is open, on screen, in a focused window: the reader is looking at it. */
  lookingAtThis: boolean;
};

const PREVIEW_CHARS = 140;

function clip(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > PREVIEW_CHARS ? `${flat.slice(0, PREVIEW_CHARS - 1)}…` : flat;
}

/**
 * Someone watching the window hears a sound (unless it is the chat they are in); someone who
 * is not gets a notification, or a sound when notifications are off.
 */
export function announce(arrival: Arrival, prefs: NotificationPrefs = loadNotificationPrefs()): void {
  if (arrival.muted && arrival.kind !== "contact_request") return;
  if (arrival.kind === "reaction" && !prefs.reactions) return;
  if (arrival.kind === "contact_request" && !prefs.contactRequests) return;

  const watching = !document.hidden && document.hasFocus();
  if (watching) {
    if (!arrival.lookingAtThis) playSound(prefs.sound);
    return;
  }
  if (!prefs.enabled || notificationPermission() !== "granted") {
    playSound(prefs.sound);
    return;
  }
  const preview = prefs.showPreview && arrival.text ? clip(arrival.text) : null;
  const body =
    arrival.kind === "message"
      ? preview ?? "New message"
      : arrival.kind === "reaction"
        ? preview ?? "Reacted to your message"
        : "Wants to add you as a contact";
  void showPageNotification({
    kind: arrival.kind,
    tag: arrival.tag,
    peer: arrival.peer,
    title: prefs.showSender ? arrival.sender : "Shroud",
    body,
    silent: prefs.sound === "none",
    countLine:
      arrival.kind === "message" ? (count) => preview ?? `${count} new messages` : undefined,
  });
}

let shownTotal: number | null = null;

/** Unread count in the title, as a dot on the tab icon, and on an installed app's icon. */
export function showUnreadCount(total: number): void {
  if (total === shownTotal) return;
  shownTotal = total;
  document.title = total > 0 ? `(${total > 999 ? "999+" : total}) Shroud` : "Shroud";
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon) icon.href = faviconHref(total > 0);
  try {
    if (total > 0) void navigator.setAppBadge?.(total).catch(() => undefined);
    else void navigator.clearAppBadge?.().catch(() => undefined);
  } catch {
    /* no badge API */
  }
}

/**
 * Locked: the tab says nothing about the account. The app icon keeps its badge — pushes set
 * it while Shroud is closed or locked anyway.
 */
export function hideUnreadCount(): void {
  shownTotal = null;
  document.title = "Shroud";
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon) icon.href = faviconHref(false);
}

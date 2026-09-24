import { ApiError, api } from "../api/client";
import { loadNotificationPrefs, serverSettings, updateNotificationPrefs } from "./prefs";

/**
 * Web Push for this browser: the service worker (`public/sw.js`), the subscription the server
 * pushes to, and routing a notification click to its chat.
 *
 * While Shroud is open and unlocked it holds a WebSocket and notifies by itself; the server
 * pushes only to devices without one. So a push reaches this browser when the tab is closed,
 * or locked (which closes the socket) — the case that matters, since a hidden tab locks after
 * a few seconds.
 */

export type Permission = "unsupported" | "default" | "granted" | "denied";

const WORKER_URL = "/sw.js";

/**
 * One change of this browser's registration at a time. Unlocking syncs while the user may be
 * flipping the switch: run side by side, the sync (still waiting on the network) subscribed
 * again after "off", or sent the settings as they were before the change.
 */
let pending: Promise<unknown> = Promise.resolve();
function oneAtATime<T>(work: () => Promise<T>): Promise<T> {
  const run = pending.then(work, work);
  pending = run.catch(() => undefined);
  return run;
}

/** The settings as they are now, for the server (never a copy read before a wait). */
function currentServerSettings() {
  return serverSettings(loadNotificationPrefs());
}

export function notificationPermission(): Permission {
  if (typeof Notification === "undefined") return "unsupported";
  return Notification.permission;
}

/** Push while closed needs a service worker and the Push API (on iOS: the installed app). */
export function pushSupported(): boolean {
  return (
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    typeof window !== "undefined" &&
    "PushManager" in window
  );
}

async function workerRegistration(create: boolean): Promise<ServiceWorkerRegistration | null> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return null;
  try {
    const existing = await navigator.serviceWorker.getRegistration("/");
    if (existing || !create) return existing ?? null;
    await navigator.serviceWorker.register(WORKER_URL, { scope: "/" });
    return await navigator.serviceWorker.ready;
  } catch {
    return null;
  }
}

function base64UrlBytes(value: string): Uint8Array<ArrayBuffer> {
  const b64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function sameKey(current: ArrayBuffer | null, wanted: Uint8Array): boolean {
  if (!current) return false;
  const bytes = new Uint8Array(current);
  return bytes.length === wanted.length && bytes.every((b, i) => b === wanted[i]);
}

/**
 * Subscribes (or keeps the subscription) and tells the server where to push. A subscription
 * made for a different server key — the server's changed — is replaced.
 */
async function subscribe(token: string): Promise<boolean> {
  if (!pushSupported()) return false;
  const registration = await workerRegistration(true);
  if (!registration) return false;
  const { public_key } = await api.webPushKey(token);
  const serverKey = base64UrlBytes(public_key);
  let subscription = await registration.pushManager.getSubscription();
  if (subscription && !sameKey(subscription.options.applicationServerKey, serverKey)) {
    await subscription.unsubscribe().catch(() => false);
    subscription = null;
  }
  subscription ??= await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: serverKey,
  });
  try {
    await api.putWebPushSubscription(token, subscription.toJSON());
  } catch (err) {
    // Refused (a push service this server does not send to): a subscription only the browser
    // knows would make Settings say pushes arrive while Shroud is closed.
    if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
      await subscription.unsubscribe().catch(() => false);
    }
    throw err;
  }
  return true;
}

async function unsubscribe(token: string | null): Promise<void> {
  const registration = await workerRegistration(false);
  try {
    const subscription = await registration?.pushManager.getSubscription();
    await subscription?.unsubscribe();
  } catch {
    /* the server forgets it below either way */
  }
  if (token) await api.deleteWebPushSubscription(token).catch(() => undefined);
}

export type EnableOutcome =
  /** Pushes arrive while Shroud is closed, and the page notifies while open. */
  | "enabled"
  /** This browser has no Web Push (Safari on iPhone outside an installed app): open only. */
  | "open-only"
  /** The browser blocks notifications for Shroud (its site settings can allow them). */
  | "denied"
  /** The permission prompt was closed without an answer: asking again can work. */
  | "dismissed"
  | "unsupported";

/**
 * Asks for permission and turns notifications on. Call it straight from a click: browsers only
 * show the permission prompt for a user's gesture, so nothing is awaited before it.
 */
export async function enableNotifications(token: string): Promise<EnableOutcome> {
  if (typeof Notification === "undefined") return "unsupported";
  const permission =
    Notification.permission === "default"
      ? await Notification.requestPermission()
      : Notification.permission;
  if (permission !== "granted") return permission === "denied" ? "denied" : "dismissed";
  updateNotificationPrefs({ enabled: true });
  return oneAtATime(async () => {
    let pushing = false;
    try {
      pushing = await subscribe(token);
    } catch {
      pushing = false;
    }
    await api.updateNotificationSettings(token, currentServerSettings()).catch(() => undefined);
    return pushing ? "enabled" : "open-only";
  });
}

export async function disableNotifications(token: string): Promise<void> {
  updateNotificationPrefs({ enabled: false });
  await oneAtATime(() => turnOff(token));
}

async function turnOff(token: string): Promise<void> {
  await unsubscribe(token);
  await api.updateNotificationSettings(token, currentServerSettings()).catch(() => undefined);
  await closeNotifications();
}

/** Sends the preferences the server acts on (after one changed in Settings). */
export async function pushSettingsToServer(token: string): Promise<void> {
  await oneAtATime(() => api.updateNotificationSettings(token, currentServerSettings()));
}

/**
 * Each unlock: the server's view matches this browser's. The push subscription can rotate and
 * permission can be taken back in the browser's own settings, both while Shroud is closed.
 */
export function syncNotifications(token: string): Promise<void> {
  return oneAtATime(async () => {
    const prefs = loadNotificationPrefs();
    if (prefs.enabled && notificationPermission() !== "granted") {
      updateNotificationPrefs({ enabled: false });
      await turnOff(token);
      return;
    }
    if (!prefs.enabled) {
      // Only a browser that was subscribed has anything to take back.
      const registration = await workerRegistration(false);
      if (await registration?.pushManager.getSubscription().catch(() => null)) {
        await unsubscribe(token);
      }
      return;
    }
    try {
      await subscribe(token);
    } catch {
      /* no Web Push here, or the server is unreachable: the next unlock tries again */
    }
    await api.updateNotificationSettings(token, currentServerSettings()).catch(() => undefined);
  });
}

type PageNotification = {
  kind: string;
  tag: string;
  peer: string | null;
  title: string;
  body: string;
  silent: boolean;
  /** Several messages of one chat collapse into one notification that counts them. */
  countLine?: (count: number) => string;
};

/** A notification from the open page, through the worker when there is one (clicks route). */
export async function showPageNotification(notification: PageNotification): Promise<void> {
  if (notificationPermission() !== "granted") return;
  const registration = await workerRegistration(false);
  let count = 1;
  if (registration && notification.countLine) {
    try {
      for (const open of await registration.getNotifications({ tag: notification.tag })) {
        const data = open.data as { kind?: string; count?: number } | null;
        if (data?.kind === "message") count += Number(data.count) || 1;
      }
    } catch {
      /* counting is cosmetic */
    }
  }
  const body = count > 1 && notification.countLine ? notification.countLine(count) : notification.body;
  const options: NotificationOptions & { renotify?: boolean; timestamp?: number } = {
    body,
    tag: notification.tag,
    renotify: true,
    silent: notification.silent,
    icon: "/icon-192.png",
    timestamp: Date.now(),
    data: { kind: notification.kind, peer: notification.peer, count },
  };
  try {
    if (registration) {
      await registration.showNotification(notification.title, options);
      return;
    }
    const shown = new Notification(notification.title, options);
    shown.onclick = () => {
      window.focus();
      shown.close();
      route({ kind: notification.kind, peer: notification.peer });
    };
  } catch {
    /* the browser refused (permission changed): nothing to show */
  }
}

/** The tag of a chat's reaction notifications (its messages use the conversation id). */
export function reactionTag(conversationId: string): string {
  return `${conversationId.toLowerCase()}:reaction`;
}

/** Closes a chat's notifications: its messages' and its reactions'. */
export async function closeChatNotifications(conversationId: string): Promise<void> {
  const id = conversationId.toLowerCase();
  await Promise.all([closeNotifications(id), closeNotifications(reactionTag(id))]);
}

/** Closes this browser's notifications — those with `tag`, or all of them. */
export async function closeNotifications(tag?: string): Promise<void> {
  const registration = await workerRegistration(false);
  if (!registration) return;
  try {
    for (const shown of await registration.getNotifications(tag ? { tag } : undefined)) {
      shown.close();
    }
  } catch {
    /* nothing open */
  }
}

/* --- clicks ------------------------------------------------------------------------------- */

export type OpenRequest = { kind: string; peer: string | null };

let opener: ((open: OpenRequest) => void) | null = null;
/**
 * A click while locked (the chats are not mounted), kept for after the PIN — in memory only:
 * nothing unsealed about a chat is written while the vault is closed. A reload drops it.
 */
let pendingOpen: OpenRequest | null = null;

function route(open: OpenRequest): void {
  if (opener) {
    opener(open);
    return;
  }
  pendingOpen = open;
}

/** Once per page: the worker's clicks come here, including one that opened this window. */
export function installNotificationClicks(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  const container = navigator.serviceWorker;
  container.addEventListener("message", (event: MessageEvent) => {
    const data = event.data as { type?: string; kind?: unknown; peer?: unknown } | null;
    if (data?.type !== "shroud.open-chat") return;
    route({
      kind: typeof data.kind === "string" ? data.kind : "message",
      peer: typeof data.peer === "string" ? data.peer : null,
    });
  });
  container.startMessages();
  void container
    .getRegistration("/")
    .then((registration) => registration?.active?.postMessage({ type: "shroud.take-pending-open" }))
    .catch(() => undefined);
}

/** The chat shell handles clicks while it is mounted, starting with one that came earlier. */
export function onNotificationOpen(handler: (open: OpenRequest) => void): () => void {
  opener = handler;
  if (pendingOpen) {
    const open = pendingOpen;
    pendingOpen = null;
    queueMicrotask(() => handler(open));
  }
  return () => {
    if (opener === handler) opener = null;
  };
}

/* --- logout ------------------------------------------------------------------------------- */

/** Nothing of this browser's push setup outlives a logout: subscription, notifications, worker. */
export async function forgetPushRegistration(): Promise<void> {
  pendingOpen = null;
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  try {
    for (const registration of await navigator.serviceWorker.getRegistrations()) {
      try {
        await (await registration.pushManager.getSubscription())?.unsubscribe();
      } catch {
        /* the server dropped it at logout already */
      }
      try {
        for (const shown of await registration.getNotifications()) shown.close();
      } catch {
        /* nothing open */
      }
      await registration.unregister().catch(() => false);
    }
  } catch {
    /* no registrations to read */
  }
  try {
    await navigator.clearAppBadge?.();
  } catch {
    /* no badge */
  }
}

/** This browser holds a push subscription (so pushes can reach it while Shroud is closed). */
export async function hasPushSubscription(): Promise<boolean> {
  if (!pushSupported()) return false;
  const registration = await workerRegistration(false);
  try {
    return Boolean(await registration?.pushManager.getSubscription());
  } catch {
    return false;
  }
}

export async function hasPushRegistration(): Promise<boolean> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return false;
  try {
    return (await navigator.serviceWorker.getRegistrations()).length > 0;
  } catch {
    return false;
  }
}

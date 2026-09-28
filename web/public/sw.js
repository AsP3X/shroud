/*
 * Shroud's service worker: Web Push notifications, nothing else. It fetches nothing and caches
 * nothing — except the one marker below that says this browser was removed from the account.
 *
 * A push arrives encrypted to this browser (RFC 8291), so only the browser reads it. It holds
 * ids, a kind and — when this browser's settings ask for it — the sender's name; never message
 * text, which the server does not have. The page itself shows richer notifications while it is
 * unlocked (src/notifications/), with the same tags, so one chat never stacks up: its messages
 * share the conversation id, its reactions `<id>:reaction`. Calls share the tag `calls`, so the
 * "Missed call" that follows an unanswered ring replaces it.
 */

const LINES = {
  message: "New message",
  reaction: "Reacted to your message",
  contact_request: "Wants to add you as a contact",
  call: "Incoming call",
  video_call: "Incoming video call",
  missed_call: "Missed call",
  call_ended: "Call ended",
  test: "Notifications are working",
};

/** A ringing call stays up until it is answered to; clicking it only brings Shroud forward. */
const RINGS = ["call", "video_call"];

/*
 * "This browser was removed from your account" (another device's Devices list). Every open tab
 * is told and runs the full wipe (src/deviceRemoval.ts, src/deviceWipe.ts). With none open, the
 * worker deletes what it can reach — IndexedDB, Cache Storage, its push subscription, the
 * notifications on screen — and the marker makes the next page load finish the job before it
 * shows anything: local storage, which a worker cannot touch, holds the rest.
 */
const REMOVED = "device_removed";
/** Must match src/deviceRemoval.ts. */
const REMOVED_MESSAGE = "shroud.device-removed";
const REMOVED_MARKER_CACHE = "shroud.device-removed";
/** The Whisper weights: public files the page's wipe keeps too. */
const KEPT_CACHES = ["transformers-cache", REMOVED_MARKER_CACHE];
/** src/crypto/mediaCache.ts, for browsers without `indexedDB.databases()`. */
const MEDIA_DB = "shroud-media";
const DB_DELETE_TIMEOUT_MS = 2500;

/** A notification click that had to open a new window: handed over when that window asks. */
let pendingOpen = null;
const PENDING_OPEN_MS = 2 * 60 * 1000;

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
  const data = payloadOf(event);
  event.waitUntil(data.kind === REMOVED ? deviceRemoved() : show(data));
});

function payloadOf(event) {
  try {
    const data = event.data ? event.data.json() : null;
    if (data && typeof data === "object") return data;
  } catch {
    /* not JSON: still a push, still shown */
  }
  return { kind: "message" };
}

async function show(data) {
  const kind = typeof data.kind === "string" && LINES[data.kind] ? data.kind : "message";
  await setBadge(data.badge);
  const tag = typeof data.tag === "string" && data.tag ? data.tag : kind;
  // Several messages in one chat read as one notification that counts them.
  let count = 1;
  if (kind === "message") {
    for (const open of await self.registration.getNotifications({ tag })) {
      if (open.data && open.data.kind === "message") count += Number(open.data.count) || 1;
    }
  }
  const title = typeof data.sender === "string" && data.sender ? data.sender : "Shroud";
  const body = kind === "message" && count > 1 ? `${count} new messages` : LINES[kind];
  await self.registration.showNotification(title, {
    body,
    tag,
    renotify: true,
    silent: data.silent === true,
    requireInteraction: RINGS.includes(kind),
    icon: "/icon-192.png",
    timestamp: Date.now(),
    data: {
      kind,
      peer: typeof data.peer_user_id === "string" ? data.peer_user_id : null,
      count,
    },
  });
}

async function deviceRemoved() {
  // First: a worker stopped halfway still leaves the next page load the whole job.
  await leaveRemovalMarker();
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  // One tab runs the wipe; its "wipe" broadcast reloads the others onto the emptied store.
  // Telling every tab would start several wipes that fight over the same databases.
  const runner =
    windows.find((client) => client.focused) ||
    windows.find((client) => client.visibilityState === "visible") ||
    windows[0];
  if (runner) {
    try {
      runner.postMessage({ type: REMOVED_MESSAGE });
    } catch {
      /* the marker still reaches it on its next load */
    }
  }
  // An open tab runs the wipe itself; deleting its databases under it would only block.
  if (windows.length === 0) {
    await Promise.all([deleteDatabases(), deleteCaches(), unsubscribePush()]);
  }
  // Earlier notifications may name a contact. Browsers require one for every push, so a neutral
  // one takes their place.
  try {
    for (const shown of await self.registration.getNotifications()) shown.close();
  } catch {
    /* nothing open */
  }
  await setBadge(0);
  await self.registration.showNotification("Shroud", {
    body: "This browser was signed out.",
    tag: REMOVED,
    icon: "/icon-192.png",
    timestamp: Date.now(),
    data: { kind: REMOVED, peer: null, count: 1 },
  });
}

async function leaveRemovalMarker() {
  try {
    const cache = await caches.open(REMOVED_MARKER_CACHE);
    await cache.put("/device-removed", new Response(String(Date.now())));
  } catch {
    /* no Cache Storage: open tabs still hear the message, and the next unlock gets a 401 */
  }
}

async function deleteDatabases() {
  const names = new Set([MEDIA_DB]);
  try {
    if (typeof indexedDB.databases === "function") {
      for (const db of await indexedDB.databases()) if (db.name) names.add(db.name);
    }
  } catch {
    /* the known one is deleted anyway */
  }
  await Promise.all(
    [...names].map(
      (name) =>
        new Promise((resolve) => {
          const timer = setTimeout(resolve, DB_DELETE_TIMEOUT_MS);
          const done = () => {
            clearTimeout(timer);
            resolve();
          };
          try {
            const request = indexedDB.deleteDatabase(name);
            request.onsuccess = done;
            request.onerror = done;
          } catch {
            done();
          }
        }),
    ),
  );
}

async function deleteCaches() {
  try {
    const names = await caches.keys();
    await Promise.all(names.filter((name) => !KEPT_CACHES.includes(name)).map((name) => caches.delete(name)));
  } catch {
    /* the page's wipe deletes them */
  }
}

async function unsubscribePush() {
  try {
    const subscription = await self.registration.pushManager.getSubscription();
    await subscription?.unsubscribe();
  } catch {
    /* the server dropped it with the device */
  }
}

async function setBadge(badge) {
  if (typeof badge !== "number" || !("setAppBadge" in self.navigator)) return;
  try {
    if (badge > 0) await self.navigator.setAppBadge(badge);
    else await self.navigator.clearAppBadge();
  } catch {
    /* badges are a nicety */
  }
}

self.addEventListener("notificationclick", (event) => {
  const data = event.notification.data || {};
  event.notification.close();
  // A test notification opens Shroud and nothing in it. So does a ringing call: once unlocked,
  // the socket hands the page the ring if it still rings. So does a removal: the page wipes.
  const open =
    data.kind === "test" || data.kind === REMOVED || RINGS.includes(data.kind)
      ? null
      : { type: "shroud.open-chat", kind: data.kind || "message", peer: data.peer || null };
  event.waitUntil(openApp(open));
});

function isAppWindow(client) {
  try {
    return new URL(client.url).pathname.startsWith("/app");
  } catch {
    return false;
  }
}

async function openApp(open) {
  // Most recently focused first: the focused window, else Shroud's chats, else any of its tabs.
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  const client = windows.find((w) => w.focused) || windows.find(isAppWindow) || windows[0];
  if (client) {
    try {
      await client.focus();
    } catch {
      /* focusing is best effort */
    }
    if (open) client.postMessage(open);
    return;
  }
  if (!open) {
    await self.clients.openWindow("/app");
    return;
  }
  // The chat is not in the URL (it would sit in the browser's history); the new window asks.
  pendingOpen = { ...open, at: Date.now() };
  await self.clients.openWindow("/app");
}

self.addEventListener("message", (event) => {
  const data = event.data || {};
  if (data.type !== "shroud.take-pending-open") return;
  const pending = pendingOpen && Date.now() - pendingOpen.at < PENDING_OPEN_MS ? pendingOpen : null;
  pendingOpen = null;
  if (pending && event.source) {
    event.source.postMessage({ type: pending.type, kind: pending.kind, peer: pending.peer });
  }
});

// A rotated subscription needs the session to re-register, which only an unlocked page has:
// the page compares and re-registers each time it opens (src/notifications/push.ts).

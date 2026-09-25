/*
 * Shroud's service worker: Web Push notifications, nothing else. It caches nothing and fetches
 * nothing.
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
  test: "Notifications are working",
};

/** A ringing call stays up until it is answered to; clicking it only brings Shroud forward. */
const RINGS = ["call", "video_call"];

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
  event.waitUntil(show(payloadOf(event)));
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
  // the socket hands the page the ring if it still rings.
  const open =
    data.kind === "test" || RINGS.includes(data.kind)
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

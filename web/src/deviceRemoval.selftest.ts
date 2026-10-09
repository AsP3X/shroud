/**
 * Removing this browser from the account elsewhere: the token hash the lock screen asks with,
 * when an answer wipes, who runs the wipe, and what the service worker does with the push.
 * Run from web/: npx esbuild src/deviceRemoval.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { readFileSync } from "node:fs";
import {
  clearRemovalMarker,
  installRemovalMessages,
  onDeviceRemoved,
  pendingRemovalReason,
  REMOVED_MARKER_CACHE,
  removalPending,
  removedWhileClosed,
  signalDeviceRemoved,
  TOKEN_HASH_KEY,
  tokenHash,
  watchForRemoval,
} from "./deviceRemoval";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

/* --- the page's world ---------------------------------------------------------------------- */

const memory = new Map<string, string>();
const page = { hidden: false };
const docListeners = new Map<string, Set<() => void>>();
const winListeners = new Map<string, Set<() => void>>();
const intervals = new Map<number, () => void>();
let nextTimer = 1;
const on = (map: Map<string, Set<() => void>>) => (type: string, fn: () => void) => {
  if (!map.has(type)) map.set(type, new Set());
  map.get(type)!.add(fn);
};
const off = (map: Map<string, Set<() => void>>) => (type: string, fn: () => void) => map.get(type)?.delete(fn);
const fire = (map: Map<string, Set<() => void>>, type: string) => {
  for (const fn of [...(map.get(type) ?? [])]) fn();
};

Object.defineProperty(globalThis, "localStorage", {
  value: {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => void memory.set(key, value),
    removeItem: (key: string) => void memory.delete(key),
  },
  configurable: true,
});
Object.defineProperty(globalThis, "document", {
  value: {
    get hidden() {
      return page.hidden;
    },
    addEventListener: on(docListeners),
    removeEventListener: off(docListeners),
  },
  configurable: true,
});
Object.defineProperty(globalThis, "window", {
  value: {
    addEventListener: on(winListeners),
    removeEventListener: off(winListeners),
    setInterval: (fn: () => void) => {
      const id = nextTimer++;
      intervals.set(id, fn);
      return id;
    },
    clearInterval: (id: number) => void intervals.delete(id),
    setTimeout: (fn: () => void, ms: number) => setTimeout(fn, ms),
    clearTimeout: (id: ReturnType<typeof setTimeout>) => clearTimeout(id),
  },
  configurable: true,
});

/** What `session-status` answers next, and what was asked. */
let answer: () => Promise<Response> = async () => Response.json({ removed: false });
const asked: { url: string; body: unknown }[] = [];
Object.defineProperty(globalThis, "fetch", {
  value: async (url: string, init: RequestInit) => {
    asked.push({ url, body: JSON.parse(String(init.body)) });
    return answer();
  },
  configurable: true,
});

/* --- the token hash ------------------------------------------------------------------------ */

// SHA-256("abc"), standard Base64 with padding — the server's `token_hash`.
check(tokenHash("abc") === "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", "known SHA-256 vector");
check(tokenHash("ü") !== tokenHash("u"), "hashes the UTF-8 bytes");
check(TOKEN_HASH_KEY === "shroud.token-hash", "the key the wipe's prefixes cover");

/* --- asking while locked ------------------------------------------------------------------- */

async function watch(run: (removed: () => number) => Promise<void>) {
  let calls = 0;
  const stop = watchForRemoval(() => {
    calls++;
  });
  await flush();
  try {
    await run(() => calls);
  } finally {
    stop();
  }
}

// No hash (a session from before it existed): nothing to ask with.
memory.delete(TOKEN_HASH_KEY);
asked.length = 0;
await watch(async (removed) => {
  check(asked.length === 0 && removed() === 0, "no hash, no request");
});

memory.set(TOKEN_HASH_KEY, tokenHash("token-1"));
asked.length = 0;
await watch(async (removed) => {
  check(asked.length === 1, "asks once on start");
  check(asked[0].url === "/api/v1/auth/session-status", `the endpoint: ${asked[0].url}`);
  check(
    (asked[0].body as { token_hash?: string }).token_hash === tokenHash("token-1"),
    "sends the stored hash",
  );
  check(removed() === 0, "removed: false keeps the browser");

  fire(docListeners, "visibilitychange");
  fire(winListeners, "focus");
  await flush();
  check(asked.length === 1, "showing the tab right after a question does not ask again");

  for (const failure of [
    async () => new Response(JSON.stringify({ error: { code: "RATE_LIMITED" } }), { status: 429 }),
    async () => new Response("", { status: 503 }),
    async () => {
      throw new TypeError("offline");
    },
    async () => Response.json({}),
  ]) {
    answer = failure;
    for (const tick of intervals.values()) tick();
    await flush();
  }
  check(asked.length === 5, `the interval keeps asking: ${asked.length}`);
  check(removed() === 0, "429, 5xx, offline and a malformed answer never wipe");

  page.hidden = true;
  for (const tick of intervals.values()) tick();
  await flush();
  check(asked.length === 5, "a hidden tab does not ask");
  page.hidden = false;

  answer = async () => Response.json({ removed: true });
  for (const tick of intervals.values()) tick();
  await flush();
  check(removed() === 1, "removed: true wipes");
  for (const tick of intervals.values()) tick();
  fire(winListeners, "online");
  await flush();
  check(removed() === 1 && asked.length === 6, "once, and then it stops asking");
});
check(intervals.size === 0, "the disposer clears the interval");
check([...docListeners.values(), ...winListeners.values()].every((set) => set.size === 0), "and the listeners");
answer = async () => Response.json({ removed: false });

/* --- who runs the wipe --------------------------------------------------------------------- */

{
  const log: string[] = [];
  signalDeviceRemoved();
  check(removalPending(), "a removal before anyone listens waits");
  const offApp = onDeviceRemoved("app", () => log.push("app"));
  check(!removalPending(), "the first listener takes it");
  await flush();
  check(log.join() === "app", "and runs it");

  const offShell = onDeviceRemoved("shell", () => log.push("shell"));
  signalDeviceRemoved();
  check(log.join() === "app,shell", "the mounted shell runs it, not the app");
  offShell();
  signalDeviceRemoved();
  check(log.join() === "app,shell,app", "without the shell the app does");
  offApp();
}

{
  let seen: string | null | undefined = "missing";
  const off = onDeviceRemoved("app", (reason) => {
    seen = reason;
  });
  signalDeviceRemoved("account_deleted");
  check(seen === "account_deleted", "a listener hears that the account was deleted");
  signalDeviceRemoved();
  check(seen === null, "a signal without a reason stays a plain removal");
  signalDeviceRemoved("other");
  check(seen === null, "any other reason stays a plain removal");
  off();

  signalDeviceRemoved("account_deleted");
  check(removalPending() && pendingRemovalReason() === "account_deleted", "it waits, and keeps the reason");
  seen = "missing";
  const offLater = onDeviceRemoved("app", (reason) => {
    seen = reason;
  });
  check(!removalPending(), "the listener takes the waiting removal");
  await flush();
  check(seen === "account_deleted", "and then receives the reason");
  offLater();
}

/* --- the service worker -------------------------------------------------------------------- */

type Stored = Map<string, Map<string, string>>;

function fakeCaches(names: string[]): { api: CacheStorage; stored: Stored } {
  const stored: Stored = new Map(names.map((name) => [name, new Map()]));
  const api = {
    async open(name: string) {
      if (!stored.has(name)) stored.set(name, new Map());
      const entries = stored.get(name)!;
      return {
        async put(request: string, response: Response) {
          entries.set(request, await response.text());
        },
      };
    },
    async keys() {
      return [...stored.keys()];
    },
    async has(name: string) {
      return stored.has(name);
    },
    async delete(name: string) {
      return stored.delete(name);
    },
  };
  return { api: api as unknown as CacheStorage, stored };
}

type WorkerRun = {
  posted: unknown[];
  /** Which window (by index) each message went to. */
  postedTo: number[];
  deletedDbs: string[];
  stored: Stored;
  shown: { title: string; body?: string; tag?: string }[];
  closed: number;
  unsubscribed: boolean;
};

async function runWorker(payload: unknown, windows: number, focused = -1): Promise<WorkerRun> {
  const run: WorkerRun = {
    posted: [],
    postedTo: [],
    deletedDbs: [],
    stored: new Map(),
    shown: [],
    closed: 0,
    unsubscribed: false,
  };
  const { api: cachesApi, stored } = fakeCaches(["transformers-cache", "offline-v1"]);
  run.stored = stored;
  const listeners: Record<string, (event: unknown) => void> = {};
  const self = {
    addEventListener: (type: string, fn: (event: unknown) => void) => {
      listeners[type] = fn;
    },
    skipWaiting() {},
    navigator: {},
    clients: {
      claim: async () => undefined,
      matchAll: async () =>
        Array.from({ length: windows }, (_, index) => ({
          focused: index === focused,
          visibilityState: index === focused ? "visible" : "hidden",
          postMessage: (m: unknown) => {
            run.posted.push(m);
            run.postedTo.push(index);
          },
        })),
    },
    registration: {
      // One notification from another chat is on screen.
      getNotifications: async (filter?: { tag?: string }) =>
        filter?.tag ? [] : [{ data: { kind: "message" }, close: () => run.closed++ }],
      showNotification: async (title: string, options: NotificationOptions) => {
        run.shown.push({ title, body: options.body, tag: options.tag });
      },
      pushManager: {
        getSubscription: async () => ({
          unsubscribe: async () => {
            run.unsubscribed = true;
            return true;
          },
        }),
      },
    },
  };
  const indexedDB = {
    databases: async () => [{ name: "shroud-media" }, { name: "shroud-other" }],
    deleteDatabase(name: string) {
      const request: { onsuccess?: () => void; onerror?: () => void } = {};
      setTimeout(() => {
        run.deletedDbs.push(name);
        request.onsuccess?.();
      }, 0);
      return request;
    },
  };
  new Function("self", "caches", "indexedDB", readFileSync("public/sw.js", "utf8"))(self, cachesApi, indexedDB);
  let work: Promise<unknown> = Promise.resolve();
  listeners.push({
    data: { json: () => payload },
    waitUntil: (promise: Promise<unknown>) => {
      work = promise;
    },
  });
  await work;
  return run;
}

const REMOVED_PUSH = { v: 1, kind: "device_removed" };

{
  // No tab open: the worker deletes what it can reach and leaves the marker.
  const run = await runWorker(REMOVED_PUSH, 0);
  check(run.posted.length === 0, "nobody to tell");
  check(run.stored.has(REMOVED_MARKER_CACHE), "leaves the marker the page looks for");
  const plainMarker = [...(run.stored.get(REMOVED_MARKER_CACHE)?.values() ?? [])].join("\n");
  check(!plainMarker.includes("account_deleted"), "a push without a reason leaves no deletion reason");
  check(run.stored.has("transformers-cache"), "keeps the Whisper weights");
  check(!run.stored.has("offline-v1"), "deletes every other cache");
  check(
    run.deletedDbs.sort().join() === "shroud-media,shroud-other",
    `deletes every database: ${run.deletedDbs.join()}`,
  );
  check(run.unsubscribed, "drops its push subscription");
  check(run.closed === 1, "closes notifications that may name a contact");
  check(
    run.shown.length === 1 &&
      run.shown[0].title === "Shroud" &&
      run.shown[0].body === "This browser was signed out.",
    `shows one neutral notification: ${JSON.stringify(run.shown)}`,
  );

  // The next page load finds the marker; the wipe's cache step takes it away with the rest.
  Object.defineProperty(globalThis, "caches", {
    value: {
      has: async (name: string) => run.stored.has(name),
      delete: async (name: string) => run.stored.delete(name),
    },
    configurable: true,
  });
  // No hash to ask with: the marker is trusted.
  memory.delete(TOKEN_HASH_KEY);
  check((await removedWhileClosed()) != null, "the page sees the marker");
  // With a hash, the server is asked first: a stale marker must not wipe a newer login.
  memory.set(TOKEN_HASH_KEY, tokenHash("token-2"));
  answer = async () => {
    throw new TypeError("offline");
  };
  check((await removedWhileClosed()) != null, "offline, the marker is trusted");
  answer = async () => Response.json({ removed: true });
  check((await removedWhileClosed()) != null, "the server confirms it");
  answer = async () => Response.json({ removed: false });
  check(!(await removedWhileClosed()), "the server says this session is fine: no wipe");
  check(!run.stored.has(REMOVED_MARKER_CACHE), "and the stale marker is dropped");
  run.stored.set(REMOVED_MARKER_CACHE, new Map());
  await clearRemovalMarker();
  check(!(await removedWhileClosed()), "and it can be cleared");
}

{
  // A Cache Storage slower than the startup check still signals the removal once it answers.
  memory.delete(TOKEN_HASH_KEY);
  let release: (value: boolean) => void = () => undefined;
  Object.defineProperty(globalThis, "caches", {
    value: { has: () => new Promise<boolean>((resolve) => (release = resolve)), delete: async () => true },
    configurable: true,
  });
  const realTimeout = (globalThis as { window: { setTimeout: unknown } }).window.setTimeout;
  (globalThis as { window: { setTimeout: unknown } }).window.setTimeout = (fn: () => void) => setTimeout(fn, 0);
  let wiped = 0;
  const offApp = onDeviceRemoved("app", () => wiped++);
  check(!(await removedWhileClosed()), "the startup check gives up on time");
  release(true);
  await flush();
  await flush();
  check(wiped === 1, "a late answer still starts the wipe");
  offApp();
  (globalThis as { window: { setTimeout: unknown } }).window.setTimeout = realTimeout;
}

{
  // The probe's answer belongs to the hash it asked with: a login meanwhile keeps its session.
  memory.set(TOKEN_HASH_KEY, tokenHash("old-token"));
  let respond: (response: Response) => void = () => undefined;
  answer = () => new Promise<Response>((resolve) => (respond = resolve));
  let calls = 0;
  const stop = watchForRemoval(() => calls++);
  await flush();
  memory.set(TOKEN_HASH_KEY, tokenHash("new-token"));
  respond(Response.json({ removed: true }));
  await flush();
  await flush();
  check(calls === 0, "an answer about the replaced session does not wipe the new one");
  stop();
  answer = async () => Response.json({ removed: false });
}

{
  // A tab is open: it runs the wipe itself, told by the worker.
  const run = await runWorker(REMOVED_PUSH, 3, 2);
  check(run.posted.length === 1 && run.postedTo[0] === 2, "tells one tab, the focused one; the others follow its wipe");
  check(run.deletedDbs.length === 0 && run.stored.has("offline-v1"), "leaves the deleting to the tab");
  check(!run.unsubscribed, "the tab's wipe drops the subscription");
  check(run.stored.has(REMOVED_MARKER_CACHE), "the marker still covers a tab closed before it acts");
  check(run.shown.length === 1 && run.shown[0].body === "This browser was signed out.", "still shows the notice");

  // The page end of that message.
  const container: { listener?: (event: { data: unknown }) => void } = {};
  Object.defineProperty(globalThis, "navigator", {
    value: {
      serviceWorker: {
        addEventListener: (_: string, fn: (event: { data: unknown }) => void) => {
          container.listener = fn;
        },
        startMessages() {},
      },
    },
    configurable: true,
  });
  installRemovalMessages();
  let wiped = 0;
  let seen: string | null = "unset";
  const offApp = onDeviceRemoved("app", (reason) => {
    wiped++;
    seen = reason;
  });
  container.listener?.({ data: { type: "shroud.open-chat" } });
  check(wiped === 0, "other worker messages are not a removal");
  container.listener?.({ data: run.posted[0] });
  check(wiped === 1 && seen === null, "a push without a reason starts a plain removal");
  container.listener?.({ data: { type: "shroud.device-removed", reason: "account_deleted" } });
  check(wiped === 2 && seen === "account_deleted", "a push that names account_deleted says so");
  container.listener?.({ data: { type: "shroud.device-removed", reason: "nope" } });
  check(wiped === 3 && seen === null, "any other push reason stays a plain removal");
  offApp();
}

{
  // Every other push is a notification as before, and never touches data.
  const run = await runWorker({ kind: "message", peer_user_id: "x", tag: "c1" }, 0);
  check(run.shown.length === 1 && run.shown[0].body === "New message", "a message push still notifies");
  check(!run.stored.has(REMOVED_MARKER_CACHE) && run.deletedDbs.length === 0, "and deletes nothing");
}

{
  // A deleted account: the worker still deletes the same things, and writes the reason down.
  const run = await runWorker({ v: 1, kind: "device_removed", reason: "account_deleted" }, 0);
  const markerBody = [...(run.stored.get(REMOVED_MARKER_CACHE)?.values() ?? [])].join("\n");
  check(markerBody.includes("account_deleted"), `the marker carries the reason: ${markerBody}`);
  check(
    run.deletedDbs.sort().join() === "shroud-media,shroud-other",
    `still deletes every database: ${run.deletedDbs.join()}`,
  );
  check(run.stored.has("transformers-cache") && !run.stored.has("offline-v1"), "still keeps only the Whisper weights");
  check(run.unsubscribed, "still drops the push subscription");
  check(run.shown.length === 1 && run.shown[0].body === "This browser was signed out.", "the notice is unchanged");
}
{
  const run = await runWorker({ v: 1, kind: "device_removed", reason: "account_deleted" }, 2, 0);
  check(
    JSON.stringify(run.posted[0]) === JSON.stringify({ type: "shroud.device-removed", reason: "account_deleted" }),
    `the open tab is told why: ${JSON.stringify(run.posted[0])}`,
  );
  check(run.deletedDbs.length === 0 && run.stored.has("offline-v1"), "an open tab still does the deleting");
}
{
  const run = await runWorker({ v: 1, kind: "device_removed", reason: "other" }, 1, 0);
  check(
    JSON.stringify(run.posted[0]) === JSON.stringify({ type: "shroud.device-removed" }),
    `an unknown reason is not forwarded: ${JSON.stringify(run.posted[0])}`,
  );
}

{
  // Locked: session-status carries the reason. A removed answer without one stays plain.
  memory.set(TOKEN_HASH_KEY, tokenHash("token-deleted"));
  answer = async () => Response.json({ removed: true, reason: "account_deleted" });
  let reason: string | null | undefined = "missing";
  const stopDeleted = watchForRemoval((next) => {
    reason = next;
  });
  await flush();
  check(reason === "account_deleted", "session-status account_deleted wipes as the account deleted");
  stopDeleted();

  answer = async () => Response.json({ removed: true });
  reason = "missing";
  const stopPlain = watchForRemoval((next) => {
    reason = next;
  });
  await flush();
  check(reason === null, "session-status removed without a reason stays a plain removal");
  stopPlain();
  answer = async () => Response.json({ removed: false });
}

{
  // Closed: the next load reads the reason out of the marker. The server wins when it answers.
  memory.delete(TOKEN_HASH_KEY);
  const entries = new Map<string, string>([["/device-removed", JSON.stringify({ reason: "account_deleted" })]]);
  Object.defineProperty(globalThis, "caches", {
    value: {
      has: async (name: string) => name === REMOVED_MARKER_CACHE && entries.size > 0,
      delete: async () => {
        entries.clear();
        return true;
      },
      open: async () => ({
        match: async (url: string) => {
          const text = entries.get(url);
          return text == null ? undefined : new Response(text);
        },
      }),
    },
    configurable: true,
  });
  const fromMarker = await removedWhileClosed();
  check(fromMarker?.reason === "account_deleted", "a closed tab reads account_deleted from the marker");

  memory.set(TOKEN_HASH_KEY, tokenHash("token-closed"));
  entries.set("/device-removed", String(Date.now()));
  answer = async () => Response.json({ removed: true, reason: "account_deleted" });
  const fromStatus = await removedWhileClosed();
  check(fromStatus?.reason === "account_deleted", "session-status names the deletion when the marker does not");

  entries.set("/device-removed", JSON.stringify({ reason: "account_deleted" }));
  answer = async () => Response.json({ removed: true });
  const plain = await removedWhileClosed();
  check(plain?.reason === null, "session-status without a reason stays a plain removal");

  answer = async () => {
    throw new TypeError("offline");
  };
  const offline = await removedWhileClosed();
  check(offline?.reason === "account_deleted", "offline, the marker's reason is trusted");
}

console.log("device removal selftest ok");

/**
 * Every request to the Shroud API names this app (`X-Shroud-Client: web/<build id>`), and both
 * WebSockets carry it as `?client=`; a 426 `UPDATE_REQUIRED` is an ordinary API error, never a
 * sign-out. Run: `npx tsx src/api/clientHeader.selftest.ts`.
 */
import { randomBytes } from "../crypto/bytes";
import { FileJobError, runFileJob } from "../media/fileJobs";
import { CLIENT_HEADER, clientName, clientNameFor, linkRelayUrl, wsUrl } from "../config";
import { api, ApiError } from "./client";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });
Object.defineProperty(globalThis, "location", {
  value: { protocol: "https:", host: "chat.example", href: "https://chat.example/chats" },
  configurable: true,
});

// The value: what the server accepts for the web (client_version.rs `valid_web_build`).
const name = clientName();
check(/^web\/[A-Za-z0-9._-]{1,64}$/.test(name), `a well-formed web build: ${name}`);
// tsx has no Vite env, as in `npm run dev`: no build id, so the fallback.
check(name === "web/dev", `no build id says web/dev: ${name}`);
// A stamped id goes as it is when the server can read it, else the fallback.
check(clientNameFor("0a1b2c3d4e5f") === "web/0a1b2c3d4e5f", "deploy.sh's 12 hex characters");
check(clientNameFor("v1.2_rc-3") === "web/v1.2_rc-3", "letters, digits, dot, dash and underscore");
check(clientNameFor("a".repeat(64)) === `web/${"a".repeat(64)}`, "64 characters is the longest");
check(clientNameFor("a".repeat(65)) === "web/dev", "65 characters is too long");
check(clientNameFor("1.0+abc") === "web/dev", "a plus sign is not allowed");
check(clientNameFor("build 7") === "web/dev", "nor a space");
check(clientNameFor("web/abc") === "web/dev", "nor a slash");
check(clientNameFor("") === "web/dev", "an empty id falls back");

// WebSockets: a browser can't set the header on the upgrade, so it goes in the query.
check(wsUrl() === "wss://chat.example/api/v1/ws?client=web%2Fdev", `realtime socket: ${wsUrl()}`);
check(
  linkRelayUrl() === "wss://chat.example/api/v1/link-relay?client=web%2Fdev",
  `link relay socket: ${linkRelayUrl()}`,
);

/* --- HTTP: every way the client reaches the API ---------------------------------------------- */

type Seen = { url: string; client: string | null };
const seen: Seen[] = [];
let answer: () => Response = () => new Response("{}", { status: 200 });

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  seen.push({ url: String(input), client: new Headers(init?.headers).get(CLIENT_HEADER) });
  return answer();
}) as typeof fetch;

class FakeXhr {
  static last: FakeXhr | null = null;
  readonly headers = new Map<string, string>();
  url = "";
  status = 0;
  statusText = "";
  responseText = "";
  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  open(_method: string, url: string) {
    this.url = url;
    FakeXhr.last = this;
  }
  setRequestHeader(key: string, value: string) {
    this.headers.set(key.toLowerCase(), value);
  }
  send() {
    this.status = 204;
    queueMicrotask(() => this.onload?.());
  }
}
Object.defineProperty(globalThis, "XMLHttpRequest", { value: FakeXhr, configurable: true });

async function sends(label: string, path: string, run: () => Promise<unknown>): Promise<void> {
  seen.length = 0;
  await run().catch(() => undefined);
  check(seen.length === 1, `${label}: one request (${seen.length})`);
  check(seen[0].url.endsWith(path), `${label}: ${seen[0].url}`);
  check(seen[0].client === name, `${label}: ${CLIENT_HEADER} is ${seen[0].client}`);
}

const TOKEN = "token";
const MEDIA = "6f1d2c3b-4a59-4e8d-9c7b-0a1b2c3d4e5f";

await sends("JSON, no session", "/api/v1/health/live", () => api.health());
await sends("JSON, signed in", "/api/v1/contacts", () => api.contacts(TOKEN));
await sends("the version check", "/api/v1/client-version?platform=web&version=", () =>
  api.clientVersion("web", ""),
);
await sends("media download", `/api/v1/media/${MEDIA}/content`, () => api.getMediaContent(TOKEN, MEDIA));
await sends("media upload", `/api/v1/media/${MEDIA}/content`, () =>
  api.putMediaContent(TOKEN, MEDIA, new Uint8Array(4)),
);

// Uploads with progress go through XHR.
await api.putMediaContent(TOKEN, MEDIA, new Uint8Array(4), () => undefined);
check(FakeXhr.last?.url === `/api/v1/media/${MEDIA}/content`, "XHR upload URL");
check(FakeXhr.last?.headers.get(CLIENT_HEADER.toLowerCase()) === name, "XHR upload names the app");

// The file worker's download (fileJobs.ts) is its own fetch.
answer = () => new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "gone" } }), { status: 404 });
seen.length = 0;
const fileUrl = `https://chat.example/api/v1/media/${MEDIA}/content`;
await runFileJob(
  { type: "open", url: fileUrl, token: TOKEN, key: randomBytes(32), size: 10, mime: "application/pdf" },
  () => undefined,
).then(
  () => check(false, "a 404 download should fail"),
  (err) => check(err instanceof FileJobError, `file job error: ${String(err)}`),
);
check(seen.length === 1 && seen[0].url === fileUrl, "file download asked once");
check(seen[0].client === name, `file download names the app: ${seen[0].client}`);

/* --- 426 UPDATE_REQUIRED: an API error like any other ---------------------------------------- */

answer = () =>
  new Response(
    JSON.stringify({ error: { code: "UPDATE_REQUIRED", message: "This version of Shroud is no longer supported." } }),
    { status: 426, headers: { "Content-Type": "application/json" } },
  );
try {
  await api.contacts(TOKEN);
  check(false, "a 426 should throw");
} catch (err) {
  check(err instanceof ApiError, "a 426 is an ApiError");
  const error = err as ApiError;
  check(error.status === 426 && error.code === "UPDATE_REQUIRED", `code and status: ${error.code} ${error.status}`);
  check(!error.isAuthFailure && !error.isDeviceRemoved, "a 426 never signs out or wipes");
}

console.log("client header selftest ok");

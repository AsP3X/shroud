/** This bundle's build id, stamped by the deploy (`VITE_WEB_BUILD`). Empty in `npm run dev`. */
export function webBuild(): string {
  return (import.meta.env?.VITE_WEB_BUILD ?? "").trim();
}

/**
 * Names this app to the server on every API request (`server/.../client_version.rs`), which
 * refuses apps below the operator's minimum with `426 UPDATE_REQUIRED`. Never sent to other hosts.
 */
export const CLIENT_HEADER = "X-Shroud-Client";

/** The build ids the server accepts from the web (`valid_web_build` in client_version.rs). */
const WEB_BUILD_RE = /^[A-Za-z0-9._-]{1,64}$/;

/**
 * `web/<build id>` for a raw build id. The server lets any well-formed web build id through,
 * but refuses an empty or malformed one once a minimum is set, so those say `web/dev`.
 */
export function clientNameFor(build: string): string {
  return `web/${WEB_BUILD_RE.test(build) ? build : "dev"}`;
}

/**
 * `web/<build id>`, the build `GET /client-version` asks about. A bundle without one
 * (`npm run dev`, a manual build) or with one the server can't read (a hand-set `1.0+abc`)
 * says `web/dev`.
 */
export function clientName(): string {
  return clientNameFor(webBuild());
}

/** A browser can't set headers on a WebSocket upgrade; the server reads `?client=` instead. */
function withClientParam(url: string): string {
  return `${url}?client=${encodeURIComponent(clientName())}`;
}

export function apiBase(): string {
  const fromWindow = window.__SHROUD_CONFIG__?.apiBase?.trim();
  if (fromWindow) return fromWindow.replace(/\/$/, "");
  return "/api/v1";
}

/** Same-origin `/api/v1/ws` as wss/ws, naming this app in `?client=`. */
export function wsUrl(): string {
  const path = `${apiBase()}/ws`;
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return withClientParam(`${proto}//${window.location.host}${path}`);
}

/** Same-origin `/api/v1/link-relay` as wss/ws — the link-preview byte pipe (see linkPreview/) — with `?client=`. */
export function linkRelayUrl(): string {
  const path = `${apiBase()}/link-relay`;
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return withClientParam(`${proto}//${window.location.host}${path}`);
}

/** First letters of the first two words, else the first two letters. Splits on
 *  `_ . -` as well as spaces so a username like `niklas_v` matches its display
 *  name "Niklas V" (iOS `AvatarView.initials` only splits on whitespace). */
export function initials(name: string): string {
  // Parts with no letter or digit (the "·" in "Contact · 7f3c") are not initials.
  const parts = name.trim().split(/[\s_.@-]+/).filter((part) => /[\p{L}\p{N}]/u.test(part));
  if (parts.length === 0) return "?";
  if (parts.length === 1) return [...parts[0]].slice(0, 2).join("").toUpperCase();
  return ([...parts[0]][0] + [...parts[1]][0]).toUpperCase();
}

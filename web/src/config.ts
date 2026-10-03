export function apiBase(): string {
  const fromWindow = window.__SHROUD_CONFIG__?.apiBase?.trim();
  if (fromWindow) return fromWindow.replace(/\/$/, "");
  return "/api/v1";
}

/** Same-origin `/api/v1/ws` as wss/ws. */
export function wsUrl(): string {
  const path = `${apiBase()}/ws`;
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${window.location.host}${path}`;
}

/** Same-origin `/api/v1/link-relay` as wss/ws — the link-preview byte pipe (see linkPreview/). */
export function linkRelayUrl(): string {
  const path = `${apiBase()}/link-relay`;
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${window.location.host}${path}`;
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

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

export function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua)
    ? "Edge"
    : /Chrome\//.test(ua)
      ? "Chrome"
      : /Firefox\//.test(ua)
        ? "Firefox"
        : /Safari\//.test(ua)
          ? "Safari"
          : "Browser";
  const os = /Mac OS X/.test(ua)
    ? "Mac"
    : /Windows/.test(ua)
      ? "Windows"
      : /Linux/.test(ua)
        ? "Linux"
        : "Web";
  return `${browser} on ${os}`;
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

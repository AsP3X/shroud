import { useSyncExternalStore } from "react";
import { storageSealed } from "./storageSeal";

/** Which veil the brand mark and tab icon draw: the app icon's, with folds, or one flat shape. */
export type LogoStyle = "detailed" | "simple";

const KEY = "shroud.logo";
const listeners = new Set<() => void>();

export function loadLogoStyle(): LogoStyle {
  try {
    return localStorage.getItem(KEY) === "simple" ? "simple" : "detailed";
  } catch {
    return "detailed";
  }
}

export function setLogoStyle(style: LogoStyle): void {
  try {
    if (!storageSealed()) localStorage.setItem(KEY, style);
  } catch {
    /* private mode: honor it for this tab only */
  }
  for (const notify of [...listeners]) notify();
}

/** The tab icon for the chosen style, with or without the unread dot. */
export function faviconHref(unread: boolean, style: LogoStyle = loadLogoStyle()): string {
  const name = style === "simple" ? "favicon-simple" : "favicon";
  return `/${name}${unread ? "-unread" : ""}.svg`;
}

/** Points the tab icon at the chosen style, keeping whatever unread state it shows. */
export function applyFavicon(): void {
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon) icon.href = faviconHref(icon.href.includes("-unread"));
}

export function installLogo(): () => void {
  applyFavicon();
  listeners.add(applyFavicon);
  return () => {
    listeners.delete(applyFavicon);
  };
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify);
  return () => {
    listeners.delete(notify);
  };
}

export function useLogoStyle(): LogoStyle {
  return useSyncExternalStore(subscribe, loadLogoStyle, () => "detailed" as LogoStyle);
}

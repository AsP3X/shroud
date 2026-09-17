import { useSyncExternalStore } from "react";

export type ThemePref = "system" | "light" | "dark";
export type ResolvedTheme = "light" | "dark";

const KEY = "shroud.theme";
const listeners = new Set<() => void>();

function isPref(value: unknown): value is ThemePref {
  return value === "system" || value === "light" || value === "dark";
}

export function loadTheme(): ThemePref {
  try {
    const raw = localStorage.getItem(KEY);
    return isPref(raw) ? raw : "system";
  } catch {
    return "system";
  }
}

function prefersLight(): boolean {
  return window.matchMedia?.("(prefers-color-scheme: light)").matches ?? false;
}

export function resolveTheme(pref: ThemePref): ResolvedTheme {
  if (pref !== "system") return pref;
  return prefersLight() ? "light" : "dark";
}

/** Paints the root element. `system` leaves `data-theme` off so the media query wins. */
export function applyTheme(pref: ThemePref): void {
  const root = document.documentElement;
  if (pref === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", pref);
  const resolved = resolveTheme(pref);
  root.style.colorScheme = resolved;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", resolved === "light" ? "#ffffff" : "#000000");
}

export function setTheme(pref: ThemePref): void {
  try {
    localStorage.setItem(KEY, pref);
  } catch {
    /* private mode: honor it for this tab only */
  }
  applyTheme(pref);
  for (const notify of [...listeners]) notify();
}

/** Applies the stored preference and keeps `system` following the OS. */
export function installTheme(): () => void {
  applyTheme(loadTheme());
  const mq = window.matchMedia?.("(prefers-color-scheme: light)");
  if (!mq) return () => {};
  const onChange = () => {
    if (loadTheme() === "system") {
      applyTheme("system");
      for (const notify of [...listeners]) notify();
    }
  };
  mq.addEventListener("change", onChange);
  return () => mq.removeEventListener("change", onChange);
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify);
  return () => {
    listeners.delete(notify);
  };
}

export function useThemePref(): ThemePref {
  return useSyncExternalStore(subscribe, loadTheme, () => "system" as ThemePref);
}

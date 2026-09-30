/**
 * Whether this browser builds link previews (Settings → Privacy → Link previews).
 *
 * On by default, like iOS `SecurityPreferences.generatesLinkPreviews`. Per browser, in
 * localStorage: a preference, not a secret. Off, links are sent bare and nothing is fetched.
 */

const KEY = "shroud.linkPreviews";

export function generatesLinkPreviews(): boolean {
  try {
    return localStorage.getItem(KEY) !== "off";
  } catch {
    return true;
  }
}

export function setGeneratesLinkPreviews(enabled: boolean): void {
  try {
    localStorage.setItem(KEY, enabled ? "on" : "off");
  } catch {
    // Storage blocked (private mode): the default stays in effect.
  }
}

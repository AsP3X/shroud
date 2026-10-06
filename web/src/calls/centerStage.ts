import { storageSealed } from "../storageSeal";

/**
 * Whether Center Stage follows faces in calls from this browser (docs/calls.md, "Framing and
 * Center Stage"). On by default; switched in the call. Per browser, in localStorage like
 * `screenQuality.ts`: a preference, not a secret.
 */

const KEY = "shroud.centerStage";

export function loadCenterStage(): boolean {
  try {
    return localStorage.getItem(KEY) !== "off";
  } catch {
    return true;
  }
}

export function saveCenterStage(on: boolean): void {
  try {
    if (!storageSealed()) localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Storage blocked (private mode): the choice holds for this tab only.
  }
}

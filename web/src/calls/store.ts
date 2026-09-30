import { useSyncExternalStore } from "react";
import type { CallView } from "./logic";

/*
 * The call this device is in, as the call screen shows it. The controller publishes a new view
 * on every change; null is no call.
 */

let current: CallView | null = null;
const listeners = new Set<() => void>();

export function getCallView(): CallView | null {
  return current;
}

export function publishCallView(view: CallView | null): void {
  current = view;
  for (const notify of [...listeners]) notify();
}

function subscribe(notify: () => void): () => void {
  listeners.add(notify);
  return () => {
    listeners.delete(notify);
  };
}

export function useCallView(): CallView | null {
  return useSyncExternalStore(subscribe, getCallView, getCallView);
}

function busy(): boolean {
  return current !== null && current.phase !== "ended";
}

/** A call rings or runs here: the call buttons wait. */
export function useCallBusy(): boolean {
  return useSyncExternalStore(subscribe, busy, busy);
}

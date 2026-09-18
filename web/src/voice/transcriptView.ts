/*
 * Which voice notes have their transcript unfolded, and which are still being
 * transcribed on this device. Session-only view state, kept outside React so it
 * survives the thread re-rendering, switching chats, and the optimistic bubble
 * being swapped for the server's copy. Transcripts start folded.
 */

type Listener = () => void;

const open = new Set<string>();
const busy = new Set<string>();
const listeners = new Set<Listener>();
let version = 0;

function emit(): void {
  version += 1;
  for (const listener of listeners) listener();
}

function flip(set: Set<string>, id: string, on: boolean): void {
  const key = id.toLowerCase();
  if (set.has(key) === on) return;
  if (on) set.add(key);
  else set.delete(key);
  emit();
}

export function subscribeTranscriptView(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Snapshot for `useSyncExternalStore`: bumps on every change. */
export function transcriptViewVersion(): number {
  return version;
}

export function isTranscriptOpen(id: string): boolean {
  return open.has(id.toLowerCase());
}

export function setTranscriptOpen(id: string, on: boolean): void {
  flip(open, id, on);
}

/** True while this device is still making the note's transcript. */
export function isTranscribing(id: string): boolean {
  return busy.has(id.toLowerCase());
}

export function setTranscribing(id: string, on: boolean): void {
  flip(busy, id, on);
}

/** The optimistic bubble's id gives way to the server's; carry its state across. */
export function rekeyTranscriptView(from: string, to: string): void {
  const a = from.toLowerCase();
  const b = to.toLowerCase();
  if (a === b) return;
  let changed = false;
  for (const set of [open, busy]) {
    if (!set.delete(a)) continue;
    set.add(b);
    changed = true;
  }
  if (changed) emit();
}

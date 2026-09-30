/*
 * How voice notes show their transcripts. The newest voice notes at the bottom
 * of a thread — two at most, with nothing newer under them — unfold by
 * themselves unless the transcript runs long; anything newer folds them away
 * again. Elsewhere a transcript stays folded until the reader taps it.
 *
 * Session-only view state, kept outside React so it survives the thread
 * re-rendering, switching chats, and the optimistic bubble being swapped for the
 * server's copy.
 */

import type { ChatMessage } from "../messaging";

/** How many of the newest voice notes show their transcript without a tap. */
const TAIL = 2;
/** Longer transcripts wait for a tap even at the bottom: two would fill the thread. */
const AUTO_OPEN_MAX_CHARS = 400;
/** A note still being transcribed only unfolds to show progress when it is this short. */
const AUTO_OPEN_MAX_WAIT_MS = 30_000;

type Listener = () => void;

/** The reader's own fold or unfold; wins over the automatic rule until the note leaves the tail. */
const choices = new Map<string, boolean>();
const busy = new Set<string>();
/** Server ids that took over an optimistic bubble, which carries on rather than arriving anew. */
const handedOff = new Set<string>();
const listeners = new Set<Listener>();
let version = 0;

function emit(): void {
  version += 1;
  for (const listener of listeners) listener();
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

/** The reader's choice for this note, or undefined to follow the automatic rule. */
export function transcriptChoice(id: string): boolean | undefined {
  return choices.get(id.toLowerCase());
}

export function setTranscriptOpen(id: string, open: boolean): void {
  const key = id.toLowerCase();
  if (choices.get(key) === open) return;
  choices.set(key, open);
  emit();
}

export function clearTranscriptChoice(id: string): void {
  if (choices.delete(id.toLowerCase())) emit();
}

/** True while this device is still making the note's transcript. */
export function isTranscribing(id: string): boolean {
  return busy.has(id.toLowerCase());
}

export function setTranscribing(id: string, on: boolean): void {
  const key = id.toLowerCase();
  if (busy.has(key) === on) return;
  if (on) busy.add(key);
  else busy.delete(key);
  emit();
}

/** The optimistic bubble's id gives way to the server's; carry its state across. */
export function rekeyTranscriptView(from: string, to: string): void {
  const a = from.toLowerCase();
  const b = to.toLowerCase();
  if (a === b) return;
  handedOff.add(b);
  const choice = choices.get(a);
  if (choice !== undefined) {
    choices.delete(a);
    choices.set(b, choice);
  }
  if (busy.delete(a)) busy.add(b);
  emit();
}

/** A bubble that took over from an optimistic one: already on screen, not a new arrival. */
export function wasHandedOff(id: string): boolean {
  return handedOff.has(id.toLowerCase());
}

/** Ids of the newest voice notes with nothing newer under them, newest first. */
export function transcriptTail(messages: readonly ChatMessage[]): string[] {
  const tail: string[] = [];
  for (let i = messages.length - 1; i >= 0 && tail.length < TAIL; i--) {
    const message = messages[i];
    if (message.kind !== "voice" || message.deleted) break;
    tail.push(message.id);
  }
  return tail;
}

/** Whether a note in the tail unfolds by itself: short text, or a short note still transcribing. */
export function opensUnasked(transcript: string | null, durationMs: number, transcribing: boolean): boolean {
  if (transcript) return transcript.length <= AUTO_OPEN_MAX_CHARS;
  return transcribing && durationMs <= AUTO_OPEN_MAX_WAIT_MS;
}

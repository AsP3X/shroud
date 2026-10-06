/**
 * Plays audio files in the chat (docs/file-sharing.md §11.5): one at a time, from an object URL
 * on the verified, decrypted `Blob` the file transfer hands over (§8 — nothing is cached). The URL
 * is revoked when another file starts, the chat closes or the chats lock. Same subscribe /
 * snapshot pattern as the voice player (playback.ts), and one sound at a time with it.
 *
 * Auto-advance on the web: when a file ends, the next audio file below it in the chat plays only
 * when its bytes are already in memory here (a file this tab sent, or the one last opened) and the
 * browser can play it; nothing is downloaded unasked. Otherwise playback stops.
 */
import { useSyncExternalStore } from "react";
import { AUDIO_RATES, playableMimeOf, SPEED_CHIP_MIN_MS } from "../audioFiles";
import { claimSound, registerSound } from "./soundFocus";

export type AudioFileState = {
  /** The file in the player (lowercased message id), playing or not; null when none is. */
  id: string | null;
  playing: boolean;
  /**
   * The file is active (§11.4): it started playing and hasn't ended or been stopped since — so a
   * pause, even one sought back to 0:00, keeps its scrubber and the now-playing bar.
   */
  active: boolean;
  /** Position and length in ms; the length from the browser, 0 until it knows. */
  positionMs: number;
  durationMs: number;
  /** The speed picked on the bar; it holds for the session, and only applies from 10 minutes. */
  rate: number;
  /** A file downloading to play once it lands (a bubble tap while it wasn't on this device). */
  waitingId: string | null;
  /** Files the browser failed to open this session: they show the plain file bubble. */
  unplayable: ReadonlySet<string>;
};

/**
 * The next file below `afterId` in the chat, when it can play at once — its bytes in memory and
 * past the content check — else null (Thread registers this).
 */
export type AudioQueue = (afterId: string) => Promise<{ id: string; blob: Blob } | null>;

const listeners = new Set<() => void>();
let audio: HTMLAudioElement | null = null;
let objectUrl: string | null = null;
let blob: Blob | null = null;
let tick: number | null = null;
let queue: AudioQueue | null = null;
/** Bumped by every start and stop: a download that lands after one of them doesn't play. */
let generation = 0;

let snapshot: AudioFileState = {
  id: null,
  playing: false,
  active: false,
  positionMs: 0,
  durationMs: 0,
  rate: 1,
  waitingId: null,
  unplayable: new Set(),
};

function key(id: string): string {
  return id.toLowerCase();
}

function set(next: Partial<AudioFileState>): void {
  snapshot = { ...snapshot, ...next };
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function stopTick(): void {
  if (tick != null) {
    window.clearInterval(tick);
    tick = null;
  }
}

function startTick(): void {
  stopTick();
  tick = window.setInterval(() => {
    if (audio) set({ positionMs: audio.currentTime * 1000 });
  }, 50);
}

/** The speed a file plays at: the session's pick from 10 minutes on, else 1×. */
function rateFor(durationMs: number): number {
  return durationMs >= SPEED_CHIP_MIN_MS ? snapshot.rate : 1;
}

function durationOf(el: HTMLAudioElement): number {
  return Number.isFinite(el.duration) && el.duration > 0 ? el.duration * 1000 : 0;
}

/** Drops the element, its URL and the bytes; the state says nothing is loaded. */
function unload(): void {
  stopTick();
  if (audio) {
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
    audio = null;
  }
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = null;
  blob = null;
}

function onEnded(el: HTMLAudioElement, id: string): void {
  if (audio !== el) return;
  stopTick();
  el.currentTime = 0;
  set({ playing: false, active: false, positionMs: 0 });
  const ended = generation;
  void (queue?.(id) ?? Promise.resolve(null)).then(
    (next) => {
      // Anything that started or stopped meanwhile wins.
      if (!next || audio !== el || generation !== ended || snapshot.playing) return;
      if (snapshot.unplayable.has(key(next.id))) return;
      playAudioFile(next.id, next.blob);
    },
    () => undefined,
  );
}

function onError(el: HTMLAudioElement, id: string): void {
  if (audio !== el) return;
  const unplayable = new Set(snapshot.unplayable);
  unplayable.add(id);
  unload();
  set({ id: null, playing: false, active: false, positionMs: 0, durationMs: 0, unplayable });
}

function play(el: HTMLAudioElement): void {
  claimSound("file");
  el.playbackRate = rateFor(durationOf(el));
  void el.play().then(
    () => {
      if (audio !== el) return;
      startTick();
      set({ playing: true, active: true, positionMs: el.currentTime * 1000 });
    },
    () => {
      // Refused (no user gesture yet, or the element failed): stays paused.
      if (audio !== el) return;
      set({ playing: false });
    },
  );
}

/** Starts `id` from the top, from its decrypted bytes; whatever played before stops. */
export function playAudioFile(id: string, file: Blob): void {
  const k = key(id);
  generation += 1;
  unload();
  blob = file;
  objectUrl = URL.createObjectURL(file);
  const el = new Audio();
  el.preload = "auto";
  el.setAttribute("playsinline", "");
  el.addEventListener("ended", () => onEnded(el, k));
  el.addEventListener("error", () => onError(el, k));
  el.addEventListener("loadedmetadata", () => {
    if (audio !== el) return;
    el.playbackRate = rateFor(durationOf(el));
    set({ durationMs: durationOf(el) });
  });
  el.addEventListener("pause", () => {
    // Paused from outside (the OS, a headset button): the state follows.
    if (audio === el && snapshot.playing && !el.ended) {
      stopTick();
      set({ playing: false, positionMs: el.currentTime * 1000 });
    }
  });
  el.src = objectUrl;
  audio = el;
  set({ id: k, playing: false, active: false, positionMs: 0, durationMs: 0, waitingId: null });
  play(el);
}

/** Play or pause the file already in the player; false when `id` isn't it. */
export function toggleAudioFile(id: string): boolean {
  if (!audio || snapshot.id !== key(id)) return false;
  if (snapshot.playing) pauseAudioFile();
  else play(audio);
  return true;
}

export function pauseAudioFile(): void {
  if (!audio || !snapshot.playing) return;
  audio.pause();
  stopTick();
  set({ playing: false, positionMs: audio.currentTime * 1000 });
}

/** Seeks the file in the player to `ms` (clamped to its length). */
export function seekAudioFile(id: string, ms: number): void {
  if (!audio || snapshot.id !== key(id)) return;
  const length = durationOf(audio);
  const to = Math.max(0, length > 0 ? Math.min(ms, length) : ms);
  audio.currentTime = to / 1000;
  set({ positionMs: to });
}

/** Stops and forgets the file in the player (the bar's ✕, a call, the chat closing, a lock). */
export function stopAudioFile(): void {
  generation += 1;
  if (!audio && !snapshot.id && !snapshot.waitingId) return;
  unload();
  set({ id: null, playing: false, active: false, positionMs: 0, durationMs: 0, waitingId: null });
}

/** 1× → 1.5× → 2× → 1×, for the rest of the session. */
export function cycleAudioRate(): void {
  const index = AUDIO_RATES.indexOf(snapshot.rate as (typeof AUDIO_RATES)[number]);
  const rate = AUDIO_RATES[(index < 0 ? 0 : index + 1) % AUDIO_RATES.length];
  snapshot = { ...snapshot, rate };
  if (audio) audio.playbackRate = rateFor(durationOf(audio));
  set({});
}

/**
 * A tap on a file that isn't on this device: it downloads, then plays — unless something else
 * started playing meanwhile, or the chat closed. Returns what `playWhenLoaded` checks.
 */
export function waitToPlay(id: string): number {
  generation += 1;
  set({ waitingId: key(id) });
  return generation;
}

/** The download of `waitToPlay` landed: plays if nothing happened since. */
export function playWhenLoaded(id: string, ticket: number, file: Blob): void {
  if (ticket !== generation || snapshot.waitingId !== key(id)) return;
  playAudioFile(id, file);
}

/** The download of `waitToPlay` failed or was stopped. */
export function stopWaiting(id: string): void {
  if (snapshot.waitingId === key(id)) set({ waitingId: null });
}

/** Whether the player holds this file's bytes (it plays without a download). */
export function audioFileLoaded(id: string): Blob | null {
  return snapshot.id === key(id) ? blob : null;
}

/** The browser couldn't open this file; it shows the plain file bubble from now on. */
export function markUnplayable(id: string): void {
  const unplayable = new Set(snapshot.unplayable);
  unplayable.add(key(id));
  set({ unplayable });
}

/** Thread's way to the next file below the one that ended; null to stop at the end. */
export function setAudioQueue(next: AudioQueue | null): void {
  queue = next;
}

/** A deleted message: stop it if it is the one in the player. */
export function releaseAudioFile(id: string): void {
  if (snapshot.id === key(id) || snapshot.waitingId === key(id)) stopAudioFile();
}

/** The server re-keyed a sent message: the player follows the bubble. */
export function rekeyAudioFile(fromId: string, toId: string): void {
  const from = key(fromId);
  const to = key(toId);
  const next: Partial<AudioFileState> = {};
  if (snapshot.id === from) next.id = to;
  if (snapshot.waitingId === from) next.waitingId = to;
  if (snapshot.unplayable.has(from)) {
    const unplayable = new Set(snapshot.unplayable);
    unplayable.delete(from);
    unplayable.add(to);
    next.unplayable = unplayable;
  }
  if (Object.keys(next).length > 0) set(next);
}

const canPlayCache = new Map<string, boolean>();

/**
 * Whether this browser says it can play an audio file of this name's type (`canPlayType`, known
 * before a download, §11.4). A file it then fails to open is marked unplayable on top.
 */
export function browserCanPlay(cleanedName: string): boolean {
  const mime = playableMimeOf(cleanedName);
  if (!mime) return false;
  let can = canPlayCache.get(mime);
  if (can == null) {
    try {
      can = typeof document !== "undefined" && document.createElement("audio").canPlayType(mime) !== "";
    } catch {
      can = false;
    }
    canPlayCache.set(mime, can);
  }
  return can;
}

/** Whether the browser failed to open this file this session (a primitive: cheap to subscribe to). */
export function useAudioFileUnplayable(id: string): boolean {
  return useSyncExternalStore(
    subscribe,
    () => snapshot.unplayable.has(key(id)),
    () => false,
  );
}

export function getAudioFileState(): AudioFileState {
  return snapshot;
}

/** The whole player (the now-playing bar). */
export function useAudioFilePlayer(): AudioFileState {
  return useSyncExternalStore(subscribe, getAudioFileState, getAudioFileState);
}

export type AudioFileView = {
  /** Its bytes are in the player. */
  loaded: boolean;
  /** Playing, or paused part-way (§11.4): the bubble shows the scrubber and the elapsed time. */
  active: boolean;
  playing: boolean;
  positionMs: number;
  durationMs: number;
  waiting: boolean;
  unplayable: boolean;
};

const IDLE: AudioFileView = {
  loaded: false,
  active: false,
  playing: false,
  positionMs: 0,
  durationMs: 0,
  waiting: false,
  unplayable: false,
};
const IDLE_UNPLAYABLE: AudioFileView = { ...IDLE, unplayable: true };
/** Views of the files involved, for the snapshot they were made from. */
let views: { source: AudioFileState; byId: Map<string, AudioFileView> } | null = null;

/** One file's slice of the player, stable while it isn't involved: other bubbles don't re-render. */
export function audioFileView(id: string): AudioFileView {
  const k = key(id);
  const s = snapshot;
  const involved = s.id === k || s.waitingId === k;
  if (!involved) return s.unplayable.has(k) ? IDLE_UNPLAYABLE : IDLE;
  if (views?.source !== s) views = { source: s, byId: new Map() };
  const cached = views.byId.get(k);
  if (cached) return cached;
  const loaded = s.id === k;
  const view: AudioFileView = {
    loaded,
    active: loaded && s.active,
    playing: loaded && s.playing,
    positionMs: loaded ? s.positionMs : 0,
    durationMs: loaded ? s.durationMs : 0,
    waiting: s.waitingId === k,
    unplayable: s.unplayable.has(k),
  };
  views.byId.set(k, view);
  return view;
}

export function useAudioFileView(id: string): AudioFileView {
  return useSyncExternalStore(
    subscribe,
    () => audioFileView(id),
    () => IDLE,
  );
}

registerSound("file", () => {
  if (snapshot.id || snapshot.waitingId) stopAudioFile();
});

if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
  // The web keeps playing in a hidden tab (§11.5); leaving the page stops it.
  window.addEventListener("pagehide", () => stopAudioFile());
}

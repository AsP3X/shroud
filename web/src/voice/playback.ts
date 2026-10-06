import { claimSound, registerSound } from "./soundFocus";

const RATES = [1, 1.5, 2] as const;

export type VoicePlaybackState = {
  activeId: string | null;
  playing: boolean;
  currentTime: number;
  duration: number;
  rate: number;
  played: ReadonlySet<string>;
};

type Listener = () => void;

const listeners = new Set<Listener>();

let audio: HTMLAudioElement | null = null;
let objectUrl: string | null = null;
let tick: number | null = null;

let snapshot: VoicePlaybackState = {
  activeId: null,
  playing: false,
  currentTime: 0,
  duration: 0,
  rate: 1,
  played: new Set(),
};

function emit(): void {
  snapshot = { ...snapshot };
  for (const listener of listeners) listener();
}

function revoke(): void {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl);
    objectUrl = null;
  }
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
    if (!audio) return;
    snapshot = {
      ...snapshot,
      currentTime: audio.currentTime,
      duration: Number.isFinite(audio.duration) ? audio.duration : snapshot.duration,
    };
    if (audio.ended) {
      finish();
      return;
    }
    emit();
  }, 33);
}

function finish(): void {
  stopTick();
  if (audio) audio.currentTime = 0;
  snapshot = { ...snapshot, playing: false, currentTime: 0 };
  emit();
}

function bind(el: HTMLAudioElement): void {
  el.addEventListener("ended", finish);
}

function makeAudio(data: Uint8Array, mime: string): HTMLAudioElement {
  revoke();
  const type = mime && mime !== "application/octet-stream" ? mime : "audio/mp4";
  const copy = new Uint8Array(data.byteLength);
  copy.set(data);
  const blob = new Blob([copy], { type });
  objectUrl = URL.createObjectURL(blob);
  const el = new Audio();
  el.preload = "auto";
  el.setAttribute("playsinline", "");
  el.src = objectUrl;
  bind(el);
  return el;
}

function start(id: string, data: Uint8Array, mime: string, fraction: number, autoplay: boolean): void {
  stopTick();
  if (audio) {
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
  }
  const el = makeAudio(data, mime);
  audio = el;
  el.playbackRate = snapshot.rate;
  const applyStart = () => {
    if (audio !== el) return;
    const duration = Number.isFinite(el.duration) ? el.duration : 0;
    el.currentTime = duration * Math.min(1, Math.max(0, fraction));
    const played = new Set(snapshot.played);
    played.add(id);
    snapshot = {
      ...snapshot,
      activeId: id,
      duration,
      currentTime: el.currentTime,
      played,
      playing: false,
    };
    if (!autoplay) {
      emit();
      return;
    }
    claimSound("voice");
    void el
      .play()
      .then(() => {
        if (audio !== el) return;
        snapshot = { ...snapshot, playing: true };
        startTick();
        emit();
      })
      .catch(() => {
        if (audio !== el) return;
        snapshot = { ...snapshot, playing: false };
        emit();
      });
    emit();
  };
  if (el.readyState >= 1) applyStart();
  else el.addEventListener("loadedmetadata", applyStart, { once: true });
}

export function getVoicePlayback(): VoicePlaybackState {
  return snapshot;
}

export function subscribeVoicePlayback(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function voiceProgress(id: string): number {
  if (snapshot.activeId !== id || snapshot.duration <= 0) return 0;
  return Math.min(1, Math.max(0, snapshot.currentTime / snapshot.duration));
}

export function voiceDisplayTime(id: string, fallbackMs: number): number {
  if (snapshot.activeId !== id) return fallbackMs / 1000;
  return snapshot.currentTime;
}

export function toggleVoice(id: string, data: Uint8Array, mime: string): void {
  if (snapshot.activeId === id && audio) {
    if (snapshot.playing) {
      audio.pause();
      stopTick();
      snapshot = { ...snapshot, playing: false, currentTime: audio.currentTime };
      emit();
    } else {
      audio.playbackRate = snapshot.rate;
      claimSound("voice");
      void audio.play().then(() => {
        snapshot = { ...snapshot, playing: true };
        startTick();
        emit();
      });
    }
    return;
  }
  start(id, data, mime, 0, true);
}

export function seekVoice(id: string, data: Uint8Array, mime: string, fraction: number): void {
  const clamped = Math.min(1, Math.max(0, fraction));
  if (snapshot.activeId !== id || !audio) {
    start(id, data, mime, clamped, false);
    return;
  }
  audio.currentTime = (Number.isFinite(audio.duration) ? audio.duration : 0) * clamped;
  snapshot = { ...snapshot, currentTime: audio.currentTime };
  emit();
}

export function cycleVoiceRate(): void {
  const index = RATES.indexOf(snapshot.rate as (typeof RATES)[number]);
  const next = RATES[(index < 0 ? 0 : index + 1) % RATES.length];
  snapshot = { ...snapshot, rate: next };
  if (audio) audio.playbackRate = next;
  emit();
}

export function stopVoice(): void {
  stopTick();
  if (audio) {
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
    audio = null;
  }
  revoke();
  snapshot = { ...snapshot, activeId: null, playing: false, currentTime: 0, duration: 0 };
  emit();
}

export function measureVoiceDuration(data: Uint8Array, mime: string): Promise<number | null> {
  return new Promise((resolve) => {
    const type = mime && mime !== "application/octet-stream" ? mime : "audio/mp4";
    const copy = new Uint8Array(data.byteLength);
    copy.set(data);
    const url = URL.createObjectURL(new Blob([copy], { type }));
    const probe = new Audio();
    let settled = false;
    const done = (value: number | null) => {
      if (settled) return;
      settled = true;
      URL.revokeObjectURL(url);
      resolve(value);
    };
    probe.preload = "metadata";
    const timer = window.setTimeout(() => done(null), 4000);
    probe.addEventListener("loadedmetadata", () => {
      window.clearTimeout(timer);
      done(Number.isFinite(probe.duration) && probe.duration > 0 ? probe.duration : null);
    });
    probe.addEventListener("error", () => {
      window.clearTimeout(timer);
      done(null);
    });
    probe.src = url;
  });
}

registerSound("voice", () => {
  if (snapshot.activeId) stopVoice();
});

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.hidden && snapshot.playing && audio) {
      audio.pause();
      stopTick();
      snapshot = { ...snapshot, playing: false, currentTime: audio.currentTime };
      emit();
    }
  });
  window.addEventListener("pagehide", () => stopVoice());
}

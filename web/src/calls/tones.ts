/*
 * Call sounds made with WebAudio rather than shipped as files: the ringtone for a call coming in,
 * the ringback (425 Hz, 1 s on and 4 s off, as European exchanges play it) for one going out.
 * Each is scheduled whole on the audio clock when it starts, so a background tab's throttled
 * timers cannot stretch it. Browsers refuse audio until the page has had a click or a key press;
 * then there is simply no sound (the call screen and the notification still show).
 */

export type CallTone = "ringtone" | "ringback";

/** Longer than either side rings (the server gives up after 60 s). */
const TONE_SECONDS = 80;

let context: AudioContext | null = null;
let playing: { kind: CallTone; out: GainNode; sources: AudioScheduledSourceNode[] } | null = null;

function audioContext(): AudioContext | null {
  if (context) return context;
  const Ctor =
    typeof window === "undefined"
      ? undefined
      : window.AudioContext ??
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  try {
    context = new Ctor();
  } catch {
    return null;
  }
  return context;
}

/**
 * Wakes the audio context inside a click or key press, so a ring that arrives later without one
 * is allowed to sound.
 */
export function primeCallTones(): void {
  const ctx = audioContext();
  if (ctx?.state === "suspended") void ctx.resume().catch(() => undefined);
}

/** 1 s of 425 Hz every 5 s. */
function ringback(ctx: AudioContext, out: GainNode, start: number): AudioScheduledSourceNode[] {
  const tone = ctx.createOscillator();
  tone.frequency.value = 425;
  const gate = ctx.createGain();
  gate.gain.setValueAtTime(0, start);
  for (let at = start; at < start + TONE_SECONDS; at += 5) {
    gate.gain.setValueAtTime(0, at);
    gate.gain.linearRampToValueAtTime(1, at + 0.02);
    gate.gain.setValueAtTime(1, at + 0.98);
    gate.gain.linearRampToValueAtTime(0, at + 1);
  }
  tone.connect(gate).connect(out);
  tone.start(start);
  tone.stop(start + TONE_SECONDS);
  return [tone];
}

/** A soft rising triad, twice, then a pause: every 4 s. */
function ringtone(ctx: AudioContext, out: GainNode, start: number): AudioScheduledSourceNode[] {
  const notes = [659.25, 830.61, 987.77];
  const sources: AudioScheduledSourceNode[] = [];
  for (let cycle = start; cycle < start + TONE_SECONDS; cycle += 4) {
    for (const phrase of [0, 0.75]) {
      notes.forEach((frequency, index) => {
        const at = cycle + phrase + index * 0.16;
        const tone = ctx.createOscillator();
        tone.type = "triangle";
        tone.frequency.value = frequency;
        const envelope = ctx.createGain();
        envelope.gain.setValueAtTime(0, at);
        envelope.gain.linearRampToValueAtTime(1, at + 0.012);
        envelope.gain.exponentialRampToValueAtTime(0.001, at + 0.55);
        tone.connect(envelope).connect(out);
        tone.start(at);
        tone.stop(at + 0.6);
        sources.push(tone);
      });
    }
  }
  return sources;
}

function stop(): void {
  if (!playing) return;
  const { out, sources } = playing;
  playing = null;
  out.disconnect();
  for (const source of sources) {
    try {
      source.stop();
    } catch {
      /* already stopped */
    }
  }
}

/** Starts one tone (stopping the other), or stops both with null. */
export function playCallTone(kind: CallTone | null): void {
  if (playing?.kind === kind) return;
  stop();
  if (!kind) return;
  const ctx = audioContext();
  if (!ctx) return;
  if (ctx.state === "suspended") void ctx.resume().catch(() => undefined);
  try {
    const out = ctx.createGain();
    out.gain.value = kind === "ringtone" ? 0.2 : 0.14;
    out.connect(ctx.destination);
    const start = ctx.currentTime + 0.05;
    const sources = kind === "ringback" ? ringback(ctx, out, start) : ringtone(ctx, out, start);
    playing = { kind, out, sources };
  } catch {
    /* no sound; the call goes on */
  }
}

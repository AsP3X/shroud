import { useEffect, useRef } from "react";
import { Mic } from "lucide-react";

/*
 * "You're speaking": a glass capsule with a mic glyph and five bars that follow our own
 * microphone, under the status line of the call screen.
 *
 * An AnalyserNode on the local stream gives the level; nothing is connected to the speakers.
 * Every frame reads one RMS and writes five transforms straight onto the bars (compositor-only,
 * no React state, no layout), with a fast attack and a slower release so the 30 fps read looks
 * continuous. Once the mic has been quiet for a moment the frame loop stops and a slow poll
 * takes over, so a silent call costs nothing.
 */

const BARS = 5;
/** The outer bars follow a little less than the middle, so it reads as a waveform. */
const WEIGHTS = [0.55, 0.8, 1, 0.8, 0.55];
const WOBBLE_RATES = [9.1, 12.7, 7.3, 11.2, 8.6];
const WOBBLE_PHASES = [0, 1.9, 3.1, 4.4, 5.6];
/** Below this the mic counts as quiet (about −45 dBFS after the mapping). */
const GATE = 0.12;
/** How long the bars keep animating after the last voice; they have settled well before. */
const HOLD_MS = 1000;
const IDLE_POLL_MS = 100;
const FRAME_MS = 1000 / 30;
/** The bar's own height is 16 px; a quiet bar shows as a 3 px dot. */
const FLOOR = 3 / 16;

let shared: AudioContext | null = null;

function audioContext(): AudioContext | null {
  if (shared?.state === "closed") shared = null;
  if (shared) return shared;
  const Ctor =
    typeof window === "undefined"
      ? undefined
      : window.AudioContext ??
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  try {
    shared = new Ctor();
  } catch {
    return null;
  }
  return shared;
}

/** Linear RMS 0…1 to a display fraction: −50 dBFS is the floor, −10 dBFS fills the bar. */
function display(linear: number): number {
  if (linear <= 0) return 0;
  const decibels = 20 * Math.log10(linear);
  return Math.min(1, Math.max(0, (decibels + 50) / 40));
}

export function SpeakingIndicator({ stream, active }: { stream: MediaStream | null; active: boolean }) {
  const root = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const element = root.current;
    if (!element || !active || !stream || stream.getAudioTracks().length === 0) return;
    const ctx = audioContext();
    if (!ctx) return;
    if (ctx.state === "suspended") void ctx.resume().catch(() => undefined);

    let source: MediaStreamAudioSourceNode;
    try {
      source = ctx.createMediaStreamSource(stream);
    } catch {
      return;
    }
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 512;
    analyser.smoothingTimeConstant = 0;
    // Chrome drops a graph that never reaches an output, the same way a gain of 0
    // silenced the voice-note meter. This sink is not played.
    const sink = ctx.createMediaStreamDestination();
    source.connect(analyser);
    analyser.connect(sink);

    const bars = Array.from(element.querySelectorAll<HTMLSpanElement>(".call-mic-bar"));
    if (bars.length < BARS) {
      source.disconnect();
      analyser.disconnect();
      sink.disconnect();
      return;
    }
    const samples = new Float32Array(analyser.fftSize);
    const heights = new Array<number>(BARS).fill(FLOOR);
    const wobble = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let target = 0;
    let lastVoice = 0;
    let lastTick = 0;
    let lastRead = 0;
    let frame = 0;
    let timer = 0;
    let stopped = false;

    const read = (now: number): void => {
      analyser.getFloatTimeDomainData(samples);
      let sum = 0;
      for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
      target = display(Math.sqrt(sum / samples.length));
      if (target > GATE) lastVoice = now;
      lastRead = now;
    };

    const draw = (now: number): void => {
      const delta = lastTick === 0 ? FRAME_MS / 1000 : Math.min(0.1, (now - lastTick) / 1000);
      lastTick = now;
      const attack = 1 - Math.exp(-delta * 40);
      const release = 1 - Math.exp(-delta * 12);
      const seconds = now / 1000;
      for (let i = 0; i < BARS; i++) {
        let goal = target * WEIGHTS[i];
        if (wobble && goal > 0) goal *= 0.75 + 0.25 * Math.sin(seconds * WOBBLE_RATES[i] + WOBBLE_PHASES[i]);
        goal = FLOOR + (1 - FLOOR) * goal;
        heights[i] += (goal - heights[i]) * (goal > heights[i] ? attack : release);
        bars[i].style.transform = `scaleY(${heights[i].toFixed(3)})`;
      }
    };

    /* Speaking: one read and one draw per frame. Quiet: a slow poll until a voice returns. */
    const tick = (now: number): void => {
      if (stopped) return;
      if (now - lastRead >= FRAME_MS - 2) read(now);
      const speaking = now - lastVoice < HOLD_MS;
      element.dataset.live = speaking ? "true" : "false";
      if (speaking) {
        draw(now);
        frame = window.requestAnimationFrame(tick);
      } else {
        lastTick = 0;
        timer = window.setTimeout(() => tick(performance.now()), IDLE_POLL_MS);
      }
    };
    tick(performance.now());

    return () => {
      stopped = true;
      window.cancelAnimationFrame(frame);
      window.clearTimeout(timer);
      source.disconnect();
      analyser.disconnect();
      sink.disconnect();
      for (const bar of bars) bar.style.transform = "";
      delete element.dataset.live;
    };
  }, [stream, active]);

  if (!active || !stream) return null;
  return (
    <span ref={root} className="call-mic" aria-hidden="true">
      <Mic size={12} />
      <span className="call-mic-bars">
        {WEIGHTS.map((_, i) => (
          <span className="call-mic-bar" key={i} />
        ))}
      </span>
    </span>
  );
}

import {
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent,
} from "react";
import { Mic, Send, Trash2 } from "lucide-react";
import { formatRecordingTime } from "../crypto/mediaPayload";
import { VOICE_METER_MS, getVoiceRecorder, subscribeVoiceRecorder } from "../voice/recorder";
import {
  DROPLET,
  prefersReducedMotion,
  visualLift,
  type VoiceRecording,
} from "./useVoiceRecording";

/* Timing for the choreography described in useVoiceRecording.ts. */
const FLIGHT_MS = 420;
/** Share of the flight spent lifting, before the droplet spreads into the capsule. */
const LIFT_SHARE = 0.34;
const CLOSE_MS = { send: 420, discard: 560 };

function latestLevel(): number {
  const levels = getVoiceRecorder().liveLevels;
  return levels.length > 0 ? levels[levels.length - 1] : 0;
}

function capsuleReady(rect: DOMRect | undefined): rect is DOMRect {
  return Boolean(rect && rect.width >= 8 && rect.height >= 8);
}

/**
 * The accent drop that stands in for the recording between the mic and the
 * strip. It never takes input; the mic underneath keeps pointer capture.
 */
export function VoiceDroplet({ voice }: { voice: VoiceRecording }) {
  const { phase, bud, budLeaving, lift } = voice;
  const node = useRef<HTMLDivElement>(null);
  const ink = useRef<HTMLSpanElement>(null);
  const icon = useRef<HTMLSpanElement>(null);
  const level = useSyncExternalStore(subscribeVoiceRecorder, latestLevel, latestLevel);

  // Launches once per opening; bud and lift are read at the moment it starts.
  useLayoutEffect(() => {
    if (phase !== "opening") return;
    const el = node.current;
    if (!el || !bud) {
      voice.landed();
      return;
    }
    let cancelled = false;
    let flight: Animation | null = null;
    let inkAnim: Animation | null = null;
    let iconAnim: Animation | null = null;
    let fallback = 0;
    let frame = 0;

    const play = (): boolean => {
      const shell = voice.shellRef.current?.getBoundingClientRect();
      const capsule = voice.capsuleRef.current?.getBoundingClientRect();
      if (!shell || !capsuleReady(capsule)) return false;
      const from = { x: bud.x, y: bud.y + visualLift(lift) };
      const to = {
        x: capsule.left - shell.left,
        y: shell.bottom - capsule.bottom,
        w: capsule.width,
        h: capsule.height,
      };
      /* Lift to the capsule's right end as a drop, then spread left into it. */
      flight = el.animate(
        [
          {
            translate: `${from.x}px ${-from.y}px`,
            width: `${DROPLET}px`,
            height: `${DROPLET}px`,
            easing: "cubic-bezier(0.3, 0, 0.3, 1)",
          },
          {
            offset: LIFT_SHARE,
            translate: `${to.x + to.w - to.h}px ${-to.y}px`,
            width: `${to.h}px`,
            height: `${to.h}px`,
            // Eases out of the pause at the top instead of snapping open.
            easing: "cubic-bezier(0.32, 0.08, 0.12, 1)",
          },
          {
            translate: `${to.x}px ${-to.y}px`,
            width: `${to.w}px`,
            height: `${to.h}px`,
          },
        ],
        { duration: FLIGHT_MS, fill: "forwards" },
      );
      // The accent drains out as it spreads, leaving exactly the capsule's surface.
      inkAnim = ink.current?.animate(
        [{ opacity: 1 }, { opacity: 1, offset: LIFT_SHARE + 0.1 }, { opacity: 0 }],
        { duration: FLIGHT_MS, fill: "forwards" },
      ) ?? null;
      iconAnim =
        icon.current?.animate(
          [{ opacity: 1, scale: 1 }, { opacity: 0, scale: 0.6, offset: 0.22 }, { opacity: 0, scale: 0.6 }],
          { duration: FLIGHT_MS, fill: "forwards" },
        ) ?? null;
      flight.onfinish = () => {
        if (!cancelled) voice.landed();
      };
      fallback = window.setTimeout(() => {
        if (!cancelled) voice.landed();
      }, FLIGHT_MS + 400);
      return true;
    };

    if (!play()) {
      frame = window.requestAnimationFrame(() => {
        if (cancelled) return;
        if (!play()) voice.landed();
      });
    }

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(frame);
      window.clearTimeout(fallback);
      if (flight) {
        flight.onfinish = null;
        flight.cancel();
      }
      inkAnim?.cancel();
      iconAnim?.cancel();
    };
  }, [phase]);

  useLayoutEffect(() => {
    if (!budLeaving) return;
    const timer = window.setTimeout(() => voice.budGone(), 400);
    return () => window.clearTimeout(timer);
  }, [budLeaving]);

  const visible = bud && (budLeaving || phase === "arming" || phase === "holding" || phase === "opening");
  if (!visible) return null;
  const listening = phase === "holding";
  return (
    <div
      ref={node}
      className="voice-droplet"
      data-phase={budLeaving ? "leaving" : phase}
      style={{
        translate: `${bud.x}px ${-(bud.y + visualLift(lift))}px`,
        ["--level" as string]: listening ? Math.min(1, level).toFixed(3) : "0",
      }}
      onAnimationEnd={(event) => {
        if (event.animationName === "voice-droplet-out") voice.budGone();
      }}
      aria-hidden="true"
    >
      <span className="voice-droplet-ink" ref={ink} />
      <span className="voice-droplet-icon" ref={icon}>
        <Mic size={18} />
      </span>
    </div>
  );
}

/** Pitch of one live bar: 3px bar + 2px gap. */
const BAR_W = 3;
const BAR_GAP = 2;

/**
 * Fills from the left like iOS, newest bar on the right. Bars keep their key
 * for the whole take, so a new bar is the only DOM change per tick; once the
 * track is full it glides one pitch left per tick instead of jumping.
 */
function LiveWave({ levels, count, frozen }: { levels: number[]; count: number; frozen: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const track = useRef<HTMLDivElement>(null);
  const [slots, setSlots] = useState(0);

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => setSlots(Math.max(1, Math.floor((el.clientWidth + BAR_GAP) / (BAR_W + BAR_GAP))));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const shown = slots > 0 ? levels.slice(-slots) : [];
  const scrolling = slots > 0 && count > slots;

  useLayoutEffect(() => {
    if (!scrolling || frozen || prefersReducedMotion()) return;
    const anim = track.current?.animate(
      [{ transform: `translateX(${BAR_W + BAR_GAP}px)` }, { transform: "translateX(0)" }],
      { duration: VOICE_METER_MS, easing: "linear" },
    );
    return () => anim?.cancel();
  }, [count, scrolling, frozen]);

  const first = count - shown.length;
  return (
    <div className={scrolling ? "voice-live-wave scrolling" : "voice-live-wave"} ref={box} aria-hidden="true">
      <div className="voice-live-track" ref={track}>
        {shown.map((sample, index) => (
          <span
            key={first + index}
            className="voice-live-bar"
            style={{ height: `${Math.max(12.5, Math.min(1, sample) * 100)}%` }}
          />
        ))}
      </div>
    </div>
  );
}

/** Rises out of the composer once the droplet is on its way; hands-free from there. */
export function VoiceStrip({ voice }: { voice: VoiceRecording }) {
  const { phase, closeKind, frozen } = voice;
  const live = useSyncExternalStore(subscribeVoiceRecorder, getVoiceRecorder, getVoiceRecorder);
  const rec = frozen ?? live;

  useLayoutEffect(() => {
    if (phase !== "closing") return;
    const ms = prefersReducedMotion() ? 0 : CLOSE_MS[closeKind];
    const timer = window.setTimeout(() => voice.stripGone(), ms);
    return () => window.clearTimeout(timer);
  }, [phase, closeKind]);

  if (phase !== "opening" && phase !== "live" && phase !== "closing") return null;

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "Escape") return;
    event.preventDefault();
    voice.discard();
  }

  return (
    <div
      className="voice-strip"
      data-state={phase}
      data-close={phase === "closing" ? closeKind : undefined}
      onAnimationEnd={(event) => {
        if (event.target === event.currentTarget && event.animationName === "voice-strip-close") {
          voice.stripGone();
        }
      }}
    >
      <div className="voice-strip-clip">
        <div
          className="voice-strip-row"
          role="group"
          aria-label="Voice message recording"
          inert={phase !== "live"}
          onKeyDown={onKeyDown}
        >
          <button
            type="button"
            className="voice-strip-discard"
            onClick={voice.discard}
            aria-label="Discard recording"
            title="Discard"
          >
            <Trash2 size={18} />
          </button>
          <div className="voice-strip-capsule" ref={voice.capsuleRef}>
            <span className="voice-rec-meta">
              <span className="voice-rec-dot" aria-hidden="true" />
              <span className="voice-rec-time">{formatRecordingTime(rec.elapsed)}</span>
            </span>
            <LiveWave levels={rec.liveLevels} count={rec.levelCount} frozen={phase === "closing"} />
          </div>
          <button
            ref={voice.sendRef}
            type="button"
            className="send voice-strip-send"
            onClick={voice.send}
            aria-label="Send voice message"
            title="Send"
          >
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type PointerEvent,
} from "react";
import { Check, CheckCheck, Clock, LoaderCircle, Pause, Play } from "lucide-react";
import type { ChatMessage } from "../messaging";
import {
  formatVoiceTime,
  normalizeWaveform,
  placeholderWaveform,
  resampleWaveform,
  waveformUsable,
} from "../crypto/mediaPayload";
import { clockTime, fullTimestamp } from "../format";
import {
  cycleVoiceRate,
  getVoicePlayback,
  measureVoiceDuration,
  seekVoice,
  subscribeVoicePlayback,
  toggleVoice,
  voiceDisplayTime,
  voiceProgress,
} from "../voice/playback";
import {
  isTranscribing,
  isTranscriptOpen,
  setTranscriptOpen,
  subscribeTranscriptView,
  transcriptViewVersion,
} from "../voice/transcriptView";
import { Highlight } from "./Highlight";


const MIN_TRUSTED_MS = 300;
/** Drawer unfold; the glyph morph and text fade in index.css run on the same clock. */
const UNFOLD_MS = 320;
const UNFOLD_EASE = "cubic-bezier(0.16, 1, 0.3, 1)";

function Receipt({ message }: { message: ChatMessage }) {
  if (message.pending) return <Clock size={13} aria-label="Sending" />;
  if (message.read) return <CheckCheck size={14} className="receipt-read" aria-label="Read" />;
  if (message.delivered) return <CheckCheck size={14} aria-label="Delivered" />;
  return <Check size={14} aria-label="Sent" />;
}

/**
 * Telegram's "→A". Open, the arrow slides off and the A's legs swing into a
 * chevron pointing back up — both states are drawn from the same strokes.
 */
function TranscriptGlyph() {
  return (
    <svg className="voice-tx-glyph" viewBox="0 0 24 24" aria-hidden="true">
      <path className="voice-tx-arrow" d="M3 12h6.4M6.9 9.3 9.6 12l-2.7 2.7" />
      <path className="voice-tx-leg voice-tx-leg-l" d="M0 0-4.5 12" />
      <path className="voice-tx-leg voice-tx-leg-r" d="M0 0 4.5 12" />
      <path className="voice-tx-bar" d="M14.3 14h5.4" />
    </svg>
  );
}

/**
 * Tweens the drawer between its old and new height whenever `key` changes:
 * folding, unfolding, and "Transcribing…" giving way to the text. Content is laid
 * out at its final width up front, so the text never re-wraps mid-animation.
 */
function useHeightTween(key: string) {
  const ref = useRef<HTMLDivElement>(null);
  /** Height measured when the user pressed the toggle, mid-tween included. */
  const pressedAt = useRef<number | null>(null);
  const settled = useRef<number | null>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) {
      settled.current = null;
      return;
    }
    const running = el.getAnimations().filter((a) => a.id === "voice-tx-height");
    const from = pressedAt.current ?? (running.length > 0 ? cssHeight(el) : settled.current);
    pressedAt.current = null;
    for (const animation of running) animation.cancel();
    const to = cssHeight(el);
    settled.current = to;
    if (from == null || Math.abs(from - to) < 1) return;
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const tween = el.animate([{ height: `${from}px` }, { height: `${to}px` }], {
      duration: UNFOLD_MS,
      easing: UNFOLD_EASE,
    });
    tween.id = "voice-tx-height";
    return () => tween.cancel();
  }, [key]);

  function markPress() {
    const el = ref.current;
    if (el) pressedAt.current = cssHeight(el);
  }

  return { ref, markPress };
}

/** Used height in CSS px (a running tween included), unaffected by transforms or zoom. */
function cssHeight(el: HTMLElement): number {
  return parseFloat(getComputedStyle(el).height) || 0;
}

export function VoiceBubble({
  message,
  loadVoice,
  query = "",
}: {
  message: ChatMessage;
  loadVoice: (message: ChatMessage) => Promise<Uint8Array | null>;
  /** Active in-chat search; a transcript that matches unfolds to show the hit. */
  query?: string;
}) {
  const playback = useSyncExternalStore(subscribeVoicePlayback, getVoicePlayback);
  useSyncExternalStore(subscribeTranscriptView, transcriptViewVersion);
  const [audio, setAudio] = useState<Uint8Array | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [measuredMs, setMeasuredMs] = useState<number | null>(null);
  const [scrub, setScrub] = useState<number | null>(null);
  /** Search query the reader folded a matching transcript under, so it stays folded. */
  const [foldedFor, setFoldedFor] = useState<string | null>(null);
  const scrubbing = useRef(false);
  const waveRef = useRef<HTMLDivElement>(null);
  const drawerId = useId();

  const stated = message.voiceDurationMs ?? 0;
  const durationMs = stated >= MIN_TRUSTED_MS ? stated : (measuredMs ?? stated);
  const seconds = durationMs / 1000;
  /** Duration still sizes the bubble; short notes keep this floor so the footer fits. */
  const durationBars = Math.min(38, Math.max(18, 16 + Math.round(seconds * 1.6)));
  const waveWidth = Math.max(148, durationBars * 5);
  /* Pack as many real samples as the track can hold (min 3px per sample). Short
     notes show the envelope at higher resolution instead of a few fat bars. */
  const maxFit = Math.max(1, Math.floor(waveWidth / 3));
  const samples = useMemo(() => {
    if (waveformUsable(message.voiceWaveform)) {
      const source = normalizeWaveform(message.voiceWaveform!);
      return source.length > maxFit ? resampleWaveform(source, maxFit) : source;
    }
    return placeholderWaveform(message.id, Math.min(durationBars, maxFit));
  }, [message.id, message.voiceWaveform, durationBars, maxFit]);
  const slot = samples.length > 0 ? waveWidth / samples.length : 5;
  const barGap = samples.length > 1 ? Math.max(1, slot * 0.4) : 0;

  const transcript = message.transcript?.trim() || null;
  const busy = !transcript && isTranscribing(message.id);
  const hasTranscriptUi = Boolean(transcript) || busy;
  const needle = query.trim().toLowerCase();
  const matched = Boolean(needle && transcript?.toLowerCase().includes(needle));
  const open =
    hasTranscriptUi &&
    (isTranscriptOpen(message.id) || (matched && foldedFor !== needle));
  /* The button only animates in when a transcript lands on a bubble already on
     screen; a thread opening with transcripts in it draws them in place. */
  const [toggleEnters] = useState(() => !hasTranscriptUi);
  const drawer = useHeightTween(`${open}|${transcript ? "text" : "wait"}`);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadFailed(false);
    void loadVoice(message).then((bytes) => {
      if (cancelled) return;
      setLoading(false);
      if (!bytes) {
        setLoadFailed(true);
        return;
      }
      setAudio(bytes);
    });
    return () => {
      cancelled = true;
    };
  }, [message.id, message.mediaObjectId, loadVoice]);

  useEffect(() => {
    if (!audio || stated >= MIN_TRUSTED_MS || measuredMs != null) return;
    void measureVoiceDuration(audio, message.mime || "audio/mp4").then((seconds) => {
      if (seconds) setMeasuredMs(Math.round(seconds * 1000));
    });
  }, [audio, stated, measuredMs, message.mime]);

  const active = playback.activeId === message.id;
  const playing = active && playback.playing;
  const progress = scrub ?? voiceProgress(message.id);
  const display = voiceDisplayTime(message.id, durationMs);
  const unplayed = !message.isMine && !playback.played.has(message.id);
  const mime = message.mime || "audio/mp4";

  function toggle() {
    if (audio) {
      toggleVoice(message.id, audio, mime);
      return;
    }
    setLoading(true);
    void loadVoice(message).then((bytes) => {
      setLoading(false);
      if (!bytes) {
        setLoadFailed(true);
        return;
      }
      setAudio(bytes);
      toggleVoice(message.id, bytes, mime);
    });
  }

  function toggleTranscript() {
    drawer.markPress();
    if (open) {
      setTranscriptOpen(message.id, false);
      if (matched) setFoldedFor(needle);
    } else {
      setTranscriptOpen(message.id, true);
      setFoldedFor(null);
    }
  }

  function onPointer(event: PointerEvent<HTMLDivElement>) {
    if (!audio || !waveRef.current) return;
    const rect = waveRef.current.getBoundingClientRect();
    if (rect.width <= 0) return;
    const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    if (event.type === "pointerdown") {
      waveRef.current.setPointerCapture(event.pointerId);
      scrubbing.current = true;
      setScrub(fraction);
    } else if (event.type === "pointermove" && scrubbing.current) {
      setScrub(fraction);
    } else if (event.type === "pointerup" || event.type === "pointercancel") {
      if (!scrubbing.current) return;
      scrubbing.current = false;
      setScrub(null);
      seekVoice(message.id, audio, mime, fraction);
    }
  }

  const rateLabel =
    playback.rate === Math.round(playback.rate)
      ? `${playback.rate}×`
      : `${playback.rate.toFixed(1)}×`;

  const toggleLabel = open
    ? "Hide transcript"
    : busy
      ? "Transcribing voice message"
      : "Show transcript";

  return (
    <>
      <div className="voice">
        <button
          type="button"
          className="voice-play"
          onClick={toggle}
          disabled={loading}
          aria-label={playing ? "Pause voice message" : "Play voice message"}
        >
          {loading && !audio ? (
            <LoaderCircle size={16} className="voice-spin" aria-hidden="true" />
          ) : playing ? (
            <Pause size={15} fill="currentColor" aria-hidden="true" />
          ) : (
            <Play size={15} fill="currentColor" aria-hidden="true" />
          )}
        </button>
        <div className="voice-body">
          <div className="voice-top">
            <div
              ref={waveRef}
              className="voice-wave"
              style={{ width: waveWidth, gap: barGap }}
              role="slider"
              aria-label="Voice waveform"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(progress * 100)}
              tabIndex={audio ? 0 : -1}
              onPointerDown={onPointer}
              onPointerMove={onPointer}
              onPointerUp={onPointer}
              onPointerCancel={onPointer}
            >
              {samples.map((sample, index) => {
                const position = index / samples.length;
                const next = (index + 1) / samples.length;
                let fill = 0;
                if (progress >= next) fill = 1;
                else if (progress > position) fill = (progress - position) / Math.max(next - position, 0.0001);
                return (
                  <span
                    key={index}
                    className="voice-bar"
                    style={{ height: `${Math.max(12, sample * 100)}%`, ["--played" as string]: String(fill) }}
                  />
                );
              })}
            </div>
            {hasTranscriptUi ? (
              <button
                type="button"
                className={toggleEnters ? "voice-tx voice-tx-enter" : "voice-tx"}
                onClick={toggleTranscript}
                aria-expanded={open}
                aria-controls={drawerId}
                aria-label={toggleLabel}
                title={toggleLabel}
                data-busy={busy ? "" : undefined}
              >
                <TranscriptGlyph />
                <svg className="voice-tx-ring" viewBox="0 0 28 28" aria-hidden="true">
                  <rect x="0.75" y="0.75" width="26.5" height="26.5" rx="8.25" />
                </svg>
              </button>
            ) : null}
          </div>
          <div className="voice-footer">
            <span className="voice-time">{formatVoiceTime(display)}</span>
            {unplayed ? <span className="voice-dot" aria-label="Unplayed" /> : null}
            {active ? (
              <button
                type="button"
                className="voice-rate"
                onClick={() => cycleVoiceRate()}
                aria-label={`Playback speed ${rateLabel}`}
              >
                {rateLabel}
              </button>
            ) : null}
            <span className="voice-meta" title={fullTimestamp(message.createdAt)}>
              <time dateTime={message.createdAt}>{clockTime(message.createdAt)}</time>
              {message.isMine && !message.deleted ? <Receipt message={message} /> : null}
            </span>
          </div>
        </div>
        {loadFailed && !audio ? <span className="sr-only">Could not load this voice message.</span> : null}
      </div>
      {hasTranscriptUi ? (
        <div
          ref={drawer.ref}
          id={drawerId}
          className="voice-tx-drawer"
          data-open={open ? "" : undefined}
        >
          {transcript ? (
            <p className="voice-tx-text">
              <Highlight text={transcript} query={query} />
            </p>
          ) : (
            <p className="voice-tx-wait">Transcribing…</p>
          )}
        </div>
      ) : null}
    </>
  );
}

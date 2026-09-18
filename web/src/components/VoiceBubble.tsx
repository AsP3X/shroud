import {
  useEffect,
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


const MIN_TRUSTED_MS = 300;

function Receipt({ message }: { message: ChatMessage }) {
  if (message.pending) return <Clock size={13} aria-label="Sending" />;
  if (message.read) return <CheckCheck size={14} className="receipt-read" aria-label="Read" />;
  if (message.delivered) return <CheckCheck size={14} aria-label="Delivered" />;
  return <Check size={14} aria-label="Sent" />;
}

export function VoiceBubble({
  message,
  loadVoice,
}: {
  message: ChatMessage;
  loadVoice: (message: ChatMessage) => Promise<Uint8Array | null>;
}) {
  const playback = useSyncExternalStore(subscribeVoicePlayback, getVoicePlayback);
  const [audio, setAudio] = useState<Uint8Array | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [measuredMs, setMeasuredMs] = useState<number | null>(null);
  const [scrub, setScrub] = useState<number | null>(null);
  const scrubbing = useRef(false);
  const waveRef = useRef<HTMLDivElement>(null);

  const stated = message.voiceDurationMs ?? 0;
  const durationMs = stated >= MIN_TRUSTED_MS ? stated : (measuredMs ?? stated);
  const seconds = durationMs / 1000;
  const barCount = Math.min(38, Math.max(18, 16 + Math.round(seconds * 1.6)));
  const samples = useMemo(() => {
    const source = waveformUsable(message.voiceWaveform)
      ? normalizeWaveform(message.voiceWaveform!)
      : placeholderWaveform(message.id, barCount);
    return resampleWaveform(source, barCount);
  }, [message.id, message.voiceWaveform, barCount]);

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

  return (
    <div className="voice-wrap">
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
      <div className="voice-body" style={{ width: Math.max(148, barCount * 5) }}>
        <div
          ref={waveRef}
          className="voice-wave"
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
      {message.transcript ? <p className="voice-transcript">{message.transcript}</p> : null}
    </div>
  );
}

import { useEffect, useState, type RefObject } from "react";
import { Pause, Play, X } from "lucide-react";
import { formatAudioElapsed, formatAudioTotal, rateLabel, SPEED_CHIP_MIN_MS } from "../audioFiles";
import type { ChatMessage } from "../messaging";
import {
  cycleAudioRate,
  stopAudioFile,
  toggleAudioFile,
  useAudioFilePlayer,
} from "../voice/audioFilePlayback";
import { FileName } from "./FileBubble";

/**
 * The strip's height. It lies over the top of the list instead of pushing it down (index.css), so
 * the list never resizes — a resize near the bottom would snap the thread back to the newest
 * message — and a bubble under it counts as out of view.
 */
const BAR_HEIGHT = 44;

/**
 * The now-playing bar (docs/file-sharing.md §11.6): while an audio file of this chat is active
 * (playing, or paused part-way) and its bubble is out of view, a 44 px strip under the thread's
 * head — play / pause, the title and artist (a click scrolls to the bubble), `{elapsed} /
 * {duration}`, the speed chip from 10 minutes, and ✕, which stops; a 2 px progress line under it.
 */
export function NowPlayingBar({
  messages,
  scroller,
  renderKey,
  onShow,
}: {
  messages: ChatMessage[];
  /** The thread's scrolling list: the bubble is "on screen" when it intersects it. */
  scroller: RefObject<HTMLDivElement | null>;
  /** Changes whenever the rendered rows do, so the bubble is looked up again. */
  renderKey: string;
  onShow: (id: string) => void;
}) {
  const player = useAudioFilePlayer();
  const active = player.id != null && player.active;
  const message = active ? messages.find((m) => m.id.toLowerCase() === player.id && !m.deleted) ?? null : null;
  const id = message ? player.id : null;
  const [bubbleInView, setBubbleInView] = useState(true);

  useEffect(() => {
    const root = scroller.current;
    if (!id || !root) return;
    const row = root.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(id)}"] .bubble`);
    if (!row || typeof IntersectionObserver === "undefined") {
      setBubbleInView(Boolean(row));
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        const entry = entries[entries.length - 1];
        if (entry) setBubbleInView(entry.isIntersecting);
      },
      { root, rootMargin: `-${BAR_HEIGHT}px 0px 0px 0px` },
    );
    observer.observe(row);
    return () => observer.disconnect();
  }, [id, scroller, renderKey]);

  const shown = Boolean(message) && !bubbleInView;

  if (!shown || !message || !id) return null;

  const title = message.audioTitle?.trim() || null;
  const artist = message.audioArtist?.trim() || null;
  const durationMs = player.durationMs > 0 ? player.durationMs : (message.audioDurationMs ?? 0);
  const progress = durationMs > 0 ? Math.min(1, player.positionMs / durationMs) : 0;
  const timing = durationMs > 0
    ? `${formatAudioElapsed(player.positionMs)} / ${formatAudioTotal(durationMs)}`
    : formatAudioElapsed(player.positionMs);
  const long = durationMs >= SPEED_CHIP_MIN_MS;

  return (
    <div className="now-playing" role="region" aria-label="Now playing">
      <button
        type="button"
        className="now-playing-toggle"
        aria-label={player.playing ? "Pause" : "Play"}
        title={player.playing ? "Pause" : "Play"}
        onClick={() => toggleAudioFile(id)}
      >
        {player.playing ? (
          <Pause size={14} fill="currentColor" strokeWidth={0} aria-hidden="true" />
        ) : (
          <Play className="audio-play-glyph" size={14} fill="currentColor" strokeWidth={0} aria-hidden="true" />
        )}
      </button>
      <button
        type="button"
        className="now-playing-text"
        title="Show in chat"
        onClick={() => onShow(id)}
      >
        <span className="now-playing-title">{title ?? <FileName name={message.fileName || "file"} />}</span>
        {artist ? <span className="now-playing-artist">{artist}</span> : null}
      </button>
      <span className="now-playing-time">{timing}</span>
      {long ? (
        <button
          type="button"
          className="now-playing-speed"
          title="Playback speed"
          onClick={cycleAudioRate}
        >
          {/* Named "Playback speed", its value the visible 1× / 1.5× / 2×. */}
          <span className="sr-only">Playback speed </span>
          {rateLabel(player.rate)}
        </button>
      ) : null}
      <button type="button" className="icon-btn now-playing-close" aria-label="Stop" title="Stop" onClick={stopAudioFile}>
        <X size={16} aria-hidden="true" />
      </button>
      <span className="now-playing-progress" aria-hidden="true" style={{ transform: `scaleX(${progress})` }} />
    </div>
  );
}

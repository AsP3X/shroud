import { type CSSProperties, type KeyboardEvent, type ReactNode } from "react";
import { ArrowDown, Pause, Play, RotateCw, Square } from "lucide-react";
import {
  audioAccessibilityLabel,
  audioDetailLine,
  formatAudioElapsed,
  formatAudioTotal,
} from "../audioFiles";
import { formatBytes } from "../format";
import { NOT_SENT } from "../files";
import type { ChatMessage } from "../messaging";
import { useFileOnDevice } from "../media/fileTransfer";
import { ringFraction, useTransfer } from "../media/transfers";
import {
  browserCanPlay,
  seekAudioFile,
  useAudioFileUnplayable,
  useAudioFileView,
} from "../voice/audioFilePlayback";
import { FileBubble, FileName } from "./FileBubble";
import { thumbnailUrl } from "./ImageBubble";
import { LinkedText } from "./LinkedText";
import { ProgressRing } from "./ProgressRing";
import { RollingText, usePaced } from "./RollingText";

/** ← / → on the scrubber move this far (§11.4). */
const KEY_SEEK_MS = 5000;

/**
 * An audio file in the chat (docs/file-sharing.md §11.4): the file bubble's shell with a round
 * 44 px cover in place of the tile — its glyph download, progress, play / pause or retry — the
 * title (`ti`, else the name cut in the middle), a detail line, and a scrubber while the file is
 * active (playing, or paused part-way). A click on the row downloads and then plays, stops a
 * download, plays or pauses, or retries a failed send; the thread does the work.
 */
export function AudioFileBubble({
  message,
  className,
  query,
  quote,
  meta,
  footer,
  onPlay,
  onCancelDownload,
  onRetry,
}: {
  message: ChatMessage;
  className: string;
  query: string;
  quote?: ReactNode;
  /** Time and ticks, the bubble's last line (unless the reactions take it). */
  meta: ReactNode;
  /** The reaction chips. */
  footer?: ReactNode;
  /** Play or pause; downloads first when the file isn't in memory. */
  onPlay: (message: ChatMessage) => void;
  onCancelDownload: (id: string) => void;
  /** Sends a failed file again; absent where there is nothing to resend. */
  onRetry?: (message: ChatMessage) => void;
}) {
  const transfer = useTransfer(message.id);
  const inMemory = useFileOnDevice(message.id);
  const player = useAudioFileView(message.id);
  const name = message.fileName || "file";
  const size = message.mediaBytes ?? null;
  const caption = message.caption?.trim() || "";
  const title = message.audioTitle?.trim() || null;
  const artist = message.audioArtist?.trim() || null;
  const thumb = thumbnailUrl(message);
  const onDevice = inMemory || player.loaded;
  /* The browser's length once it has the file; the sealed `d` until then. */
  const durationMs = player.durationMs > 0 ? player.durationMs : (message.audioDurationMs ?? null);

  const notSent = message.isMine && message.failed;
  const uploading = message.isMine && (message.pending || transfer?.direction === "up");
  const downloading = transfer?.direction === "down";
  const busy = uploading || downloading;
  const canTap = !(uploading && !downloading) && (!notSent || Boolean(onRetry));

  let glyph: ReactNode;
  let detail: string;
  if (notSent) {
    glyph = <RotateCw size={18} aria-hidden="true" />;
    detail = NOT_SENT;
  } else if (busy) {
    glyph = (
      <>
        <ProgressRing progress={ringFraction(transfer)} size={38} stroke={2.5} />
        <Square className="file-stop" size={11} fill="currentColor" strokeWidth={0} aria-hidden="true" />
      </>
    );
    // Bytes of the file itself, whichever way they move (as the file bubble counts them).
    const total = size ?? transfer?.total ?? 0;
    const moved =
      transfer?.phase === "preparing"
        ? 0
        : transfer?.phase === "finishing"
          ? total
          : transfer?.total
            ? Math.round((transfer.loaded / transfer.total) * total)
            : 0;
    detail = `${formatBytes(Math.min(total, moved))} of ${formatBytes(total)}`;
  } else {
    glyph = !onDevice ? (
      <ArrowDown size={18} aria-hidden="true" />
    ) : player.playing ? (
      <Pause size={18} fill="currentColor" strokeWidth={0} aria-hidden="true" />
    ) : (
      <Play className="audio-play-glyph" size={18} fill="currentColor" strokeWidth={0} aria-hidden="true" />
    );
    detail = audioDetailLine({
      name,
      artist,
      durationMs,
      size,
      onDevice,
      elapsedMs: player.active ? player.positionMs : null,
    });
  }
  // Rolls at most twice a second while bytes move; the playing time ticks once a second anyway.
  const shownDetail = usePaced(detail, busy);
  const scrubbing = player.active && !notSent && !busy;

  const label = audioAccessibilityLabel(title ?? name, artist, message.audioDurationMs ?? durationMs);
  const tap = () => {
    if (notSent) onRetry?.(message);
    else if (downloading) onCancelDownload(message.id);
    else onPlay(message);
  };

  const coverClass = ["file-tile", "audio-cover", thumb ? "has-thumb" : "", busy ? "is-busy" : ""]
    .filter(Boolean)
    .join(" ");
  const body = (
    <>
      <span className={coverClass} aria-hidden="true">
        {thumb ? <img className="file-thumb" src={thumb} alt="" draggable={false} /> : null}
        <span className="file-glyph">{glyph}</span>
      </span>
      <span className="file-text">
        {title ? (
          <span className="audio-title" title={title}>
            {title}
          </span>
        ) : (
          <FileName name={name} />
        )}
        <span className="file-meta audio-detail">
          <RollingText text={shownDetail} />
        </span>
      </span>
    </>
  );

  const classes = [className, "file-msg", "audio-msg", caption ? "has-caption" : "", quote ? "has-quote" : ""]
    .filter(Boolean)
    .join(" ");
  return (
    <div className={classes}>
      {quote}
      {canTap ? (
        <button
          type="button"
          className="file-row"
          aria-label={label}
          aria-pressed={onDevice && !busy && !notSent ? player.playing : undefined}
          onClick={tap}
        >
          {body}
        </button>
      ) : (
        <div className="file-row" role="group" aria-label={label}>
          {body}
        </div>
      )}
      {scrubbing ? (
        <AudioScrubber
          id={message.id}
          positionMs={player.positionMs}
          durationMs={durationMs ?? 0}
        />
      ) : null}
      {caption ? (
        <p className="bubble-text file-caption">
          <LinkedText text={caption} query={query} />
        </p>
      ) : null}
      {meta ? <div className="bubble-meta-row">{meta}</div> : null}
      {footer}
    </div>
  );
}

/**
 * An audio file's bubble, or — when this browser can't play its type (`canPlayType`, before a
 * download) or failed to open it (after) — the plain file bubble with **Can't play in this
 * browser**, whose click does what a file click does (§11.4).
 */
export function AudioOrFileBubble(
  props: Parameters<typeof AudioFileBubble>[0] & { onOpen: (message: ChatMessage) => void },
) {
  const failed = useAudioFileUnplayable(props.message.id);
  const { onOpen, onPlay, ...shared } = props;
  if (failed || !browserCanPlay(props.message.fileName || "")) {
    return <FileBubble {...shared} onOpen={onOpen} cantPlay />;
  }
  return <AudioFileBubble {...shared} onPlay={onPlay} />;
}

/**
 * The active file's scrubber: a range input (dragging or clicking seeks, ← / → seek 5 s), its
 * played part filled through `--played`.
 */
function AudioScrubber({ id, positionMs, durationMs }: { id: string; positionMs: number; durationMs: number }) {
  const max = Math.max(1, durationMs);
  const value = Math.min(max, Math.max(0, positionMs));
  const played = `${(value / max) * 100}%`;
  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    seekAudioFile(id, value + (event.key === "ArrowRight" ? KEY_SEEK_MS : -KEY_SEEK_MS));
  }
  return (
    <input
      type="range"
      className="audio-scrub"
      min={0}
      max={max}
      step="any"
      value={value}
      style={{ "--played": played } as CSSProperties}
      aria-label="Playback position"
      aria-valuetext={durationMs > 0 ? `${formatAudioElapsed(value)} of ${formatAudioTotal(durationMs)}` : formatAudioElapsed(value)}
      onChange={(event) => seekAudioFile(id, Number(event.target.value))}
      onKeyDown={onKeyDown}
    />
  );
}

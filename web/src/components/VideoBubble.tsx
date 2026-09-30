import { useEffect, type ReactNode } from "react";
import { AlertTriangle, Play, Video } from "lucide-react";
import { clockTime, formatBytes, fullTimestamp } from "../format";
import type { ChatMessage } from "../messaging";
import { lookForVideo, useVideoState } from "../media/videos";
import { ringFraction, useTransfer } from "../media/transfers";
import { clockLabel } from "../media/videoPlan";
import { Highlight } from "./Highlight";
import { ProgressRing } from "./ProgressRing";
import { Receipt } from "./Receipt";
import { thumbnailUrl } from "./ImageBubble";

const MAX_W = 360;
const MAX_H = 340;
const MIN_W = 150;
const MIN_H = 110;
const UNKNOWN_W = 260;
const UNKNOWN_H = 168;

export function videoBox(message: ChatMessage, captioned: boolean): { width: number; height: number } {
  const w = message.imageWidth ?? 0;
  const h = message.imageHeight ?? 0;
  if (!(w > 0 && h > 0)) return { width: UNKNOWN_W, height: UNKNOWN_H };
  const scale = Math.min(MAX_W / w, MAX_H / h, 1);
  const width = Math.round(Math.max(MIN_W, w * scale));
  const height = Math.round(Math.max(MIN_H, h * scale));
  return { width: captioned ? MAX_W : width, height };
}

/**
 * A video message: the sealed preview (or a sharp poster) sits behind a play disc
 * and a duration badge. The clip itself is never fetched until the person asks —
 * tap downloads, tap again plays.
 */
export function VideoBubble({
  message,
  className,
  query,
  onOpen,
  quote,
  footer,
  onDownload,
  onCancelDownload,
}: {
  message: ChatMessage;
  className: string;
  query: string;
  onOpen: (message: ChatMessage) => void;
  /** Reply header drawn on the bubble above the poster. */
  quote?: ReactNode;
  /** Under the media and caption: the reaction chips. */
  footer?: ReactNode;
  onDownload: (message: ChatMessage) => void;
  onCancelDownload: (id: string) => void;
}) {
  const state = useVideoState(message.id);
  const transfer = useTransfer(message.id);
  const caption = message.caption?.trim() || "";
  const box = videoBox(message, Boolean(caption));
  const thumb = thumbnailUrl(message);
  const fraction = ringFraction(transfer);
  const uploading = message.isMine && (message.pending || transfer?.direction === "up");
  const notSent = message.isMine && message.failed;
  const downloading = transfer?.direction === "down";
  const needsDownload = !state.ready && !state.stored && !uploading && !notSent && Boolean(message.mediaObjectId);

  useEffect(() => {
    lookForVideo(message);
  }, [message.id, message.mediaKey]);

  const duration = clockLabel((message.videoDurationMs ?? 0) / 1000);

  const sizeLabel = (() => {
    if (notSent) return null;
    if (transfer?.phase === "preparing") return "Compressing";
    if (transfer?.phase === "finishing") return uploading ? "Sending" : "Decrypting";
    if (transfer?.phase === "transferring") {
      if (!transfer.total) return null;
      if (transfer.loaded > 0) return `${formatBytes(transfer.loaded)} / ${formatBytes(transfer.total)}`;
      return formatBytes(transfer.total);
    }
    if ((needsDownload || downloading) && message.mediaBytes) return formatBytes(message.mediaBytes);
    return null;
  })();

  const meta = (
    <>
      <time dateTime={message.createdAt}>{clockTime(message.createdAt)}</time>
      {message.isMine ? <Receipt message={message} /> : null}
    </>
  );

  let center: ReactNode = null;
  if (notSent) {
    center = (
      <span className="photo-badge photo-badge-danger">
        <AlertTriangle size={18} aria-hidden="true" />
        Not sent
      </span>
    );
  } else if (uploading || downloading) {
    center = (
      <span className="video-disc video-disc-busy" aria-hidden="true">
        <ProgressRing progress={fraction} size={54} />
      </span>
    );
  } else {
    center = (
      <span className="video-disc" aria-hidden="true">
        <Play size={22} fill="currentColor" />
      </span>
    );
  }

  const label = notSent
    ? "Video didn’t send"
    : downloading
      ? "Cancel download"
      : needsDownload
        ? `Download video, ${duration}`
        : caption
          ? `Video: ${caption}`
          : `Video, ${duration}`;

  return (
    <div
      className={`${className} video-msg${caption ? " has-caption" : ""}${quote ? " has-reply" : ""}`}
      style={{ width: box.width }}
    >
      {quote}
      <button
        type="button"
        className={`video-frame${state.poster || thumb ? "" : " is-empty"}${notSent ? " is-failed" : ""}`}
        style={{ aspectRatio: `${box.width} / ${box.height}` }}
        aria-label={label}
        disabled={uploading && !notSent}
        onClick={() => {
          if (notSent) return;
          if (uploading) return;
          if (downloading) {
            onCancelDownload(message.id);
            return;
          }
          if (needsDownload) {
            onDownload(message);
            return;
          }
          onOpen(message);
        }}
      >
        {thumb ? <img className="photo-thumb" src={thumb} alt="" draggable={false} /> : null}
        {state.poster ? (
          <img className="video-poster" src={state.poster} alt="" draggable={false} />
        ) : !thumb ? (
          <span className="photo-empty" aria-hidden="true">
            <Video size={26} />
          </span>
        ) : null}
        <span className="video-scrim" aria-hidden="true" />
        <span className="video-badge">
          <Play size={8} fill="currentColor" />
          {duration}
          {sizeLabel ? (
            <>
              <span className="video-badge-dot">·</span>
              {sizeLabel}
            </>
          ) : null}
        </span>
        <span className="photo-center">{center}</span>
        {!caption ? (
          <span className="photo-meta" title={fullTimestamp(message.createdAt)}>
            {meta}
          </span>
        ) : null}
      </button>
      {caption ? (
        <div className="photo-caption">
          <p className="bubble-text">
            <Highlight text={caption} query={query} />
          </p>
          <span className="bubble-meta" title={fullTimestamp(message.createdAt)}>
            {meta}
          </span>
        </div>
      ) : null}
      {footer ? <div className="media-reactions">{footer}</div> : null}
    </div>
  );
}

import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { AlertTriangle, ImageIcon, RotateCw } from "lucide-react";
import { clockTime, formatBytes, fullTimestamp } from "../format";
import type { ChatMessage } from "../messaging";
import { peekImage, type LoadedImage } from "../media/images";
import { transferFraction, useTransfer } from "../media/transfers";
import { Highlight } from "./Highlight";
import { ProgressRing } from "./ProgressRing";
import { Receipt } from "./Receipt";

/* On-screen photo size, in iOS proportions: never upscaled past the original,
   no side under MIN_SIDE (the crop covers the rest), capped at MAX_W × MAX_H. */
const MAX_W = 360;
const MAX_H = 380;
const MIN_SIDE = 140;
const UNKNOWN_SIDE = 260;
/** Start fetching a little before a photo scrolls into view. */
const PRELOAD_MARGIN = "600px 0px";

export function photoBox(message: ChatMessage): { width: number; height: number } {
  const w = message.imageWidth ?? 0;
  const h = message.imageHeight ?? 0;
  if (!(w > 0 && h > 0)) return { width: UNKNOWN_SIDE, height: UNKNOWN_SIDE };
  const scale = Math.min(MAX_W / w, MAX_H / h, 1);
  return {
    width: Math.round(Math.max(MIN_SIDE, w * scale)),
    height: Math.round(Math.max(MIN_SIDE, h * scale)),
  };
}

/** Data URL of the envelope preview, drawn blurred until the photo itself is in. */
export function thumbnailUrl(message: ChatMessage): string | null {
  return message.thumbnail ? `data:image/jpeg;base64,${message.thumbnail}` : null;
}

/**
 * A photo message: the sealed preview blurs up into the full image as it
 * downloads, with the time over the photo (or under the caption). Photos load
 * when they near the viewport; a failed load retries on click.
 */
export function ImageBubble({
  message,
  className,
  query,
  loadImage,
  onOpen,
}: {
  message: ChatMessage;
  /** The row's bubble classes (side, grouping, pending/failed). */
  className: string;
  query: string;
  loadImage: (message: ChatMessage) => Promise<LoadedImage | null>;
  onOpen: (message: ChatMessage) => void;
}) {
  const [image, setImage] = useState<LoadedImage | null>(() => peekImage(message.id));
  const [status, setStatus] = useState<"idle" | "loading" | "failed">("idle");
  const [painted, setPainted] = useState(false);
  /** Already decoded when mounted (a re-keyed or remounted bubble): show it without a fade. */
  const [instant, setInstant] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const frame = useRef<HTMLButtonElement>(null);
  const full = useRef<HTMLImageElement>(null);
  const latest = useRef({ message, loadImage });
  latest.current = { message, loadImage };
  const transfer = useTransfer(message.id);

  const canLoad = Boolean(message.mediaObjectId && message.mediaKey);
  useEffect(() => {
    if (image || !canLoad) return;
    const node = frame.current;
    if (!node) return;
    let cancelled = false;
    const start = () => {
      setStatus("loading");
      void latest.current.loadImage(latest.current.message).then((result) => {
        if (cancelled) return;
        if (result) {
          setImage(result);
          setStatus("idle");
        } else {
          setStatus("failed");
        }
      });
    };
    if (typeof IntersectionObserver === "undefined") {
      start();
      return () => {
        cancelled = true;
      };
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        start();
      },
      { rootMargin: PRELOAD_MARGIN },
    );
    observer.observe(node);
    return () => {
      cancelled = true;
      observer.disconnect();
    };
  }, [image, canLoad, attempt]);

  useLayoutEffect(() => {
    const node = full.current;
    if (node?.complete && node.naturalWidth > 0) {
      setInstant(true);
      setPainted(true);
    }
  }, [image?.url]);

  const box = photoBox(message);
  const caption = message.caption?.trim() || "";
  const thumb = thumbnailUrl(message);
  const fraction = transferFraction(transfer);
  const uploading = message.isMine && (message.pending || transfer?.direction === "up");
  const notSent = message.isMine && message.failed;
  const downloading = !image && (status === "loading" || transfer?.direction === "down");
  const loadFailed = !image && status === "failed";

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
  } else if (uploading) {
    center = (
      <span className="photo-spinner" aria-label="Sending photo">
        <ProgressRing progress={fraction} />
      </span>
    );
  } else if (loadFailed) {
    center = (
      <span className="photo-spinner photo-retry" aria-hidden="true">
        <RotateCw size={18} />
      </span>
    );
  } else if (downloading) {
    center = (
      <span className="photo-spinner" aria-label="Loading photo">
        <ProgressRing progress={fraction} />
      </span>
    );
  }

  const sizeLabel = (() => {
    if (image || uploading || !message.mediaBytes) return null;
    if (transfer?.direction === "down" && transfer.loaded > 0) {
      return `${formatBytes(transfer.loaded)} / ${formatBytes(message.mediaBytes)}`;
    }
    return formatBytes(message.mediaBytes);
  })();

  return (
    <div
      className={`${className} photo-msg${caption ? " has-caption" : ""}`}
      style={{ width: box.width }}
    >
      <button
        ref={frame}
        type="button"
        className={`photo-frame${painted ? " is-painted" : ""}${loadFailed ? " is-failed" : ""}`}
        style={{ aspectRatio: `${box.width} / ${box.height}` }}
        aria-label={loadFailed ? "Photo didn’t load. Try again" : caption ? `Photo: ${caption}` : "Photo"}
        onClick={() => {
          if (loadFailed) {
            setStatus("idle");
            setAttempt((n) => n + 1);
            return;
          }
          if (notSent) return;
          onOpen(message);
        }}
      >
        {thumb ? <img className="photo-thumb" src={thumb} alt="" draggable={false} /> : null}
        {!thumb && !painted ? (
          <span className="photo-empty" aria-hidden="true">
            <ImageIcon size={26} />
          </span>
        ) : null}
        {image ? (
          <img
            ref={full}
            className={`photo-full${instant ? " instant" : ""}`}
            src={image.url}
            alt=""
            draggable={false}
            onLoad={() => setPainted(true)}
          />
        ) : null}
        {center ? <span className="photo-center">{center}</span> : null}
        {sizeLabel ? <span className="photo-size">{sizeLabel}</span> : null}
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
    </div>
  );
}

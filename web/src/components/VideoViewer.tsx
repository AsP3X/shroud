import { useCallback, useEffect, useRef, useState, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import { Download, Pause, Play, X } from "lucide-react";
import { fullTimestamp } from "../format";
import type { ChatMessage } from "../messaging";
import { peekVideo, videoFileName, type LoadedVideo } from "../media/videos";
import { ringFraction, useTransfer } from "../media/transfers";
import { clockLabel } from "../media/videoPlan";
import { thumbnailUrl } from "./ImageBubble";
import { ProgressRing } from "./ProgressRing";
import { stopVoice } from "../voice/playback";

const SWIPE_CLOSE = 110;
const TAP_SLOP = 6;
const CHROME_IDLE_MS = 2800;

/**
 * Full-screen playback for one clip. Telegram's viewer: black surface, sender and
 * date in the top bar, a slim scrubber, chrome that hides while it plays, drag
 * down (or Esc) to leave.
 */
export function VideoViewer({
  message,
  peerName,
  loadVideo,
  onClose,
}: {
  message: ChatMessage;
  peerName: string;
  loadVideo: (message: ChatMessage) => Promise<LoadedVideo | null>;
  onClose: () => void;
}) {
  const [video, setVideo] = useState<LoadedVideo | null>(() => peekVideo(message.id));
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [ready, setReady] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [chrome, setChrome] = useState(true);
  const [duration, setDuration] = useState((message.videoDurationMs ?? 0) / 1000);
  const [current, setCurrent] = useState(0);
  const [scrub, setScrub] = useState<number | null>(null);
  const [swipe, setSwipe] = useState(0);
  const player = useRef<HTMLVideoElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const hideTimer = useRef<number | null>(null);
  const drag = useRef<{ y: number; moved: boolean } | null>(null);
  const transfer = useTransfer(message.id);
  const latest = useRef({ onClose, loadVideo, message });
  latest.current = { onClose, loadVideo, message };

  useEffect(() => {
    stopVoice();
    const previous = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus?.();
      if (hideTimer.current) window.clearTimeout(hideTimer.current);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const readyNow = peekVideo(message.id);
    setVideo(readyNow);
    setFailed(false);
    setReady(false);
    setPlaying(false);
    if (!readyNow) {
      void latest.current.loadVideo(message).then((result) => {
        if (cancelled) return;
        if (result) setVideo(result);
        else setFailed(true);
      });
    }
    return () => {
      cancelled = true;
    };
  }, [message.id]);

  useEffect(() => {
    if (!video) {
      setSrc(null);
      return;
    }
    setReady(false);
    const url = URL.createObjectURL(video.blob);
    setSrc(url);
    return () => URL.revokeObjectURL(url);
  }, [video]);

  const scheduleHide = useCallback(() => {
    if (hideTimer.current) window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => {
      const node = player.current;
      if (node && !node.paused) setChrome(false);
    }, CHROME_IDLE_MS);
  }, []);

  const showChrome = useCallback(() => {
    setChrome(true);
    scheduleHide();
  }, [scheduleHide]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const node = player.current;
      if (event.key === "Escape") {
        event.preventDefault();
        latest.current.onClose();
      } else if (event.key === " " || event.key === "k") {
        event.preventDefault();
        if (!node) return;
        if (node.paused) void node.play();
        else node.pause();
        showChrome();
      } else if (event.key === "ArrowLeft" && node) {
        event.preventDefault();
        node.currentTime = Math.max(0, node.currentTime - 5);
        showChrome();
      } else if (event.key === "ArrowRight" && node) {
        event.preventDefault();
        node.currentTime = Math.min(node.duration || 0, node.currentTime + 5);
        showChrome();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [showChrome]);

  function togglePlay() {
    const node = player.current;
    if (!node || !ready) return;
    if (node.paused) void node.play();
    else node.pause();
    showChrome();
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 && event.pointerType === "mouse") return;
    // Play / chrome controls handle their own clicks; a stage gesture would double-fire.
    if ((event.target as HTMLElement | null)?.closest("button, input")) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { y: event.clientY, moved: false };
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const active = drag.current;
    if (!active) return;
    const dy = event.clientY - active.y;
    if (!active.moved && Math.abs(dy) > TAP_SLOP) active.moved = true;
    if (active.moved && event.pointerType !== "mouse") setSwipe(Math.max(0, dy));
  }

  function onPointerUp(event: PointerEvent<HTMLDivElement>) {
    const active = drag.current;
    drag.current = null;
    const dy = swipe;
    setSwipe(0);
    if (!active) return;
    if (active.moved) {
      if (dy > SWIPE_CLOSE) latest.current.onClose();
      return;
    }
    if (event.pointerType === "mouse") {
      // Click the picture to play/pause; click the empty stage to leave.
      if ((event.target as HTMLElement | null)?.closest(".viewer-clip")) togglePlay();
      else latest.current.onClose();
      return;
    }
    if (chrome) setChrome(false);
    else showChrome();
  }

  function seek(fraction: number) {
    const node = player.current;
    if (!node || !(node.duration > 0)) return;
    node.currentTime = Math.min(node.duration, Math.max(0, fraction * node.duration));
    showChrome();
  }

  function save() {
    if (!video) return;
    const url = URL.createObjectURL(video.blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = videoFileName(message);
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1500);
  }

  const shown = scrub ?? current;
  const caption = message.caption?.trim() || "";
  const thumb = thumbnailUrl(message);
  const fraction = ringFraction(transfer);
  const dismiss = Math.min(1, swipe / 320);

  return createPortal(
    <div
      className={`viewer viewer-video${chrome ? "" : " viewer-bare"}`}
      role="dialog"
      aria-modal="true"
      aria-label={`Video from ${message.isMine ? "you" : peerName}`}
      style={{ ["--viewer-dim" as string]: String(1 - dismiss * 0.75) }}
    >
      <div className="viewer-backdrop" aria-hidden="true" />
      <header className="viewer-bar">
        <div className="viewer-who">
          <strong>{message.isMine ? "You" : peerName}</strong>
          <span>{fullTimestamp(message.createdAt)}</span>
        </div>
        <div className="viewer-actions">
          <button className="viewer-btn" type="button" aria-label="Save video" title="Save" onClick={save} disabled={!video}>
            <Download size={19} />
          </button>
          <button
            ref={closeRef}
            className="viewer-btn"
            type="button"
            aria-label="Close"
            title="Close (Esc)"
            onClick={() => latest.current.onClose()}
          >
            <X size={20} />
          </button>
        </div>
      </header>

      <div
        className="viewer-stage"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => {
          drag.current = null;
          setSwipe(0);
        }}
      >
        <div
          className="viewer-slide"
          style={swipe ? { transform: `translate3d(0, ${swipe}px, 0) scale(${1 - dismiss * 0.12})` } : undefined}
        >
          <div className="viewer-clip">
            {thumb && !ready ? <img className="viewer-thumb" src={thumb} alt="" draggable={false} /> : null}
            {src ? (
              <video
                ref={player}
                className={`viewer-media${ready ? " is-painted" : ""}`}
                src={src}
                playsInline
                preload="auto"
                onLoadedMetadata={(event) => {
                  const node = event.currentTarget;
                  if (node.duration > 0) setDuration(node.duration);
                }}
                onCanPlay={() => {
                  setReady(true);
                  void player.current?.play().catch(() => {
                    setPlaying(false);
                    setChrome(true);
                  });
                }}
                onPlay={() => {
                  setPlaying(true);
                  scheduleHide();
                }}
                onPause={() => {
                  setPlaying(false);
                  setChrome(true);
                  if (hideTimer.current) window.clearTimeout(hideTimer.current);
                }}
                onEnded={() => {
                  setPlaying(false);
                  setChrome(true);
                }}
                onTimeUpdate={(event) => {
                  if (scrub == null) setCurrent(event.currentTarget.currentTime);
                }}
                onClick={(event) => event.stopPropagation()}
              />
            ) : null}
            {!ready && !failed ? (
              <span className="viewer-loading">
                <ProgressRing progress={fraction} size={48} />
              </span>
            ) : null}
            {failed ? <span className="viewer-error">This video couldn’t be opened.</span> : null}
            {ready && (!playing || chrome) ? (
              <button className="video-disc viewer-play" type="button" aria-label={playing ? "Pause" : "Play"} onClick={togglePlay}>
                {playing ? <Pause size={28} fill="currentColor" /> : <Play size={28} fill="currentColor" />}
              </button>
            ) : null}
          </div>
        </div>
      </div>

      <div className="viewer-transport" hidden={!ready}>
        <span>{clockLabel(shown)}</span>
        <input
          className="viewer-scrub"
          type="range"
          min={0}
          max={1}
          step={0.001}
          aria-label="Playback position"
          value={duration > 0 ? Math.min(1, shown / duration) : 0}
          onPointerDown={() => setScrub(current)}
          onChange={(event) => {
            const next = Number(event.currentTarget.value) * duration;
            setScrub(next);
            seek(Number(event.currentTarget.value));
          }}
          onPointerUp={() => setScrub(null)}
        />
        <span>{duration > 0 ? `-${clockLabel(Math.max(0, duration - shown))}` : "-0:00"}</span>
      </div>

      {caption ? <p className="viewer-caption">{caption}</p> : null}
    </div>,
    document.body,
  );
}

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type PointerEvent,
  type WheelEvent,
} from "react";
import { createPortal } from "react-dom";
import { ChevronLeft, ChevronRight, Download, X } from "lucide-react";
import { fullTimestamp } from "../format";
import type { ChatMessage } from "../messaging";
import { peekImage, photoFileName, type LoadedImage } from "../media/images";
import { transferFraction, useTransfer } from "../media/transfers";
import { thumbnailUrl } from "./ImageBubble";
import { ProgressRing } from "./ProgressRing";

const MAX_ZOOM = 4;
/** Where a click, tap or double-tap zooms to. */
const TAP_ZOOM = 2.5;
/** Horizontal swipe that flips to the next photo, and downward swipe that closes. */
const SWIPE_NAV = 64;
const SWIPE_CLOSE = 110;
const DOUBLE_TAP_MS = 280;
/** Movement under this is still a tap. */
const TAP_SLOP = 6;

type View = { scale: number; x: number; y: number };
const REST: View = { scale: 1, x: 0, y: 0 };
type Point = { x: number; y: number };

type Gesture =
  | { kind: "single"; start: Point; from: View; moved: boolean; pointerType: string; onImage: boolean }
  | { kind: "pinch"; distance: number; mid: Point; from: View };

function fitBox(
  photo: ChatMessage,
  stage: { width: number; height: number },
  natural: { width: number; height: number } | null,
) {
  const w = natural?.width || photo.imageWidth || 0;
  const h = natural?.height || photo.imageHeight || 0;
  if (!(w > 0 && h > 0) || !stage.width || !stage.height) return null;
  // Never blow a small photo up past its own pixels.
  const scale = Math.min(stage.width / w, stage.height / h, 1);
  return { width: Math.round(w * scale), height: Math.round(h * scale) };
}

/**
 * Full-screen photo viewer for one chat: arrows or swipes page through its photos;
 * click / double-tap / pinch / wheel zooms, drag pans, swipe down (or Esc) closes.
 */
export function ImageViewer({
  photos,
  startId,
  peerName,
  loadImage,
  onClose,
}: {
  /** Every photo in the chat, oldest first. */
  photos: ChatMessage[];
  startId: string;
  peerName: string;
  loadImage: (message: ChatMessage) => Promise<LoadedImage | null>;
  onClose: () => void;
}) {
  const [current, setCurrent] = useState({
    id: startId,
    index: Math.max(0, photos.findIndex((p) => p.id === startId)),
  });
  // Follow the photo, not the slot: new messages shift indices, and a sent photo is
  // re-keyed by the server while we look at it.
  const found = photos.findIndex((p) => p.id === current.id);
  const index = found >= 0 ? found : Math.min(current.index, photos.length - 1);
  const photo = photos[index] as ChatMessage | undefined;

  const [image, setImage] = useState<LoadedImage | null>(() => (photo ? peekImage(photo.id) : null));
  const [failed, setFailed] = useState(false);
  const [painted, setPainted] = useState(false);
  /** Pixel size of the decoded photo (the payload's may predate orientation fixes). */
  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const [view, setView] = useState<View>(REST);
  const [swipe, setSwipe] = useState<Point | null>(null);
  /** A finger or mouse is moving the photo: follow it 1:1, no easing. */
  const [dragging, setDragging] = useState(false);
  const [chrome, setChrome] = useState(true);
  const [stage, setStage] = useState({ width: 0, height: 0 });
  const stageRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const pointers = useRef(new Map<number, Point>());
  const gesture = useRef<Gesture | null>(null);
  const lastTap = useRef<{ at: number; point: Point } | null>(null);
  const tapTimer = useRef<number | null>(null);
  const transfer = useTransfer(photo?.id ?? "");
  const latest = useRef({ onClose, loadImage });
  latest.current = { onClose, loadImage };

  const go = useCallback(
    (step: number) => {
      const next = photos[index + step];
      if (!next) return;
      setCurrent({ id: next.id, index: index + step });
    },
    [photos, index],
  );

  useEffect(() => {
    if (photo && found < 0) setCurrent({ id: photo.id, index });
  }, [photo, found, index]);

  /* Load the photo on screen, then warm its neighbours so paging feels instant. */
  const photoId = photo?.id;
  useEffect(() => {
    if (!photo) return;
    const ready = peekImage(photo.id);
    setImage(ready);
    setFailed(false);
    setPainted(false);
    setNatural(null);
    setView(REST);
    setSwipe(null);
    let cancelled = false;
    if (!ready) {
      void latest.current.loadImage(photo).then((result) => {
        if (cancelled) return;
        if (result) setImage(result);
        else setFailed(true);
      });
    }
    for (const neighbour of [photos[index - 1], photos[index + 1]]) {
      if (neighbour && !peekImage(neighbour.id)) void latest.current.loadImage(neighbour);
    }
    return () => {
      cancelled = true;
    };
    // Keyed on the photo on screen only: the list around it changing must not reload it.
  }, [photoId]);

  useLayoutEffect(() => {
    const node = imgRef.current;
    if (node?.complete && node.naturalWidth > 0 && image && node.src === image.url) {
      setNatural({ width: node.naturalWidth, height: node.naturalHeight });
      setPainted(true);
    }
  }, [image]);

  /* The stage's content box (inside its padding) is the room a photo gets. */
  useLayoutEffect(() => {
    const node = stageRef.current;
    if (!node) return;
    const observer = new ResizeObserver(([entry]) => {
      setStage({ width: entry.contentRect.width, height: entry.contentRect.height });
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  /* Keyboard, focus and page scroll belong to the viewer while it is open. */
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus?.();
      if (tapTimer.current) window.clearTimeout(tapTimer.current);
    };
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        latest.current.onClose();
      } else if (event.key === "ArrowLeft") {
        event.preventDefault();
        go(-1);
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        go(1);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [go]);

  // Size unknown until it decodes (no dimensions sealed): hold a square meanwhile.
  const box = photo
    ? (fitBox(photo, stage, natural) ??
      (stage.width && stage.height
        ? { width: Math.min(stage.width, 320), height: Math.min(stage.width, stage.height, 320) }
        : null))
    : null;

  /** Keeps a zoomed photo covering the stage, and a smaller one centred. */
  const clamp = useCallback(
    (next: View): View => {
      const scale = Math.min(MAX_ZOOM, Math.max(1, next.scale));
      if (!box || scale <= 1) return REST;
      const maxX = Math.max(0, (box.width * scale - stage.width) / 2);
      const maxY = Math.max(0, (box.height * scale - stage.height) / 2);
      return {
        scale,
        x: Math.min(maxX, Math.max(-maxX, next.x)),
        y: Math.min(maxY, Math.max(-maxY, next.y)),
      };
    },
    [box, stage.width, stage.height],
  );

  /** Point relative to the stage centre, which is also the photo's transform origin. */
  const local = (clientX: number, clientY: number): Point => {
    const rect = stageRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return { x: clientX - rect.left - rect.width / 2, y: clientY - rect.top - rect.height / 2 };
  };

  /** Scale to `scale`, keeping the photo point under `anchor` where it is. */
  const zoomAt = useCallback(
    (from: View, scale: number, anchor: Point): View => {
      const ratio = scale / from.scale;
      return clamp({
        scale,
        x: anchor.x - (anchor.x - from.x) * ratio,
        y: anchor.y - (anchor.y - from.y) * ratio,
      });
    },
    [clamp],
  );

  function toggleZoom(at: Point) {
    setView((now) => (now.scale > 1 ? REST : zoomAt(now, TAP_ZOOM, at)));
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 && event.pointerType === "mouse") return;
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = local(event.clientX, event.clientY);
    pointers.current.set(event.pointerId, point);
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()];
      gesture.current = {
        kind: "pinch",
        distance: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
        from: view,
      };
      setSwipe(null);
      setDragging(true);
      return;
    }
    gesture.current = {
      kind: "single",
      start: point,
      from: view,
      moved: false,
      pointerType: event.pointerType,
      onImage: event.target === imgRef.current,
    };
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    if (!pointers.current.has(event.pointerId)) return;
    const point = local(event.clientX, event.clientY);
    pointers.current.set(event.pointerId, point);
    const active = gesture.current;
    if (!active) return;
    if (active.kind === "pinch") {
      const [a, b] = [...pointers.current.values()];
      if (!a || !b) return;
      const distance = Math.hypot(a.x - b.x, a.y - b.y);
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      const zoomed = zoomAt(active.from, active.from.scale * (distance / active.distance), active.mid);
      setView(clamp({ ...zoomed, x: zoomed.x + mid.x - active.mid.x, y: zoomed.y + mid.y - active.mid.y }));
      return;
    }
    const dx = point.x - active.start.x;
    const dy = point.y - active.start.y;
    if (!active.moved && Math.hypot(dx, dy) > TAP_SLOP) {
      active.moved = true;
      setDragging(true);
    }
    if (!active.moved) return;
    if (active.from.scale > 1) {
      setView(clamp({ ...active.from, x: active.from.x + dx, y: active.from.y + dy }));
    } else if (active.pointerType !== "mouse") {
      setSwipe({ x: dx, y: Math.max(0, dy) });
    }
  }

  function onPointerUp(event: PointerEvent<HTMLDivElement>) {
    const point = pointers.current.get(event.pointerId) ?? local(event.clientX, event.clientY);
    pointers.current.delete(event.pointerId);
    const active = gesture.current;
    if (!active) return;
    if (active.kind === "pinch") {
      if (pointers.current.size === 0) {
        gesture.current = null;
        setDragging(false);
      } else {
        // One finger stays down: carry on as a pan from here.
        const [rest] = [...pointers.current.values()];
        gesture.current = { kind: "single", start: rest, from: view, moved: true, pointerType: "touch", onImage: true };
      }
      return;
    }
    gesture.current = null;
    setDragging(false);
    if (event.type === "pointercancel") {
      setSwipe(null);
      return;
    }
    if (active.moved) {
      const dx = point.x - active.start.x;
      const dy = point.y - active.start.y;
      if (active.from.scale <= 1 && active.pointerType !== "mouse") {
        if (Math.abs(dx) > SWIPE_NAV && Math.abs(dx) > Math.abs(dy)) go(dx < 0 ? 1 : -1);
        else if (dy > SWIPE_CLOSE && dy > Math.abs(dx)) {
          latest.current.onClose();
          return;
        }
      }
      setSwipe(null);
      return;
    }
    if (active.pointerType === "mouse") {
      // Desktop: click the photo to zoom, anywhere else to leave.
      if (active.onImage || view.scale > 1) toggleZoom(point);
      else latest.current.onClose();
      return;
    }
    // Touch: double-tap zooms; a single tap shows or hides the chrome.
    const now = performance.now();
    const previous = lastTap.current;
    if (previous && now - previous.at < DOUBLE_TAP_MS && Math.hypot(point.x - previous.point.x, point.y - previous.point.y) < 40) {
      lastTap.current = null;
      if (tapTimer.current) window.clearTimeout(tapTimer.current);
      toggleZoom(point);
      return;
    }
    lastTap.current = { at: now, point };
    if (tapTimer.current) window.clearTimeout(tapTimer.current);
    tapTimer.current = window.setTimeout(() => setChrome((shown) => !shown), DOUBLE_TAP_MS);
  }

  function onWheel(event: WheelEvent<HTMLDivElement>) {
    if (!box) return;
    event.preventDefault();
    // Trackpad pinches arrive as ctrl+wheel with small deltas; mouse wheels step.
    const factor = Math.exp(-event.deltaY * (event.ctrlKey ? 0.01 : 0.0018));
    const anchor = local(event.clientX, event.clientY);
    setView((now) => zoomAt(now, now.scale * factor, anchor));
  }

  function save() {
    if (!image || !photo) return;
    const url = URL.createObjectURL(image.original);
    const link = document.createElement("a");
    link.href = url;
    link.download = photoFileName(photo, image.original);
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1500);
  }

  if (!photo) return null;
  const thumb = thumbnailUrl(photo);
  const caption = photo.caption?.trim() || "";
  const fraction = transferFraction(transfer);
  const dismiss = swipe ? Math.min(1, swipe.y / 320) : 0;
  const settling = !swipe && !dragging;

  return createPortal(
    <div
      className={`viewer${chrome ? "" : " viewer-bare"}`}
      role="dialog"
      aria-modal="true"
      aria-label={`Photo from ${photo.isMine ? "you" : peerName}`}
      style={{ ["--viewer-dim" as string]: String(1 - dismiss * 0.75) }}
    >
      <div className="viewer-backdrop" aria-hidden="true" />
      <header className="viewer-bar">
        <div className="viewer-who">
          <strong>{photo.isMine ? "You" : peerName}</strong>
          <span>{fullTimestamp(photo.createdAt)}</span>
        </div>
        {photos.length > 1 ? (
          <span className="viewer-count" aria-live="polite">
            {index + 1} of {photos.length}
          </span>
        ) : null}
        <div className="viewer-actions">
          <button className="viewer-btn" type="button" aria-label="Save photo" title="Save" onClick={save} disabled={!image}>
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
        ref={stageRef}
        className="viewer-stage"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
      >
        <div
          className={`viewer-slide${settling ? " settling" : ""}`}
          style={{ transform: swipe ? `translate3d(${swipe.x}px, ${swipe.y}px, 0) scale(${1 - dismiss * 0.12})` : undefined }}
        >
          <div
            className={`viewer-photo${view.scale > 1 ? " zoomed" : ""}${settling ? " settling" : ""}`}
            style={{
              width: box?.width,
              height: box?.height,
              transform: `translate3d(${view.x}px, ${view.y}px, 0) scale(${view.scale})`,
            }}
          >
            {thumb && !painted ? <img className="viewer-thumb" src={thumb} alt="" draggable={false} /> : null}
            {image ? (
              <img
                ref={imgRef}
                key={image.url}
                className={`viewer-img${painted ? " is-painted" : ""}`}
                src={image.url}
                alt={caption || "Photo"}
                draggable={false}
                onLoad={(event) => {
                  const node = event.currentTarget;
                  setNatural({ width: node.naturalWidth, height: node.naturalHeight });
                  setPainted(true);
                }}
              />
            ) : null}
            {!image && !failed ? (
              <span className="viewer-loading">
                <ProgressRing progress={fraction} size={48} />
              </span>
            ) : null}
            {failed ? <span className="viewer-error">This photo couldn’t be loaded.</span> : null}
          </div>
        </div>
      </div>

      {index > 0 ? (
        <button className="viewer-nav viewer-prev" type="button" aria-label="Previous photo" onClick={() => go(-1)}>
          <ChevronLeft size={26} />
        </button>
      ) : null}
      {index < photos.length - 1 ? (
        <button className="viewer-nav viewer-next" type="button" aria-label="Next photo" onClick={() => go(1)}>
          <ChevronRight size={26} />
        </button>
      ) : null}

      {caption ? <p className="viewer-caption">{caption}</p> : null}
    </div>,
    document.body,
  );
}

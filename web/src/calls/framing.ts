/**
 * Which part of our camera goes out (docs/calls.md, "Framing and Center Stage").
 *
 * The picture is cut to the shape of the area the other side shows it in (their `media_state`
 * `view`), from the camera's full resolution, so a phone that fills its screen with our picture
 * gets every pixel it shows instead of enlarging the middle of a wide frame. With Center Stage on,
 * the cut also follows the faces in it: it pans to keep them in the middle and zooms in on them,
 * as far as the camera has pixels to spare.
 *
 * Pure geometry, in upright picture pixels (as the picture is seen, rotation applied). The
 * iPhone (`CallFraming.swift`) and Android (`CallFraming.kt`) run the same numbers.
 */

export type Size = { width: number; height: number };
export type Rect = { x: number; y: number; width: number; height: number };

/** The most pixels the output's longer side gets: the camera ladder's top rung (1080p). */
export const FRAME_MAX_LONG = 1920;
/** The narrowest and the widest shape we cut to (width / height): a tall phone, a wide window. */
const MIN_ASPECT = 0.4;
const MAX_ASPECT = 2.5;
/** A new view shape is taken only when it differs this much: a window being dragged is not. */
const SHAPE_CHANGE = 0.03;
/** How much the faces fill the cut: their height 30 % of it (head and shoulders). */
const FACE_HEIGHT_SHARE = 0.3;
/** Their width at most this much of it, for several people side by side. */
const FACE_WIDTH_SHARE = 0.6;
/** Where the faces' middle sits from the top of the cut. */
const FACE_FROM_TOP = 0.42;
/** The cut is never enlarged more than this into the output (so it never turns soft). */
const MAX_UPSCALE = 1.25;
/** Nor zoomed in more than this on the whole picture. */
const MAX_ZOOM = 2.5;
/** A new target closer than this to the last one (share of the cut's size) is not followed. */
const DEADBAND = 0.06;
/** How fast the cut follows (seconds to cover about two thirds of the way): pan, then zoom. */
const PAN_SECONDS = 0.35;
const ZOOM_SECONDS = 0.6;
/** No face for this long: the cut goes back to the whole picture. */
const LOST_MS = 1_500;

function even(value: number): number {
  return Math.max(2, Math.round(value / 2) * 2);
}

function clampAspect(aspect: number): number {
  return Math.min(MAX_ASPECT, Math.max(MIN_ASPECT, aspect));
}

/** The largest rectangle of `aspect` inside `size`, in its middle. */
export function largestInside(size: Size, aspect: number): Rect {
  let width = size.width;
  let height = width / aspect;
  if (height > size.height) {
    height = size.height;
    width = height * aspect;
  }
  return { x: (size.width - width) / 2, y: (size.height - height) / 2, width, height };
}

/**
 * The output picture: the shape of their view (or the camera's own, when they did not say), as
 * large as the camera can fill without enlarging, at most 1080p on its longer side. Even sides.
 */
export function outputSize(capture: Size, view: Size | null): Size {
  const aspect = clampAspect(view && view.width > 0 && view.height > 0 ? view.width / view.height : capture.width / capture.height);
  const base = largestInside(capture, aspect);
  const long = Math.min(FRAME_MAX_LONG, Math.max(base.width, base.height));
  return aspect >= 1
    ? { width: even(long), height: even(long / aspect) }
    : { width: even(long * aspect), height: even(long) };
}

/** Whether a newly reported view shape is far enough from the one in use to cut to it. */
export function shapeChanged(current: Size | null, next: Size | null): boolean {
  if (!current || !next) return current !== next;
  const a = current.width / current.height;
  const b = next.width / next.height;
  return Math.abs(a - b) / a > SHAPE_CHANGE;
}

/** The smallest box around every face (each in upright pixels), or null for none. */
export function faceUnion(faces: readonly Rect[]): Rect | null {
  if (faces.length === 0) return null;
  let left = Infinity;
  let top = Infinity;
  let right = -Infinity;
  let bottom = -Infinity;
  for (const face of faces) {
    left = Math.min(left, face.x);
    top = Math.min(top, face.y);
    right = Math.max(right, face.x + face.width);
    bottom = Math.max(bottom, face.y + face.height);
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * Where the cut should be: the whole picture in the output's shape, or, with faces, a cut around
 * them (head and shoulders, the faces a little above the middle), no tighter than the output's
 * pixels allow, and always inside the picture.
 */
export function targetCrop(capture: Size, output: Size, faces: Rect | null): Rect {
  const aspect = output.width / output.height;
  const base = largestInside(capture, aspect);
  if (!faces) return base;
  const fromHeight = faces.height / FACE_HEIGHT_SHARE;
  const fromWidth = faces.width / FACE_WIDTH_SHARE / aspect;
  const least = Math.max(output.height / MAX_UPSCALE, base.height / MAX_ZOOM);
  const height = Math.min(base.height, Math.max(fromHeight, fromWidth, least));
  const width = height * aspect;
  const centerX = faces.x + faces.width / 2;
  const centerY = faces.y + faces.height / 2;
  const x = Math.min(capture.width - width, Math.max(0, centerX - width / 2));
  const y = Math.min(capture.height - height, Math.max(0, centerY - height * FACE_FROM_TOP));
  return { x, y, width, height };
}

function far(a: Rect, b: Rect): boolean {
  const size = Math.max(a.width, a.height);
  const moved = Math.hypot(a.x + a.width / 2 - (b.x + b.width / 2), a.y + a.height / 2 - (b.y + b.height / 2));
  return moved > size * DEADBAND || Math.abs(a.height - b.height) > a.height * DEADBAND;
}

/** One step of `from` toward `to` after `seconds`, covering about two thirds of it every `tau`. */
function ease(from: number, to: number, seconds: number, tau: number): number {
  return from + (to - from) * (1 - Math.exp(-seconds / tau));
}

/**
 * Where to put a picture of `picture`'s size, filled into a `box` (aspect fill: scaled to cover it
 * and cropped), so that `focus` (a point in the picture, 0…1 on each axis) lands as near the box's
 * middle as the picture allows: the offset of the scaled picture's top left inside the box, never
 * past an edge. Our own small picture uses it to keep the faces in view (docs/calls.md, "Framing
 * and Center Stage"); a box and a picture of one shape need no offset.
 */
export function coverOffset(box: Size, picture: Size, focus: { x: number; y: number }): { x: number; y: number; scale: number } {
  if (box.width <= 0 || box.height <= 0 || picture.width <= 0 || picture.height <= 0) return { x: 0, y: 0, scale: 1 };
  const scale = Math.max(box.width / picture.width, box.height / picture.height);
  const axis = (boxSide: number, pictureSide: number, at: number) => {
    const shown = pictureSide * scale;
    if (shown - boxSide < 0.5) return (boxSide - shown) / 2;
    return Math.min(0, Math.max(boxSide - shown, boxSide / 2 - at * shown));
  };
  return { x: axis(box.width, picture.width, focus.x), y: axis(box.height, picture.height, focus.y), scale };
}

/**
 * The cut over time, for one camera: `faces` gives it what the detector saw, `next` where the cut
 * is for a frame. It glides (pans quicker than it zooms), ignores small jitter, holds on a face
 * that is briefly lost, and goes back to the whole picture once none has been seen for a while.
 */
export class Framer {
  private capture: Size = { width: 0, height: 0 };
  private output: Size = { width: 0, height: 0 };
  private current: Rect | null = null;
  private target: Rect | null = null;
  private lastFace = -Infinity;
  private lastStep: number | null = null;
  private follow = true;
  /** The faces' middle the cut aims at (upright pixels), or null: none to follow. */
  private faceTarget: { x: number; y: number } | null = null;
  /** The point our own picture centres on, gliding like the cut: the faces' middle, else the cut's. */
  private faceAt: { x: number; y: number } | null = null;

  /** The picture coming in and the output going out; a change starts again from the whole picture. */
  configure(capture: Size, output: Size, follow: boolean): void {
    const same =
      capture.width === this.capture.width &&
      capture.height === this.capture.height &&
      output.width === this.output.width &&
      output.height === this.output.height;
    this.follow = follow;
    if (same && this.current) {
      if (!follow) {
        this.target = targetCrop(capture, output, null);
        this.faceTarget = null;
      }
      return;
    }
    this.capture = capture;
    this.output = output;
    this.current = targetCrop(capture, output, null);
    this.target = this.current;
    this.faceTarget = null;
    this.faceAt = null;
    this.lastStep = null;
  }

  /** What the detector saw in the picture at `now` (ms): faces in upright pixels, maybe none. */
  faces(faces: readonly Rect[], now: number): void {
    if (!this.current) return;
    const union = this.follow ? faceUnion(faces) : null;
    if (union) this.lastFace = now;
    else if (now - this.lastFace < LOST_MS) return;
    const next = targetCrop(this.capture, this.output, union);
    if (!this.target || far(this.target, next)) {
      this.target = next;
      this.faceTarget = union ? { x: union.x + union.width / 2, y: union.y + union.height / 2 } : null;
    }
  }

  /** Where the cut is for a frame at `now` (ms). */
  next(now: number): Rect {
    if (!this.current || !this.target) return { x: 0, y: 0, width: this.capture.width, height: this.capture.height };
    const seconds = this.lastStep === null ? 0 : Math.max(0, Math.min(0.25, (now - this.lastStep) / 1000));
    this.lastStep = now;
    const t = this.target;
    const c = this.current;
    const height = ease(c.height, t.height, seconds, ZOOM_SECONDS);
    const width = height * (this.output.width / this.output.height);
    const centerX = ease(c.x + c.width / 2, t.x + t.width / 2, seconds, PAN_SECONDS);
    const centerY = ease(c.y + c.height / 2, t.y + t.height / 2, seconds, PAN_SECONDS);
    const x = Math.min(this.capture.width - width, Math.max(0, centerX - width / 2));
    const y = Math.min(this.capture.height - height, Math.max(0, centerY - height / 2));
    this.current = { x, y, width, height };
    const aim = this.faceTarget ?? { x: x + width / 2, y: y + height / 2 };
    const at = this.faceAt ?? aim;
    this.faceAt = { x: ease(at.x, aim.x, seconds, PAN_SECONDS), y: ease(at.y, aim.y, seconds, PAN_SECONDS) };
    return this.current;
  }

  /**
   * Where the faces are in the cut that last went out (0…1 on each axis), for our own small
   * picture to centre on; the middle when there are none to follow.
   */
  focus(): { x: number; y: number } {
    const cut = this.current;
    const at = this.faceAt;
    if (!cut || !at || cut.width <= 0 || cut.height <= 0) return { x: 0.5, y: 0.5 };
    const clamp = (value: number) => Math.min(1, Math.max(0, value));
    return { x: clamp((at.x - cut.x) / cut.width), y: clamp((at.y - cut.y) / cut.height) };
  }
}

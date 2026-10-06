/**
 * Faces for Center Stage on the web: YuNet (libfacedetection, MIT, 232 KB, `models/`), run on the
 * ONNX runtime the app already ships for Whisper, in the face worker (faceWorker.ts). Pure helpers:
 * the model's input from a picture's pixels, and faces from its outputs. Nothing leaves the device.
 *
 * The model takes one 640×640 picture, BGR, 0…255, channels first. A camera frame is shrunk to fit
 * it at its top left (the rest stays black), so a face's box maps back by the same scale.
 */

import type { Rect } from "./framing";

export const YUNET_SIZE = 640;
const STRIDES = [8, 16, 32] as const;
/** A face this sure or surer counts. */
const SCORE_MIN = 0.6;
/** Boxes overlapping more than this are the same face. */
const SAME_FACE = 0.3;
const MOST_FACES = 8;

export type Face = Rect & { score: number };

/** The scale a `width`×`height` picture is drawn at into the model's square. */
export function yunetScale(width: number, height: number): number {
  return YUNET_SIZE / Math.max(width, height);
}

/**
 * The model's input from RGBA pixels of the square (as `getImageData` gives them): blue, green,
 * red planes of 0…255.
 */
export function yunetInput(rgba: Uint8ClampedArray | Uint8Array): Float32Array {
  const plane = YUNET_SIZE * YUNET_SIZE;
  const out = new Float32Array(plane * 3);
  for (let i = 0, p = 0; p < plane; i += 4, p++) {
    out[p] = rgba[i + 2];
    out[plane + p] = rgba[i + 1];
    out[plane * 2 + p] = rgba[i];
  }
  return out;
}

function clamp01(value: number): number {
  return value < 0 ? 0 : value > 1 ? 1 : value;
}

function overlap(a: Face, b: Face): number {
  const left = Math.max(a.x, b.x);
  const top = Math.max(a.y, b.y);
  const right = Math.min(a.x + a.width, b.x + b.width);
  const bottom = Math.min(a.y + a.height, b.y + b.height);
  const inter = Math.max(0, right - left) * Math.max(0, bottom - top);
  const union = a.width * a.height + b.width * b.height - inter;
  return union > 0 ? inter / union : 0;
}

/**
 * Faces from the model's outputs (`cls_8` … `bbox_32`, as flat arrays), in the square's pixels:
 * each stride's grid cell scores `sqrt(cls × obj)`, and its box is its offset from the cell and
 * its log size, times the stride (OpenCV's FaceDetectorYN). Overlapping boxes keep the surest.
 */
export function decodeYunet(outputs: Record<string, ArrayLike<number>>): Face[] {
  const found: Face[] = [];
  for (const stride of STRIDES) {
    const cls = outputs[`cls_${stride}`];
    const obj = outputs[`obj_${stride}`];
    const bbox = outputs[`bbox_${stride}`];
    if (!cls || !obj || !bbox) continue;
    const cols = YUNET_SIZE / stride;
    const cells = cols * cols;
    for (let index = 0; index < cells; index++) {
      const score = Math.sqrt(clamp01(cls[index]) * clamp01(obj[index]));
      if (score < SCORE_MIN) continue;
      const row = Math.floor(index / cols);
      const col = index % cols;
      const centerX = (col + bbox[index * 4]) * stride;
      const centerY = (row + bbox[index * 4 + 1]) * stride;
      const width = Math.exp(bbox[index * 4 + 2]) * stride;
      const height = Math.exp(bbox[index * 4 + 3]) * stride;
      found.push({ x: centerX - width / 2, y: centerY - height / 2, width, height, score });
    }
  }
  found.sort((a, b) => b.score - a.score);
  const kept: Face[] = [];
  for (const face of found) {
    if (kept.every((other) => overlap(face, other) <= SAME_FACE)) kept.push(face);
    if (kept.length === MOST_FACES) break;
  }
  return kept;
}

/** Faces from the square back in the picture's pixels (the square drew it at `scale`). */
export function facesInPicture(faces: readonly Face[], scale: number): Face[] {
  return faces.map((face) => ({
    x: face.x / scale,
    y: face.y / scale,
    width: face.width / scale,
    height: face.height / scale,
    score: face.score,
  }));
}

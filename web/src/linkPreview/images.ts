/**
 * A link preview's pictures, made in the browser from the page's image — the same two JPEGs
 * iOS `LinkPreviewFetcher.prepareImages` makes: a large one (≤ 1024 px on its long edge) for
 * the big layout, and a square thumbnail (≤ 6 KB, sealed inline as `th`) for the small one.
 *
 * The declared size is read from the file header first (`imageDimensions`, pure): a few KB of
 * PNG can declare a 20 000 × 20 000 canvas, and decoding that would freeze the tab.
 */

import { MAX_THUMBNAIL_BYTES } from "../links";

/** Largest canvas decoded, in pixels (same limit as iOS). */
export const MAX_IMAGE_PIXELS = 40_000_000;
/** Smaller than this on either side is an icon or a tracking pixel, not a preview. */
export const MIN_IMAGE_EDGE = 80;
/** Long edge of the large-layout JPEG (≈ 3× a bubble's width). */
export const LARGE_IMAGE_EDGE = 1024;
/** Edge of the square thumbnail (54 pt at 3×). */
export const THUMBNAIL_EDGE = 160;
/** Long edge of the blurred placeholder sealed with a large picture (`th` of a `t:"link"`). */
const PLACEHOLDER_EDGE = 48;

export type ImageSize = { width: number; height: number };

const u16be = (b: Uint8Array, i: number) => (b[i] << 8) | b[i + 1];
const u16le = (b: Uint8Array, i: number) => b[i] | (b[i + 1] << 8);
const u24le = (b: Uint8Array, i: number) => b[i] | (b[i + 1] << 8) | (b[i + 2] << 16);
const u32be = (b: Uint8Array, i: number) => ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]);
const ascii = (b: Uint8Array, i: number, n: number) => String.fromCharCode(...b.subarray(i, i + n));

/**
 * The size an image file declares, from its header alone: PNG, JPEG, GIF, WebP. Null for
 * anything else — such an image is simply not previewed.
 */
export function imageDimensions(bytes: Uint8Array): ImageSize | null {
  // PNG: signature, then the IHDR chunk.
  if (bytes.length >= 24 && bytes[0] === 0x89 && ascii(bytes, 1, 3) === "PNG" && ascii(bytes, 12, 4) === "IHDR") {
    return { width: u32be(bytes, 16), height: u32be(bytes, 20) };
  }
  // GIF: logical screen size.
  if (bytes.length >= 10 && (ascii(bytes, 0, 6) === "GIF87a" || ascii(bytes, 0, 6) === "GIF89a")) {
    return { width: u16le(bytes, 6), height: u16le(bytes, 8) };
  }
  // WebP: RIFF container, VP8 / VP8L / VP8X.
  if (bytes.length >= 30 && ascii(bytes, 0, 4) === "RIFF" && ascii(bytes, 8, 4) === "WEBP") {
    const kind = ascii(bytes, 12, 4);
    if (kind === "VP8X") return { width: u24le(bytes, 24) + 1, height: u24le(bytes, 27) + 1 };
    if (kind === "VP8 ") return { width: u16le(bytes, 26) & 0x3fff, height: u16le(bytes, 28) & 0x3fff };
    if (kind === "VP8L") {
      const bits = bytes[21] | (bytes[22] << 8) | (bytes[23] << 16) | (bytes[24] << 24);
      return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
    }
    return null;
  }
  // JPEG: walk the markers to the first start-of-frame.
  if (bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2;
    while (offset + 9 <= bytes.length) {
      if (bytes[offset] !== 0xff) return null;
      const marker = bytes[offset + 1];
      if (marker === 0xff) {
        offset += 1; // fill byte
        continue;
      }
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        offset += 2; // markers without a length
        continue;
      }
      const isFrame = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
      if (isFrame) return { width: u16be(bytes, offset + 7), height: u16be(bytes, offset + 5) };
      offset += 2 + u16be(bytes, offset + 2);
    }
    return null;
  }
  return null;
}

/** True when an image of this declared size is worth decoding, and safe to. */
export function isDecodableSize(size: ImageSize | null): size is ImageSize {
  return (
    size !== null &&
    size.width >= MIN_IMAGE_EDGE &&
    size.height >= MIN_IMAGE_EDGE &&
    size.width * size.height <= MAX_IMAGE_PIXELS
  );
}

export type PreparedLinkImages = {
  /** JPEG for the large layout. */
  large: Uint8Array;
  /** Base64 JPEG ≤ `MAX_THUMBNAIL_BYTES` for the small layout; null if it would not fit. */
  thumbnail: string | null;
  /** Base64 JPEG of a few hundred bytes, drawn blurred while the large picture downloads. */
  placeholder: string | null;
  width: number;
  height: number;
};

type Canvas = OffscreenCanvas | HTMLCanvasElement;

function canvas(width: number, height: number): Canvas {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  const element = document.createElement("canvas");
  element.width = width;
  element.height = height;
  return element;
}

async function jpeg(target: Canvas, quality: number): Promise<Uint8Array> {
  const blob =
    "convertToBlob" in target
      ? await target.convertToBlob({ type: "image/jpeg", quality })
      : await new Promise<Blob>((resolve, reject) =>
          (target as HTMLCanvasElement).toBlob(
            (made) => (made ? resolve(made) : reject(new Error("JPEG encode failed"))),
            "image/jpeg",
            quality,
          ),
        );
  return new Uint8Array(await blob.arrayBuffer());
}

/** Draws `source` (a crop of it) onto a white canvas — JPEG has no alpha. */
function draw(
  source: ImageBitmap,
  width: number,
  height: number,
  crop: { x: number; y: number; w: number; h: number } = { x: 0, y: 0, w: source.width, h: source.height },
): Canvas {
  const target = canvas(width, height);
  const context = target.getContext("2d") as CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D | null;
  if (!context) throw new Error("No 2D canvas");
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, width, height);
  context.imageSmoothingQuality = "high";
  context.drawImage(source, crop.x, crop.y, crop.w, crop.h, 0, 0, width, height);
  return target;
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

/**
 * Downsampled large JPEG, square thumbnail, and placeholder — or null for an image that is too
 * small, too big to decode safely, or not decodable at all.
 */
export async function prepareLinkImages(bytes: Uint8Array): Promise<PreparedLinkImages | null> {
  const declared = imageDimensions(bytes);
  if (!isDecodableSize(declared)) return null;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(new Blob([bytes as BlobPart]));
  } catch {
    return null;
  }
  try {
    const scale = Math.min(1, LARGE_IMAGE_EDGE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const large = await jpeg(draw(bitmap, width, height), 0.72);

    // Center square crop, shrinking until it fits the envelope budget.
    const side = Math.min(bitmap.width, bitmap.height);
    const crop = { x: (bitmap.width - side) / 2, y: (bitmap.height - side) / 2, w: side, h: side };
    let thumbnail: string | null = null;
    let edge = THUMBNAIL_EDGE;
    let quality = 0.7;
    for (let attempt = 0; attempt < 5; attempt++) {
      const data = await jpeg(draw(bitmap, edge, edge, crop), quality);
      if (data.byteLength <= MAX_THUMBNAIL_BYTES) {
        thumbnail = toBase64(data);
        break;
      }
      edge = Math.max(96, edge - 24);
      quality = Math.max(0.35, quality - 0.1);
    }

    const placeholderScale = PLACEHOLDER_EDGE / Math.max(bitmap.width, bitmap.height);
    const placeholderData = await jpeg(
      draw(
        bitmap,
        Math.max(1, Math.round(bitmap.width * placeholderScale)),
        Math.max(1, Math.round(bitmap.height * placeholderScale)),
      ),
      0.5,
    );
    return { large, thumbnail, placeholder: toBase64(placeholderData), width, height };
  } finally {
    bitmap.close();
  }
}

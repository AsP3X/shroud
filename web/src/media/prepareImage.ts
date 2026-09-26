import { envelopePreview, fitEdge } from "./envelopePreview";
import { heifToJpeg, isHeif } from "./heic";

/**
 * Photos leave the browser like iOS "HD": longest edge 2560 px, JPEG quality 0.85.
 * Re-encoding through a canvas also bakes in EXIF orientation and drops every
 * metadata block — GPS included — which a pass-through upload would leak.
 */
const MAX_EDGE = 2560;
const JPEG_QUALITY = 0.85;
/** Same plaintext budget as videos: 2 GiB sealed, with room for AES-GCM. */
const MAX_IMAGE_BYTES = 2 * 1024 * 1024 * 1024 - 1024 * 1024;
/**
 * This path decodes the file into a bitmap and then downsizes it to `MAX_EDGE`.
 * A multi-gigabyte decode takes the tab down before that resize, so refuse it first.
 * The encoded result is still checked against `MAX_IMAGE_BYTES`.
 */
const MAX_SOURCE_BYTES = 64 * 1024 * 1024;
/** Formats that can carry transparency; everything else is flattened to JPEG. */
const MAY_HAVE_ALPHA = /^image\/(png|webp|gif|avif|bmp|x-icon|vnd\.microsoft\.icon)$/i;

/** Same cap as the iPhone's picker; each photo is its own message. */
export const MAX_PHOTOS_PER_SEND = 10;
/** `image/*` alone doesn't offer .heic files on every desktop browser. */
export const PHOTO_ACCEPT = "image/*,.heic,.heif";

export type PreparedImage = {
  bytes: Uint8Array;
  mime: "image/jpeg" | "image/png";
  width: number;
  height: number;
  /** ≤ 6 KB JPEG sealed into the envelope; null when even the smallest try is bigger. */
  thumb: Uint8Array | null;
};

type Source = { image: HTMLImageElement; width: number; height: number; release: () => void };

async function loadElement(blob: Blob): Promise<Source> {
  const url = URL.createObjectURL(blob);
  const image = new Image();
  image.decoding = "async";
  image.src = url;
  try {
    await image.decode();
  } catch (err) {
    URL.revokeObjectURL(url);
    throw err;
  }
  // `naturalWidth/Height` and `drawImage` both follow EXIF orientation.
  return {
    image,
    width: image.naturalWidth,
    height: image.naturalHeight,
    release: () => URL.revokeObjectURL(url),
  };
}

async function loadSource(file: Blob): Promise<Source> {
  try {
    return await loadElement(file);
  } catch {
    // Chrome and Firefox can't draw HEIC (a .heic from a Mac, say): convert first.
    const bytes = new Uint8Array(await file.arrayBuffer());
    if (!isHeif(bytes, file.type)) throw new Error("This file isn’t an image this browser can read.");
    const converted = await heifToJpeg(bytes, 0.95);
    return loadElement(converted.blob);
  }
}

function hasTransparency(source: Source): boolean {
  const size = 48;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return false;
  context.drawImage(source.image, 0, 0, size, size);
  const { data } = context.getImageData(0, 0, size, size);
  for (let i = 3; i < data.length; i += 4) if (data[i] < 250) return true;
  return false;
}

async function render(
  source: Source,
  width: number,
  height: number,
  type: PreparedImage["mime"],
  quality?: number,
): Promise<Uint8Array> {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const opaque = type === "image/jpeg";
  const context = canvas.getContext("2d", { alpha: !opaque });
  if (!context) throw new Error("This browser can’t prepare images.");
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  if (opaque) {
    // JPEG has no alpha: transparent pixels would turn black without a ground.
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
  }
  context.drawImage(source.image, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, quality));
  // Give the backing store back now; Safari counts canvases against a small budget.
  canvas.width = 0;
  canvas.height = 0;
  if (!blob) throw new Error("Couldn’t encode the image.");
  return new Uint8Array(await blob.arrayBuffer());
}

function fit(source: Source, edge: number): { width: number; height: number } {
  return fitEdge(source.width, source.height, edge);
}

function thumbnail(source: Source): Promise<Uint8Array | null> {
  return envelopePreview((edge, quality) => {
    const size = fit(source, edge);
    return render(source, size.width, size.height, "image/jpeg", quality);
  });
}

/** Decodes, orients, downsizes and re-encodes one picked or pasted image for sending. */
export async function prepareImage(file: Blob): Promise<PreparedImage> {
  if (file.size > MAX_SOURCE_BYTES) throw new Error("That image is over 64 MB — too large to send.");
  const source = await loadSource(file);
  try {
    if (!source.width || !source.height) throw new Error("This image has no pixels to send.");
    const { width, height } = fit(source, MAX_EDGE);
    const mime = MAY_HAVE_ALPHA.test(file.type) && hasTransparency(source) ? "image/png" : "image/jpeg";
    const bytes = await render(source, width, height, mime, mime === "image/jpeg" ? JPEG_QUALITY : undefined);
    if (bytes.byteLength > MAX_IMAGE_BYTES) throw new Error("That image is too large to send.");
    return { bytes, mime, width, height, thumb: await thumbnail(source) };
  } finally {
    source.release();
  }
}

/** Files from a picker, a paste or a drop that look like photos (SVG is not one). */
export function imageFiles(files: Iterable<File> | ArrayLike<File> | null | undefined): File[] {
  if (!files) return [];
  return Array.from(files).filter(
    (file) =>
      (file.type.startsWith("image/") && file.type !== "image/svg+xml") ||
      /\.(heic|heif)$/i.test(file.name),
  );
}

/**
 * Photos on a paste: some browsers put a screenshot in `items` and leave `files`
 * empty (Chrome), others fill `files`. Prefer `files` so we don't double-count.
 */
export function clipboardImages(data: DataTransfer | null | undefined): File[] {
  if (!data) return [];
  const fromFiles = imageFiles(data.files);
  if (fromFiles.length) return fromFiles;
  const extras: File[] = [];
  for (const item of Array.from(data.items ?? [])) {
    if (item.kind !== "file") continue;
    const file = item.getAsFile();
    if (file) extras.push(file);
  }
  return imageFiles(extras);
}

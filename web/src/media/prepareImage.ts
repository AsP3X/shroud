import { imageDimensions } from "../linkPreview/images";
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
 * A file this large is not decoded until its header says the bitmap is an ordinary photo.
 * The file size itself is not the limit: a lightly compressed picture can be well over
 * this and still shrink to `MAX_EDGE`. A multi-gigabyte file is refused by `MAX_IMAGE_BYTES`.
 */
const MAX_FULL_DECODE_BYTES = 64 * 1024 * 1024;
/** Above this, the picture is decoded already scaled to `MAX_EDGE` instead of at full size. */
const MAX_DECODE_PIXELS = 80_000_000;
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

type Source = { image: CanvasImageSource; width: number; height: number; release: () => void };

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
  if (file.size > MAX_FULL_DECODE_BYTES) {
    const head = new Uint8Array(await file.slice(0, 8 * 1024 * 1024).arrayBuffer());
    // The HEIC converter builds the whole frame, so a file this large is refused.
    // Anything whose header does not give a size is refused too: decoding it blind is
    // how a multi-hundred-megabyte file takes the tab down.
    const size = displayPixelSize(head);
    if (isHeif(head, file.type) || !size) throw new Error("That image is too large to send.");
    if (size.width * size.height > MAX_DECODE_PIXELS) return loadScaled(file, size);
    try {
      return await loadElement(file);
    } catch {
      throw new Error("This file isn’t an image this browser can read.");
    }
  }
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

/**
 * Pixel size as the picture will be drawn, from the file header alone. JPEG orientations
 * 5–8 swap the axes. Null when the header does not say.
 */
export function displayPixelSize(bytes: Uint8Array): { width: number; height: number } | null {
  const size = imageDimensions(bytes);
  if (!size || size.width <= 0 || size.height <= 0) return null;
  const orientation = jpegOrientation(bytes);
  if (orientation >= 5 && orientation <= 8) return { width: size.height, height: size.width };
  return size;
}

/** EXIF orientation (1–8), or 1 when the file has none. */
function jpegOrientation(bytes: Uint8Array): number {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return 1;
  let offset = 2;
  while (offset + 4 < bytes.length) {
    if (bytes[offset] !== 0xff) return 1;
    const marker = bytes[offset + 1];
    if (marker === 0xff) {
      offset += 1;
      continue;
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2;
      continue;
    }
    const length = (bytes[offset + 2] << 8) | bytes[offset + 3];
    if (length < 2 || offset + 2 + length > bytes.length) return 1;
    if (marker === 0xe1) {
      const start = offset + 4;
      if (
        start + 14 <= bytes.length &&
        bytes[start] === 0x45 &&
        bytes[start + 1] === 0x78 &&
        bytes[start + 2] === 0x69 &&
        bytes[start + 3] === 0x66 &&
        bytes[start + 4] === 0 &&
        bytes[start + 5] === 0
      ) {
        const value = tiffOrientation(bytes.subarray(start + 6, offset + 2 + length));
        if (value) return value;
      }
    }
    if (marker === 0xda) return 1;
    offset += 2 + length;
  }
  return 1;
}

function tiffOrientation(tiff: Uint8Array): number | null {
  if (tiff.length < 16) return null;
  const le = tiff[0] === 0x49 && tiff[1] === 0x49;
  const be = tiff[0] === 0x4d && tiff[1] === 0x4d;
  if (!le && !be) return null;
  const u16 = (i: number) => (le ? tiff[i] | (tiff[i + 1] << 8) : (tiff[i] << 8) | tiff[i + 1]);
  const u32 = (i: number) =>
    le
      ? (tiff[i] | (tiff[i + 1] << 8) | (tiff[i + 2] << 16) | (tiff[i + 3] << 24)) >>> 0
      : ((tiff[i] << 24) | (tiff[i + 1] << 16) | (tiff[i + 2] << 8) | tiff[i + 3]) >>> 0;
  if (u16(2) !== 42) return null;
  let ifd = u32(4);
  if (ifd + 2 > tiff.length) return null;
  const count = u16(ifd);
  ifd += 2;
  for (let n = 0; n < count; n += 1) {
    const entry = ifd + n * 12;
    if (entry + 12 > tiff.length) return null;
    if (u16(entry) !== 0x0112) continue;
    const type = u16(entry + 2);
    const values = u32(entry + 4);
    if (type !== 3 || values < 1) return null;
    const value = u16(entry + 8);
    return value >= 1 && value <= 8 ? value : null;
  }
  return null;
}

/**
 * Decode a picture whose full bitmap would not fit, already scaled to `MAX_EDGE`.
 * `oriented` is the size the header says the picture will be drawn at.
 */
async function loadScaled(file: Blob, oriented: { width: number; height: number }): Promise<Source> {
  if (typeof createImageBitmap !== "function") throw new Error("That image is too large to send.");
  const fitted = fitEdge(oriented.width, oriented.height, MAX_EDGE);
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, {
      imageOrientation: "from-image",
      resizeWidth: fitted.width,
      resizeHeight: fitted.height,
      resizeQuality: "high",
    });
  } catch {
    throw new Error("This file isn’t an image this browser can read.");
  }
  const pixels = bitmap.width * bitmap.height;
  const aspect = bitmap.width / bitmap.height;
  const wanted = oriented.width / oriented.height;
  // The resize is ignored (the bitmap is still huge) or it stretched a rotated picture
  // into the wrong box. Either way the full frame does not fit, so this photo cannot be sent.
  if (pixels > MAX_DECODE_PIXELS || Math.abs(aspect - wanted) > 0.02) {
    bitmap.close();
    throw new Error("That image is too large to send.");
  }
  return {
    image: bitmap,
    width: bitmap.width,
    height: bitmap.height,
    release: () => bitmap.close(),
  };
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
  if (file.size > MAX_IMAGE_BYTES) throw new Error("That image is too large to send.");
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

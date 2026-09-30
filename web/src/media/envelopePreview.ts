/**
 * The preview sealed into a photo or video message (`th`): a tiny JPEG the bubble
 * shows before the media itself is on this device. Matches iOS
 * `MediaCrypto.chatPreviewJPEG`. No DOM here, so the video worker can use it too.
 */
export const THUMB_EDGE = 160;
export const THUMB_QUALITY = 0.42;
/** The envelope is capped at 60 KB sealed; the preview gets a small, fixed share. */
export const MAX_THUMB_BYTES = 6 * 1024;

/**
 * Same ladder as iOS: shrink the edge and the quality until the preview fits.
 * `encode` draws the picture with its longest edge at `edge` px and returns the JPEG.
 * Null when even the smallest try is too big.
 */
export async function envelopePreview(
  encode: (edge: number, quality: number) => Promise<Uint8Array>,
): Promise<Uint8Array | null> {
  let edge = THUMB_EDGE;
  let quality = THUMB_QUALITY;
  for (let attempt = 0; attempt < 5; attempt++) {
    const jpeg = await encode(Math.round(edge), Math.min(0.85, Math.max(0.15, quality)));
    if (jpeg.byteLength <= MAX_THUMB_BYTES) return jpeg;
    edge = Math.max(80, edge * 0.7);
    quality = Math.max(0.15, quality - 0.08);
  }
  return null;
}

/** `width × height` scaled so the longest edge is at most `edge` (never upscaled). */
export function fitEdge(width: number, height: number, edge: number): { width: number; height: number } {
  const scale = Math.min(1, edge / Math.max(width, height));
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

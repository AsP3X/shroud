/*
 * HEIC → JPEG for browsers that can't draw HEIC (everything but Safari). iPhones
 * send library originals untouched, so most photos from iOS arrive as HEIC.
 *
 * libheif's WASM build compiles synchronously, which Chrome only allows off the
 * main thread — hence the worker. The bundle has no eval/new Function, so it runs
 * under the app's CSP ('wasm-unsafe-eval' only). Pixels never leave this worker
 * except as the finished JPEG.
 */
import buildLibheif, { type HeifDecoder, type HeifImage } from "libheif-js/libheif-wasm/libheif-bundle.mjs";

type Request = { id: number; bytes: ArrayBuffer; quality: number };

const libheif = buildLibheif();

async function toJpeg(bytes: ArrayBuffer, quality: number) {
  let decoder: HeifDecoder | null = null;
  let images: HeifImage[] = [];
  try {
    decoder = new libheif.HeifDecoder();
    images = decoder.decode(bytes);
    const image = images[0];
    if (!image) throw new Error("No image inside this HEIF file.");
    const width = image.get_width();
    const height = image.get_height();
    // `display` writes colour only, so start from opaque pixels.
    const pixels = new ImageData(width, height);
    pixels.data.fill(255);
    await new Promise<void>((resolve, reject) => {
      image.display(pixels, (result) => (result ? resolve() : reject(new Error("HEIF decode failed."))));
    });
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext("2d");
    if (!context) throw new Error("No 2D canvas in this worker.");
    context.putImageData(pixels, 0, 0);
    const blob = await canvas.convertToBlob({ type: "image/jpeg", quality });
    return { blob, width, height };
  } finally {
    for (const image of images) image.free();
    if (decoder?.decoder) libheif.heif_context_free(decoder.decoder);
  }
}

self.onmessage = async (event: MessageEvent<Request>) => {
  const { id, bytes, quality } = event.data;
  try {
    const result = await toJpeg(bytes, quality);
    self.postMessage({ id, ...result });
  } catch (err) {
    self.postMessage({ id, error: err instanceof Error ? err.message : String(err) });
  }
};

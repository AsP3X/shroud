/**
 * The pictures of a PDF's first page (docs/file-sharing.md §10.1): the `th` a sender seals, and
 * the sharper local render the bubble shows once the viewer has opened the file.
 */
import type { PDFDocumentProxy } from "pdfjs-dist";
import type { PdfSendPreview } from "../messaging";
import { MAX_THUMB_BYTES } from "./envelopePreview";
import { loadPdfjs, openPdfBlob } from "./pdfjs";
import { rememberPdfCard, rememberPdfPageCount } from "./pdfMemory";

/** The sender's `th`: page 1 drawn this wide, its top 2:1 cut out. */
const SEND_WIDTH = 480;
/** Smaller than this and the preview isn't worth sending. */
const SEND_MIN_WIDTH = 160;
const SEND_QUALITY = 0.5;
const SEND_MIN_QUALITY = 0.25;
/** A PDF that takes longer than this to read goes out without `th` and `pg`. */
const SEND_BUDGET_MS = 2000;
/** The card's width in the bubble (300 cap − 2 × 4 inset), in CSS pixels. */
export const PDF_CARD_WIDTH = 292;

function blobOf(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

/** A canvas `width` wide holding the top of page 1 (at most 2:1), drawn on white. */
async function drawTop(doc: PDFDocumentProxy, width: number): Promise<HTMLCanvasElement> {
  const page = await doc.getPage(1);
  try {
    const natural = page.getViewport({ scale: 1 });
    const viewport = page.getViewport({ scale: width / natural.width });
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(viewport.width);
    // The top 2:1 of the page; the whole page when it is wider than that.
    canvas.height = Math.max(1, Math.round(Math.min(viewport.height, viewport.width / 2)));
    // The "print" intent draws in one go instead of one animation frame at a time, so a send
    // from a tab in the background (where frames stop) still gets its preview inside the 2 s.
    await page.render({ canvas, viewport, background: "#ffffff", intent: "print" }).promise;
    return canvas;
  } finally {
    page.cleanup();
  }
}

/** Shrinks the width by 0.8 and the quality until the JPEG fits 6 KB; null below 160 px wide. */
async function sendJpeg(source: HTMLCanvasElement): Promise<{ jpeg: Uint8Array; width: number; height: number } | null> {
  let width = source.width;
  let quality = SEND_QUALITY;
  while (width >= SEND_MIN_WIDTH) {
    const w = Math.round(width);
    const h = Math.max(1, Math.round((source.height * w) / source.width));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, w, h);
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(source, 0, 0, w, h);
    const blob = await blobOf(canvas, "image/jpeg", quality);
    if (blob && blob.size <= MAX_THUMB_BYTES) return { jpeg: new Uint8Array(await blob.arrayBuffer()), width: w, height: h };
    width *= 0.8;
    quality = Math.max(SEND_MIN_QUALITY, quality - 0.08);
  }
  return null;
}

/**
 * `th` and `pg` for a PDF about to be sent, read off the picked file in ranges. Null when the
 * PDF needs a password, doesn't parse, or takes longer than 2 s: the file goes out without them.
 */
export async function pdfSendPreview(file: Blob): Promise<PdfSendPreview | null> {
  let pdf: Awaited<ReturnType<typeof loadPdfjs>>;
  try {
    // Loading pdf.js itself (once per tab) is not reading the PDF: the 2 s start after it.
    pdf = await loadPdfjs();
  } catch {
    return null;
  }
  // No `onPassword`: a protected PDF rejects with a PasswordException and goes out without one.
  const task = openPdfBlob(pdf, file, { disableAutoFetch: true });
  let timer = 0;
  const work = (async (): Promise<PdfSendPreview | null> => {
    const doc = await task.promise;
    const pages = doc.numPages;
    let picture: Awaited<ReturnType<typeof sendJpeg>> = null;
    try {
      picture = await sendJpeg(await drawTop(doc, SEND_WIDTH));
    } catch {
      picture = null;
    }
    return { thumb: picture?.jpeg ?? null, width: picture?.width ?? 0, height: picture?.height ?? 0, pages };
  })();
  work.catch(() => undefined);
  const timeout = new Promise<null>((resolve) => {
    timer = window.setTimeout(() => resolve(null), SEND_BUDGET_MS);
  });
  try {
    return await Promise.race([work, timeout]);
  } catch {
    // Protected (a PasswordException), damaged or unreadable: sent without a preview.
    return null;
  } finally {
    window.clearTimeout(timer);
    // Read or not, the document and its worker go: nothing of the file stays in memory.
    void task.destroy();
  }
}

/**
 * The bubble's local render (§10.1) from a document the viewer has open: page 1 at the card's
 * pixel size (card width × device pixel ratio), cropped 2:1 from the top, plus the page count.
 * Kept in memory only (`pdfMemory.ts`).
 */
export async function renderPdfCard(doc: PDFDocumentProxy, messageId: string): Promise<void> {
  rememberPdfPageCount(messageId, doc.numPages);
  const scale = Math.min(3, Math.max(1, window.devicePixelRatio || 1));
  const canvas = await drawTop(doc, Math.round(PDF_CARD_WIDTH * scale));
  const blob = await blobOf(canvas, "image/jpeg", 0.9);
  if (!blob) return;
  rememberPdfCard(messageId, { url: URL.createObjectURL(blob), width: canvas.width, height: canvas.height });
}

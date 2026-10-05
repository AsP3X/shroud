/**
 * pdf.js for Shroud's PDF previews and viewer (docs/file-sharing.md §10). Loaded on first use, so
 * it is never part of the chat's first download. Everything it fetches comes from this origin, as
 * the CSP demands: the worker is bundled by Vite, and the CMaps, standard fonts, ICC profile and
 * WebAssembly decoders are copied out of the package by `pdfjsAssets.ts` (vite.config.ts) to
 * `/pdfjs/{version}/`. The document itself is never fetched: pdf.js reads it in ranges off a
 * `Blob` (the decrypted file, or the file the sender picked) through `PDFDataRangeTransport`.
 */
import type { DocumentInitParameters, PDFDocumentLoadingTask } from "pdfjs-dist/types/src/display/api";
import workerUrl from "pdfjs-dist/build/pdf.worker.mjs?worker&url";

export type PdfLib = typeof import("pdfjs-dist");
export type PdfViewerLib = typeof import("pdfjs-dist/web/pdf_viewer.mjs");

/** Bytes pdf.js asks for at once: one SHRF1 segment's worth. */
const RANGE_CHUNK = 64 * 1024;

let lib: Promise<PdfLib> | null = null;
let viewerLib: Promise<PdfViewerLib> | null = null;

/** pdf.js itself (the display layer), with its worker set up. */
export function loadPdfjs(): Promise<PdfLib> {
  lib ??= import("pdfjs-dist").then(
    (mod) => {
      mod.GlobalWorkerOptions.workerSrc = new URL(workerUrl, window.location.href).href;
      return mod;
    },
    (err: unknown) => {
      lib = null;
      throw err;
    },
  );
  return lib;
}

/** The viewer components. They read `globalThis.pdfjsLib`, which loading pdf.js first sets. */
export function loadPdfViewer(): Promise<{ lib: PdfLib; viewer: PdfViewerLib }> {
  viewerLib ??= loadPdfjs()
    .then(() => import("pdfjs-dist/web/pdf_viewer.mjs"))
    .catch((err: unknown) => {
      viewerLib = null;
      throw err;
    });
  return Promise.all([loadPdfjs(), viewerLib]).then(([pdf, viewer]) => ({ lib: pdf, viewer }));
}

/** Where `pdfjsAssets.ts` puts the package's data files, on this origin. */
function assetBase(version: string): string {
  return new URL(`${import.meta.env.BASE_URL}pdfjs/${version}/`, window.location.href).href;
}

/**
 * Opens a PDF held in a `Blob`, reading only the ranges pdf.js asks for (`Blob.slice`), never the
 * whole file into one buffer of ours. No scripting, no XFA, no eval: pdf.js 6 has no `eval` path
 * left at all (the `isEvalSupported` option is gone), and `enableScripting` stays off.
 */
export function openPdfBlob(
  pdf: PdfLib,
  blob: Blob,
  options: Pick<DocumentInitParameters, "disableAutoFetch" | "password"> = {},
): PDFDocumentLoadingTask {
  class BlobRangeTransport extends pdf.PDFDataRangeTransport {
    #closed = false;
    constructor() {
      super(blob.size, new Uint8Array(0));
    }
    override requestDataRange(begin: number, end: number): void {
      void blob
        .slice(begin, end)
        .arrayBuffer()
        .then((bytes) => {
          if (!this.#closed) this.onDataRange(begin, new Uint8Array(bytes));
        })
        .catch(() => undefined);
    }
    override abort(): void {
      this.#closed = true;
    }
  }
  const base = assetBase(pdf.version);
  return pdf.getDocument({
    range: new BlobRangeTransport(),
    rangeChunkSize: RANGE_CHUNK,
    // Nothing is streamed: every byte pdf.js reads is a range it asked for.
    disableStream: true,
    cMapUrl: `${base}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${base}standard_fonts/`,
    wasmUrl: `${base}wasm/`,
    iccUrl: `${base}iccs/`,
    enableXfa: false,
    stopAtErrors: false,
    verbosity: pdf.VerbosityLevel.ERRORS,
    ...options,
  });
}

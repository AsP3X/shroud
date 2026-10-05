import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { PDFDocumentProxy, RenderTask } from "pdfjs-dist";

/*
 * The PDF viewer's pages sidebar (docs/file-sharing.md §10.2): one column of page thumbnails, as
 * a sidebar on wide windows and a drawer on narrow ones. Rows are laid out from the page sizes
 * alone and only the rows near the visible part are in the DOM, so a 500-page PDF scrolls its list
 * at once; only the thumbnails in view (and a few ahead) are drawn, two at a time, and the
 * finished ones stay in a bounded cache for the viewer's life.
 */

/** A thumbnail is this wide, at its page's aspect; no thumbnail is taller than THUMB_MAX_HEIGHT. */
export const THUMB_WIDTH = 128;
const THUMB_MAX_HEIGHT = 182;
/** Number line under each thumbnail: 6 below it, 12 px text on a 16 px line. */
const NUMBER_GAP = 6;
const NUMBER_LINE = 16;
/** Rows are 20 apart; the list is padded 16 at the top and bottom. */
const ROW_GAP = 20;
const LIST_PADDING = 16;
/** Rows kept in the DOM beyond the visible part, and rows drawn ahead of it. */
const DOM_OVERSCAN = 6;
const DRAW_AHEAD = 3;
const CONCURRENCY = 2;
/** Decoded pixels (w × h × 4) the finished thumbnails may hold. */
const CACHE_BYTES = 12 * 1024 * 1024;
/** After the reader scrolls the list, it stops following the document for this long. */
const USER_SCROLL_MS = 800;

type Thumb = { url: string; bytes: number };

/**
 * Draws page thumbnails on request and keeps them as JPEG object URLs, least recently used out
 * first past ~12 MB of decoded pixels. Renders for pages no longer wanted are cancelled.
 */
export class PdfThumbnailCache {
  #thumbs = new Map<number, Thumb>();
  #bytes = 0;
  #wanted: number[] = [];
  #running = new Map<number, RenderTask | null>();
  #listeners = new Set<() => void>();
  #version = 0;
  #started = false;
  #closed = false;

  constructor(private readonly doc: PDFDocumentProxy) {}

  readonly subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  };

  readonly version = (): number => this.#version;

  url(page: number): string | null {
    return this.#thumbs.get(page)?.url ?? null;
  }

  /** Nothing is drawn before the document's first page is on screen. */
  start(): void {
    this.#started = true;
    this.#pump();
  }

  /** The pages to draw, most urgent first; anything else in flight is cancelled. */
  want(pages: number[]): void {
    if (this.#closed) return;
    this.#wanted = pages;
    const keep = new Set(pages);
    for (const [page, task] of this.#running) if (!keep.has(page)) task?.cancel();
    // Seen again: last to be evicted.
    for (const page of pages) {
      const thumb = this.#thumbs.get(page);
      if (thumb) {
        this.#thumbs.delete(page);
        this.#thumbs.set(page, thumb);
      }
    }
    this.#pump();
  }

  #pump(): void {
    if (!this.#started || this.#closed) return;
    for (const page of this.#wanted) {
      if (this.#running.size >= CONCURRENCY) return;
      if (this.#thumbs.has(page) || this.#running.has(page)) continue;
      this.#running.set(page, null);
      void this.#draw(page).finally(() => {
        this.#running.delete(page);
        this.#pump();
      });
    }
  }

  async #draw(number: number): Promise<void> {
    let canvas: HTMLCanvasElement | null = null;
    try {
      const page = await this.doc.getPage(number);
      if (this.#closed || !this.#wanted.includes(number)) return;
      const natural = page.getViewport({ scale: 1 });
      const box = thumbBox(natural.height / natural.width);
      const scale = (box.width * Math.min(2, window.devicePixelRatio || 1)) / natural.width;
      const viewport = page.getViewport({ scale });
      canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(viewport.width));
      canvas.height = Math.max(1, Math.round(viewport.height));
      const task = page.render({ canvas, viewport, background: "#ffffff" });
      this.#running.set(number, task);
      await task.promise;
      const blob = await new Promise<Blob | null>((resolve) => canvas!.toBlob(resolve, "image/jpeg", 0.85));
      if (!blob || this.#closed) return;
      this.#store(number, { url: URL.createObjectURL(blob), bytes: canvas.width * canvas.height * 4 });
    } catch {
      /* Cancelled, or a page that won't draw: it keeps its white placeholder. */
    } finally {
      if (canvas) canvas.width = canvas.height = 0;
    }
  }

  #store(page: number, thumb: Thumb): void {
    this.#thumbs.set(page, thumb);
    this.#bytes += thumb.bytes;
    const wanted = new Set(this.#wanted);
    for (const [old, entry] of this.#thumbs) {
      if (this.#bytes <= CACHE_BYTES) break;
      if (wanted.has(old)) continue;
      this.#thumbs.delete(old);
      this.#bytes -= entry.bytes;
      URL.revokeObjectURL(entry.url);
    }
    this.#version += 1;
    for (const listener of [...this.#listeners]) listener();
  }

  close(): void {
    this.#closed = true;
    for (const task of this.#running.values()) task?.cancel();
    this.#running.clear();
    for (const thumb of this.#thumbs.values()) URL.revokeObjectURL(thumb.url);
    this.#thumbs.clear();
    this.#bytes = 0;
    this.#listeners.clear();
  }
}

/** A thumbnail's size for a page of this height ÷ width. */
function thumbBox(ratio: number): { width: number; height: number } {
  const r = ratio > 0 && Number.isFinite(ratio) ? ratio : Math.SQRT2;
  const height = Math.min(THUMB_MAX_HEIGHT, THUMB_WIDTH * r);
  return { width: Math.round(height / r), height: Math.round(height) };
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(low, high), Math.max(low, value));
}

function prefersReducedMotion(): boolean {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

/** The first row whose bottom is below `y`. */
function rowAt(tops: number[], heights: number[], y: number): number {
  let low = 0;
  let high = tops.length - 1;
  while (low < high) {
    const mid = (low + high) >> 1;
    if (tops[mid] + heights[mid] < y) low = mid + 1;
    else high = mid;
  }
  return low;
}

/**
 * The column of thumbnails. `active` is false while the sidebar is closed: nothing is drawn and
 * nothing follows. While active, the list keeps the current page in view as the document scrolls,
 * unless the reader is scrolling the list.
 */
export function PdfPagesList({
  cache,
  ratios,
  current,
  active,
  onPick,
}: {
  cache: PdfThumbnailCache;
  /** Height ÷ width of each page. */
  ratios: number[];
  current: number;
  active: boolean;
  onPick: (page: number) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ top: 0, height: 0 });
  useSyncExternalStore(cache.subscribe, cache.version, cache.version);
  /** Until when the reader is taken to be scrolling the list. */
  const userUntil = useRef(0);
  /** Where our own (following) scroll is heading, while it runs; its scroll events aren't the reader's. */
  const selfTarget = useRef<{ top: number; until: number } | null>(null);

  const layout = useMemo(() => {
    const boxes = ratios.map(thumbBox);
    const heights = boxes.map((b) => b.height + NUMBER_GAP + NUMBER_LINE);
    const tops: number[] = [];
    let y = LIST_PADDING;
    for (const h of heights) {
      tops.push(y);
      y += h + ROW_GAP;
    }
    const total = ratios.length ? y - ROW_GAP + LIST_PADDING : 0;
    return { boxes, heights, tops, total };
  }, [ratios]);

  useLayoutEffect(() => {
    const node = scroller.current;
    if (!node) return;
    const measure = () => setView({ top: node.scrollTop, height: node.clientHeight });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const count = ratios.length;
  const first = count ? rowAt(layout.tops, layout.heights, view.top) : 0;
  const last = count ? rowAt(layout.tops, layout.heights, view.top + view.height) : -1;

  /* Draw what is in view, then a few rows either side, nearest first. */
  useEffect(() => {
    if (!active || !count || view.height === 0) {
      cache.want([]);
      return;
    }
    const pages: number[] = [];
    for (let i = first; i <= last; i++) pages.push(i + 1);
    for (let k = 1; k <= DRAW_AHEAD; k++) {
      if (last + k < count) pages.push(last + k + 1);
      if (first - k >= 0) pages.push(first - k + 1);
    }
    cache.want(pages);
  }, [cache, active, count, first, last, view.height]);

  /** Scrolls row `index` into the visible part when it isn't (or centres it, on open). */
  const reveal = useCallback(
    (index: number, mode: "nearest" | "center", smooth: boolean) => {
      const node = scroller.current;
      if (!node || index < 0 || index >= count) return;
      const top = layout.tops[index];
      const bottom = top + layout.heights[index];
      let target: number | null = null;
      if (mode === "center") target = top - (node.clientHeight - layout.heights[index]) / 2;
      else if (top - LIST_PADDING < node.scrollTop) target = top - LIST_PADDING;
      else if (bottom + LIST_PADDING > node.scrollTop + node.clientHeight) target = bottom + LIST_PADDING - node.clientHeight;
      if (target == null) return;
      const to = clamp(target, 0, node.scrollHeight - node.clientHeight);
      // Animated when the page is near; a far jump (End, a search hit) lands at once.
      const near = Math.abs(to - node.scrollTop) < node.clientHeight * 2;
      const behavior = smooth && near && !prefersReducedMotion() ? "smooth" : "auto";
      selfTarget.current = { top: to, until: performance.now() + 1500 };
      node.scrollTo({ top: to, behavior });
    },
    [count, layout],
  );

  /* Opening (or the layout settling): the current page in the middle. */
  const shownOnce = useRef(false);
  useLayoutEffect(() => {
    if (!active) {
      shownOnce.current = false;
      return;
    }
    if (shownOnce.current || !count || view.height === 0) return;
    shownOnce.current = true;
    reveal(current - 1, "center", false);
  }, [active, count, view.height, current, reveal]);

  /* Following the document, unless the reader is busy with the list. */
  useEffect(() => {
    if (!active || !shownOnce.current) return;
    if (performance.now() < userUntil.current) return;
    reveal(current - 1, "nearest", true);
  }, [active, current, reveal]);

  const markUser = () => {
    selfTarget.current = null;
    userUntil.current = performance.now() + USER_SCROLL_MS;
  };

  const rows = [];
  if (count) {
    const from = Math.max(0, first - DOM_OVERSCAN);
    const to = Math.min(count - 1, last + DOM_OVERSCAN);
    for (let i = from; i <= to; i++) {
      const page = i + 1;
      const box = layout.boxes[i];
      const url = cache.url(page);
      const isCurrent = page === current;
      rows.push(
        <div
          key={page}
          role="listitem"
          aria-setsize={count}
          aria-posinset={page}
          className="pdf-page-row"
          style={{ transform: `translateY(${layout.tops[i]}px)`, height: layout.heights[i] }}
        >
          <button
            type="button"
            className={`pdf-page-thumb${isCurrent ? " is-current" : ""}`}
            aria-label={`Page ${page} of ${count}`}
            aria-current={isCurrent ? "page" : undefined}
            tabIndex={active ? 0 : -1}
            onClick={() => onPick(page)}
          >
            <span className="pdf-page-frame" style={{ width: box.width, height: box.height }}>
              {url ? <img src={url} alt="" draggable={false} decoding="async" /> : null}
            </span>
            <span className="pdf-page-number">{page}</span>
          </button>
        </div>,
      );
    }
  }

  return (
    <div
      ref={scroller}
      className="pdf-pages-scroll"
      onScroll={(event) => {
        const node = event.currentTarget;
        const self = selfTarget.current;
        if (self && performance.now() < self.until) {
          if (Math.abs(node.scrollTop - self.top) <= 1) selfTarget.current = null;
        } else markUser();
        setView((now) => (now.top === node.scrollTop ? now : { top: node.scrollTop, height: node.clientHeight }));
      }}
      onWheel={markUser}
      onTouchStart={markUser}
      onPointerDown={markUser}
      onKeyDown={markUser}
    >
      <div role="list" aria-label="Pages" className="pdf-pages-list" style={{ height: layout.total }}>
        {rows}
      </div>
    </div>
  );
}
